/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.files.SkillPaths
import me.rerere.rikkahub.utils.JsonInstant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
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

    @Test
    fun rikkahub248FullAppDataArchiveReachesRestoreReadyStage() = withTempDir { root ->
        val archive = zip(root, mapOf(
            "settings.json" to rikkahubSettingsJson(),
            "rikka_hub.db" to "database",
            "rikka_hub-wal" to "wal",
            "rikka_hub-shm" to "shm",
            "upload/attachment.bin" to "upload",
            "skills/example/SKILL.md" to "skill",
            "fonts/custom-font.ttf" to "font",
        ))
        val staging = root.resolve("staging")
        val applicationRoot = root.resolve("application")

        val result = BackupArchiveSecurity.stageAndPreflight<Settings, String>(
            archive = archive,
            stagingRoot = staging,
            limits = rikkahubFixtureLimits(),
            decodeSettings = { source ->
                SettingsJsonMigrator.decodeBackupSettings(source)
            },
            decodePluginSettings = { it },
            restoreTargets = restoreTargets(root),
        )

        assertEquals(RIKKAHUB_FAST_MODEL_ID, result.settings.titleModelId.toString())
        assertEquals(RIKKAHUB_FAST_MODEL_ID, result.settings.suggestionModelId.toString())
        assertTrue(staging.resolve("fonts/custom-font.ttf").isFile)
        assertFalse(applicationRoot.resolve("fonts/custom-font.ttf").exists())
        assertEquals(7, result.entryCount)
        result.root.deleteRecursively()
    }

    @Test
    fun unknownProviderTypeFailsBeforeAnyRestoreTargetWrite() = withTempDir { root ->
        val providerType = "future-provider-marker"
        val archive = zip(root, mapOf(
            "settings.json" to rikkahubSettingsJson(providerType),
            "upload/attachment.bin" to "upload",
        ))
        val staging = root.resolve("staging")
        val applicationRoot = root.resolve("application")
        val failure = try {
            BackupArchiveSecurity.stageAndPreflight<Settings, String>(
                archive = archive,
                stagingRoot = staging,
                limits = rikkahubFixtureLimits(),
                decodeSettings = { source ->
                    SettingsJsonMigrator.decodeBackupSettings(source)
                },
                decodePluginSettings = { it },
                restoreTargets = restoreTargets(root),
            )
            fail("Expected unknown provider type to be rejected")
            null
        } catch (e: BackupArchiveException) {
            e
        }

        assertEquals(BackupArchiveFailure.INVALID_SETTINGS, failure!!.reason)
        assertEquals("BR-30-P", backupRestoreDiagnosticValue(failure))
        assertFalse(failure.message.orEmpty().contains(providerType))
        assertFalse(staging.exists())
        assertFalse(applicationRoot.resolve("upload/attachment.bin").exists())
    }

    @Test
    fun nullableModelWithoutARealFallbackRemainsInvalidSettings() = withTempDir { root ->
        val archive = zip(root, mapOf(
            "settings.json" to """{"titleModelId":null,"providers":[]}""",
        ))
        val staging = root.resolve("staging")
        val failure = try {
            BackupArchiveSecurity.stageAndPreflight<Settings, String>(
                archive = archive,
                stagingRoot = staging,
                limits = limits(),
                decodeSettings = { source -> SettingsJsonMigrator.decodeBackupSettings(source) },
                decodePluginSettings = { it },
            )
            fail("Expected settings without a real model fallback to be rejected")
            null
        } catch (e: BackupArchiveException) {
            e
        }

        assertEquals(BackupArchiveFailure.INVALID_SETTINGS, failure!!.reason)
        assertEquals("BR-30-C", backupRestoreDiagnosticValue(failure))
        assertFalse(staging.exists())
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
    fun ordinarySourceDirectoryIsCollectedWithoutChangingRelativePaths() = withTempDir { root ->
        val source = root.resolve("source").apply { mkdirs() }
        source.resolve("nested").mkdirs()
        source.resolve("nested/file.txt").writeText("content")

        val files = BackupArchiveSecurity.collectSafeSourceFiles(source)

        assertEquals(listOf("nested/file.txt"), files.map { it.relativePath })
    }

    @Test
    fun sourceFileSymlinkOutsideRootIsRejectedWithoutSensitiveDiagnostics() = withTempDir { root ->
        val marker = "secret-source-marker"
        val source = root.resolve("source").apply { mkdirs() }
        val outside = root.resolve(marker).apply { writeText("private") }
        createSymlinkOrSkip(source.resolve("linked-file").toPath(), outside.toPath())

        val failure = sourceTraversalFailure(source)

        assertFalse(failure.message.orEmpty().contains(marker))
        assertFalse(failure.toString().contains(root.absolutePath))
    }

    @Test
    fun sourceDirectorySymlinkOutsideRootIsRejected() = withTempDir { root ->
        val source = root.resolve("source").apply { mkdirs() }
        val outside = root.resolve("outside").apply { mkdirs() }
        outside.resolve("private.txt").writeText("private")
        createSymlinkOrSkip(source.resolve("linked-directory").toPath(), outside.toPath())

        sourceTraversalFailure(source)
    }

    @Test
    fun sourceSymlinkLoopIsRejectedWithoutTraversal() = withTempDir { root ->
        val source = root.resolve("source").apply { mkdirs() }
        val nested = source.resolve("nested").apply { mkdirs() }
        createSymlinkOrSkip(nested.resolve("loop").toPath(), source.toPath())

        sourceTraversalFailure(source)
    }

    @Test
    fun singleLevelSkillFileFailsTargetPreflightBeforeItReturns() = withTempDir { root ->
        val archive = zip(root, mapOf(
            "settings.json" to "settings",
            "skills/orphan.txt" to "invalid",
        ))
        val staging = root.resolve("staging")

        assertFails(BackupArchiveFailure.INVALID_ENTRY_PATH) {
            preflight(archive, staging, restoreTargets(root))
        }
        assertFalse(staging.exists())
    }

    @Test
    fun skillNameRejectedBySkillPathsFailsTargetPreflight() = withTempDir { root ->
        val marker = "secret-invalid-skill"
        val archive = zip(root, mapOf(
            "settings.json" to "settings",
            "skills/ /$marker.txt" to "invalid",
        ))
        val staging = root.resolve("staging")
        val failure = try {
            preflight(archive, staging, restoreTargets(root))
            fail("Expected invalid skill rejection")
            null
        } catch (e: BackupArchiveException) {
            e
        }

        assertEquals(BackupArchiveFailure.INVALID_ENTRY_PATH, failure!!.reason)
        assertFalse(failure.message.orEmpty().contains(marker))
        assertFalse(staging.exists())
    }

    @Test
    fun validSkillAndRealUploadAndPluginTargetsPassPreflight() = withTempDir { root ->
        val archive = zip(root, mapOf(
            "settings.json" to "settings",
            "upload/nested/file.txt" to "upload",
            "skills/example/references/info.md" to "skill",
            "Orangechat/plugins/example/index.js" to "plugin",
        ))
        val staging = root.resolve("staging")

        val result = preflight(archive, staging, restoreTargets(root))

        assertTrue(result.root.isDirectory)
        assertFalse(root.resolve("application/upload/nested/file.txt").exists())
        assertFalse(root.resolve("application/plugins/example/index.js").exists())
        result.root.deleteRecursively()
    }

    @Test
    fun existingSymlinkCannotEscapeRealTargetRoot() = withTempDir { root ->
        val applicationRoot = root.resolve("application").apply { mkdirs() }
        val uploadsRoot = applicationRoot.resolve("upload").apply { mkdirs() }
        val outside = root.resolve("outside").apply { mkdirs() }
        try {
            Files.createSymbolicLink(uploadsRoot.resolve("linked").toPath(), outside.toPath())
        } catch (e: UnsupportedOperationException) {
            assumeNoException(e)
        } catch (e: IOException) {
            assumeNoException(e)
        } catch (e: SecurityException) {
            assumeNoException(e)
        }
        val archive = zip(root, mapOf(
            "settings.json" to "settings",
            "upload/linked/escape.txt" to "invalid",
        ))

        assertFails(BackupArchiveFailure.INVALID_ENTRY_PATH) {
            preflight(archive, root.resolve("staging"), restoreTargets(root))
        }
        assertFalse(outside.resolve("escape.txt").exists())
    }

    @Test
    fun existingSymlinkCannotEscapeRealPluginTargetRoot() = withTempDir { root ->
        val applicationRoot = root.resolve("application").apply { mkdirs() }
        val pluginsRoot = applicationRoot.resolve("plugins").apply { mkdirs() }
        val outside = root.resolve("outside").apply { mkdirs() }
        try {
            Files.createSymbolicLink(pluginsRoot.resolve("linked").toPath(), outside.toPath())
        } catch (e: UnsupportedOperationException) {
            assumeNoException(e)
        } catch (e: IOException) {
            assumeNoException(e)
        } catch (e: SecurityException) {
            assumeNoException(e)
        }
        val archive = zip(root, mapOf(
            "settings.json" to "settings",
            "Orangechat/plugins/linked/escape.txt" to "invalid",
        ))

        assertFails(BackupArchiveFailure.INVALID_ENTRY_PATH) {
            preflight(archive, root.resolve("staging"), restoreTargets(root))
        }
        assertFalse(outside.resolve("escape.txt").exists())
    }

    @Test
    fun skillRelativePathSymlinkCannotEscapeSkillDirectory() = withTempDir { root ->
        val applicationRoot = root.resolve("application").apply { mkdirs() }
        val skillDir = applicationRoot.resolve("skills/example").apply { mkdirs() }
        val outside = root.resolve("outside").apply { mkdirs() }
        try {
            Files.createSymbolicLink(skillDir.resolve("linked").toPath(), outside.toPath())
        } catch (e: UnsupportedOperationException) {
            assumeNoException(e)
        } catch (e: IOException) {
            assumeNoException(e)
        } catch (e: SecurityException) {
            assumeNoException(e)
        }
        val archive = zip(root, mapOf(
            "settings.json" to "settings",
            "skills/example/linked/escape.txt" to "invalid",
        ))

        assertFails(BackupArchiveFailure.INVALID_ENTRY_PATH) {
            preflight(archive, root.resolve("staging"), restoreTargets(root))
        }
        assertFalse(outside.resolve("escape.txt").exists())
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
        val archive = zip(root, mapOf(
            "settings.json" to "ok",
            "unknown.bin" to "0123456789",
        ))
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
    fun settingsLimitIsEnforcedDuringExtractionBeforeGenericEntryLimit() = withTempDir { root ->
        val archive = zip(root, mapOf("settings.json" to "0123456789"))
        assertPreflightFails(
            archive,
            root.resolve("staging"),
            BackupArchiveFailure.SETTINGS_TOO_LARGE,
            limits(maxEntryBytes = 100, maxSettingsJsonBytes = 4),
        )
    }

    @Test
    fun pluginSettingsLimitIsEnforcedDuringExtractionBeforeGenericEntryLimit() = withTempDir { root ->
        val archive = zip(root, mapOf(
            "settings.json" to "ok",
            "plugin_settings.json" to "0123456789",
        ))
        assertPreflightFails(
            archive,
            root.resolve("staging"),
            BackupArchiveFailure.PLUGIN_SETTINGS_TOO_LARGE,
            limits(maxEntryBytes = 100, maxPluginSettingsJsonBytes = 4),
        )
    }

    @Test
    fun totalLimitRejectsSecondEntryBeforeWritingPastRemainingBudget() {
        val output = ByteArrayOutputStream()
        val firstWritten = BackupArchiveSecurity.copyLimited(
            ByteArrayInputStream("first!".toByteArray()),
            output,
            maxBytes = 100,
            failure = BackupArchiveFailure.ENTRY_TOO_LARGE,
            totalRemainingBytes = 10,
        )
        assertFails(BackupArchiveFailure.TOTAL_TOO_LARGE) {
            BackupArchiveSecurity.copyLimited(
                ByteArrayInputStream("second-entry".toByteArray()),
                output,
                maxBytes = 100,
                failure = BackupArchiveFailure.ENTRY_TOO_LARGE,
                totalRemainingBytes = 10 - firstWritten,
            )
        }
        assertEquals(firstWritten.toInt(), output.size())
    }

    @Test
    fun archiveCreationBudgetUsesActualStreamBytesWhenSourceGrows() = withTempDir { root ->
        val source = root.resolve("growing.bin").apply { writeText("1234") }
        val initiallyObservedLength = source.length()
        val output = ByteArrayOutputStream()
        val budget = BackupArchiveWriteBudget(limits(maxEntryBytes = 6, maxTotalExtractedBytes = 100))
        budget.beginEntry(6, BackupArchiveFailure.ENTRY_TOO_LARGE)
        val delegate = source.inputStream()
        val growingInput = object : java.io.InputStream() {
            private var grew = false

            override fun read(): Int = delegate.read()

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val read = delegate.read(buffer, offset, minOf(length, 4))
                if (!grew && read > 0) {
                    source.appendText("56789")
                    grew = true
                }
                return read
            }

            override fun close() = delegate.close()
        }

        assertFails(BackupArchiveFailure.ENTRY_TOO_LARGE) {
            growingInput.use { input ->
                BackupArchiveSecurity.copyArchiveEntry(input, output, budget)
            }
        }
        assertEquals(4L, initiallyObservedLength)
        assertEquals(9L, source.length())
        assertEquals(4, output.size())
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

    @Test
    fun settingsDecodeCancellationPropagatesAndCleansStaging() = withTempDir { root ->
        val cancellation = CancellationException("secret-cancellation-marker")
        val staging = root.resolve("staging")
        val archive = zip(root, mapOf("settings.json" to "settings"))

        val thrown = try {
            BackupArchiveSecurity.stageAndPreflight(
                archive,
                staging,
                limits(),
                decodeSettings = { throw cancellation },
                decodePluginSettings = { it },
            )
            fail("Expected cancellation")
            null
        } catch (e: CancellationException) {
            e
        }

        assertSame(cancellation, thrown)
        assertFalse(staging.exists())
    }

    @Test
    fun pluginSettingsDecodeCancellationPropagatesAndCleansStaging() = withTempDir { root ->
        val cancellation = CancellationException("secret-cancellation-marker")
        val staging = root.resolve("staging")
        val archive = zip(root, mapOf(
            "settings.json" to "settings",
            "plugin_settings.json" to "plugins",
        ))

        val thrown = try {
            BackupArchiveSecurity.stageAndPreflight(
                archive,
                staging,
                limits(),
                decodeSettings = { it },
                decodePluginSettings = { throw cancellation },
            )
            fail("Expected cancellation")
            null
        } catch (e: CancellationException) {
            e
        }

        assertSame(cancellation, thrown)
        assertFalse(staging.exists())
    }

    @Test
    fun temporaryFileOwnershipDeletesFileWhenPostCreateWorkFails() = withTempDir { root ->
        val file = root.resolve("export.zip")
        val failure = IllegalStateException("safe failure")

        val thrown = try {
            runBlocking {
                BackupArchiveSecurity.transferTemporaryFileOwnership(
                    prepare = { file.apply { writeText("archive") } },
                    beforeTransfer = { throw failure },
                )
            }
            fail("Expected export ownership failure")
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertSame(failure, thrown)
        assertFalse(file.exists())
    }

    @Test
    fun temporaryFileOwnershipPreservesCancellationAndDeletesFile() = withTempDir { root ->
        val file = root.resolve("export.zip")
        val cancellation = CancellationException("secret-cancellation-marker")

        val thrown = try {
            runBlocking {
                BackupArchiveSecurity.transferTemporaryFileOwnership(
                    prepare = { file.apply { writeText("archive") } },
                    beforeTransfer = { throw cancellation },
                )
            }
            fail("Expected cancellation")
            null
        } catch (e: CancellationException) {
            e
        }

        assertSame(cancellation, thrown)
        assertFalse(file.exists())
    }

    @Test
    fun temporaryFileOwnershipTransfersSuccessfulFileToCaller() = withTempDir { root ->
        val file = root.resolve("export.zip")

        val result = runBlocking {
            BackupArchiveSecurity.transferTemporaryFileOwnership(
                prepare = { file.apply { writeText("archive") } },
                beforeTransfer = {},
            )
        }

        assertEquals(file, result)
        assertTrue(file.isFile)
    }

    private fun assertUnsafe(name: String) {
        assertFails(BackupArchiveFailure.INVALID_ENTRY_PATH) {
            BackupArchiveSecurity.validateEntryName(name)
        }
    }

    private fun preflight(
        archive: File,
        staging: File,
        restoreTargets: BackupRestoreTargets? = null,
        customLimits: BackupArchiveLimits = limits(),
    ) =
        BackupArchiveSecurity.stageAndPreflight(
            archive,
            staging,
            customLimits,
            decodeSettings = { it },
            decodePluginSettings = { it },
            restoreTargets = restoreTargets,
        )

    private fun assertPreflightFails(
        archive: File,
        staging: File,
        reason: BackupArchiveFailure,
        customLimits: BackupArchiveLimits = limits(),
    ) {
        assertFails(reason) { preflight(archive, staging, customLimits = customLimits) }
        assertFalse(staging.exists())
    }

    private fun restoreTargets(root: File): BackupRestoreTargets {
        val applicationRoot = root.resolve("application")
        return BackupRestoreTargets(
            uploadsRoot = applicationRoot.resolve("upload"),
            skillsRoot = applicationRoot.resolve("skills"),
            pluginsRoot = applicationRoot.resolve("plugins"),
        )
    }

    private fun sourceTraversalFailure(source: File): BackupArchiveException {
        val failure = try {
            BackupArchiveSecurity.collectSafeSourceFiles(source)
            fail("Expected unsafe source rejection")
            null
        } catch (e: BackupArchiveException) {
            assertEquals(BackupArchiveFailure.INVALID_ENTRY_PATH, e.reason)
            e
        }
        return failure!!
    }

    private fun createSymlinkOrSkip(link: java.nio.file.Path, target: java.nio.file.Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (e: UnsupportedOperationException) {
            assumeNoException(e)
        } catch (e: IOException) {
            assumeNoException(e)
        } catch (e: SecurityException) {
            assumeNoException(e)
        }
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

    // Mirrors the public RikkaHub 2.4.8 Settings shape with all credential-bearing values intentionally empty.
    private fun rikkahubSettingsJson(providerType: String = "openai"): String = """
        {
          "dynamicColor": true,
          "themeId": "default",
          "customThemes": [],
          "developerMode": false,
          "displaySetting": {
            "bubbleOpacity": 1.0,
            "showDateTimeInMessage": false,
            "updateCheckDisabledUntilEpochMillis": 0,
            "ttsOnlyReadOutsideBrackets": false,
            "chatCustomFontPath": "",
            "chatCustomFontName": ""
          },
          "favoriteModels": [],
          "chatModelId": "$RIKKAHUB_CHAT_MODEL_ID",
          "fastModelId": "$RIKKAHUB_FAST_MODEL_ID",
          "titleModelId": null,
          "imageGenerationModelId": "$RIKKAHUB_CHAT_MODEL_ID",
          "titlePrompt": "safe-title-prompt",
          "translateModeId": "$RIKKAHUB_CHAT_MODEL_ID",
          "translatePrompt": "safe-translate-prompt",
          "translateThinkingBudget": 0,
          "enableSuggestion": true,
          "suggestionModelId": null,
          "suggestionPrompt": "safe-suggestion-prompt",
          "ocrModelId": "$RIKKAHUB_CHAT_MODEL_ID",
          "ocrPrompt": "safe-ocr-prompt",
          "compressModelId": "$RIKKAHUB_CHAT_MODEL_ID",
          "compressPrompt": "safe-compress-prompt",
          "assistantId": "$RIKKAHUB_PROVIDER_ID",
          "providers": [
            {
              "type": "$providerType",
              "id": "$RIKKAHUB_PROVIDER_ID",
              "models": [
                {"id": "$RIKKAHUB_FAST_MODEL_ID", "modelId": "fast"},
                {"id": "$RIKKAHUB_CHAT_MODEL_ID", "modelId": "chat"}
              ]
            }
          ],
          "assistants": [
            {
              "id": "$RIKKAHUB_ASSISTANT_ID",
              "contextMessageLimit": 0,
              "localTools": [{"type": "time_info"}],
              "enableWebSearch": false,
              "useGradientBackground": false,
              "allowConversationPromptInjection": false
            }
          ],
          "assistantTags": [],
          "searchServices": [
            {"type": "bing_local", "id": "$RIKKAHUB_SEARCH_ID"}
          ],
          "searchCommonOptions": {},
          "searchServiceSelected": 0,
          "mcpServers": [],
          "webDavConfig": {
            "url": "",
            "username": "",
            "password": "",
            "path": "rikkahub_backups",
            "items": ["DATABASE", "FILES"]
          },
          "s3Config": {
            "endpoint": "",
            "accessKeyId": "",
            "secretAccessKey": "",
            "bucket": "",
            "region": "auto",
            "pathStyle": true,
            "items": ["DATABASE", "FILES"]
          },
          "ttsProviders": [
            {
              "type": "system",
              "id": "$RIKKAHUB_TTS_ID",
              "name": "",
              "speechRate": 1.0,
              "pitch": 1.0
            }
          ],
          "selectedTTSProviderId": "$RIKKAHUB_TTS_ID",
          "defaultTTSPlaybackSpeed": 1.0,
          "asrProviders": [],
          "selectedASRProviderId": null,
          "modeInjections": [
            {
              "type": "mode",
              "id": "$RIKKAHUB_INJECTION_ID",
              "name": "",
              "enabled": true,
              "priority": 0,
              "position": "after_system_prompt",
              "content": "safe-mode-prompt",
              "injectDepth": 4,
              "role": "user"
            }
          ],
          "lorebooks": [],
          "quickMessages": [],
          "webServerEnabled": false,
          "webServerPort": 8080,
          "webServerJwtEnabled": false,
          "webServerAccessPassword": "",
          "webServerLocalhostOnly": false,
          "backupReminderConfig": {
            "enabled": false,
            "intervalDays": 7,
            "lastBackupTime": 0
          },
          "launchCount": 0,
          "sponsorAlertDismissedAt": 0
        }
    """.trimIndent()

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
        maxPluginSettingsJsonBytes: Long = 1024,
    ) = BackupArchiveLimits(
        maxCompressedBytes = 4096,
        maxEntries = maxEntries,
        maxEntryBytes = maxEntryBytes,
        maxTotalExtractedBytes = maxTotalExtractedBytes,
        maxSettingsJsonBytes = maxSettingsJsonBytes,
        maxPluginSettingsJsonBytes = maxPluginSettingsJsonBytes,
    )

    private fun rikkahubFixtureLimits() = limits(
        maxTotalExtractedBytes = 32 * 1024,
        maxSettingsJsonBytes = 16 * 1024,
    )

    private fun withTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("backup-security-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val RIKKAHUB_PROVIDER_ID = "50000000-0000-4000-8000-000000000005"
        const val RIKKAHUB_FAST_MODEL_ID = "60000000-0000-4000-8000-000000000006"
        const val RIKKAHUB_CHAT_MODEL_ID = "70000000-0000-4000-8000-000000000007"
        const val RIKKAHUB_ASSISTANT_ID = "80000000-0000-4000-8000-000000000008"
        const val RIKKAHUB_SEARCH_ID = "90000000-0000-4000-8000-000000000009"
        const val RIKKAHUB_TTS_ID = "a0000000-0000-4000-8000-00000000000a"
        const val RIKKAHUB_INJECTION_ID = "b0000000-0000-4000-8000-00000000000b"
    }
}
