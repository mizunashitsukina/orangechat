/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.backup.tabs

import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sync.BackupArchiveFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupImportErrorMappingTest {
    @Test
    fun settingsCompatibilityFailureUsesDedicatedSafeMessage() {
        assertEquals(
            R.string.backup_page_settings_incompatible,
            backupArchiveFailureMessageRes(BackupArchiveFailure.INVALID_SETTINGS),
        )
    }

    @Test
    fun unsafePathAndResourceLimitsRemainSafelyClassified() {
        assertEquals(
            R.string.backup_page_unsafe_archive_path,
            backupArchiveFailureMessageRes(BackupArchiveFailure.INVALID_ENTRY_PATH),
        )
        listOf(
            BackupArchiveFailure.ARCHIVE_TOO_LARGE,
            BackupArchiveFailure.TOO_MANY_ENTRIES,
            BackupArchiveFailure.ENTRY_TOO_LARGE,
            BackupArchiveFailure.TOTAL_TOO_LARGE,
            BackupArchiveFailure.SETTINGS_TOO_LARGE,
            BackupArchiveFailure.PLUGIN_SETTINGS_TOO_LARGE,
        ).forEach { reason ->
            assertEquals(
                R.string.backup_page_archive_limits_exceeded,
                backupArchiveFailureMessageRes(reason),
            )
        }
    }

    @Test
    fun otherFailuresUseGenericMessageWithoutUntrustedDetails() {
        listOf(
            BackupArchiveFailure.INVALID_ARCHIVE,
            BackupArchiveFailure.DUPLICATE_PROTECTED_ENTRY,
            BackupArchiveFailure.MISSING_SETTINGS,
            BackupArchiveFailure.INVALID_PLUGIN_SETTINGS,
            BackupArchiveFailure.TEMPORARY_STORAGE_ERROR,
        ).forEach { reason ->
            assertEquals(
                R.string.backup_page_restore_failed_generic,
                backupArchiveFailureMessageRes(reason),
            )
        }
    }
}
