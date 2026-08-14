/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.backup.tabs

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import me.rerere.rikkahub.data.sync.BackupArchiveException
import me.rerere.rikkahub.data.sync.BackupArchiveFailure
import me.rerere.rikkahub.data.sync.BackupContainerException
import me.rerere.rikkahub.data.sync.BackupContainerFailure
import me.rerere.rikkahub.data.sync.BackupRestoreDiagnosticCode
import me.rerere.rikkahub.data.sync.LocalBackupException
import me.rerere.rikkahub.data.sync.LocalBackupFailure
import me.rerere.rikkahub.data.sync.backupRestoreDiagnosticCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupImportErrorMappingTest {
    @Test
    fun everyExistingTypedFailureMapsToAUniqueStableCode() {
        val mappings = buildList {
            LocalBackupFailure.entries.forEach { reason ->
                add(backupRestoreDiagnosticCode(LocalBackupException(reason)))
            }
            BackupContainerFailure.entries.forEach { reason ->
                add(backupRestoreDiagnosticCode(BackupContainerException(reason)))
            }
            BackupArchiveFailure.entries.forEach { reason ->
                add(backupRestoreDiagnosticCode(BackupArchiveException(reason)))
            }
        }

        assertEquals(BackupRestoreDiagnosticCode.entries.size - 1, mappings.size)
        assertEquals(mappings.size, mappings.map { it.value }.distinct().size)
        assertTrue(mappings.all { it.value.matches(Regex("BR-[0-9]{2}")) })
        assertFalse(mappings.contains(BackupRestoreDiagnosticCode.UNKNOWN_FAILURE))
    }

    @Test
    fun stableCodesMatchTheirExistingFailureCategories() {
        assertEquals(
            BackupRestoreDiagnosticCode.LOCAL_INVALID_FORMAT,
            backupRestoreDiagnosticCode(LocalBackupException(LocalBackupFailure.INVALID_FORMAT)),
        )
        assertEquals(
            BackupRestoreDiagnosticCode.CONTAINER_AUTHENTICATION_FAILED,
            backupRestoreDiagnosticCode(BackupContainerException(BackupContainerFailure.AUTHENTICATION_FAILED)),
        )
        assertEquals(
            BackupRestoreDiagnosticCode.ARCHIVE_INVALID_OR_DAMAGED,
            backupRestoreDiagnosticCode(BackupArchiveException(BackupArchiveFailure.INVALID_ARCHIVE)),
        )
        assertEquals(
            BackupRestoreDiagnosticCode.ARCHIVE_UNSAFE_PATH,
            backupRestoreDiagnosticCode(BackupArchiveException(BackupArchiveFailure.INVALID_ENTRY_PATH)),
        )
        assertEquals(
            BackupRestoreDiagnosticCode.INCOMPATIBLE_SETTINGS,
            backupRestoreDiagnosticCode(BackupArchiveException(BackupArchiveFailure.INVALID_SETTINGS)),
        )
        assertEquals(
            BackupRestoreDiagnosticCode.INCOMPATIBLE_PLUGIN_SETTINGS,
            backupRestoreDiagnosticCode(BackupArchiveException(BackupArchiveFailure.INVALID_PLUGIN_SETTINGS)),
        )
        assertEquals(
            BackupRestoreDiagnosticCode.TEMPORARY_STORAGE_FAILURE,
            backupRestoreDiagnosticCode(BackupArchiveException(BackupArchiveFailure.TEMPORARY_STORAGE_ERROR)),
        )
    }

    @Test
    fun unknownFailureUsesSafeGenericCodeWithoutExceptionDetails() {
        val privateMarker = "private-backup-marker"
        val error = IllegalStateException(privateMarker)

        val diagnostic = backupRestoreDiagnosticCode(error)

        assertEquals(BackupRestoreDiagnosticCode.UNKNOWN_FAILURE, diagnostic)
        assertFalse(diagnostic.value.contains(privateMarker))
        assertFalse(diagnostic.toString().contains(privateMarker))
    }

    @Test
    fun cancellationIsControlFlowAndIsNeverMapped() {
        try {
            backupRestoreDiagnosticCode(CancellationException("private-cancellation-marker"))
            fail("Expected cancellation to propagate")
        } catch (_: CancellationException) {
            // Expected: UI catches cancellation before requesting a diagnostic code.
        }
    }

    @Test
    fun uiAndRestoreChainDoNotRenderOrLogExceptionDetails() {
        val sources = listOf(
            projectFile("src/main/java/me/rerere/rikkahub/ui/pages/backup/tabs/ImportExportTab.kt"),
            projectFile("src/main/java/me/rerere/rikkahub/data/sync/LocalBackupService.kt"),
            projectFile("src/main/java/me/rerere/rikkahub/data/sync/BackupArchiveService.kt"),
            projectFile("src/main/java/me/rerere/rikkahub/data/sync/BackupRestoreDiagnostics.kt"),
            projectFile("src/main/java/me/rerere/rikkahub/data/datastore/migration/SettingsJsonMigrator.kt"),
        ).map { Files.readString(it) }
        val ui = sources.first()

        assertTrue(ui.contains("backup_page_restore_failed_diagnostic, code"))
        sources.forEach { source ->
            assertFalse(source.contains("printStackTrace"))
            assertFalse(source.contains("stackTraceToString"))
            assertFalse(source.contains("error.message"))
            assertFalse(source.contains("e.message"))
        }
    }

    private fun projectFile(relativePath: String): Path {
        val workingDirectory = Path.of("").toAbsolutePath().normalize()
        return listOfNotNull(
            workingDirectory.resolve(relativePath),
            workingDirectory.resolve("app").resolve(relativePath),
            workingDirectory.parent?.resolve("app")?.resolve(relativePath),
        ).firstOrNull { Files.isRegularFile(it) }
            ?: throw AssertionError("Required source file is missing")
    }
}
