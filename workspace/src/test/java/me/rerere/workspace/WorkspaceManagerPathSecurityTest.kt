/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.workspace

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class WorkspaceManagerPathSecurityTest {
    private lateinit var testRoot: File
    private lateinit var manager: WorkspaceManager

    @Before
    fun setUp() {
        testRoot = Files.createTempDirectory("workspace-path-security-").toFile()
        manager = WorkspaceManager(File(testRoot, "workspaces"))
        manager.ensureWorkspace(WORKSPACE_ROOT)
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
    }

    @Test
    fun validFilesCanBeExportedFromBothStorageAreas() {
        val filesContent = "workspace-file".toByteArray()
        val linuxContent = "rootfs-file".toByteArray()
        File(manager.filesDir(WORKSPACE_ROOT), "notes/file.txt").apply {
            parentFile?.mkdirs()
            writeBytes(filesContent)
        }
        File(manager.linuxDir(WORKSPACE_ROOT), "etc/config.txt").apply {
            parentFile?.mkdirs()
            writeBytes(linuxContent)
        }

        assertArrayEquals(filesContent, export("notes/file.txt", WorkspaceStorageArea.FILES))
        assertArrayEquals(linuxContent, export("etc/config.txt", WorkspaceStorageArea.LINUX))
    }

    @Test
    fun traversalCannotEscapeEitherStorageArea() {
        listOf("../outside.txt", "..\\outside.txt", "folder/../../outside.txt").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                export(path, WorkspaceStorageArea.FILES)
            }
            assertThrows(IllegalArgumentException::class.java) {
                export(path, WorkspaceStorageArea.LINUX)
            }
        }
    }

    @Test
    fun symbolicLinkCannotEscapeWorkspaceRootWhenSupported() {
        val outside = File(testRoot, "outside.txt").apply { writeText("outside") }
        val link = File(manager.filesDir(WORKSPACE_ROOT), "outside-link.txt")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (exception: Exception) {
            assumeNoException("Symbolic links are unavailable in this environment", exception)
        }

        assertThrows(IllegalArgumentException::class.java) {
            export("outside-link.txt", WorkspaceStorageArea.FILES)
        }
    }

    private fun export(path: String, area: WorkspaceStorageArea): ByteArray {
        val output = ByteArrayOutputStream()
        manager.exportFile(WORKSPACE_ROOT, path, area, output)
        return output.toByteArray()
    }

    private companion object {
        const val WORKSPACE_ROOT = "workspace-test"
    }
}
