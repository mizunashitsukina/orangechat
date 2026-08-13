/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupContainerTest {
    @Test
    fun nonEmptyContentRoundTrips() = withTempDir { root ->
        val plaintext = "encrypted backup payload".toByteArray()
        val container = encrypt(root, plaintext)

        assertArrayEquals(plaintext, decrypt(root, container))
        assertEquals(".ocbackup", BACKUP_CONTAINER_EXTENSION)
    }

    @Test
    fun emptyContentRoundTripsWithAuthenticatedTerminalRecord() = withTempDir { root ->
        val container = encrypt(root, byteArrayOf())
        val records = parseRecords(container.readBytes())

        assertTrue(decrypt(root, container).isEmpty())
        assertEquals(1, records.size)
        assertEquals(RECORD_END, records.single().type)
        assertEquals(0, records.single().index)
        assertEquals(TERMINAL_PLAINTEXT_BYTES, records.single().plaintextBytes)
    }

    @Test
    fun chunkBoundariesAndMultipleChunksRoundTrip() = withTempDir { root ->
        listOf(CHUNK_BYTES - 1, CHUNK_BYTES, CHUNK_BYTES + 1, CHUNK_BYTES * 3).forEach { size ->
            val plaintext = patternedBytes(size)
            val container = encrypt(root, plaintext, name = "boundary-$size")
            val dataRecords = parseRecords(container.readBytes()).filter { it.type == RECORD_DATA }

            assertEquals((size + CHUNK_BYTES - 1) / CHUNK_BYTES, dataRecords.size)
            assertArrayEquals(plaintext, decrypt(root, container, name = "boundary-$size"))
        }
    }

    @Test
    fun productionRandomSourceProducesDifferentContainers() = withTempDir { root ->
        val plaintext = patternedBytes(CHUNK_BYTES + 17)
        val firstSource = root.resolve("random-a.zip").apply { writeBytes(plaintext) }
        val secondSource = root.resolve("random-b.zip").apply { writeBytes(plaintext) }
        val first = root.resolve("random-a.ocbackup")
        val second = root.resolve("random-b.ocbackup")
        BackupContainer.encrypt(firstSource, first, TEST_PASSWORD.copyOf())
        BackupContainer.encrypt(secondSource, second, TEST_PASSWORD.copyOf())

        assertFalse(first.readBytes().contentEquals(second.readBytes()))
    }

    @Test
    fun deterministicTestRandomExposesExpectedContainerStructure() = withTempDir { root ->
        val container = encrypt(
            root,
            patternedBytes(CHUNK_BYTES + 1),
            options = testOptions(random = IncrementingRandom(0x10)),
        )
        val bytes = container.readBytes()
        val records = parseRecords(bytes)

        assertArrayEquals(
            byteArrayOf('O'.code.toByte(), 'C'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte()),
            bytes.copyOfRange(0, 4),
        )
        assertEquals(1, bytes[4].toInt())
        assertEquals(1, bytes[5].toInt())
        assertEquals(1, bytes[6].toInt())
        assertArrayEquals((0x10..0x1f).map { it.toByte() }.toByteArray(), bytes.copyOfRange(28, 44))
        assertArrayEquals((0x20..0x27).map { it.toByte() }.toByteArray(), bytes.copyOfRange(44, 52))
        assertEquals(listOf(0, 1, 2), records.map { it.index })
        assertEquals(listOf(RECORD_DATA, RECORD_DATA, RECORD_END), records.map { it.type })
    }

    @Test
    fun formatMatchesIndependentAesGcmAndPbkdf2ReferenceVector() = withTempDir { root ->
        val plaintext = "provider-compatibility".toByteArray()
        val source = root.resolve("vector.zip").apply { writeBytes(plaintext) }
        val container = root.resolve("vector.ocbackup")
        val options = BackupContainerWriteOptions(
            chunkBytes = CHUNK_BYTES,
            kdfIterations = TEST_ITERATIONS,
            random = IncrementingRandom(1),
        )

        BackupContainer.encryptForTesting(source, container, TEST_PASSWORD.copyOf(), testLimits(), options)

        assertArrayEquals(INDEPENDENT_REFERENCE_VECTOR.hexToBytes(), container.readBytes())
        assertArrayEquals(plaintext, decrypt(root, container, name = "vector"))
    }

    @Test
    fun wrongPasswordReturnsNoPartialPlaintext() = withTempDir { root ->
        val marker = "private-plaintext-marker".toByteArray()
        val password = "correct-test-password".toCharArray()
        val container = encrypt(root, marker, password = password)
        val destination = root.resolve("restored.zip").apply { writeText("preserved") }

        val failure = decryptFailure(container, destination, "wrong-test-password".toCharArray())

        assertEquals(BackupContainerFailure.AUTHENTICATION_FAILED, failure.reason)
        assertEquals("preserved", destination.readText())
        assertNoTemporaryFiles(root)
    }

    @Test
    fun modifiedAuthenticatedHeaderFails() = withTempDir { root ->
        val bytes = encrypt(root, patternedBytes(32)).readBytes()
        bytes[SALT_OFFSET] = (bytes[SALT_OFFSET].toInt() xor 1).toByte()

        assertAuthenticationFailure(root, bytes)
    }

    @Test
    fun modifiedCiphertextFails() = withTempDir { root ->
        val bytes = encrypt(root, patternedBytes(32)).readBytes()
        val first = parseRecords(bytes).first()
        bytes[first.ciphertextOffset] = (bytes[first.ciphertextOffset].toInt() xor 1).toByte()

        assertAuthenticationFailure(root, bytes)
    }

    @Test
    fun modifiedAuthenticationTagFails() = withTempDir { root ->
        val bytes = encrypt(root, patternedBytes(32)).readBytes()
        val first = parseRecords(bytes).first()
        val tagOffset = first.endOffset - 1
        bytes[tagOffset] = (bytes[tagOffset].toInt() xor 1).toByte()

        assertAuthenticationFailure(root, bytes)
    }

    @Test
    fun deletedMiddleChunkFails() = withTempDir { root ->
        val bytes = encrypt(root, patternedBytes(CHUNK_BYTES * 3)).readBytes()
        val records = parseRecords(bytes)
        val withoutMiddle = removeRange(bytes, records[1].startOffset, records[1].endOffset)

        assertAuthenticationFailure(root, withoutMiddle)
    }

    @Test
    fun deletedLastDataChunkFailsAgainstAuthenticatedTerminalState() = withTempDir { root ->
        val bytes = encrypt(root, patternedBytes(CHUNK_BYTES * 2)).readBytes()
        val records = parseRecords(bytes)
        val withoutLastData = removeRange(bytes, records[1].startOffset, records[1].endOffset)

        assertAuthenticationFailure(root, withoutLastData)
    }

    @Test
    fun reorderedChunksFail() = withTempDir { root ->
        val bytes = encrypt(root, patternedBytes(CHUNK_BYTES * 2)).readBytes()
        val records = parseRecords(bytes)
        val reordered = ByteArrayOutputStream().apply {
            write(bytes, 0, HEADER_BYTES)
            write(bytes, records[1].startOffset, records[1].size)
            write(bytes, records[0].startOffset, records[0].size)
            write(bytes, records[2].startOffset, records[2].size)
        }.toByteArray()

        assertAuthenticationFailure(root, reordered)
    }

    @Test
    fun duplicatedChunkFails() = withTempDir { root ->
        val bytes = encrypt(root, patternedBytes(CHUNK_BYTES * 2)).readBytes()
        val records = parseRecords(bytes)
        val duplicated = ByteArrayOutputStream().apply {
            write(bytes, 0, HEADER_BYTES)
            write(bytes, records[0].startOffset, records[0].size)
            write(bytes, records[0].startOffset, records[0].size)
            write(bytes, records[1].startOffset, records[1].size)
            write(bytes, records[2].startOffset, records[2].size)
        }.toByteArray()

        assertAuthenticationFailure(root, duplicated)
    }

    @Test
    fun unknownRecordTypeFails() = withTempDir { root ->
        val bytes = encrypt(root, patternedBytes(16)).readBytes()
        val first = parseRecords(bytes).first()
        bytes[first.startOffset] = 99

        assertAuthenticationFailure(root, bytes)
    }

    @Test
    fun truncatedTerminalRecordFails() = withTempDir { root ->
        val bytes = encrypt(root, patternedBytes(16)).readBytes()

        assertAuthenticationFailure(root, bytes.copyOf(bytes.size - 1))
    }

    @Test
    fun modifiedAuthenticatedTerminalStateFails() = withTempDir { root ->
        val bytes = encrypt(root, patternedBytes(CHUNK_BYTES + 17)).readBytes()
        val terminal = parseRecords(bytes).last()
        bytes[terminal.ciphertextOffset] = (bytes[terminal.ciphertextOffset].toInt() xor 1).toByte()

        assertAuthenticationFailure(root, bytes)
    }

    @Test
    fun appendedGarbageFails() = withTempDir { root ->
        val bytes = encrypt(root, patternedBytes(16)).readBytes() + byteArrayOf(0x55)

        assertAuthenticationFailure(root, bytes)
    }

    @Test
    fun invalidMagicVersionAlgorithmAndKdfParametersAreRejected() = withTempDir { root ->
        val original = encrypt(root, patternedBytes(16)).readBytes()
        val invalidContainers = listOf(
            original.copyOf().also { it[0] = 'X'.code.toByte() },
            original.copyOf().also { it[VERSION_OFFSET] = 2 },
            original.copyOf().also { it[ALGORITHM_OFFSET] = 2 },
            original.copyOf().also { it[KDF_OFFSET] = 2 },
            original.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(ITERATIONS_OFFSET, 0) },
            original.copyOf().also {
                ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(ITERATIONS_OFFSET, 101)
            },
            original.copyOf().also {
                ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(CHUNK_BYTES_OFFSET, Int.MAX_VALUE)
            },
        )

        invalidContainers.forEachIndexed { index, bytes ->
            val failure = decryptFailure(writeContainer(root, bytes, "invalid-$index"), root.resolve("out-$index.zip"))
            assertEquals(BackupContainerFailure.INVALID_FORMAT, failure.reason)
        }
    }

    @Test
    fun maliciousHugeRecordLengthIsRejectedBeforeOutputAllocation() = withTempDir { root ->
        val bytes = encrypt(root, patternedBytes(16)).readBytes()
        val record = parseRecords(bytes).first()
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(record.startOffset + RECORD_PLAINTEXT_LENGTH_OFFSET, Int.MAX_VALUE)
            putInt(record.startOffset + RECORD_CIPHERTEXT_LENGTH_OFFSET, Int.MAX_VALUE)
        }
        val destination = root.resolve("out.zip").apply { writeText("preserved") }

        val failure = decryptFailure(writeContainer(root, bytes), destination)

        assertEquals(BackupContainerFailure.RESOURCE_LIMIT, failure.reason)
        assertEquals("preserved", destination.readText())
        assertNoTemporaryFiles(root)
    }

    @Test
    fun chunkCountLimitStopsBeforeNonceCounterCanBeReused() = withTempDir { root ->
        val source = root.resolve("source.zip").apply { writeBytes(patternedBytes(CHUNK_BYTES + 1)) }
        val destination = root.resolve("encrypted.ocbackup")
        val constrained = testLimits(maxDataChunks = 1)

        val failure = encryptFailure(source, destination, limits = constrained)

        assertEquals(BackupContainerFailure.RESOURCE_LIMIT, failure.reason)
        assertFalse(destination.exists())
        assertNoTemporaryFiles(root)
    }

    @Test
    fun containerSizeLimitStopsStreamingBeforePublishingOutput() = withTempDir { root ->
        val source = root.resolve("source.zip").apply { writeBytes(patternedBytes(32)) }
        val destination = root.resolve("encrypted.ocbackup")
        val constrained = testLimits().copy(maxContainerBytes = 100)

        val failure = encryptFailure(source, destination, limits = constrained)

        assertEquals(BackupContainerFailure.RESOURCE_LIMIT, failure.reason)
        assertFalse(destination.exists())
        assertNoTemporaryFiles(root)
    }

    @Test
    fun invalidCounterPolicyIsRejected() = withTempDir { root ->
        val source = root.resolve("source.zip").apply { writeBytes(byteArrayOf(1)) }

        val failure = encryptFailure(
            source,
            root.resolve("encrypted.ocbackup"),
            limits = testLimits(maxDataChunks = Int.MAX_VALUE),
        )

        assertEquals(BackupContainerFailure.INVALID_FORMAT, failure.reason)
    }

    @Test
    fun cancellationPropagatesAndCleansPartialDecryptionOutput() = withTempDir { root ->
        val container = encrypt(root, patternedBytes(CHUNK_BYTES * 3))
        val destination = root.resolve("restored.zip").apply { writeText("preserved") }
        val cancellation = CancellationException("test-cancellation")
        var checks = 0

        val thrown = try {
            BackupContainer.decrypt(container, destination, TEST_PASSWORD.copyOf(), testLimits()) {
                if (++checks == 6) throw cancellation
            }
            fail("Expected cancellation")
            null
        } catch (e: CancellationException) {
            e
        }

        assertSame(cancellation, thrown)
        assertEquals("preserved", destination.readText())
        assertNoTemporaryFiles(root)
    }

    @Test
    fun cancellationPropagatesAndCleansPartialEncryptionOutput() = withTempDir { root ->
        val source = root.resolve("source.zip").apply { writeBytes(patternedBytes(CHUNK_BYTES * 3)) }
        val destination = root.resolve("encrypted.ocbackup")
        val cancellation = CancellationException("test-cancellation")
        var checks = 0

        val thrown = try {
            BackupContainer.encryptForTesting(
                source,
                destination,
                TEST_PASSWORD.copyOf(),
                testLimits(),
                testOptions(),
            ) {
                if (++checks == 6) throw cancellation
            }
            fail("Expected cancellation")
            null
        } catch (e: CancellationException) {
            e
        }

        assertSame(cancellation, thrown)
        assertFalse(destination.exists())
        assertNoTemporaryFiles(root)
    }

    @Test
    fun passwordAndPlaintextMarkersNeverAppearInSafeFailure() = withTempDir { root ->
        val plaintextMarker = "plaintext-private-marker"
        val passwordMarker = "password-private-marker"
        val container = encrypt(root, plaintextMarker.toByteArray(), password = passwordMarker.toCharArray())

        val failure = decryptFailure(container, root.resolve("out.zip"), "wrong-$passwordMarker".toCharArray())
        val diagnostic = failure.toString() + failure.message.orEmpty()

        assertFalse(diagnostic.contains(plaintextMarker))
        assertFalse(diagnostic.contains(passwordMarker))
        assertEquals(BackupContainerFailure.AUTHENTICATION_FAILED.safeMessage, failure.message)
    }

    @Test
    fun callerPasswordArraysAreClearedAfterUse() = withTempDir { root ->
        val encryptionPassword = TEST_PASSWORD.copyOf()
        val source = root.resolve("source.zip").apply { writeBytes(patternedBytes(16)) }
        val container = root.resolve("encrypted.ocbackup")
        BackupContainer.encryptForTesting(source, container, encryptionPassword, testLimits(), testOptions())
        assertTrue(encryptionPassword.all { it == '\u0000' })

        val decryptionPassword = TEST_PASSWORD.copyOf()
        BackupContainer.decrypt(container, root.resolve("restored.zip"), decryptionPassword, testLimits())
        assertTrue(decryptionPassword.all { it == '\u0000' })
    }

    private fun encrypt(
        root: File,
        plaintext: ByteArray,
        name: String = "backup",
        password: CharArray = TEST_PASSWORD.copyOf(),
        options: BackupContainerWriteOptions = testOptions(),
    ): File {
        val source = root.resolve("$name.zip").apply { writeBytes(plaintext) }
        val destination = root.resolve("$name.ocbackup")
        BackupContainer.encryptForTesting(source, destination, password, testLimits(), options)
        return destination
    }

    private fun decrypt(root: File, container: File, name: String = "restored"): ByteArray {
        val destination = root.resolve("$name.zip")
        BackupContainer.decrypt(container, destination, TEST_PASSWORD.copyOf(), testLimits())
        return destination.readBytes()
    }

    private fun assertAuthenticationFailure(root: File, bytes: ByteArray) {
        val destination = root.resolve("out-${bytes.contentHashCode()}.zip")
        val failure = decryptFailure(writeContainer(root, bytes, "input-${bytes.contentHashCode()}"), destination)
        assertEquals(BackupContainerFailure.AUTHENTICATION_FAILED, failure.reason)
        assertFalse(destination.exists())
        assertNoTemporaryFiles(root)
    }

    private fun decryptFailure(
        container: File,
        destination: File,
        password: CharArray = TEST_PASSWORD.copyOf(),
    ): BackupContainerException = try {
        BackupContainer.decrypt(container, destination, password, testLimits())
        fail("Expected encrypted backup rejection")
        throw AssertionError("unreachable")
    } catch (e: BackupContainerException) {
        e
    }

    private fun encryptFailure(
        source: File,
        destination: File,
        limits: BackupContainerLimits,
    ): BackupContainerException = try {
        BackupContainer.encryptForTesting(source, destination, TEST_PASSWORD.copyOf(), limits, testOptions())
        fail("Expected encrypted backup rejection")
        throw AssertionError("unreachable")
    } catch (e: BackupContainerException) {
        e
    }

    private fun writeContainer(root: File, bytes: ByteArray, name: String = "tampered"): File =
        root.resolve("$name.ocbackup").apply { writeBytes(bytes) }

    private fun parseRecords(bytes: ByteArray): List<TestRecord> {
        require(bytes.size >= HEADER_BYTES)
        val records = mutableListOf<TestRecord>()
        var offset = HEADER_BYTES
        while (offset < bytes.size) {
            require(bytes.size - offset >= RECORD_HEADER_BYTES)
            val buffer = ByteBuffer.wrap(bytes, offset, RECORD_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            val type = buffer.get().toInt() and 0xff
            val index = buffer.int
            val plaintextBytes = buffer.int
            val ciphertextBytes = buffer.int
            require(ciphertextBytes >= 0 && ciphertextBytes <= bytes.size - offset - RECORD_HEADER_BYTES)
            val end = offset + RECORD_HEADER_BYTES + ciphertextBytes
            records += TestRecord(type, index, plaintextBytes, offset, offset + RECORD_HEADER_BYTES, end)
            offset = end
        }
        return records
    }

    private fun removeRange(bytes: ByteArray, start: Int, end: Int): ByteArray =
        ByteArrayOutputStream(bytes.size - (end - start)).apply {
            write(bytes, 0, start)
            write(bytes, end, bytes.size - end)
        }.toByteArray()

    private fun patternedBytes(size: Int): ByteArray = ByteArray(size) { index -> (index * 31).toByte() }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun assertNoTemporaryFiles(root: File) {
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".ocbackup-") && it.name.endsWith(".tmp") })
    }

    private fun testOptions(random: BackupRandom = IncrementingRandom(1)) = BackupContainerWriteOptions(
        chunkBytes = CHUNK_BYTES,
        kdfIterations = TEST_ITERATIONS,
        random = random,
    )

    private fun testLimits(maxDataChunks: Int = 16) = BackupContainerLimits(
        maxContainerBytes = 256L * 1024,
        maxPlaintextBytes = 128L * 1024,
        minChunkBytes = CHUNK_BYTES,
        maxChunkBytes = CHUNK_BYTES,
        maxDataChunks = maxDataChunks,
        minKdfIterations = 1,
        maxKdfIterations = 100,
    )

    private fun withTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("backup-container-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private class IncrementingRandom(start: Int) : BackupRandom {
        private var value = start

        override fun nextBytes(target: ByteArray) {
            target.indices.forEach { index -> target[index] = value++.toByte() }
        }
    }

    private data class TestRecord(
        val type: Int,
        val index: Int,
        val plaintextBytes: Int,
        val startOffset: Int,
        val ciphertextOffset: Int,
        val endOffset: Int,
    ) {
        val size: Int get() = endOffset - startOffset
    }

    companion object {
        private const val HEADER_BYTES = 52
        private const val RECORD_HEADER_BYTES = 13
        private const val RECORD_DATA = 1
        private const val RECORD_END = 2
        private const val TERMINAL_PLAINTEXT_BYTES = 12
        private const val VERSION_OFFSET = 4
        private const val ALGORITHM_OFFSET = 5
        private const val KDF_OFFSET = 6
        private const val ITERATIONS_OFFSET = 12
        private const val CHUNK_BYTES_OFFSET = 16
        private const val SALT_OFFSET = 28
        private const val RECORD_PLAINTEXT_LENGTH_OFFSET = 5
        private const val RECORD_CIPHERTEXT_LENGTH_OFFSET = 9
        private const val CHUNK_BYTES = 4 * 1024
        private const val TEST_ITERATIONS = 2
        // Generated independently with Python cryptography 50.0.0 from the documented version 1 byte format.
        private const val INDEPENDENT_REFERENCE_VECTOR =
            "4f43424b0101010000000034000000020000100000100008000000000102030405060708090a0b0c0d0e0f10" +
                "1112131415161718010000000000000016000000260369d6138ddb3af3349decc2731da6e5f525350415e503" +
                "a778d00705d8155246626b0689361002000000010000000c0000001c86d5b3c4654ec1148ca59b21b976662" +
                "f546de30279a40063fc119325"
        private val TEST_PASSWORD = "unit-test-password".toCharArray()
    }
}
