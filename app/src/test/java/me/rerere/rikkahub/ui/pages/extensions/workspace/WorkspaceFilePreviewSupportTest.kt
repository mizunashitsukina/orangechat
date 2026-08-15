/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceFilePreviewSupportTest {
    @Test
    fun commonTextAndCodeFilesUseTextPreview() {
        listOf(
            "notes.txt",
            "README.md",
            "settings.json",
            "layout.xml",
            "config.yaml",
            "app.kt",
            "build.log",
            "Dockerfile",
        ).forEach { path ->
            assertEquals(WorkspaceFilePreviewKind.TEXT, workspaceFilePreviewDescriptor(path).kind)
        }
    }

    @Test
    fun commonImagesUseImagePreview() {
        listOf("image.png", "photo.JPG", "animation.gif", "preview.webp").forEach { path ->
            assertEquals(WorkspaceFilePreviewKind.IMAGE, workspaceFilePreviewDescriptor(path).kind)
        }
    }

    @Test
    fun unsupportedFilesKeepSafeGenericMetadata() {
        val descriptor = workspaceFilePreviewDescriptor("archive.bin")
        assertEquals(WorkspaceFilePreviewKind.OTHER, descriptor.kind)
        assertEquals("application/octet-stream", descriptor.mimeType)
    }

    @Test
    fun workspaceToolPathsResolveToExpectedStorageArea() {
        assertEquals(
            WorkspaceToolFilePath(WorkspaceStorageArea.FILES, "notes/today.md"),
            resolveWorkspaceToolFilePath("/workspace/notes/today.md")
        )
        assertEquals(
            WorkspaceToolFilePath(WorkspaceStorageArea.LINUX, "etc/hosts"),
            resolveWorkspaceToolFilePath("/etc/hosts")
        )
    }

    @Test
    fun workspaceToolPathsRejectTraversalAndInvalidInput() {
        listOf(
            "/workspace/../private.txt",
            "\\workspace\\..\\private.txt",
            "relative/file.txt",
            "/workspace/file\u0000.txt",
        ).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                resolveWorkspaceToolFilePath(path)
            }
        }
    }
}
