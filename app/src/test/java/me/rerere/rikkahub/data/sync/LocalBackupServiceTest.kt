/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalBackupServiceTest {
    @Test
    fun encryptedExportUsesOcbkMagicAndDeletesPlaintextArchive() = withTempDir { root ->
        lateinit var plaintextArchive: File
        val service = service(
            root = root,
            createArchive = { legacyArchive(root).also { plaintextArchive = it } },
        )

        val prepared = runBlocking { service.prepareEncryptedExport(PASSWORD.copyOf()) }
        try {
            assertArrayEquals(OCBK_MAGIC, prepared.file.readBytes().copyOf(4))
            assertFalse(prepared.file.readBytes().copyOf(2).contentEquals(PK_MAGIC))
            assertTrue(prepared.file.name.endsWith(BACKUP_CONTAINER_EXTENSION))
            assertFalse(plaintextArchive.exists())
        } finally {
            prepared.close()
        }
        assertNoLocalTemporaryFiles(root)
    }

    @Test
    fun correctPasswordAuthenticatesBeforeExistingArchivePreflightRuns() = withTempDir { root ->
        var restored = 0
        val exporter = service(root)
        val prepared = runBlocking { exporter.prepareEncryptedExport(PASSWORD.copyOf()) }
        val encryptedBytes = prepared.file.readBytes()
        prepared.close()
        val importer = service(root, restoreArchive = { archive ->
            val staging = root.resolve("restore-staging")
            val staged = BackupArchiveSecurity.stageAndPreflight<String, Unit>(
                archive = archive,
                stagingRoot = staging,
                decodeSettings = { it },
                decodePluginSettings = {},
            )
            assertEquals("{}", staged.settings)
            staging.deleteRecursively()
            restored++
        })

        val staged = runBlocking { importer.stageImport { ByteArrayInputStream(encryptedBytes) } }
        runBlocking { importer.restore(staged, PASSWORD.copyOf()) }

        assertEquals(1, restored)
        assertNoLocalTemporaryFiles(root)
    }

    @Test
    fun wrongPasswordNeverCallsRestoreAndCleansTemporaryFiles() = withTempDir { root ->
        val encrypted = encryptedBytes(root)
        var restored = false
        val service = service(root, restoreArchive = { restored = true })
        val staged = runBlocking { service.stageImport { ByteArrayInputStream(encrypted) } }

        val failure = try {
            runBlocking { service.restore(staged, "wrong-backup-password".toCharArray()) }
            fail("Expected authentication failure")
            throw AssertionError("unreachable")
        } catch (e: BackupContainerException) {
            e
        }

        assertEquals(BackupContainerFailure.AUTHENTICATION_FAILED, failure.reason)
        assertFalse(restored)
        assertNoLocalTemporaryFiles(root)
    }

    @Test
    fun damagedAndTruncatedContainersNeverCallRestore() = withTempDir { root ->
        val original = encryptedBytes(root)
        val damaged = original.copyOf().also { it[it.lastIndex / 2] = (it[it.lastIndex / 2].toInt() xor 1).toByte() }
        val truncated = original.copyOf(original.size - 1)

        listOf(damaged, truncated).forEach { input ->
            var restored = false
            val service = service(root, restoreArchive = { restored = true })
            val staged = runBlocking { service.stageImport { ByteArrayInputStream(input) } }
            try {
                runBlocking { service.restore(staged, PASSWORD.copyOf()) }
                fail("Expected encrypted backup rejection")
            } catch (_: BackupContainerException) {
                // Expected safe failure.
            }
            assertFalse(restored)
            assertNoLocalTemporaryFiles(root)
        }
    }

    @Test
    fun legacyZipRequiresExplicitConfirmationBeforeRestore() = withTempDir { root ->
        val legacyBytes = legacyBytes(root)
        var restored = 0
        val service = service(root, restoreArchive = { restored++ })
        val declined = runBlocking { service.stageImport { ByteArrayInputStream(legacyBytes) } }

        assertEquals(LocalBackupFormat.LEGACY_ZIP, declined.format)
        declined.close()
        assertEquals(0, restored)

        val confirmed = runBlocking { service.stageImport { ByteArrayInputStream(legacyBytes) } }
        runBlocking { service.restore(confirmed, legacyConfirmed = true) }
        assertEquals(1, restored)
        assertNoLocalTemporaryFiles(root)
    }

    @Test
    fun legacyRestoreCallWithoutConfirmationFailsClosed() = withTempDir { root ->
        val service = service(root)
        val staged = runBlocking {
            service.stageImport { ByteArrayInputStream(legacyBytes(root)) }
        }

        val failure = try {
            runBlocking { service.restore(staged) }
            fail("Expected confirmation requirement")
            throw AssertionError("unreachable")
        } catch (e: LocalBackupException) {
            e
        }

        assertEquals(LocalBackupFailure.LEGACY_CONFIRMATION_REQUIRED, failure.reason)
        assertNoLocalTemporaryFiles(root)
    }

    @Test
    fun passwordPolicyCountsUnicodeCodePointsAndRequiresMatchingConfirmation() {
        assertEquals(
            LocalBackupPasswordFailure.EMPTY,
            validateLocalBackupExportPassword("", ""),
        )
        assertEquals(
            LocalBackupPasswordFailure.EMPTY,
            validateLocalBackupExportPassword("          ", "          "),
        )
        assertEquals(
            LocalBackupPasswordFailure.TOO_SHORT,
            validateLocalBackupExportPassword("123456789", "123456789"),
        )
        assertEquals(
            LocalBackupPasswordFailure.MISMATCH,
            validateLocalBackupExportPassword("1234567890", "1234567891"),
        )
        val tenCodePoints = "😀😀😀😀😀😀😀😀😀😀"
        assertEquals(20, tenCodePoints.length)
        assertEquals(null, validateLocalBackupExportPassword(tenCodePoints, tenCodePoints))
    }

    @Test
    fun safWriteFailureRequestsIncompleteTargetDeletion() = withTempDir { root ->
        val service = service(root)
        val prepared = runBlocking { service.prepareEncryptedExport(PASSWORD.copyOf()) }
        var deleteRequested = false

        try {
            runBlocking {
                service.copyToDestination(
                    prepared,
                    openDestination = {
                        object : OutputStream() {
                            override fun write(value: Int) = throw IOException("test-write-failure")
                        }
                    },
                    deleteIncompleteDestination = { deleteRequested = true },
                )
            }
            fail("Expected copy failure")
        } catch (e: LocalBackupException) {
            assertEquals(LocalBackupFailure.IO_FAILURE, e.reason)
        } finally {
            prepared.close()
        }

        assertTrue(deleteRequested)
        assertNoLocalTemporaryFiles(root)
    }

    @Test
    fun successfulSafCopyTransfersOnlyEncryptedContainerAndRecordsBackupTime() = withTempDir { root ->
        var recorded = 0
        val service = LocalBackupService(
            cacheDirectory = root,
            createArchive = { legacyArchive(root) },
            restoreArchive = {},
            recordBackupTime = { recorded++ },
            crypto = testCrypto(),
        )
        val prepared = runBlocking { service.prepareEncryptedExport(PASSWORD.copyOf()) }
        val destination = ByteArrayOutputStream()
        try {
            runBlocking {
                service.copyToDestination(
                    prepared,
                    openDestination = { destination },
                    deleteIncompleteDestination = { fail("Successful destination must not be deleted") },
                )
            }
            assertArrayEquals(OCBK_MAGIC, destination.toByteArray().copyOf(4))
            assertEquals(1, recorded)
        } finally {
            prepared.close()
        }
        assertNoLocalTemporaryFiles(root)
    }

    @Test
    fun recordBackupFailureDeletesIncompleteDestinationAndKeepsPreparedOwnership() = withTempDir { root ->
        val service = LocalBackupService(
            cacheDirectory = root,
            createArchive = { legacyArchive(root) },
            restoreArchive = {},
            recordBackupTime = { throw IOException("test-record-failure") },
            crypto = testCrypto(),
        )
        val prepared = runBlocking { service.prepareEncryptedExport(PASSWORD.copyOf()) }
        var deleteRequested = false
        try {
            runBlocking {
                service.copyToDestination(
                    prepared,
                    openDestination = { ByteArrayOutputStream() },
                    deleteIncompleteDestination = { deleteRequested = true },
                )
            }
            fail("Expected record failure")
        } catch (e: LocalBackupException) {
            assertEquals(LocalBackupFailure.IO_FAILURE, e.reason)
        } finally {
            prepared.close()
        }
        assertTrue(deleteRequested)
        assertNoLocalTemporaryFiles(root)
    }

    @Test
    fun cancellationPropagatesAndCleansEncryptedImportOutput() = withTempDir { root ->
        val cancellation = CancellationException("test-cancellation")
        var decryptCalls = 0
        var restoreCalls = 0
        lateinit var decryptedOutput: File
        val service = service(
            root = root,
            restoreArchive = { restoreCalls++ },
            crypto = object : LocalBackupContainerCrypto {
                override fun encrypt(
                    source: File,
                    destination: File,
                    password: CharArray,
                    cancellationCheck: () -> Unit,
                ) = Unit

                override fun decrypt(
                    source: File,
                    destination: File,
                    password: CharArray,
                    cancellationCheck: () -> Unit,
                ) {
                    decryptCalls++
                    decryptedOutput = destination
                    destination.writeText("unverified-plaintext-marker")
                    throw cancellation
                }
            },
        )
        val staged = runBlocking { service.stageImport { ByteArrayInputStream(OCBK_MAGIC + byteArrayOf(1)) } }
        val stagedInput = staged.file
        val suppliedPassword = PASSWORD.copyOf()

        var cancellationObserved = false
        try {
            runBlocking { service.restore(staged, suppliedPassword) }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            cancellationObserved = true
        }

        assertTrue(cancellationObserved)
        assertEquals(1, decryptCalls)
        assertEquals(0, restoreCalls)
        assertFalse(decryptedOutput.exists())
        assertFalse(stagedInput.exists())
        assertTrue(suppliedPassword.all { it == '\u0000' })
        runBlocking { service.stageImport { ByteArrayInputStream(legacyBytes(root)) } }.close()
        assertNoLocalTemporaryFiles(root)
    }

    @Test
    fun concurrentExportIsRejectedUntilPreparedFileOwnershipIsReleased() = withTempDir { root ->
        val service = service(root)
        val first = runBlocking { service.prepareEncryptedExport(PASSWORD.copyOf()) }

        val failure = try {
            runBlocking { service.prepareEncryptedExport(PASSWORD.copyOf()) }
            fail("Expected operation gate")
            throw AssertionError("unreachable")
        } catch (e: LocalBackupException) {
            e
        } finally {
            first.close()
        }

        assertEquals(LocalBackupFailure.OPERATION_IN_PROGRESS, failure.reason)
        assertNoLocalTemporaryFiles(root)
    }

    @Test
    fun concurrentImportIsRejectedUntilStagedFileOwnershipIsReleased() = withTempDir { root ->
        val service = service(root)
        val legacy = legacyBytes(root)
        val first = runBlocking { service.stageImport { ByteArrayInputStream(legacy) } }

        val failure = try {
            runBlocking { service.stageImport { ByteArrayInputStream(legacy) } }
            fail("Expected operation gate")
            throw AssertionError("unreachable")
        } catch (e: LocalBackupException) {
            e
        } finally {
            first.close()
        }

        assertEquals(LocalBackupFailure.OPERATION_IN_PROGRESS, failure.reason)
        assertNoLocalTemporaryFiles(root)
    }

    @Test
    fun safeDiagnosticsDoNotExposePasswordPlaintextOrUriMarkers() = withTempDir { root ->
        val passwordMarker = "password-private-marker"
        val plaintextMarker = "plaintext-private-marker"
        val uriMarker = "content-private-marker"
        val service = service(root)
        val staged = runBlocking {
            service.stageImport { ByteArrayInputStream(encryptedBytes(root, plaintextMarker, passwordMarker)) }
        }

        val failure = try {
            runBlocking { service.restore(staged, "wrong-$passwordMarker".toCharArray()) }
            fail("Expected authentication failure")
            throw AssertionError("unreachable")
        } catch (e: BackupContainerException) {
            e
        }
        val diagnostic = failure.toString() + failure.message.orEmpty()

        assertFalse(diagnostic.contains(passwordMarker))
        assertFalse(diagnostic.contains(plaintextMarker))
        assertFalse(diagnostic.contains(uriMarker))
        assertNoLocalTemporaryFiles(root)
    }

    @Test
    fun remoteBackupImplementationsRemainOnTheirExistingZipPaths() {
        listOf(
            projectFile("src/main/java/me/rerere/rikkahub/data/sync/S3Sync.kt"),
            projectFile("src/main/java/me/rerere/rikkahub/data/sync/webdav/WebDavSync.kt"),
        ).forEach { source ->
            val text = Files.readString(source)
            assertTrue(text.contains(".zip"))
            assertFalse(text.contains("BackupContainer"))
            assertFalse(text.contains("LocalBackupService"))
            assertFalse(text.contains(BACKUP_CONTAINER_EXTENSION))
        }
    }

    private fun service(
        root: File,
        createArchive: suspend () -> File = { legacyArchive(root) },
        restoreArchive: suspend (File) -> Unit = {},
        crypto: LocalBackupContainerCrypto = testCrypto(),
    ) = LocalBackupService(
        cacheDirectory = root,
        createArchive = createArchive,
        restoreArchive = restoreArchive,
        recordBackupTime = {},
        crypto = crypto,
    )

    private fun encryptedBytes(
        root: File,
        plaintext: String = "{}",
        password: String = PASSWORD.concatToString(),
    ): ByteArray {
        val source = root.resolve("source-${System.nanoTime()}.zip").apply { writeBytes(plaintext.toByteArray()) }
        val destination = root.resolve("encrypted-${System.nanoTime()}.ocbackup")
        testCrypto().encrypt(source, destination, password.toCharArray()) {}
        val bytes = destination.readBytes()
        source.delete()
        destination.delete()
        return bytes
    }

    private fun legacyArchive(root: File): File = root.resolve("backup-${System.nanoTime()}.zip").apply {
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }
    }

    private fun legacyBytes(root: File): ByteArray {
        val archive = legacyArchive(root)
        return try {
            archive.readBytes()
        } finally {
            archive.delete()
        }
    }

    private fun testCrypto(): LocalBackupContainerCrypto = object : LocalBackupContainerCrypto {
        override fun encrypt(
            source: File,
            destination: File,
            password: CharArray,
            cancellationCheck: () -> Unit,
        ) = BackupContainer.encryptForTesting(
            source,
            destination,
            password,
            TEST_LIMITS,
            TEST_OPTIONS,
            cancellationCheck,
        )

        override fun decrypt(
            source: File,
            destination: File,
            password: CharArray,
            cancellationCheck: () -> Unit,
        ) = BackupContainer.decrypt(source, destination, password, TEST_LIMITS, cancellationCheck)
    }

    private fun assertNoLocalTemporaryFiles(root: File) {
        assertTrue(
            root.listFiles().orEmpty().none {
                it.name.startsWith("local-") || it.name.startsWith(".ocbackup-") ||
                    it.name.startsWith("backup-") || it.name.startsWith("source-") ||
                    it.name.startsWith("encrypted-") || it.name == "restore-staging"
            },
        )
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

    private fun withTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("local-backup-service-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private class IncrementingRandom : BackupRandom {
        private var next = 1

        override fun nextBytes(target: ByteArray) {
            target.indices.forEach { target[it] = next++.toByte() }
        }
    }

    companion object {
        private val PASSWORD = "unit-test-password".toCharArray()
        private val OCBK_MAGIC = byteArrayOf(0x4f, 0x43, 0x42, 0x4b)
        private val PK_MAGIC = byteArrayOf(0x50, 0x4b)
        private val TEST_LIMITS = BackupContainerLimits(
            maxContainerBytes = 1024 * 1024,
            maxPlaintextBytes = 512 * 1024,
            minChunkBytes = 4 * 1024,
            maxChunkBytes = 4 * 1024,
            maxDataChunks = 128,
            minKdfIterations = 1,
            maxKdfIterations = 10,
        )
        private val TEST_OPTIONS = BackupContainerWriteOptions(
            chunkBytes = 4 * 1024,
            kdfIterations = 2,
            random = IncrementingRandom(),
        )
    }
}
