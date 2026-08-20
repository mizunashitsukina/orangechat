/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject
import java.io.File

private enum class WorkspacePreviewError {
    INVALID_LOCATION,
    TOO_LARGE,
    LOAD_FAILED,
}

@Composable
fun WorkspaceFilePreviewPage(
    id: String,
    areaName: String,
    path: String,
) {
    val repository = koinInject<WorkspaceRepository>()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fileName = remember(path) { path.replace('\\', '/').substringAfterLast('/').ifBlank { path } }
    val descriptor = remember(path) { workspaceFilePreviewDescriptor(path) }
    val area = remember(areaName) { runCatching { WorkspaceStorageArea.valueOf(areaName) }.getOrNull() }

    var loading by remember(id, areaName, path) { mutableStateOf(true) }
    var sizeBytes by remember(id, areaName, path) { mutableStateOf<Long?>(null) }
    var textContent by remember(id, areaName, path) { mutableStateOf<String?>(null) }
    var cachedFile by remember(id, areaName, path) { mutableStateOf<File?>(null) }
    var error by remember(id, areaName, path) { mutableStateOf<WorkspacePreviewError?>(null) }
    var openingExternal by remember(id, areaName, path) { mutableStateOf(false) }
    var externalOpenFailed by remember(id, areaName, path) { mutableStateOf(false) }

    DisposableEffect(cachedFile) {
        val file = cachedFile
        onDispose { file?.delete() }
    }

    LaunchedEffect(id, areaName, path) {
        if (area == null) {
            error = WorkspacePreviewError.INVALID_LOCATION
            loading = false
            return@LaunchedEffect
        }
        try {
            val size = repository.fileSize(id, area, path)
            sizeBytes = size
            when (descriptor.kind) {
                WorkspaceFilePreviewKind.TEXT -> {
                    if (size > WorkspaceRepository.MAX_TEXT_PREVIEW_BYTES) {
                        error = WorkspacePreviewError.TOO_LARGE
                    } else {
                        textContent = repository.readTextForPreview(id, area, path)
                    }
                }

                WorkspaceFilePreviewKind.IMAGE -> {
                    val target = createPreviewTempFile(context.cacheDir, descriptor.extension)
                    cachedFile = target
                    target.outputStream().use { output -> repository.exportFile(id, area, path, output) }
                }

                WorkspaceFilePreviewKind.OTHER -> Unit
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            cachedFile?.delete()
            cachedFile = null
            error = WorkspacePreviewError.LOAD_FAILED
        } finally {
            loading = false
        }
    }

    fun openWithSystemApp() {
        val storageArea = area ?: return
        if (openingExternal) return
        scope.launch {
            openingExternal = true
            externalOpenFailed = false
            try {
                val file = cachedFile ?: createPreviewTempFile(context.cacheDir, descriptor.extension).also { target ->
                    cachedFile = target
                    target.outputStream().use { output ->
                        repository.exportFile(id, storageArea, path, output)
                    }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, descriptor.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, null))
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: ActivityNotFoundException) {
                externalOpenFailed = true
            } catch (_: Exception) {
                cachedFile?.delete()
                cachedFile = null
                externalOpenFailed = true
            } finally {
                openingExternal = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> CircularProgressIndicator()

                descriptor.kind == WorkspaceFilePreviewKind.TEXT && textContent != null -> {
                    SelectionContainer {
                        Text(
                            text = textContent.orEmpty(),
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(16.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                descriptor.kind == WorkspaceFilePreviewKind.IMAGE && cachedFile?.isFile == true -> {
                    AsyncImage(
                        model = cachedFile,
                        contentDescription = fileName,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentScale = ContentScale.Fit,
                    )
                }

                else -> UnsupportedPreview(
                    fileName = fileName,
                    mimeType = descriptor.mimeType,
                    sizeBytes = sizeBytes,
                    error = error,
                    openingExternal = openingExternal,
                    externalOpenFailed = externalOpenFailed,
                    onOpen = ::openWithSystemApp,
                )
            }
        }
    }
}

@Composable
private fun UnsupportedPreview(
    fileName: String,
    mimeType: String,
    sizeBytes: Long?,
    error: WorkspacePreviewError?,
    openingExternal: Boolean,
    externalOpenFailed: Boolean,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(fileName, style = MaterialTheme.typography.titleMedium)
        Text(
            text = when (error) {
                WorkspacePreviewError.TOO_LARGE -> stringResource(R.string.workspace_preview_too_large)
                WorkspacePreviewError.INVALID_LOCATION,
                WorkspacePreviewError.LOAD_FAILED -> stringResource(R.string.workspace_preview_load_failed)
                null -> stringResource(R.string.workspace_preview_unsupported)
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.workspace_preview_file_type, mimeType))
        sizeBytes?.let {
            Text(stringResource(R.string.workspace_preview_file_size, it.fileSizeToString()))
        }
        Button(onClick = onOpen, enabled = !openingExternal && error != WorkspacePreviewError.INVALID_LOCATION) {
            Text(
                if (openingExternal) {
                    stringResource(R.string.workspace_preview_opening)
                } else {
                    stringResource(R.string.workspace_preview_open_with_app)
                }
            )
        }
        if (externalOpenFailed) {
            Text(
                text = stringResource(R.string.workspace_preview_open_failed),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun createPreviewTempFile(cacheDir: File, extension: String): File {
    val directory = File(cacheDir, "workspace_preview").apply { mkdirs() }
    val suffix = extension.takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }?.let { ".$it" } ?: ".bin"
    return File.createTempFile("preview_", suffix, directory)
}
