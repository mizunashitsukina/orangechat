/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.webdav

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.BackupArchiveService
import me.rerere.rikkahub.data.sync.BackupContentSelection
import me.rerere.rikkahub.data.sync.MAX_BACKUP_COMPRESSED_BYTES
import me.rerere.rikkahub.plugin.repository.PluginRepository
import java.io.File
import java.time.Instant

private const val TAG = "WebDavSync"

class WebDavSync(
    settingsStore: SettingsStore,
    json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
    pluginRepository: PluginRepository,
) {
    private val archiveService = BackupArchiveService(settingsStore, json, context, pluginRepository)

    private fun client(config: WebDavConfig) = WebDavClient(config, httpClient)

    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        client(config).propfind(depth = 0).getOrThrow()
        Log.i(TAG, "WebDAV connection test succeeded")
    }

    suspend fun backup(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config, includePlugins = false)
        try {
            val client = client(config)
            client.ensureCollectionExists().getOrThrow()
            client.put(file.name, file, "application/zip").getOrThrow()
            Log.i(TAG, "WebDAV backup upload succeeded: bytes=${file.length()}")
        } finally {
            file.delete()
        }
    }

    suspend fun listBackupFiles(config: WebDavConfig): List<WebDavBackupItem> = withContext(Dispatchers.IO) {
        val client = client(config)
        client.ensureCollectionExists().getOrThrow()
        client.list().getOrThrow()
            .filter { !it.isCollection && it.displayName.startsWith("backup_") && it.displayName.endsWith(".zip") }
            .map { WebDavBackupItem(it.href, it.displayName, it.contentLength, it.lastModified ?: Instant.EPOCH) }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restore(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val backupFile = archiveService.createCacheFile("webdav-download", ".zip")
        try {
            client(config).downloadToFile(
                path = item.displayName,
                targetFile = backupFile,
                maxBytes = MAX_BACKUP_COMPRESSED_BYTES,
            ).getOrThrow()
            archiveService.restore(backupFile, selection(config, includePlugins = false))
            Log.i(TAG, "WebDAV backup restore succeeded")
        } finally {
            backupFile.delete()
        }
    }

    suspend fun deleteBackupFile(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        client(config).delete(item.displayName).getOrThrow()
        Log.i(TAG, "WebDAV backup deletion succeeded")
    }

    suspend fun restoreFromLocalFile(file: File, config: WebDavConfig) = withContext(Dispatchers.IO) {
        archiveService.restore(file, selection(config, includePlugins = true))
    }

    suspend fun prepareBackupFile(config: WebDavConfig, includePlugins: Boolean = true): File =
        archiveService.create(selection(config, includePlugins))

    private fun selection(config: WebDavConfig, includePlugins: Boolean) = BackupContentSelection(
        includeDatabase = config.items.contains(WebDavConfig.BackupItem.DATABASE),
        includeFiles = config.items.contains(WebDavConfig.BackupItem.FILES),
        includePlugins = includePlugins,
    )
}

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
