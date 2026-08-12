/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.webdav

import android.util.Log
import android.util.Xml
import io.ktor.client.HttpClient
import io.ktor.client.request.basicAuth
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.readAvailable
import io.ktor.util.cio.readChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.BackupArchiveException
import me.rerere.rikkahub.data.sync.BackupArchiveFailure
import me.rerere.rikkahub.data.sync.MAX_BACKUP_COMPRESSED_BYTES
import me.rerere.rikkahub.data.sync.MAX_REMOTE_METADATA_BYTES
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val TAG = "WebDavClient"

class WebDavClient(
    private val config: WebDavConfig,
    private val httpClient: HttpClient,
) {
    private fun WebDavConfig.buildUrl(vararg segments: String): String {
        val base = url.trimEnd('/')
        val pathSegments = listOfNotNull(
            path.takeIf { it.isNotBlank() }?.trim('/'),
            *segments.map { it.trim('/') }.toTypedArray()
        ).filter { it.isNotEmpty() }

        return if (pathSegments.isEmpty()) {
            base
        } else {
            "$base/${pathSegments.joinToString("/")}"
        }
    }
    suspend fun put(
        path: String,
        data: ByteArray,
        contentType: String = "application/octet-stream",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val url = config.buildUrl(path)
            Log.d(TAG, "WebDAV PUT started")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Put
                basicAuth(config.username, config.password)
                headers {
                    append("Content-Type", contentType)
                    append("Content-Length", data.size.toString())
                }
                setBody(data)
            }

            if (!response.status.isSuccess()) {
                Log.e(TAG, "WebDAV PUT failed: status=${response.status.value}")
                throw WebDavException("WebDAV request failed: ${response.status.value}", response.status.value)
            }

            Log.d(TAG, "WebDAV PUT succeeded")
            Unit
        }
    }

    suspend fun put(
        path: String,
        file: File,
        contentType: String = "application/octet-stream",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val url = config.buildUrl(path)
            Log.d(TAG, "WebDAV file upload started")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Put
                basicAuth(config.username, config.password)
                headers {
                    append("Content-Type", contentType)
                    append("Content-Length", file.length().toString())
                }
                // Stream file content to avoid loading the whole backup zip into heap.
                setBody(file.readChannel())
            }

            if (!response.status.isSuccess()) {
                Log.e(TAG, "WebDAV file upload failed: status=${response.status.value}")
                throw WebDavException("WebDAV request failed: ${response.status.value}", response.status.value)
            }

            Log.d(TAG, "WebDAV file upload succeeded: bytes=${file.length()}")
            Unit
        }
    }

    suspend fun get(path: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val url = config.buildUrl(path)
            Log.d(TAG, "WebDAV GET started")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Get
                basicAuth(config.username, config.password)
            }

            if (!response.status.isSuccess()) {
                Log.e(TAG, "WebDAV GET failed: status=${response.status.value}")
                throw WebDavException("WebDAV request failed: ${response.status.value}", response.status.value)
            }

            val channel = response.bodyAsChannel()
            channel.toInputStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                me.rerere.rikkahub.data.sync.BackupArchiveSecurity.copyLimited(
                    input,
                    output,
                    MAX_BACKUP_COMPRESSED_BYTES,
                    BackupArchiveFailure.ARCHIVE_TOO_LARGE,
                )
                output.toByteArray()
            }
        }
    }

    suspend fun getStream(path: String): Result<InputStream> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val url = config.buildUrl(path)
            Log.d(TAG, "WebDAV stream GET started")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Get
                basicAuth(config.username, config.password)
            }

            if (!response.status.isSuccess()) {
                Log.e(TAG, "WebDAV stream GET failed: status=${response.status.value}")
                throw WebDavException("WebDAV request failed: ${response.status.value}", response.status.value)
            }

            response.bodyAsChannel().toInputStream()
        }
    }

    suspend fun downloadToFile(
        path: String,
        targetFile: File,
        maxBytes: Long = MAX_BACKUP_COMPRESSED_BYTES,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val url = config.buildUrl(path)
            Log.d(TAG, "WebDAV download started")

            httpClient.prepareRequest(url) {
                method = HttpMethod.Get
                basicAuth(config.username, config.password)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    Log.e(TAG, "WebDAV download failed: status=${response.status.value}")
                    throw WebDavException("WebDAV request failed: ${response.status.value}", response.status.value)
                }

                val channel = response.bodyAsChannel()
                targetFile.outputStream().use { outputStream ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    while (!channel.isClosedForRead) {
                        val bytesRead = channel.readAvailable(buffer)
                        if (bytesRead > 0) {
                            if (bytesRead > maxBytes - downloaded) {
                                throw BackupArchiveException(BackupArchiveFailure.ARCHIVE_TOO_LARGE)
                            }
                            outputStream.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                        }
                    }
                }
                Log.d(TAG, "WebDAV download succeeded: bytes=${targetFile.length()}")
            }
            Unit
        }
    }

    suspend fun delete(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val url = config.buildUrl(path)
            Log.d(TAG, "WebDAV DELETE started")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Delete
                basicAuth(config.username, config.password)
            }

            if (!response.status.isSuccess()) {
                Log.e(TAG, "WebDAV DELETE failed: status=${response.status.value}")
                throw WebDavException("WebDAV request failed: ${response.status.value}", response.status.value)
            }

            Log.d(TAG, "WebDAV DELETE succeeded")
            Unit
        }
    }

    suspend fun head(path: String): Result<WebDavResourceInfo> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val url = config.buildUrl(path)
            Log.d(TAG, "WebDAV HEAD started")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Head
                basicAuth(config.username, config.password)
            }

            if (!response.status.isSuccess()) {
                throw WebDavException("WebDAV request failed: ${response.status.value}", response.status.value)
            }

            WebDavResourceInfo(
                href = path,
                displayName = path.substringAfterLast("/"),
                contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: 0,
                contentType = response.headers["Content-Type"] ?: "application/octet-stream",
                lastModified = parseLastModified(response.headers["Last-Modified"]),
                isCollection = false,
            )
        }
    }

    suspend fun mkcol(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val url = config.buildUrl(path)
            Log.d(TAG, "WebDAV MKCOL started")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod("MKCOL")
                basicAuth(config.username, config.password)
            }

            // 201 Created or 405 Method Not Allowed (already exists) are acceptable
            if (!response.status.isSuccess() && response.status != HttpStatusCode.MethodNotAllowed) {
                Log.e(TAG, "WebDAV MKCOL failed: status=${response.status.value}")
                throw WebDavException("WebDAV request failed: ${response.status.value}", response.status.value)
            }

            Log.d(TAG, "WebDAV MKCOL succeeded")
            Unit
        }
    }

    suspend fun propfind(
        path: String = "",
        depth: Int = 1,
    ): Result<List<WebDavResourceInfo>> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val url = config.buildUrl(path)
            Log.d(TAG, "WebDAV PROPFIND started: depth=$depth")

            val propfindBody = """<?xml version="1.0" encoding="UTF-8"?>
                |<D:propfind xmlns:D="DAV:">
                |  <D:prop>
                |    <D:displayname/>
                |    <D:getcontentlength/>
                |    <D:getcontenttype/>
                |    <D:getlastmodified/>
                |    <D:resourcetype/>
                |  </D:prop>
                |</D:propfind>
            """.trimMargin()

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod("PROPFIND")
                basicAuth(config.username, config.password)
                headers {
                    append("Content-Type", "application/xml; charset=utf-8")
                    append("Depth", depth.toString())
                }
                setBody(propfindBody)
            }

            if (!response.status.isSuccess() && response.status.value != 207) {
                Log.e(TAG, "WebDAV PROPFIND failed: status=${response.status.value}")
                throw WebDavException("WebDAV request failed: ${response.status.value}", response.status.value)
            }

            val xmlBody = response.bodyAsChannel().toInputStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                me.rerere.rikkahub.data.sync.BackupArchiveSecurity.copyLimited(
                    input,
                    output,
                    MAX_REMOTE_METADATA_BYTES,
                    BackupArchiveFailure.ENTRY_TOO_LARGE,
                )
                output.toString(Charsets.UTF_8.name())
            }
            parsePropfindResponse(xmlBody, url)
        }
    }

    suspend fun exists(path: String): Boolean {
        return head(path).isSuccess
    }

    suspend fun ensureCollectionExists(path: String = ""): Result<Unit> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            Log.d(TAG, "WebDAV collection check started")

            // Try propfind first to check if it exists
            val propfindResult = propfind(path, depth = 0)
            if (propfindResult.isSuccess) {
                Log.d(TAG, "WebDAV collection exists")
                return@runSuspendCatching
            }

            // Create collection if not exists
            mkcol(path).getOrThrow()
        }
    }

    suspend fun list(path: String = ""): Result<List<WebDavResourceInfo>> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val result = propfind(path, depth = 1).getOrThrow()
            // Filter out the parent directory itself (first entry)
            if (result.isNotEmpty()) {
                result.drop(1)
            } else {
                emptyList()
            }
        }
    }

    private fun parsePropfindResponse(xml: String, baseUrl: String): List<WebDavResourceInfo> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        val resources = mutableListOf<WebDavResourceInfo>()
        var currentHref: String? = null
        var currentDisplayName: String? = null
        var currentContentLength: Long = 0
        var currentContentType: String? = null
        var currentLastModified: Instant? = null
        var currentIsCollection = false
        var currentTag: String? = null
        var inResponse = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name.substringAfter(":")
                    when (currentTag) {
                        "response" -> {
                            inResponse = true
                            currentHref = null
                            currentDisplayName = null
                            currentContentLength = 0
                            currentContentType = null
                            currentLastModified = null
                            currentIsCollection = false
                        }
                        "collection" -> {
                            currentIsCollection = true
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty() && inResponse) {
                        when (currentTag) {
                            "href" -> currentHref = text
                            "displayname" -> currentDisplayName = text
                            "getcontentlength" -> currentContentLength = text.toLongOrNull() ?: 0
                            "getcontenttype" -> currentContentType = text
                            "getlastmodified" -> currentLastModified = parseLastModified(text)
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val tagName = parser.name.substringAfter(":")
                    if (tagName == "response" && currentHref != null) {
                        val displayName = currentDisplayName
                            ?: currentHref!!.trimEnd('/').substringAfterLast("/")

                        resources.add(
                            WebDavResourceInfo(
                                href = currentHref!!,
                                displayName = displayName,
                                contentLength = currentContentLength,
                                contentType = currentContentType ?: "application/octet-stream",
                                lastModified = currentLastModified,
                                isCollection = currentIsCollection,
                            )
                        )
                        inResponse = false
                    }
                    currentTag = null
                }
            }
            parser.next()
        }

        return resources
    }

    private fun parseLastModified(dateString: String?): Instant? {
        if (dateString.isNullOrBlank()) return null

        return try {
            // RFC 1123 format: "Tue, 15 Nov 1994 08:12:31 GMT"
            ZonedDateTime.parse(dateString, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (e: Exception) {
            try {
                // RFC 850 format: "Tuesday, 15-Nov-94 08:12:31 GMT"
                ZonedDateTime.parse(dateString, DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz")).toInstant()
            } catch (e: Exception) {
                try {
                    // ISO 8601
                    Instant.parse(dateString)
                } catch (e: Exception) {
                    Log.w(TAG, "WebDAV date parse failed")
                    null
                }
            }
        }
    }
}

data class WebDavResourceInfo(
    val href: String,
    val displayName: String,
    val contentLength: Long,
    val contentType: String,
    val lastModified: Instant?,
    val isCollection: Boolean,
)

class WebDavException(
    message: String,
    val statusCode: Int,
) : Exception(message)

private inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
