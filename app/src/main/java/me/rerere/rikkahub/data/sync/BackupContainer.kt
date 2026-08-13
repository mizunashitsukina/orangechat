/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.util.concurrent.CancellationException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException
import javax.crypto.spec.SecretKeySpec

const val BACKUP_CONTAINER_EXTENSION = ".ocbackup"

/**
 * Resource and work-factor limits for the encrypted backup container.
 *
 * The 600,000-iteration default follows the current OWASP PBKDF2-HMAC-SHA-256 baseline. The versioned header records
 * this value so a later format revision can raise it after representative Android benchmarks. Readers reject values
 * outside the explicit range before starting expensive key derivation; production never silently lowers the work
 * factor when a device is slow.
 */
internal data class BackupContainerLimits(
    val maxContainerBytes: Long = MAX_BACKUP_CONTAINER_BYTES,
    val maxPlaintextBytes: Long = MAX_BACKUP_CONTAINER_PLAINTEXT_BYTES,
    val minChunkBytes: Int = MIN_BACKUP_CONTAINER_CHUNK_BYTES,
    val maxChunkBytes: Int = MAX_BACKUP_CONTAINER_CHUNK_BYTES,
    val maxDataChunks: Int = MAX_BACKUP_CONTAINER_DATA_CHUNKS,
    val minKdfIterations: Int = MIN_BACKUP_KDF_ITERATIONS,
    val maxKdfIterations: Int = MAX_BACKUP_KDF_ITERATIONS,
)

internal data class BackupContainerWriteOptions(
    val chunkBytes: Int = DEFAULT_BACKUP_CONTAINER_CHUNK_BYTES,
    val kdfIterations: Int = DEFAULT_BACKUP_KDF_ITERATIONS,
    val random: BackupRandom = SecureBackupRandom,
)

const val MAX_BACKUP_CONTAINER_BYTES: Long = 576L * 1024 * 1024
const val MAX_BACKUP_CONTAINER_PLAINTEXT_BYTES: Long = 512L * 1024 * 1024
const val MIN_BACKUP_CONTAINER_CHUNK_BYTES: Int = 4 * 1024
const val DEFAULT_BACKUP_CONTAINER_CHUNK_BYTES: Int = 1024 * 1024
const val MAX_BACKUP_CONTAINER_CHUNK_BYTES: Int = 4 * 1024 * 1024
const val MAX_BACKUP_CONTAINER_DATA_CHUNKS: Int = 131_072
const val MIN_BACKUP_KDF_ITERATIONS: Int = 100_000
const val DEFAULT_BACKUP_KDF_ITERATIONS: Int = 600_000
const val MAX_BACKUP_KDF_ITERATIONS: Int = 2_000_000

internal class BackupContainerException(
    val reason: BackupContainerFailure,
) : Exception(reason.safeMessage)

internal enum class BackupContainerFailure(val safeMessage: String) {
    INVALID_FORMAT("Encrypted backup format is invalid or unsupported"),
    AUTHENTICATION_FAILED("Backup password is incorrect or encrypted backup is damaged"),
    RESOURCE_LIMIT("Encrypted backup exceeds the allowed resource limits"),
    CRYPTO_UNAVAILABLE("Required backup encryption is unavailable"),
    IO_FAILURE("Unable to process encrypted backup safely"),
}

