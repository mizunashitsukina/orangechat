/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.files.SkillPaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Central limits for backup archives. The defaults accept practical phone backups while bounding
 * disk, heap, and CPU exposure. Tests inject smaller limits instead of allocating large fixtures.
 */
data class BackupArchiveLimits(
    val maxCompressedBytes: Long = MAX_BACKUP_COMPRESSED_BYTES,
    val maxEntries: Int = MAX_BACKUP_ENTRY_COUNT,
    val maxEntryBytes: Long = MAX_BACKUP_ENTRY_BYTES,
    val maxTotalExtractedBytes: Long = MAX_BACKUP_TOTAL_EXTRACTED_BYTES,
    val maxSettingsJsonBytes: Long = MAX_SETTINGS_JSON_BYTES,
    val maxPluginSettingsJsonBytes: Long = MAX_PLUGIN_SETTINGS_JSON_BYTES,
)

const val MAX_BACKUP_COMPRESSED_BYTES: Long = 512L * 1024 * 1024
const val MAX_BACKUP_ENTRY_COUNT: Int = 10_000
const val MAX_BACKUP_ENTRY_BYTES: Long = 256L * 1024 * 1024
const val MAX_BACKUP_TOTAL_EXTRACTED_BYTES: Long = 2L * 1024 * 1024 * 1024
const val MAX_SETTINGS_JSON_BYTES: Long = 8L * 1024 * 1024
const val MAX_PLUGIN_SETTINGS_JSON_BYTES: Long = 8L * 1024 * 1024
const val MAX_THIRD_PARTY_IMPORT_BYTES: Long = 64L * 1024 * 1024
const val MAX_REMOTE_METADATA_BYTES: Long = 8L * 1024 * 1024

private val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:")
private val PROTECTED_ROOT_ENTRIES = setOf(
    "settings.json",
    "plugin_settings.json",
    "rikka_hub.db",
    "rikka_hub-wal",
    "rikka_hub-shm",
)

class BackupArchiveException internal constructor(
    val reason: BackupArchiveFailure,
    cause: Throwable? = null,
) : Exception(reason.safeMessage, cause)

enum class BackupArchiveFailure(val safeMessage: String) {
    ARCHIVE_TOO_LARGE("Backup archive exceeds the allowed size"),
    INVALID_ARCHIVE("Backup archive is invalid or damaged"),
    INVALID_ENTRY_PATH("Backup archive contains an unsafe path"),
    TOO_MANY_ENTRIES("Backup archive contains too many entries"),
    ENTRY_TOO_LARGE("Backup archive entry exceeds the allowed size"),
    TOTAL_TOO_LARGE("Backup archive expands beyond the allowed size"),
    SETTINGS_TOO_LARGE("Backup settings exceed the allowed size"),
    PLUGIN_SETTINGS_TOO_LARGE("Plugin settings exceed the allowed size"),
    DUPLICATE_PROTECTED_ENTRY("Backup archive contains duplicate protected data"),
    MISSING_SETTINGS("Backup archive does not contain settings"),
    INVALID_SETTINGS("Backup settings are invalid"),
    INVALID_PLUGIN_SETTINGS("Plugin settings are invalid"),
    TEMPORARY_STORAGE_ERROR("Unable to prepare backup safely"),
}

data class StagedBackupArchive<TSettings, TPluginSettings>(
    val root: File,
    val settings: TSettings,
    val pluginSettings: TPluginSettings?,
    val entryCount: Int,
    val totalExtractedBytes: Long,
)

data class BackupRestoreTargets(
    val uploadsRoot: File? = null,
    val skillsRoot: File? = null,
    val pluginsRoot: File? = null,
    val uploadsArchiveDirectory: String = "upload",
    val skillsArchiveDirectory: String = "skills",
    val pluginsArchiveDirectory: String = "Orangechat/plugins",
)

data class BackupSourceFile(
    val file: File,
    val relativePath: String,
)

