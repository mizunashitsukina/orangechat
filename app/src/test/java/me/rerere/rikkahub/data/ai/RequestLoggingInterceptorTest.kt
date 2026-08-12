/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import java.io.IOException
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class RequestLoggingInterceptorTest {
    private val secretUrl = "https://private.example.test/v1/chat?token=secret-query"
    private val secretHeader = "Bearer secret-token"
    private val secretRequestBody = "secret request prompt"
    private val secretResponseBody = "secret response content"

    @Before
    fun setUp() {
        Logging.clear()
    }

    @After
    fun tearDown() {
        Logging.clear()
    }

    @Test
    fun successfulResponseLogsOnlySafeMetadata() {
        val client = clientWithTerminalInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("X-Secret-Response", "secret-response-header")
                .body(secretResponseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }

        client.newCall(secretRequest()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals(secretResponseBody, response.body?.string())
        }

        val entry = Logging.getRequestLogs().single()
        assertSafeMetadata(entry, responseCode = 200, errorType = null)
    }

    @Test
    fun thrownExceptionLogsOnlySafeMetadataAndIsRethrown() {
        val client = clientWithTerminalInterceptor {
            throw IOException("secret upstream error body")
        }

        val thrown = runCatching {
            client.newCall(secretRequest()).execute()
        }.exceptionOrNull()

        assertTrue(thrown is IOException)
        assertEquals("secret upstream error body", thrown?.message)

        val entry = Logging.getRequestLogs().single()
        assertSafeMetadata(entry, responseCode = null, errorType = "IOException")
    }

    private fun clientWithTerminalInterceptor(
        terminal: (Interceptor.Chain) -> Response,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(RequestLoggingInterceptor())
        .addInterceptor(Interceptor { chain -> terminal(chain) })
        .build()

    private fun secretRequest(): Request = Request.Builder()
        .url(secretUrl)
        .header("Authorization", secretHeader)
        .post(
            secretRequestBody.toRequestBody("application/json".toMediaType())
        )
        .build()

    private fun assertSafeMetadata(
        entry: me.rerere.common.android.LogEntry.RequestLog,
        responseCode: Int?,
        errorType: String?,
    ) {
        assertEquals("[redacted]", entry.url)
        assertEquals("POST", entry.method)
        assertEquals(responseCode, entry.responseCode)
        assertEquals(errorType, entry.error)
        assertTrue(entry.durationMs != null && entry.durationMs >= 0)
        assertTrue(entry.requestHeaders.isEmpty())
        assertNull(entry.requestBody)
        assertTrue(entry.responseHeaders.isEmpty())
        assertNull(entry.responseBody)

        val serializedEntry = entry.toString()
        listOf(
            secretUrl,
            secretHeader,
            secretRequestBody,
            secretResponseBody,
            "secret upstream error body",
            "secret-response-header",
        ).forEach { secret ->
            assertFalse(serializedEntry.contains(secret))
        }
    }
}