internal object BackupContainer {
    private val MAGIC = byteArrayOf('O'.code.toByte(), 'C'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val VERSION: Int = 1
    private const val ALGORITHM_AES_256_GCM: Int = 1
    private const val KDF_PBKDF2_HMAC_SHA256: Int = 1
    private const val FLAGS: Int = 0
    private const val HEADER_BYTES: Int = 52
    private const val RECORD_HEADER_BYTES: Int = 13
    private const val RECORD_DATA: Int = 1
    private const val RECORD_END: Int = 2

    fun encrypt(
        source: File,
        destination: File,
        password: CharArray,
        limits: BackupContainerLimits = BackupContainerLimits(),
        cancellationCheck: () -> Unit = {},
    ) = encryptInternal(
        source,
        destination,
        password,
        limits,
        BackupContainerWriteOptions(),
        cancellationCheck,
    )

    /** Test-only entry point for deterministic randomness and reduced KDF work. */
    internal fun encryptForTesting(
        source: File,
        destination: File,
        password: CharArray,
        limits: BackupContainerLimits,
        options: BackupContainerWriteOptions,
        cancellationCheck: () -> Unit = {},
    ) = encryptInternal(source, destination, password, limits, options, cancellationCheck)

    private fun encryptInternal(
        source: File,
        destination: File,
        password: CharArray,
        limits: BackupContainerLimits,
        options: BackupContainerWriteOptions,
        cancellationCheck: () -> Unit,
    ) {
        try {
            validatePolicy(limits)
            validateWriteOptions(options, limits)
            validateDistinctFiles(source, destination)
            if (!source.isFile || source.length() > limits.maxPlaintextBytes) {
                throw BackupContainerException(BackupContainerFailure.RESOURCE_LIMIT)
            }

            withTemporaryDestination(destination) { temporary ->
                try {
                    source.inputStream().buffered().use { input ->
                        temporary.outputStream().buffered().use { fileOutput ->
                            encryptStream(input, fileOutput, password, limits, options, cancellationCheck)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BackupContainerException) {
                    throw e
                } catch (_: GeneralSecurityException) {
                    throw BackupContainerException(BackupContainerFailure.CRYPTO_UNAVAILABLE)
                } catch (_: IOException) {
                    throw BackupContainerException(BackupContainerFailure.IO_FAILURE)
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }

    fun decrypt(
        source: File,
        destination: File,
        password: CharArray,
        limits: BackupContainerLimits = BackupContainerLimits(),
        cancellationCheck: () -> Unit = {},
    ) {
        try {
            validatePolicy(limits)
            validateDistinctFiles(source, destination)
            if (!source.isFile || source.length() < HEADER_BYTES + RECORD_HEADER_BYTES + BACKUP_GCM_TAG_BYTES) {
                throw BackupContainerException(BackupContainerFailure.INVALID_FORMAT)
            }
            if (source.length() > limits.maxContainerBytes) {
                throw BackupContainerException(BackupContainerFailure.RESOURCE_LIMIT)
            }

            withTemporaryDestination(destination) { temporary ->
                try {
                    source.inputStream().buffered().use { fileInput ->
                        val input = LimitedInputStream(fileInput, limits.maxContainerBytes)
                        temporary.outputStream().buffered().use { output ->
                            decryptStream(input, output, password, limits, cancellationCheck)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BackupContainerException) {
                    throw e
                } catch (_: GeneralSecurityException) {
                    throw BackupContainerException(BackupContainerFailure.CRYPTO_UNAVAILABLE)
                } catch (_: IOException) {
                    throw BackupContainerException(BackupContainerFailure.IO_FAILURE)
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }

    private fun encryptStream(
        input: InputStream,
        output: OutputStream,
        password: CharArray,
        limits: BackupContainerLimits,
        options: BackupContainerWriteOptions,
        cancellationCheck: () -> Unit,
    ) {
        cancellationCheck()
        val salt = ByteArray(BACKUP_SALT_BYTES).also(options.random::nextBytes)
        val noncePrefix = ByteArray(BACKUP_NONCE_PREFIX_BYTES).also(options.random::nextBytes)
        val header = encodeHeader(options.kdfIterations, options.chunkBytes, salt, noncePrefix)
        val limitedOutput = LimitedOutputStream(output, limits.maxContainerBytes)
        limitedOutput.write(header)

        BackupCrypto.withDerivedKey(password, salt, options.kdfIterations) { key ->
            val plaintext = ByteArray(options.chunkBytes)
            var chunkIndex = 0
            var totalPlaintext = 0L
            try {
                while (true) {
                    cancellationCheck()
                    val length = readChunk(input, plaintext, cancellationCheck)
                    if (length == 0) break
                    if (chunkIndex >= limits.maxDataChunks || length > limits.maxPlaintextBytes - totalPlaintext) {
                        throw BackupContainerException(BackupContainerFailure.RESOURCE_LIMIT)
                    }
                    val record = encodeRecord(RECORD_DATA, chunkIndex, length, length + BACKUP_GCM_TAG_BYTES)
                    val ciphertext = BackupCrypto.encrypt(
                        key,
                        BackupCrypto.nonce(noncePrefix, chunkIndex),
                        header,
                        record,
                        plaintext,
                        length,
                    )
                    try {
                        limitedOutput.write(record)
                        limitedOutput.write(ciphertext)
                    } finally {
                        ciphertext.fill(0)
                    }
                    totalPlaintext += length
                    chunkIndex++
                }

                cancellationCheck()
                val terminal = encodeRecord(
                    RECORD_END,
                    chunkIndex,
                    plaintextBytes = 0,
                    ciphertextBytes = BACKUP_GCM_TAG_BYTES,
                )
                val authenticationTag = BackupCrypto.encrypt(
                    key,
                    BackupCrypto.nonce(noncePrefix, chunkIndex),
                    header,
                    terminal,
                    plaintext,
                    plaintextLength = 0,
                )
                try {
                    limitedOutput.write(terminal)
                    limitedOutput.write(authenticationTag)
                    limitedOutput.flush()
                } finally {
                    authenticationTag.fill(0)
                }
            } finally {
                plaintext.fill(0)
            }
        }
    }

    private fun decryptStream(
        input: InputStream,
        output: OutputStream,
        password: CharArray,
        limits: BackupContainerLimits,
        cancellationCheck: () -> Unit,
    ) {
        cancellationCheck()
        val headerBytes = readExactly(input, HEADER_BYTES, BackupContainerFailure.INVALID_FORMAT)
        val header = decodeHeader(headerBytes, limits)

        try {
            BackupCrypto.withDerivedKey(password, header.salt, header.kdfIterations) { key ->
                var expectedIndex = 0
                var totalPlaintext = 0L
                while (true) {
                    cancellationCheck()
                    val recordBytes = readExactly(
                        input,
                        RECORD_HEADER_BYTES,
                        BackupContainerFailure.AUTHENTICATION_FAILED,
                    )
                    val record = decodeRecord(recordBytes)
                    if (record.index != expectedIndex) authenticationFailure()

                    when (record.type) {
                        RECORD_DATA -> {
                            if (expectedIndex >= limits.maxDataChunks ||
                                record.plaintextBytes !in 1..header.chunkBytes ||
                                record.ciphertextBytes != record.plaintextBytes + BACKUP_GCM_TAG_BYTES ||
                                record.plaintextBytes > limits.maxPlaintextBytes - totalPlaintext
                            ) {
                                throw BackupContainerException(BackupContainerFailure.RESOURCE_LIMIT)
                            }
                            val ciphertext = readExactly(
                                input,
                                record.ciphertextBytes,
                                BackupContainerFailure.AUTHENTICATION_FAILED,
                            )
                            val plaintext = decryptRecord(key, header, recordBytes, ciphertext)
                            try {
                                if (plaintext.size != record.plaintextBytes) authenticationFailure()
                                cancellationCheck()
                                output.write(plaintext)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: BackupContainerException) {
                                throw e
                            } catch (_: IOException) {
                                throw BackupContainerException(BackupContainerFailure.IO_FAILURE)
                            } finally {
                                plaintext.fill(0)
                                ciphertext.fill(0)
                            }
                            totalPlaintext += record.plaintextBytes
                            expectedIndex++
                        }

                        RECORD_END -> {
                            if (record.plaintextBytes != 0 || record.ciphertextBytes != BACKUP_GCM_TAG_BYTES) {
                                authenticationFailure()
                            }
                            val tag = readExactly(
                                input,
                                record.ciphertextBytes,
                                BackupContainerFailure.AUTHENTICATION_FAILED,
                            )
                            val terminalPlaintext = decryptRecord(key, header, recordBytes, tag)
                            try {
                                if (terminalPlaintext.isNotEmpty()) authenticationFailure()
                            } finally {
                                terminalPlaintext.fill(0)
                                tag.fill(0)
                            }
                            cancellationCheck()
                            if (input.read() != -1) authenticationFailure()
                            output.flush()
                            return@withDerivedKey
                        }

                        else -> authenticationFailure()
                    }
                }
            }
        } finally {
            header.salt.fill(0)
            header.noncePrefix.fill(0)
        }
    }

    private fun decryptRecord(
        key: SecretKeySpec,
        header: DecodedHeader,
        recordBytes: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray = try {
        BackupCrypto.decrypt(
            key,
            BackupCrypto.nonce(header.noncePrefix, decodeRecord(recordBytes).index),
            header.encoded,
            recordBytes,
            ciphertext,
        )
    } catch (_: AEADBadTagException) {
        throw BackupContainerException(BackupContainerFailure.AUTHENTICATION_FAILED)
    } catch (_: BadPaddingException) {
        throw BackupContainerException(BackupContainerFailure.AUTHENTICATION_FAILED)
    } catch (_: IllegalBlockSizeException) {
        throw BackupContainerException(BackupContainerFailure.AUTHENTICATION_FAILED)
    }

    private fun encodeHeader(
        iterations: Int,
        chunkBytes: Int,
        salt: ByteArray,
        noncePrefix: ByteArray,
    ): ByteArray = ByteArrayOutputStream(HEADER_BYTES).use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(MAGIC)
            output.writeByte(VERSION)
            output.writeByte(ALGORITHM_AES_256_GCM)
            output.writeByte(KDF_PBKDF2_HMAC_SHA256)
            output.writeByte(FLAGS)
            output.writeInt(HEADER_BYTES)
            output.writeInt(iterations)
            output.writeInt(chunkBytes)
            output.writeShort(BACKUP_SALT_BYTES)
            output.writeShort(BACKUP_NONCE_PREFIX_BYTES)
            output.writeInt(0)
            output.write(salt)
            output.write(noncePrefix)
        }
        bytes.toByteArray()
    }

    private fun decodeHeader(encoded: ByteArray, limits: BackupContainerLimits): DecodedHeader {
        if (encoded.size != HEADER_BYTES || !encoded.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupContainerException(BackupContainerFailure.INVALID_FORMAT)
        }
        val input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        input.position(MAGIC.size)
        val version = input.get().toInt() and 0xff
        val algorithm = input.get().toInt() and 0xff
        val kdf = input.get().toInt() and 0xff
        val flags = input.get().toInt() and 0xff
        val headerLength = input.int
        val iterations = input.int
        val chunkBytes = input.int
        val saltBytes = input.short.toInt() and 0xffff
        val noncePrefixBytes = input.short.toInt() and 0xffff
        val reserved = input.int
        if (version != VERSION || algorithm != ALGORITHM_AES_256_GCM || kdf != KDF_PBKDF2_HMAC_SHA256 ||
            flags != FLAGS || headerLength != HEADER_BYTES || saltBytes != BACKUP_SALT_BYTES ||
            noncePrefixBytes != BACKUP_NONCE_PREFIX_BYTES || reserved != 0 ||
            iterations !in limits.minKdfIterations..limits.maxKdfIterations ||
            chunkBytes !in limits.minChunkBytes..limits.maxChunkBytes
        ) {
            throw BackupContainerException(BackupContainerFailure.INVALID_FORMAT)
        }
        val salt = ByteArray(saltBytes).also { input.get(it) }
        val noncePrefix = ByteArray(noncePrefixBytes).also { input.get(it) }
        if (input.hasRemaining()) throw BackupContainerException(BackupContainerFailure.INVALID_FORMAT)
        return DecodedHeader(encoded, iterations, chunkBytes, salt, noncePrefix)
    }

    private fun encodeRecord(type: Int, index: Int, plaintextBytes: Int, ciphertextBytes: Int): ByteArray =
        ByteBuffer.allocate(RECORD_HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(type.toByte())
            .putInt(index)
            .putInt(plaintextBytes)
            .putInt(ciphertextBytes)
            .array()

    private fun decodeRecord(encoded: ByteArray): DecodedRecord {
        if (encoded.size != RECORD_HEADER_BYTES) authenticationFailure()
        val input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        return DecodedRecord(
            type = input.get().toInt() and 0xff,
            index = input.int,
            plaintextBytes = input.int,
            ciphertextBytes = input.int,
        ).also {
            if (it.index < 0 || it.plaintextBytes < 0 || it.ciphertextBytes < 0) authenticationFailure()
        }
    }

    private fun readChunk(input: InputStream, buffer: ByteArray, cancellationCheck: () -> Unit): Int {
        var offset = 0
        var zeroReads = 0
        while (offset < buffer.size) {
            cancellationCheck()
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            if (read == 0) {
                if (++zeroReads > 16) throw BackupContainerException(BackupContainerFailure.IO_FAILURE)
                continue
            }
            zeroReads = 0
            offset += read
        }
        return offset
    }

    private fun readExactly(input: InputStream, length: Int, failure: BackupContainerFailure): ByteArray {
        if (length < 0) throw BackupContainerException(failure)
        val bytes = ByteArray(length)
        var offset = 0
        try {
            while (offset < length) {
                val read = input.read(bytes, offset, length - offset)
                if (read < 0) throw BackupContainerException(failure)
                if (read == 0) continue
                offset += read
            }
            return bytes
        } catch (e: BackupContainerException) {
            bytes.fill(0)
            throw e
        }
    }

    private fun validateWriteOptions(options: BackupContainerWriteOptions, limits: BackupContainerLimits) {
        if (options.chunkBytes !in limits.minChunkBytes..limits.maxChunkBytes ||
            options.kdfIterations !in limits.minKdfIterations..limits.maxKdfIterations
        ) {
            throw BackupContainerException(BackupContainerFailure.INVALID_FORMAT)
        }
    }

    private fun validatePolicy(limits: BackupContainerLimits) {
        if (limits.maxContainerBytes < HEADER_BYTES + RECORD_HEADER_BYTES + BACKUP_GCM_TAG_BYTES ||
            limits.maxContainerBytes > MAX_BACKUP_CONTAINER_BYTES || limits.maxPlaintextBytes < 0 ||
            limits.maxPlaintextBytes > MAX_BACKUP_CONTAINER_PLAINTEXT_BYTES || limits.minChunkBytes <= 0 ||
            limits.maxChunkBytes < limits.minChunkBytes || limits.maxChunkBytes > MAX_BACKUP_CONTAINER_CHUNK_BYTES ||
            limits.maxDataChunks !in 1..MAX_BACKUP_CONTAINER_DATA_CHUNKS || limits.minKdfIterations <= 0 ||
            limits.maxKdfIterations < limits.minKdfIterations ||
            limits.maxKdfIterations > MAX_BACKUP_KDF_ITERATIONS
        ) {
            throw BackupContainerException(BackupContainerFailure.INVALID_FORMAT)
        }
    }

    private fun validateDistinctFiles(source: File, destination: File) {
        val sameFile = try {
            source.canonicalFile == destination.canonicalFile
        } catch (_: IOException) {
            throw BackupContainerException(BackupContainerFailure.IO_FAILURE)
        }
        if (sameFile) {
            throw BackupContainerException(BackupContainerFailure.IO_FAILURE)
        }
    }

    private inline fun withTemporaryDestination(destination: File, write: (File) -> Unit) {
        val parent = destination.absoluteFile.parentFile
            ?: throw BackupContainerException(BackupContainerFailure.IO_FAILURE)
        if (!parent.isDirectory) throw BackupContainerException(BackupContainerFailure.IO_FAILURE)
        val temporary = try {
            Files.createTempFile(parent.toPath(), ".ocbackup-", ".tmp").toFile()
        } catch (_: IOException) {
            throw BackupContainerException(BackupContainerFailure.IO_FAILURE)
        }
        var committed = false
        try {
            write(temporary)
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            committed = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupContainerException) {
            throw e
        } catch (_: IOException) {
            throw BackupContainerException(BackupContainerFailure.IO_FAILURE)
        } finally {
            if (!committed) temporary.delete()
        }
    }

    private fun authenticationFailure(): Nothing =
        throw BackupContainerException(BackupContainerFailure.AUTHENTICATION_FAILED)

    private data class DecodedHeader(
        val encoded: ByteArray,
        val kdfIterations: Int,
        val chunkBytes: Int,
        val salt: ByteArray,
        val noncePrefix: ByteArray,
    )

    private data class DecodedRecord(
        val type: Int,
        val index: Int,
        val plaintextBytes: Int,
        val ciphertextBytes: Int,
    )

    private class LimitedInputStream(input: InputStream, private val maximum: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0 && ++count > maximum) {
                throw BackupContainerException(BackupContainerFailure.RESOURCE_LIMIT)
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val allowed = minOf(length.toLong(), maximum - count + 1).coerceAtLeast(1).toInt()
            val read = super.read(buffer, offset, allowed)
            if (read > 0) {
                count += read
                if (count > maximum) throw BackupContainerException(BackupContainerFailure.RESOURCE_LIMIT)
            }
            return read
        }
    }

    private class LimitedOutputStream(output: OutputStream, private val maximum: Long) : FilterOutputStream(output) {
        private var count = 0L

        override fun write(value: Int) {
            if (count >= maximum) throw BackupContainerException(BackupContainerFailure.RESOURCE_LIMIT)
            out.write(value)
            count++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length > maximum - count) throw BackupContainerException(BackupContainerFailure.RESOURCE_LIMIT)
            out.write(buffer, offset, length)
            count += length
        }
    }
}
