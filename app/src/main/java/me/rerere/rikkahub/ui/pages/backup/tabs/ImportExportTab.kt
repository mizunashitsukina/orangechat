/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.backup.tabs

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FileImport
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.StickyHeader
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.data.sync.BackupArchiveFailure
import me.rerere.rikkahub.data.sync.BackupArchiveSecurity
import me.rerere.rikkahub.data.sync.MAX_BACKUP_COMPRESSED_BYTES
import me.rerere.rikkahub.data.sync.MAX_THIRD_PARTY_IMPORT_BYTES
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun ImportExportTab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isExporting by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }

    // 导入类型：local 为本地备份，chatbox 为 Chatbox 导入，cherry 为 Cherry Studio 导入
    var importType by remember { mutableStateOf("local") }

    // 创建文件保存的launcher
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { targetUri ->
            scope.launch {
                isExporting = true
                var exportFile: File? = null
                try {
                    // 导出文件
                    exportFile = vm.exportToFile()

                    // 复制到用户选择的位置
                    context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                        FileInputStream(exportFile!!).use { inputStream ->
                            BackupArchiveSecurity.copyLimited(
                                inputStream,
                                outputStream,
                                MAX_BACKUP_COMPRESSED_BYTES,
                                BackupArchiveFailure.ARCHIVE_TOO_LARGE,
                            )
                        }
                    } ?: throw IllegalArgumentException("Unable to open export destination")

                    toaster.show(
                        context.getString(R.string.backup_page_backup_success),
                        type = ToastType.Success
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, ""),
                        type = ToastType.Error
                    )
                } finally {
                    exportFile?.delete()
                    isExporting = false
                }
            }
        }
    }

    // 创建文件选择的launcher
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { sourceUri ->
            scope.launch {
                isRestoring = true
                var tempFile: File? = null
                try {
                    when (importType) {
                        "local" -> {
                            tempFile = File.createTempFile("backup-import-", ".zip", context.cacheDir)

                            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                FileOutputStream(tempFile!!).use { outputStream ->
                                    BackupArchiveSecurity.copyLimited(
                                        inputStream,
                                        outputStream,
                                        MAX_BACKUP_COMPRESSED_BYTES,
                                        BackupArchiveFailure.ARCHIVE_TOO_LARGE,
                                    )
                                }
                            } ?: throw IllegalArgumentException("Unable to read selected backup")

                            vm.restoreFromLocalFile(tempFile!!)
                        }

                        "chatbox" -> {
                            tempFile = File.createTempFile("chatbox-import-", ".json", context.cacheDir)

                            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                FileOutputStream(tempFile!!).use { outputStream ->
                                    BackupArchiveSecurity.copyLimited(
                                        inputStream,
                                        outputStream,
                                        MAX_THIRD_PARTY_IMPORT_BYTES,
                                        BackupArchiveFailure.ARCHIVE_TOO_LARGE,
                                    )
                                }
                            } ?: throw IllegalArgumentException("Unable to read selected import")

                            vm.restoreFromChatBox(tempFile!!)
                        }

                        "cherry" -> {
                            tempFile = File.createTempFile("cherry-import-", ".zip", context.cacheDir)

                            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                FileOutputStream(tempFile!!).use { outputStream ->
                                    BackupArchiveSecurity.copyLimited(
                                        inputStream,
                                        outputStream,
                                        MAX_THIRD_PARTY_IMPORT_BYTES,
                                        BackupArchiveFailure.ARCHIVE_TOO_LARGE,
                                    )
                                }
                            } ?: throw IllegalArgumentException("Unable to read selected import")

                            vm.restoreFromCherryStudio(tempFile!!)
                        }
                    }

                    toaster.show(
                        context.getString(R.string.backup_page_restore_success),
                        type = ToastType.Success
                    )
                    onShowRestartDialog()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, ""),
                        type = ToastType.Error
                    )
                } finally {
                    tempFile?.delete()
                    isRestoring = false
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_local_backup_export))
            }
        }

        item {
            CardGroup {
                item(
                    onClick = if (!isExporting) {
                        {
                            val timestamp = LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            createDocumentLauncher.launch("rikkahub_backup_$timestamp.zip")
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_export)) },
                    supportingContent = {
                        Text(
                            if (isExporting) {
                                stringResource(R.string.backup_page_exporting)
                            } else {
                                stringResource(R.string.backup_page_export_desc)
                            }
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
                            openDocumentLauncher.launch(arrayOf("application/zip"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_import)) },
                    supportingContent = {
                        Text(
                            if (isRestoring) {
                                stringResource(R.string.backup_page_importing)
                            } else {
                                stringResource(R.string.backup_page_import_desc)
                            }
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
            StickyHeader {
                Text(stringResource(R.string.backup_page_import_from_other_app))
            }
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
