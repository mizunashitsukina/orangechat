/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sync.BACKUP_CONTAINER_EXTENSION
import me.rerere.rikkahub.data.sync.BackupArchiveFailure
import me.rerere.rikkahub.data.sync.BackupArchiveSecurity
import me.rerere.rikkahub.data.sync.BackupContainerException
import me.rerere.rikkahub.data.sync.BackupContainerFailure
import me.rerere.rikkahub.data.sync.LocalBackupFormat
import me.rerere.rikkahub.data.sync.LocalBackupPasswordFailure
import me.rerere.rikkahub.data.sync.MAX_THIRD_PARTY_IMPORT_BYTES
import me.rerere.rikkahub.data.sync.PreparedLocalBackup
import me.rerere.rikkahub.data.sync.StagedLocalBackup
import me.rerere.rikkahub.data.sync.validateLocalBackupExportPassword
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.StickyHeader
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM

private enum class LocalBackupDialog {
    EXPORT_PASSWORD,
    IMPORT_PASSWORD,
    LEGACY_WARNING,
}

@Composable
fun ImportExportTab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit,
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isExporting by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var importType by remember { mutableStateOf("local") }
    var dialog by remember { mutableStateOf<LocalBackupDialog?>(null) }
    var password by remember { mutableStateOf("") }
    var passwordConfirmation by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<LocalBackupPasswordFailure?>(null) }
    var preparedExport by remember { mutableStateOf<PreparedLocalBackup?>(null) }
    var stagedImport by remember { mutableStateOf<StagedLocalBackup?>(null) }

    fun clearPasswordState() {
        password = ""
        passwordConfirmation = ""
        passwordVisible = false
        passwordError = null
    }

    fun cancelLocalImport() {
        stagedImport?.close()
        stagedImport = null
        dialog = null
        clearPasswordState()
        isRestoring = false
    }

    DisposableEffect(Unit) {
        onDispose {
            preparedExport?.close()
            stagedImport?.close()
            clearPasswordState()
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val prepared = preparedExport
        if (uri == null || prepared == null) {
            prepared?.close()
            preparedExport = null
            isExporting = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                vm.copyLocalBackupToDestination(
                    prepared = prepared,
                    openDestination = {
                        context.contentResolver.openOutputStream(uri)
                            ?: throw IllegalStateException("Unable to open export destination")
                    },
                    deleteIncompleteDestination = { context.contentResolver.delete(uri, null, null) },
                )
                toaster.show(context.getString(R.string.backup_page_backup_success), type = ToastType.Success)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                toaster.show(context.getString(R.string.backup_page_export_failed), type = ToastType.Error)
            } finally {
                prepared.close()
                preparedExport = null
                isExporting = false
            }
        }
    }

    fun restoreStagedBackup(legacyConfirmed: Boolean) {
        val staged = stagedImport ?: return
        val passwordChars = if (staged.format == LocalBackupFormat.ENCRYPTED_CONTAINER) {
            password.toCharArray()
        } else {
            null
        }
        dialog = null
        clearPasswordState()
        scope.launch {
            try {
                vm.restoreLocalBackup(staged, passwordChars, legacyConfirmed)
                toaster.show(context.getString(R.string.backup_page_restore_success), type = ToastType.Success)
                onShowRestartDialog()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupContainerException) {
                val message = if (e.reason == BackupContainerFailure.AUTHENTICATION_FAILED ||
                    e.reason == BackupContainerFailure.INVALID_FORMAT
                ) {
                    context.getString(R.string.backup_page_encrypted_auth_failed)
                } else {
                    context.getString(R.string.backup_page_restore_failed_generic)
                }
                toaster.show(message, type = ToastType.Error)
            } catch (_: Exception) {
                toaster.show(context.getString(R.string.backup_page_restore_failed_generic), type = ToastType.Error)
            } finally {
                passwordChars?.fill('\u0000')
                stagedImport = null
                isRestoring = false
                clearPasswordState()
            }
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isRestoring = true
            var tempFile: File? = null
            try {
                when (importType) {
                    "local" -> {
                        val staged = vm.stageLocalBackup {
                            context.contentResolver.openInputStream(uri)
                                ?: throw IllegalArgumentException("Unable to read selected backup")
                        }
                        stagedImport = staged
                        dialog = when (staged.format) {
                            LocalBackupFormat.ENCRYPTED_CONTAINER -> LocalBackupDialog.IMPORT_PASSWORD
                            LocalBackupFormat.LEGACY_ZIP -> LocalBackupDialog.LEGACY_WARNING
                        }
                    }

                    "chatbox" -> {
                        tempFile = File.createTempFile("chatbox-import-", ".json", context.cacheDir)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tempFile!!).use { output ->
                                BackupArchiveSecurity.copyLimited(
                                    input,
                                    output,
                                    MAX_THIRD_PARTY_IMPORT_BYTES,
                                    BackupArchiveFailure.ARCHIVE_TOO_LARGE,
                                )
                            }
                        } ?: throw IllegalArgumentException("Unable to read selected import")
                        vm.restoreFromChatBox(tempFile!!)
                    }

                    "cherry" -> {
                        tempFile = File.createTempFile("cherry-import-", ".zip", context.cacheDir)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tempFile!!).use { output ->
                                BackupArchiveSecurity.copyLimited(
                                    input,
                                    output,
                                    MAX_THIRD_PARTY_IMPORT_BYTES,
                                    BackupArchiveFailure.ARCHIVE_TOO_LARGE,
                                )
                            }
                        } ?: throw IllegalArgumentException("Unable to read selected import")
                        vm.restoreFromCherryStudio(tempFile!!)
                    }
                }

                if (importType != "local") {
                    toaster.show(context.getString(R.string.backup_page_restore_success), type = ToastType.Success)
                    onShowRestartDialog()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                stagedImport?.close()
                stagedImport = null
                toaster.show(context.getString(R.string.backup_page_restore_failed_generic), type = ToastType.Error)
            } finally {
                tempFile?.delete()
                if (dialog == null) isRestoring = false
            }
        }
    }

    when (dialog) {
        LocalBackupDialog.EXPORT_PASSWORD -> BackupPasswordDialog(
            title = stringResource(R.string.backup_page_encrypted_export_title),
            message = stringResource(R.string.backup_page_encrypted_export_warning),
            password = password,
            confirmation = passwordConfirmation,
            passwordVisible = passwordVisible,
            passwordError = passwordError,
            onPasswordChange = {
                password = it
                passwordError = null
            },
            onConfirmationChange = {
                passwordConfirmation = it
                passwordError = null
            },
            onToggleVisibility = { passwordVisible = !passwordVisible },
            onDismiss = {
                dialog = null
                clearPasswordState()
            },
            onConfirm = {
                val failure = validateLocalBackupExportPassword(password, passwordConfirmation)
                if (failure != null) {
                    passwordError = failure
                } else {
                    val passwordChars = password.toCharArray()
                    dialog = null
                    clearPasswordState()
                    isExporting = true
                    scope.launch {
                        try {
                            val prepared = vm.prepareEncryptedLocalBackup(passwordChars)
                            preparedExport = prepared
                            val timestamp = LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            createDocumentLauncher.launch("orangechat_backup_$timestamp$BACKUP_CONTAINER_EXTENSION")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            preparedExport?.close()
                            preparedExport = null
                            toaster.show(
                                context.getString(R.string.backup_page_export_failed),
                                type = ToastType.Error,
                            )
                            isExporting = false
                        } finally {
                            passwordChars.fill('\u0000')
                        }
                    }
                }
            },
        )

        LocalBackupDialog.IMPORT_PASSWORD -> BackupPasswordDialog(
            title = stringResource(R.string.backup_page_encrypted_import_title),
            message = stringResource(R.string.backup_page_encrypted_import_prompt),
            password = password,
            confirmation = null,
            passwordVisible = passwordVisible,
            passwordError = if (passwordError == LocalBackupPasswordFailure.EMPTY) passwordError else null,
            onPasswordChange = {
                password = it
                passwordError = null
            },
            onConfirmationChange = {},
            onToggleVisibility = { passwordVisible = !passwordVisible },
            onDismiss = ::cancelLocalImport,
            onConfirm = {
                if (password.isBlank()) {
                    passwordError = LocalBackupPasswordFailure.EMPTY
                } else {
                    restoreStagedBackup(legacyConfirmed = false)
                }
            },
        )

        LocalBackupDialog.LEGACY_WARNING -> AlertDialog(
            onDismissRequest = ::cancelLocalImport,
            title = { Text(stringResource(R.string.backup_page_legacy_backup_title)) },
            text = { Text(stringResource(R.string.backup_page_legacy_backup_warning)) },
            dismissButton = {
                TextButton(onClick = ::cancelLocalImport) { Text(stringResource(R.string.cancel)) }
            },
            confirmButton = {
                TextButton(onClick = { restoreStagedBackup(legacyConfirmed = true) }) {
                    Text(stringResource(R.string.backup_page_legacy_backup_confirm))
                }
            },
        )

        null -> Unit
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        stickyHeader {
            StickyHeader { Text(stringResource(R.string.backup_page_local_backup_export)) }
        }

        item {
            CardGroup {
                item(
                    onClick = if (!isExporting) {
                        { dialog = LocalBackupDialog.EXPORT_PASSWORD }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_export)) },
                    supportingContent = {
                        Text(
                            if (isExporting) {
                                stringResource(R.string.backup_page_exporting)
                            } else {
                                stringResource(R.string.backup_page_encrypted_export_desc)
                            },
                        )
                    },
                    leadingContent = {
                        if (isExporting) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.File01, null)
                        }
                    },
                )

                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "local"
                            openDocumentLauncher.launch(
                                arrayOf("application/octet-stream", "application/zip", "application/x-zip-compressed"),
                            )
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_import)) },
                    supportingContent = {
                        Text(
                            if (isRestoring) {
                                stringResource(R.string.backup_page_importing)
                            } else {
                                stringResource(R.string.backup_page_encrypted_import_desc)
                            },
                        )
                    },
                    leadingContent = {
                        if (isRestoring) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }

        stickyHeader {
            StickyHeader { Text(stringResource(R.string.backup_page_import_from_other_app)) }
        }

        item {
            CardGroup {
                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "chatbox"
                            openDocumentLauncher.launch(arrayOf("application/json"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_chatbox)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_chatbox_desc)) },
                    leadingContent = {
                        if (isRestoring && importType == "chatbox") {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )

                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "cherry"
                            openDocumentLauncher.launch(arrayOf("application/zip"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_cherry_studio)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_cherry_studio_desc)) },
                    leadingContent = {
                        if (isRestoring && importType == "cherry") {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BackupPasswordDialog(
    title: String,
    message: String,
    password: String,
    confirmation: String?,
    passwordVisible: Boolean,
    passwordError: LocalBackupPasswordFailure?,
    onPasswordChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val errorText = passwordError?.let {
        stringResource(
            when (it) {
                LocalBackupPasswordFailure.EMPTY -> R.string.backup_page_password_empty
                LocalBackupPasswordFailure.TOO_SHORT -> R.string.backup_page_password_too_short
                LocalBackupPasswordFailure.MISMATCH -> R.string.backup_page_password_mismatch
            },
        )
    }
    val visibilityDescription = stringResource(
        if (passwordVisible) R.string.backup_page_hide_password else R.string.backup_page_show_password,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.backup_page_backup_password)) },
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = onToggleVisibility) {
                            Icon(
                                imageVector = if (passwordVisible) HugeIcons.ViewOff else HugeIcons.View,
                                contentDescription = visibilityDescription,
                            )
                        }
                    },
                    singleLine = true,
                    isError = errorText != null,
                    supportingText = errorText?.let { { Text(it) } },
                )
                confirmation?.let {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = it,
                        onValueChange = onConfirmationChange,
                        label = { Text(stringResource(R.string.backup_page_confirm_backup_password)) },
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        singleLine = true,
                        isError = passwordError == LocalBackupPasswordFailure.MISMATCH,
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm)) } },
    )
}
