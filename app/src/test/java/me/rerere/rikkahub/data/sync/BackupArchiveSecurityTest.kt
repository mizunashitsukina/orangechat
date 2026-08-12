/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.files.SkillPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveSecurityTest {
    @Test
    fun legalLegacyArchivePassesPreflight() = withTempDir { root ->
        val archive = zip(root, mapOf(
            "settings.json" to "legacy-settings",
            "rikka_hub.db" to "database",
            "uploads/photo.jpg" to "image",
            "skills/example/SKILL.md" to "skill",
            "plugins/example/index.js" to "plugin",
        ))
        val staging = root.resolve("staging")
        val result = preflight(archive, staging)
        assertEquals("legacy-settings", result.settings)
        assertTrue(staging.resolve("uploads/photo.jpg").isFile)
        staging.deleteRecursively()
    }

    @Test fun forwardSlashTraversalIsRejected() = assertUnsafe("../escape")
    @Test fun backslashTraversalIsRejected() = assertUnsafe("uploads\\..\\escape")
    @Test fun absolutePathIsRejected() = assertUnsafe("/absolute/path")

    @Test
    fun windowsDriveAndUncPathsAreRejected() {
        listOf(
            "C:\\private\\file",
            "C:/private/file",
            "C:relative-file",
            "\\\\server\\share\\file",
            "//server/share/file",
        )
            .forEach(::assertUnsafe)
    }

    @Test fun nulCharacterIsRejected() = assertUnsafe("uploads/bad\u0000name")

    @Test
    fun uploadsPathCannotEscapeRoot() = withTempDir { root ->
        assertFails(BackupArchiveFailure.INVALID_ENTRY_PATH) {
            BackupArchiveSecurity.resolveInside(root.resolve("uploads"), "../outside")
        }
    }

    @Test
    fun pluginsPathCannotEscapeRoot() = withTempDir { root ->
        assertFails(BackupArchiveFailure.INVALID_ENTRY_PATH) {
            BackupArchiveSecurity.resolveInside(root.resolve("plugins"), "child/../../outside")
        }
    }

    @Test
    fun skillsContinueUsingSkillPathsSafety() = withTempDir { root ->
        val skillsRoot = root.resolve("skills").apply { mkdirs() }
        val skill = SkillPaths.resolveSkillDir(skillsRoot, "safe-skill")
        assertNotNull(skill)
        assertNotNull(SkillPaths.resolveSkillFile(skill!!, "references/info.md"))
        assertNull(SkillPaths.resolveSkillDir(skillsRoot, "../outside"))
        assertNull(SkillPaths.resolveSkillFile(skill, "../../outside"))
    }

    @Test
    fun duplicateSettingsIsRejected() = withTempDir { root ->
        val archive = zip(root, linkedMapOf(
            "settings.json" to "first",
            "settingx.json" to "second",
        ))
        val bytes = archive.readBytes()
        replaceAll(bytes, "settingx.json".toByteArray(), "settings.json".toByteArray())
        archive.writeBytes(bytes)
        assertPreflightFails(archive, root.resolve("staging"), BackupArchiveFailure.DUPLICATE_PROTECTED_ENTRY)
    }

    @Test
    fun singleEntryLimitIsEnforced() = withTempDir { root ->
        val archive = zip(root, mapOf("settings.json" to "0123456789"))
        assertPreflightFails(
            archive,
            root.resolve("staging"),
            BackupArchiveFailure.ENTRY_TOO_LARGE,
            limits(maxEntryBytes = 4),
        )
    }

    @Test
    fun totalExtractedLimitIsEnforced() = withTempDir { root ->
        val archive = zip(root, mapOf("settings.json" to "1234", "unknown.bin" to "5678"))
        assertPreflightFails(
            archive,
            root.resolve("staging"),
            BackupArchiveFailure.TOTAL_TOO_LARGE,
            limits(maxTotalExtractedBytes = 6),
        )
    }

    @Test
    fun entryCountLimitIsEnforced() = withTempDir { root ->
        val archive = zip(root, mapOf("settings.json" to "ok", "one" to "1", "two" to "2"))
        assertPreflightFails(
            archive,
            root.resolve("staging"),
            BackupArchiveFailure.TOO_MANY_ENTRIES,
            limits(maxEntries = 2),
        )
    }

    @Test
    fun oversizedSettingsJsonIsRejected() = withTempDir { root ->
        val archive = zip(root, mapOf("settings.json" to "0123456789"))
        assertPreflightFails(
            archive,
            root.resolve("staging"),
            BackupArchiveFailure.SETTINGS_TOO_LARGE,
            limits(maxSettingsJsonBytes = 4),
        )
    }

    @Test
    fun compressedArchiveLimitIsEnforced() = withTempDir { root ->
        val archive = zip(root, mapOf("settings.json" to "settings"))
        assertPreflightFails(
            archive,
            root.resolve("staging"),
            BackupArchiveFailure.ARCHIVE_TOO_LARGE,
            limits().copy(maxCompressedBytes = archive.length() - 1),
        )
    }

    @Test
    fun damagedZipFailsBeforeApplicationWriteAndCleansStaging() = withTempDir { root ->
        val archive = root.resolve("damaged.zip").apply { writeText("not a zip") }
        val staging = root.resolve("staging")
        var applicationWrites = 0
        try {
            BackupArchiveSecurity.stageAndPreflight(
                archive,
                staging,
                limits(),
                decodeSettings = { applicationWrites++; it },
                decodePluginSettings = { it },
            )
            fail("Expected damaged archive rejection")
        } catch (_: BackupArchiveException) {
            assertEquals(0, applicationWrites)
            assertFalse(staging.exists())
        }
    }

    @Test
    fun failureCleansStagingAndTemporaryContent() = withTempDir { root ->
        val archive = zip(root, mapOf("settings.json" to "secret", "../escape" to "bad"))
        val staging = root.resolve("staging")
        assertPreflightFails(archive, staging, BackupArchiveFailure.INVALID_ENTRY_PATH)
        assertFalse(staging.exists())
    }

    @Test
    fun safeArchiveCompletesRestorePreparation() = withTempDir { root ->
        val archive = zip(root, mapOf(
            "settings.json" to "settings",
            "plugin_settings.json" to "plugins",
            "uploads/nested/file.txt" to "content",
            "unknown/safe.bin" to "unknown",
        ))
        val result = preflight(archive, root.resolve("staging"))
        assertEquals("settings", result.settings)
        assertEquals("plugins", result.pluginSettings)
        assertEquals(4, result.entryCount)
        result.root.deleteRecursively()
    }

    @Test
    fun diagnosticsDoNotContainSensitiveInput() = withTempDir { root ->
        val marker = "secret-token-private-url"
        val archive = zip(root, mapOf("../$marker" to "payload"))
        val failure = try {
            preflight(archive, root.resolve("staging"))
            fail("Expected unsafe path rejection")
            null
        } catch (e: BackupArchiveException) {
            e
        }
        assertFalse(failure!!.message.orEmpty().contains(marker))
        assertFalse(failure.toString().contains(marker))
    }

    private fun assertUnsafe(name: String) {
        assertFails(BackupArchiveFailure.INVALID_ENTRY_PATH) {
            BackupArchiveSecurity.validateEntryName(name)
        }
    }

    private fun preflight(archive: File, staging: File, customLimits: BackupArchiveLimits = limits()) =
        BackupArchiveSecurity.stageAndPreflight(
            archive,
            staging,
            customLimits,
            decodeSettings = { it },
            decodePluginSettings = { it },
        )

    private fun assertPreflightFails(
        archive: File,
        staging: File,
        reason: BackupArchiveFailure,
        customLimits: BackupArchiveLimits = limits(),
    ) {
        assertFails(reason) { preflight(archive, staging, customLimits) }
        assertFalse(staging.exists())
    }

    private fun assertFails(reason: BackupArchiveFailure, block: () -> Unit) {
        try {
            block()
            fail("Expected $reason")
        } catch (e: BackupArchiveException) {
            assertEquals(reason, e.reason)
        }
    }

    private fun zip(root: File, entries: Map<String, String>): File {
        val archive = File.createTempFile("archive-", ".zip", root)
        ZipOutputStream(archive.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return archive
    }

    private fun replaceAll(bytes: ByteArray, from: ByteArray, to: ByteArray) {
        require(from.size == to.size)
        for (start in 0..bytes.size - from.size) {
            if (from.indices.all { bytes[start + it] == from[it] }) {
                to.indices.forEach { bytes[start + it] = to[it] }
            }
        }
    }

    private fun limits(
        maxEntries: Int = 100,
        maxEntryBytes: Long = 1024,
        maxTotalExtractedBytes: Long = 4096,
        maxSettingsJsonBytes: Long = 1024,
    ) = BackupArchiveLimits(
        maxCompressedBytes = 4096,
        maxEntries = maxEntries,
        maxEntryBytes = maxEntryBytes,
        maxTotalExtractedBytes = maxTotalExtractedBytes,
        maxSettingsJsonBytes = maxSettingsJsonBytes,
        maxPluginSettingsJsonBytes = 1024,
    )

    private fun withTempDir(block: (File) -> Unit) {
        val root = createTempDir(prefix = "backup-security-")
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
