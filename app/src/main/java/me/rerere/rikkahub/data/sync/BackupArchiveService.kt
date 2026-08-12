/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.SkillPaths
import me.rerere.rikkahub.plugin.repository.PluginRepository
import me.rerere.rikkahub.plugin.repository.PluginSettingsExport
import me.rerere.rikkahub.plugin.scanner.PluginScanner
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "BackupArchiveService"

data class BackupContentSelection(
    val includeDatabase: Boolean,
    val includeFiles: Boolean,
    val includePlugins: Boolean,
)

class BackupArchiveService(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val pluginRepository: PluginRepository?,
) {
    suspend fun create(selection: BackupContentSelection): File {
        // Preserve the established backup_*.zip naming contract used by remote listing filters.
        val backupFile = createCacheFile("backup_", ".zip")
        try {
            val budget = ArchiveWriteBudget()
            ZipOutputStream(
                BoundedOutputStream(FileOutputStream(backupFile), MAX_BACKUP_COMPRESSED_BYTES)
            ).use { zip ->
                addText(zip, "settings.json", json.encodeToString(settingsStore.settingsFlow.value), budget)
                if (selection.includeDatabase) addDatabase(zip, budget)
                if (selection.includeFiles) {
                    addDirectory(
                        zip,
                        File(context.filesDir, FileFolders.UPLOAD),
                        "${FileFolders.UPLOAD}/",
                        budget,
                    )
                    addDirectory(
                        zip,
                        File(context.filesDir, FileFolders.SKILLS),
                        "${FileFolders.SKILLS}/",
                        budget,
                    )
                    if (selection.includePlugins) {
                        pluginRepository?.let { repository ->
                            addText(
                                zip,
                                "plugin_settings.json",
                                json.encodeToString(repository.exportPluginSettings()),
                                budget,
                            )
                        }
                        addDirectory(
                            zip,
                            PluginScanner(context).pluginsDir,
                            "${PluginScanner.PLUGINS_DIR}/",
                            budget,
                        )
                    }
                }
            }
            if (backupFile.length() > MAX_BACKUP_COMPRESSED_BYTES) {
                throw BackupArchiveException(BackupArchiveFailure.ARCHIVE_TOO_LARGE)
            }
            Log.i(TAG, "Backup archive created: bytes=${backupFile.length()}")
            return backupFile
        } catch (e: Exception) {
            backupFile.delete()
            Log.e(TAG, "Backup archive creation failed: ${e.javaClass.simpleName}")
            throw e
        }
    }

    suspend fun restore(archive: File, selection: BackupContentSelection) {
        val stagingRoot = createStagingDirectory()
        val staged = BackupArchiveSecurity.stageAndPreflight(
            archive = archive,
            stagingRoot = stagingRoot,
            decodeSettings = { source ->
                json.decodeFromString<Settings>(SettingsJsonMigrator.migrate(source))
            },
            decodePluginSettings = { source -> json.decodeFromString<PluginSettingsExport>(source) },
            restoreTargets = BackupRestoreTargets(
                uploadsRoot = if (selection.includeFiles) uploadsRoot() else null,
                skillsRoot = if (selection.includeFiles) skillsRoot() else null,
                pluginsRoot = if (selection.includeFiles && selection.includePlugins) pluginsRoot() else null,
                uploadsArchiveDirectory = FileFolders.UPLOAD,
                skillsArchiveDirectory = FileFolders.SKILLS,
                pluginsArchiveDirectory = PluginScanner.PLUGINS_DIR,
            ),
        )

        try {
            // Archive input and every selected target path are validated before the first application-data write.
            settingsStore.update(staged.settings)
            if (selection.includeDatabase) restoreDatabase(staged.root)
            if (selection.includeFiles) {
                restoreUploads(staged.root)
                restoreSkills(staged.root)
                if (selection.includePlugins) {
                    restorePlugins(staged.root)
                    staged.pluginSettings?.let { pluginRepository?.importPluginSettings(it) }
                }
            }
            Log.i(
                TAG,
                "Backup restore completed: entries=${staged.entryCount}, bytes=${staged.totalExtractedBytes}",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Backup restore failed: ${e.javaClass.simpleName}")
            throw e
        } finally {
            stagingRoot.deleteRecursively()
        }
    }

    fun createCacheFile(prefix: String, suffix: String): File =
        File.createTempFile("$prefix-", suffix, context.cacheDir)

    private fun createStagingDirectory(): File =
        File(context.cacheDir, "backup-staging-${UUID.randomUUID()}")

    private fun addDatabase(zip: ZipOutputStream, budget: ArchiveWriteBudget) {
        val database = context.getDatabasePath("rikka_hub")
        addFileIfPresent(zip, database, "rikka_hub.db", budget)
        addFileIfPresent(zip, File(database.parentFile, "rikka_hub-wal"), "rikka_hub-wal", budget)
        addFileIfPresent(zip, File(database.parentFile, "rikka_hub-shm"), "rikka_hub-shm", budget)
    }

    private fun addDirectory(zip: ZipOutputStream, root: File, prefix: String, budget: ArchiveWriteBudget) {
        if (!root.isDirectory) return
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            addFile(zip, file, prefix + file.relativeTo(root).invariantSeparatorsPath, budget)
        }
    }

    private fun addFileIfPresent(zip: ZipOutputStream, file: File, name: String, budget: ArchiveWriteBudget) {
        if (file.isFile) addFile(zip, file, name, budget)
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String, budget: ArchiveWriteBudget) {
        BackupArchiveSecurity.validateEntryName(name)
        budget.addEntry(file.length())
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { input ->
            BackupArchiveSecurity.copyLimited(
                input,
                zip,
                MAX_BACKUP_ENTRY_BYTES,
                BackupArchiveFailure.ENTRY_TOO_LARGE,
            )
        }
        zip.closeEntry()
    }

    private fun addText(zip: ZipOutputStream, name: String, content: String, budget: ArchiveWriteBudget) {
        BackupArchiveSecurity.validateEntryName(name)
        val bytes = content.toByteArray(Charsets.UTF_8)
        val maximum = if (name == "plugin_settings.json") {
            MAX_PLUGIN_SETTINGS_JSON_BYTES
        } else {
            MAX_SETTINGS_JSON_BYTES
        }
        if (bytes.size > maximum) {
            throw BackupArchiveException(
                if (name == "plugin_settings.json") BackupArchiveFailure.PLUGIN_SETTINGS_TOO_LARGE
                else BackupArchiveFailure.SETTINGS_TOO_LARGE
            )
        }
        budget.addEntry(bytes.size.toLong())
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun restoreDatabase(staging: File) {
        val database = context.getDatabasePath("rikka_hub")
        listOf(
            "rikka_hub.db" to database,
            "rikka_hub-wal" to File(database.parentFile, "rikka_hub-wal"),
            "rikka_hub-shm" to File(database.parentFile, "rikka_hub-shm"),
        ).forEach { (name, target) ->
            copyStagedFile(staging.resolve(name), target)
        }
    }

    private fun restoreUploads(staging: File) {
        val sourceRoot = staging.resolve(FileFolders.UPLOAD)
        val targetRoot = uploadsRoot()
        restoreDirectory(sourceRoot, targetRoot) { relative ->
            BackupArchiveSecurity.resolveInside(targetRoot, relative)
        }
    }

    private fun restorePlugins(staging: File) {
        val sourceRoot = staging.resolve(PluginScanner.PLUGINS_DIR)
        val targetRoot = pluginsRoot()
        restoreDirectory(sourceRoot, targetRoot) { relative ->
            BackupArchiveSecurity.resolveInside(targetRoot, relative)
        }
    }

    private fun restoreSkills(staging: File) {
        val sourceRoot = staging.resolve(FileFolders.SKILLS)
        if (!sourceRoot.isDirectory) return
        val skillsRoot = skillsRoot()
        sourceRoot.walkTopDown().filter { it.isFile }.forEach { source ->
            val relative = source.relativeTo(sourceRoot).invariantSeparatorsPath
            val skillName = relative.substringBefore('/', missingDelimiterValue = "")
            val skillPath = relative.substringAfter('/', missingDelimiterValue = "")
            val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName)
                ?: throw BackupArchiveException(BackupArchiveFailure.INVALID_ENTRY_PATH)
            val target = SkillPaths.resolveSkillFile(skillDir, skillPath)
                ?: throw BackupArchiveException(BackupArchiveFailure.INVALID_ENTRY_PATH)
            copyStagedFile(source, target)
        }
    }

    private fun uploadsRoot(): File = File(context.filesDir, FileFolders.UPLOAD)

    private fun skillsRoot(): File = File(context.filesDir, FileFolders.SKILLS)

    private fun pluginsRoot(): File = File(Environment.getExternalStorageDirectory(), PluginScanner.PLUGINS_DIR)

    private fun restoreDirectory(sourceRoot: File, targetRoot: File, targetFor: (String) -> File) {
        if (!sourceRoot.isDirectory) return
        sourceRoot.walkTopDown().filter { it.isFile }.forEach { source ->
            val relative = source.relativeTo(sourceRoot).invariantSeparatorsPath
            copyStagedFile(source, targetFor(relative))
        }
    }

    private fun copyStagedFile(source: File, target: File) {
        if (!source.isFile) return
        target.parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                BackupArchiveSecurity.copyLimited(
                    input,
                    output,
                    MAX_BACKUP_ENTRY_BYTES,
                    BackupArchiveFailure.ENTRY_TOO_LARGE,
                )
            }
        }
    }

    private class ArchiveWriteBudget {
        private var entries = 0
        private var totalBytes = 0L

        fun addEntry(bytes: Long) {
            if (++entries > MAX_BACKUP_ENTRY_COUNT) {
                throw BackupArchiveException(BackupArchiveFailure.TOO_MANY_ENTRIES)
            }
            if (bytes > MAX_BACKUP_ENTRY_BYTES) {
                throw BackupArchiveException(BackupArchiveFailure.ENTRY_TOO_LARGE)
            }
            if (bytes > MAX_BACKUP_TOTAL_EXTRACTED_BYTES - totalBytes) {
                throw BackupArchiveException(BackupArchiveFailure.TOTAL_TOO_LARGE)
            }
            totalBytes += bytes
        }
    }

    private class BoundedOutputStream(output: OutputStream, private val maximum: Long) :
        FilterOutputStream(output) {
        private var written = 0L

        override fun write(value: Int) {
            ensureCapacity(1)
            out.write(value)
            written++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            ensureCapacity(length)
            out.write(buffer, offset, length)
            written += length
        }

        private fun ensureCapacity(length: Int) {
            if (length > maximum - written) {
                throw BackupArchiveException(BackupArchiveFailure.ARCHIVE_TOO_LARGE)
            }
        }
    }
}
