/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync

import java.util.concurrent.CancellationException
import me.rerere.rikkahub.data.datastore.migration.BackupSettingsCompatibilityException

/** Stable, privacy-safe identifiers for the failure categories already exposed by the restore pipeline. */
internal enum class BackupRestoreDiagnosticCode(val value: String) {
    LOCAL_INVALID_FORMAT("BR-01"),
    LEGACY_CONFIRMATION_REQUIRED("BR-02"),
    OPERATION_IN_PROGRESS("BR-03"),
    LOCAL_IO_FAILURE("BR-04"),

    CONTAINER_INVALID_FORMAT("BR-10"),
    CONTAINER_AUTHENTICATION_FAILED("BR-11"),
    CONTAINER_RESOURCE_LIMIT("BR-12"),
    CONTAINER_CRYPTO_UNAVAILABLE("BR-13"),
    CONTAINER_IO_FAILURE("BR-14"),

    ARCHIVE_COMPRESSED_SIZE_LIMIT("BR-20"),
    ARCHIVE_INVALID_OR_DAMAGED("BR-21"),
    ARCHIVE_UNSAFE_PATH("BR-22"),
    ARCHIVE_ENTRY_COUNT_LIMIT("BR-23"),
    ARCHIVE_ENTRY_SIZE_LIMIT("BR-24"),
    ARCHIVE_TOTAL_SIZE_LIMIT("BR-25"),
    SETTINGS_SIZE_LIMIT("BR-26"),
    PLUGIN_SETTINGS_SIZE_LIMIT("BR-27"),
    DUPLICATE_PROTECTED_ENTRY("BR-28"),
    MISSING_SETTINGS("BR-29"),
    INCOMPATIBLE_SETTINGS("BR-30"),
    INCOMPATIBLE_PLUGIN_SETTINGS("BR-31"),
    TEMPORARY_STORAGE_FAILURE("BR-32"),

    UNKNOWN_FAILURE("BR-99"),
}

/**
 * Maps only typed failures that already exist in the restore pipeline. No exception text, path, entry name, or
 * configuration value is included. Cancellation is deliberately not a diagnostic failure and remains control flow.
 */
internal fun backupRestoreDiagnosticCode(error: Throwable): BackupRestoreDiagnosticCode {
    if (error is CancellationException) throw error
    return when (error) {
        is LocalBackupException -> when (error.reason) {
            LocalBackupFailure.INVALID_FORMAT -> BackupRestoreDiagnosticCode.LOCAL_INVALID_FORMAT
            LocalBackupFailure.LEGACY_CONFIRMATION_REQUIRED -> {
                BackupRestoreDiagnosticCode.LEGACY_CONFIRMATION_REQUIRED
            }
            LocalBackupFailure.OPERATION_IN_PROGRESS -> BackupRestoreDiagnosticCode.OPERATION_IN_PROGRESS
            LocalBackupFailure.IO_FAILURE -> BackupRestoreDiagnosticCode.LOCAL_IO_FAILURE
        }

        is BackupContainerException -> when (error.reason) {
            BackupContainerFailure.INVALID_FORMAT -> BackupRestoreDiagnosticCode.CONTAINER_INVALID_FORMAT
            BackupContainerFailure.AUTHENTICATION_FAILED -> {
                BackupRestoreDiagnosticCode.CONTAINER_AUTHENTICATION_FAILED
            }
            BackupContainerFailure.RESOURCE_LIMIT -> BackupRestoreDiagnosticCode.CONTAINER_RESOURCE_LIMIT
            BackupContainerFailure.CRYPTO_UNAVAILABLE -> BackupRestoreDiagnosticCode.CONTAINER_CRYPTO_UNAVAILABLE
            BackupContainerFailure.IO_FAILURE -> BackupRestoreDiagnosticCode.CONTAINER_IO_FAILURE
        }

        is BackupArchiveException -> when (error.reason) {
            BackupArchiveFailure.ARCHIVE_TOO_LARGE -> BackupRestoreDiagnosticCode.ARCHIVE_COMPRESSED_SIZE_LIMIT
            BackupArchiveFailure.INVALID_ARCHIVE -> BackupRestoreDiagnosticCode.ARCHIVE_INVALID_OR_DAMAGED
            BackupArchiveFailure.INVALID_ENTRY_PATH -> BackupRestoreDiagnosticCode.ARCHIVE_UNSAFE_PATH
            BackupArchiveFailure.TOO_MANY_ENTRIES -> BackupRestoreDiagnosticCode.ARCHIVE_ENTRY_COUNT_LIMIT
            BackupArchiveFailure.ENTRY_TOO_LARGE -> BackupRestoreDiagnosticCode.ARCHIVE_ENTRY_SIZE_LIMIT
            BackupArchiveFailure.TOTAL_TOO_LARGE -> BackupRestoreDiagnosticCode.ARCHIVE_TOTAL_SIZE_LIMIT
            BackupArchiveFailure.SETTINGS_TOO_LARGE -> BackupRestoreDiagnosticCode.SETTINGS_SIZE_LIMIT
            BackupArchiveFailure.PLUGIN_SETTINGS_TOO_LARGE -> {
                BackupRestoreDiagnosticCode.PLUGIN_SETTINGS_SIZE_LIMIT
            }
            BackupArchiveFailure.DUPLICATE_PROTECTED_ENTRY -> {
                BackupRestoreDiagnosticCode.DUPLICATE_PROTECTED_ENTRY
            }
            BackupArchiveFailure.MISSING_SETTINGS -> BackupRestoreDiagnosticCode.MISSING_SETTINGS
            BackupArchiveFailure.INVALID_SETTINGS -> BackupRestoreDiagnosticCode.INCOMPATIBLE_SETTINGS
            BackupArchiveFailure.INVALID_PLUGIN_SETTINGS -> {
                BackupRestoreDiagnosticCode.INCOMPATIBLE_PLUGIN_SETTINGS
            }
            BackupArchiveFailure.TEMPORARY_STORAGE_ERROR -> {
                BackupRestoreDiagnosticCode.TEMPORARY_STORAGE_FAILURE
            }
        }

        else -> BackupRestoreDiagnosticCode.UNKNOWN_FAILURE
    }
}

internal fun backupRestoreDiagnosticValue(error: Throwable): String {
    val base = backupRestoreDiagnosticCode(error)
    if (error is BackupArchiveException && error.reason == BackupArchiveFailure.INVALID_SETTINGS) {
        val compatibility = error.cause as? BackupSettingsCompatibilityException
        return compatibility?.section?.diagnosticCode ?: base.value
    }
    return base.value
}
