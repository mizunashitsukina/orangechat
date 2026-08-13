/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.s3.S3Client
import me.rerere.rikkahub.data.sync.s3.S3Config
import java.io.File
import java.time.Instant

private const val TAG = "S3Sync"

class S3Sync(
    settingsStore: SettingsStore,
    json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
) {
    private val archiveService = BackupArchiveService(settingsStore, json, context, null)

    private fun client(config: S3Config) = S3Client(config, httpClient)

    suspend fun testS3(config: S3Config) = withContext(Dispatchers.IO) {
        client(config).listObjects(maxKeys = 1).getOrThrow()
        Log.i(TAG, "S3 connection test succeeded")
    }

    suspend fun backupToS3(config: S3Config) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config)
        try {
            client(config).putObject(
                key = "rikkahub_backups/${file.name}",
                file = file,
                contentType = "application/zip",
            ).getOrThrow()
            Log.i(TAG, "S3 backup upload succeeded: bytes=${file.length()}")
        } finally {
            file.delete()
        }
    }

    suspend fun listBackupFiles(config: S3Config): List<S3BackupItem> = withContext(Dispatchers.IO) {
        client(config).listObjects(prefix = "rikkahub_backups/", maxKeys = 1000).getOrThrow().objects
            .filter { it.key.startsWith("rikkahub_backups/backup_") && it.key.endsWith(".zip") }
            .map { obj ->
                S3BackupItem(obj.key, obj.key.substringAfterLast('/'), obj.size, obj.lastModified ?: Instant.EPOCH)
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restoreFromS3(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        val backupFile = archiveService.createCacheFile("s3-download", ".zip")
        try {
            client(config).downloadObjectToFile(
                key = item.key,
                targetFile = backupFile,
                maxBytes = MAX_BACKUP_COMPRESSED_BYTES,
            ).getOrThrow()
            archiveService.restore(backupFile, selection(config))
            Log.i(TAG, "S3 backup restore succeeded")
        } finally {
            backupFile.delete()
        }
    }

    suspend fun deleteS3BackupFile(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        client(config).deleteObject(item.key).getOrThrow()
        Log.i(TAG, "S3 backup deletion succeeded")
    }

    suspend fun prepareBackupFile(config: S3Config): File = archiveService.create(selection(config))

    private fun selection(config: S3Config) = BackupContentSelection(
        includeDatabase = config.items.contains(S3Config.BackupItem.DATABASE),
        includeFiles = config.items.contains(S3Config.BackupItem.FILES),
        includePlugins = false,
    )
}

data class S3BackupItem(
    val key: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