class BackupArchiveWriteBudget(
    private val limits: BackupArchiveLimits = BackupArchiveLimits(),
) {
    private var entries = 0
    private var totalBytes = 0L
    private var entryBytes = 0L
    private var entryLimit = 0L
    private var entryFailure = BackupArchiveFailure.ENTRY_TOO_LARGE

    fun beginEntry(maxBytes: Long, failure: BackupArchiveFailure) {
        if (++entries > limits.maxEntries) {
            throw BackupArchiveException(BackupArchiveFailure.TOO_MANY_ENTRIES)
        }
        entryBytes = 0
        entryLimit = maxBytes
        entryFailure = failure
    }

    fun write(output: OutputStream, buffer: ByteArray, offset: Int, length: Int) {
        if (length > entryLimit - entryBytes) throw BackupArchiveException(entryFailure)
        if (length > limits.maxTotalExtractedBytes - totalBytes) {
            throw BackupArchiveException(BackupArchiveFailure.TOTAL_TOO_LARGE)
        }
        output.write(buffer, offset, length)
        entryBytes += length
        totalBytes += length
    }
}

object BackupArchiveSecurity {
    fun collectSafeSourceFiles(root: File): List<BackupSourceFile> {
        val rootPath = root.toPath()
        if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        if (Files.isSymbolicLink(rootPath) || !Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            throw BackupArchiveException(BackupArchiveFailure.INVALID_ENTRY_PATH)
        }
        val canonicalRoot = root.canonicalFile.toPath()
        val files = mutableListOf<BackupSourceFile>()
        Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                validateSourcePath(dir, attrs, canonicalRoot)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                validateSourcePath(file, attrs, canonicalRoot)
                if (!attrs.isRegularFile) {
                    throw BackupArchiveException(BackupArchiveFailure.INVALID_ENTRY_PATH)
                }
                val relative = rootPath.relativize(file).toString().replace(File.separatorChar, '/')
                validateEntryName(relative)
                files += BackupSourceFile(file.toFile(), relative)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                throw BackupArchiveException(BackupArchiveFailure.INVALID_ENTRY_PATH)
            }
        })
        return files
    }

    fun openSafeSourceFile(root: File, file: File): InputStream {
        val rootPath = root.toPath()
        val filePath = file.toPath()
        if (Files.isSymbolicLink(rootPath) || Files.isSymbolicLink(filePath) ||
            !Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw BackupArchiveException(BackupArchiveFailure.INVALID_ENTRY_PATH)
        }
        val canonicalRoot = root.canonicalFile.toPath()
        val canonicalFile = file.canonicalFile.toPath()
        if (!canonicalFile.startsWith(canonicalRoot) || canonicalFile == canonicalRoot) {
            throw BackupArchiveException(BackupArchiveFailure.INVALID_ENTRY_PATH)
        }
        return try {
            Files.newInputStream(filePath, LinkOption.NOFOLLOW_LINKS)
        } catch (e: java.io.IOException) {
            throw BackupArchiveException(BackupArchiveFailure.INVALID_ENTRY_PATH)
        }
    }

    suspend fun transferTemporaryFileOwnership(
        prepare: suspend () -> File,
        beforeTransfer: suspend () -> Unit,
    ): File {
        val file = prepare()
        try {
            beforeTransfer()
            return file
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    private fun validateSourcePath(path: Path, attrs: BasicFileAttributes, canonicalRoot: Path) {
        if (attrs.isSymbolicLink || Files.isSymbolicLink(path)) {
            throw BackupArchiveException(BackupArchiveFailure.INVALID_ENTRY_PATH)
        }
        val canonicalPath = path.toFile().canonicalFile.toPath()
        if (!canonicalPath.startsWith(canonicalRoot)) {
            throw BackupArchiveException(BackupArchiveFailure.INVALID_ENTRY_PATH)
        }
    }

    fun validateEntryName(name: String): String {
        if (name.isEmpty() || name.indexOf('\u0000') >= 0) {
            throw BackupArchiveException(BackupArchiveFailure.INVALID_ENTRY_PATH)
        }
        if (name.startsWith('/') || name.startsWith('\\') || name.startsWith("//") ||
            name.startsWith("\\\\") || WINDOWS_DRIVE_PATH.containsMatchIn(name)
        ) {
            throw BackupArchiveException(BackupArchiveFailure.INVALID_ENTRY_PATH)
        }

        val normalized = name.replace('\\', '/')
        val segments = normalized.split('/')
        if (segments.withIndex().any { (index, segment) ->
                (segment.isEmpty() && index != segments.lastIndex) || segment == "." || segment == ".."
            }
        ) {
            throw BackupArchiveException(BackupArchiveFailure.INVALID_ENTRY_PATH)
        }
        if (normalized.isBlank()) {
            throw BackupArchiveException(BackupArchiveFailure.INVALID_ENTRY_PATH)
        }
        return normalized
    }

    fun resolveInside(root: File, relativePath: String): File {
        val normalized = validateEntryName(relativePath).trimEnd('/')
        if (normalized.isEmpty()) {
            throw BackupArchiveException(BackupArchiveFailure.INVALID_ENTRY_PATH)
        }
        val rootPath = root.canonicalFile.toPath()
        val targetPath = root.resolve(normalized).canonicalFile.toPath()
        if (!targetPath.startsWith(rootPath) || targetPath == rootPath) {
            throw BackupArchiveException(BackupArchiveFailure.INVALID_ENTRY_PATH)
        }
        return targetPath.toFile()
    }

    fun <TSettings, TPluginSettings> stageAndPreflight(
        archive: File,
        stagingRoot: File,
        limits: BackupArchiveLimits = BackupArchiveLimits(),
        decodeSettings: (String) -> TSettings,
        decodePluginSettings: (String) -> TPluginSettings,
        restoreTargets: BackupRestoreTargets? = null,
    ): StagedBackupArchive<TSettings, TPluginSettings> {
        if (!archive.isFile || archive.length() > limits.maxCompressedBytes) {
            throw BackupArchiveException(BackupArchiveFailure.ARCHIVE_TOO_LARGE)
        }
        if (stagingRoot.exists() || !stagingRoot.mkdirs()) {
            throw BackupArchiveException(BackupArchiveFailure.TEMPORARY_STORAGE_ERROR)
        }

        var entryCount = 0
        var totalBytes = 0L
        val protectedEntries = mutableSetOf<String>()
        var decodedSettings: TSettings? = null
        var pluginSettings: TPluginSettings? = null

        try {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > limits.maxEntries) {
                        throw BackupArchiveException(BackupArchiveFailure.TOO_MANY_ENTRIES)
                    }
                    val name = validateEntryName(entry.name)
                    if (name in PROTECTED_ROOT_ENTRIES && !protectedEntries.add(name)) {
                        throw BackupArchiveException(BackupArchiveFailure.DUPLICATE_PROTECTED_ENTRY)
                    }
                    val target = resolveInside(stagingRoot, name)
                    if (entry.isDirectory) {
                        if (!target.mkdirs() && !target.isDirectory) {
                            throw BackupArchiveException(BackupArchiveFailure.TEMPORARY_STORAGE_ERROR)
                        }
                    } else {
                        target.parentFile?.let { parent ->
                            if (!parent.mkdirs() && !parent.isDirectory) {
                                throw BackupArchiveException(BackupArchiveFailure.TEMPORARY_STORAGE_ERROR)
                            }
                        }
                        target.outputStream().buffered().use { output ->
                            val (entryLimit, entryFailure) = entryLimit(name, limits)
                            val written = copyLimited(
                                input = zip,
                                output = output,
                                maxBytes = entryLimit,
                                failure = entryFailure,
                                totalRemainingBytes = limits.maxTotalExtractedBytes - totalBytes,
                            )
                            totalBytes += written
                        }
                    }
                    zip.closeEntry()
                }
            }

            val settingsFile = stagingRoot.resolve("settings.json")
            if (!settingsFile.isFile) throw BackupArchiveException(BackupArchiveFailure.MISSING_SETTINGS)
            decodedSettings = try {
                decodeSettings(readLimitedText(settingsFile, limits.maxSettingsJsonBytes))
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupArchiveException) {
                throw e
            } catch (e: Exception) {
                throw BackupArchiveException(BackupArchiveFailure.INVALID_SETTINGS, e)
            }

            val pluginFile = stagingRoot.resolve("plugin_settings.json")
            if (pluginFile.exists()) {
                pluginSettings = try {
                    decodePluginSettings(readLimitedText(pluginFile, limits.maxPluginSettingsJsonBytes))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BackupArchiveException) {
                    throw e
                } catch (e: Exception) {
                    throw BackupArchiveException(BackupArchiveFailure.INVALID_PLUGIN_SETTINGS, e)
                }
            }

            restoreTargets?.let { preflightRestoreTargets(stagingRoot, it) }

            @Suppress("UNCHECKED_CAST")
            return StagedBackupArchive(
                stagingRoot,
                decodedSettings as TSettings,
                pluginSettings,
                entryCount,
                totalBytes,
            )
        } catch (e: CancellationException) {
            stagingRoot.deleteRecursively()
            throw e
        } catch (e: BackupArchiveException) {
            stagingRoot.deleteRecursively()
            throw e
        } catch (e: ZipException) {
            stagingRoot.deleteRecursively()
            throw BackupArchiveException(BackupArchiveFailure.INVALID_ARCHIVE, e)
        } catch (e: Exception) {
            stagingRoot.deleteRecursively()
            throw BackupArchiveException(BackupArchiveFailure.INVALID_ARCHIVE, e)
        }
    }

    fun preflightRestoreTargets(stagingRoot: File, targets: BackupRestoreTargets) {
        targets.uploadsRoot?.let { targetRoot ->
            preflightDirectoryTargets(
                stagingRoot.resolve(targets.uploadsArchiveDirectory),
                targetRoot,
            ) { root, relative -> resolveInside(root, relative) }
        }
        targets.pluginsRoot?.let { targetRoot ->
            preflightDirectoryTargets(
                stagingRoot.resolve(targets.pluginsArchiveDirectory),
                targetRoot,
            ) { root, relative -> resolveInside(root, relative) }
        }
        targets.skillsRoot?.let { skillsRoot ->
            val sourceRoot = stagingRoot.resolve(targets.skillsArchiveDirectory)
            if (!sourceRoot.isDirectory) return@let
            sourceRoot.walkTopDown().filter { it.isFile }.forEach { source ->
                val relative = source.relativeTo(sourceRoot).invariantSeparatorsPath
                val separator = relative.indexOf('/')
                if (separator <= 0 || separator == relative.lastIndex) {
                    throw BackupArchiveException(BackupArchiveFailure.INVALID_ENTRY_PATH)
                }
                val skillName = relative.substring(0, separator)
                val skillRelativePath = relative.substring(separator + 1)
                val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName)
                    ?: throw BackupArchiveException(BackupArchiveFailure.INVALID_ENTRY_PATH)
                SkillPaths.resolveSkillFile(skillDir, skillRelativePath)
                    ?: throw BackupArchiveException(BackupArchiveFailure.INVALID_ENTRY_PATH)
            }
        }
    }

    private fun preflightDirectoryTargets(
        sourceRoot: File,
        targetRoot: File,
        resolveTarget: (File, String) -> File,
    ) {
        if (!sourceRoot.isDirectory) return
        sourceRoot.walkTopDown().filter { it.isFile }.forEach { source ->
            resolveTarget(targetRoot, source.relativeTo(sourceRoot).invariantSeparatorsPath)
        }
    }

    fun readLimitedText(file: File, maxBytes: Long): String {
        if (!file.isFile || file.length() > maxBytes) {
            val failure = textLimitFailure(file, maxBytes)
            throw BackupArchiveException(failure)
        }
        return file.inputStream().use { input ->
            val output = ByteArrayOutputStream(minOf(file.length(), maxBytes).toInt())
            copyLimited(input, output, maxBytes, textLimitFailure(file, maxBytes))
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun textLimitFailure(file: File, maxBytes: Long): BackupArchiveFailure = when {
        file.name == "plugin_settings.json" -> BackupArchiveFailure.PLUGIN_SETTINGS_TOO_LARGE
        maxBytes == MAX_THIRD_PARTY_IMPORT_BYTES -> BackupArchiveFailure.ENTRY_TOO_LARGE
        else -> BackupArchiveFailure.SETTINGS_TOO_LARGE
    }

    fun copyLimited(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
        failure: BackupArchiveFailure = BackupArchiveFailure.ARCHIVE_TOO_LARGE,
        totalRemainingBytes: Long = Long.MAX_VALUE,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > maxBytes - total) throw BackupArchiveException(failure)
            if (read > totalRemainingBytes - total) {
                throw BackupArchiveException(BackupArchiveFailure.TOTAL_TOO_LARGE)
            }
            output.write(buffer, 0, read)
            total += read
        }
        return total
    }

    fun copyArchiveEntry(
        input: InputStream,
        output: OutputStream,
        budget: BackupArchiveWriteBudget,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            budget.write(output, buffer, 0, read)
        }
    }

    private fun entryLimit(
        name: String,
        limits: BackupArchiveLimits,
    ): Pair<Long, BackupArchiveFailure> = when (name) {
        "settings.json" -> limits.maxSettingsJsonBytes to BackupArchiveFailure.SETTINGS_TOO_LARGE
        "plugin_settings.json" -> {
            limits.maxPluginSettingsJsonBytes to BackupArchiveFailure.PLUGIN_SETTINGS_TOO_LARGE
        }
        else -> limits.maxEntryBytes to BackupArchiveFailure.ENTRY_TOO_LARGE
    }
}
