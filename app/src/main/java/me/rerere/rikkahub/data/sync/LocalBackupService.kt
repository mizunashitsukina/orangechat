/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val MIN_LOCAL_BACKUP_PASSWORD_CODE_POINTS = 10

internal enum class LocalBackupFormat {
    ENCRYPTED_CONTAINER,
    LEGACY_ZIP,
}

internal enum class LocalBackupPasswordFailure {
    EMPTY,
    TOO_SHORT,
    MISMATCH,
}

internal enum class LocalBackupFailure(val safeMessage: String) {
    INVALID_FORMAT("Selected file is not a supported backup"),
    LEGACY_CONFIRMATION_REQUIRED("Legacy unencrypted backup confirmation is required"),
    OPERATION_IN_PROGRESS("A local backup operation is already in progress"),
    IO_FAILURE("Unable to process the local backup safely"),
}

internal class LocalBackupException(
    val reason: LocalBackupFailure,
) : Exception(reason.safeMessage)

internal interface LocalBackupContainerCrypto {
    fun encrypt(source: File, destination: File, password: CharArray, cancellationCheck: () -> Unit)

    fun decrypt(source: File, destination: File, password: CharArray, cancellationCheck: () -> Unit)
}

private object ProductionLocalBackupContainerCrypto : LocalBackupContainerCrypto {
    override fun encrypt(
        source: File,
        destination: File,
        password: CharArray,
        cancellationCheck: () -> Unit,
    ) = BackupContainer.encrypt(source, destination, password, cancellationCheck = cancellationCheck)

    override fun decrypt(
        source: File,
        destination: File,
        password: CharArray,
        cancellationCheck: () -> Unit,
    ) = BackupContainer.decrypt(source, destination, password, cancellationCheck = cancellationCheck)
}

internal class PreparedLocalBackup internal constructor(
    val file: File,
    private val release: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                file.delete()
            } finally {
                release()
            }
        }
    }
}

internal class StagedLocalBackup internal constructor(
    val file: File,
    val format: LocalBackupFormat,
    private val release: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    fun requireOpen() {
        if (closed.get()) throw LocalBackupException(LocalBackupFailure.INVALID_FORMAT)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                file.delete()
            } finally {
                release()
            }
        }
    }
}

/**
 * Local-only wrapper around the existing ZIP archive service.
 *
 * Remote S3 and WebDAV paths intentionally do not use this class. All intermediate files are created beneath the
 * supplied application cache directory, and encrypted input is fully authenticated before [restoreArchive] is called.
 */
internal class LocalBackupService(
    private val cacheDirectory: File,
    private val createArchive: suspend () -> File,
    private val restoreArchive: suspend (File) -> Unit,
    private val recordBackupTime: suspend () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val crypto: LocalBackupContainerCrypto = ProductionLocalBackupContainerCrypto,
) {
    private val exportInProgress = AtomicBoolean(false)
    private val importInProgress = AtomicBoolean(false)

    suspend fun prepareEncryptedExport(password: CharArray): PreparedLocalBackup {
        try {
            acquire(exportInProgress)
        } catch (e: Exception) {
            password.fill('\u0000')
            throw e
        }
        var plaintextArchive: File? = null
        var encryptedBackup: File? = null
        var transferred = false
        try {
            return withContext(dispatcher) {
                currentCoroutineContext().ensureActive()
                plaintextArchive = createArchive()
                requirePrivateCacheFile(plaintextArchive!!)
                encryptedBackup = createCacheFile("local-export-", BACKUP_CONTAINER_EXTENSION)
                val operationContext = currentCoroutineContext()
                crypto.encrypt(
                    plaintextArchive!!,
                    encryptedBackup!!,
                    password,
                ) { operationContext.ensureActive() }
                transferred = true
                PreparedLocalBackup(encryptedBackup!!) { exportInProgress.set(false) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupContainerException) {
            throw e
        } catch (e: BackupArchiveException) {
            throw e
        } catch (e: LocalBackupException) {
            throw e
        } catch (_: Exception) {
            throw LocalBackupException(LocalBackupFailure.IO_FAILURE)
        } finally {
            password.fill('\u0000')
            plaintextArchive?.delete()
            if (!transferred) {
                encryptedBackup?.delete()
                exportInProgress.set(false)
            }
        }
    }

    suspend fun stageImport(openInput: () -> InputStream): StagedLocalBackup {
        acquire(importInProgress)
        var staged: File? = null
        var transferred = false
        try {
            return withContext(dispatcher) {
                val operationContext = currentCoroutineContext()
                staged = createCacheFile("local-import-", ".tmp")
                openInput().buffered().use { source ->
                    val signature = readSignature(source)
                    val format = detectFormat(signature)
                    val maximum = when (format) {
                        LocalBackupFormat.ENCRYPTED_CONTAINER -> MAX_BACKUP_CONTAINER_BYTES
                        LocalBackupFormat.LEGACY_ZIP -> MAX_BACKUP_COMPRESSED_BYTES
                    }
                    staged!!.outputStream().buffered().use { destination ->
                        destination.write(signature)
                        copyLimited(source, destination, maximum - signature.size) {
                            operationContext.ensureActive()
                        }
                    }
                    transferred = true
                    StagedLocalBackup(staged!!, format) { importInProgress.set(false) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupArchiveException) {
            throw e
        } catch (e: LocalBackupException) {
            throw e
        } catch (_: Exception) {
            throw LocalBackupException(LocalBackupFailure.IO_FAILURE)
        } finally {
            if (!transferred) {
                staged?.delete()
                importInProgress.set(false)
            }
        }
    }

    suspend fun restore(
        staged: StagedLocalBackup,
        password: CharArray? = null,
        legacyConfirmed: Boolean = false,
    ) {
        var decryptedArchive: File? = null
        try {
            withContext(dispatcher) {
                currentCoroutineContext().ensureActive()
                staged.requireOpen()
                when (staged.format) {
                    LocalBackupFormat.ENCRYPTED_CONTAINER -> {
                        val suppliedPassword = password
                            ?.takeIf { it.isNotEmpty() }
                            ?: throw LocalBackupException(LocalBackupFailure.INVALID_FORMAT)
                        decryptedArchive = createCacheFile("local-decrypted-", ".zip")
                        val operationContext = currentCoroutineContext()
                        crypto.decrypt(
                            staged.file,
                            decryptedArchive!!,
                            suppliedPassword,
                        ) { operationContext.ensureActive() }
                        restoreArchive(decryptedArchive!!)
                    }

                    LocalBackupFormat.LEGACY_ZIP -> {
                        if (!legacyConfirmed) {
                            throw LocalBackupException(LocalBackupFailure.LEGACY_CONFIRMATION_REQUIRED)
                        }
                        restoreArchive(staged.file)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupContainerException) {
            throw e
        } catch (e: BackupArchiveException) {
            throw e
        } catch (e: LocalBackupException) {
            throw e
        } catch (_: Exception) {
            throw LocalBackupException(LocalBackupFailure.IO_FAILURE)
        } finally {
            password?.fill('\u0000')
            decryptedArchive?.delete()
            staged.close()
        }
    }

    suspend fun copyToDestination(
        prepared: PreparedLocalBackup,
        openDestination: () -> OutputStream,
        deleteIncompleteDestination: () -> Unit,
    ) {
        try {
            withContext(dispatcher) {
                val operationContext = currentCoroutineContext()
                openDestination().buffered().use { destination ->
                    prepared.file.inputStream().buffered().use { source ->
                        copyLimited(source, destination, MAX_BACKUP_CONTAINER_BYTES) {
                            operationContext.ensureActive()
                        }
                    }
                    destination.flush()
                }
                recordBackupTime()
            }
        } catch (e: CancellationException) {
            safelyDeleteIncomplete(deleteIncompleteDestination)
            throw e
        } catch (_: Exception) {
            safelyDeleteIncomplete(deleteIncompleteDestination)
            throw LocalBackupException(LocalBackupFailure.IO_FAILURE)
        }
    }

    private fun readSignature(input: InputStream): ByteArray {
        val signature = ByteArray(4)
        var offset = 0
        var zeroReads = 0
        while (offset < signature.size) {
            val read = input.read(signature, offset, signature.size - offset)
            if (read < 0) break
            if (read == 0) {
                if (++zeroReads > 16) throw LocalBackupException(LocalBackupFailure.IO_FAILURE)
                continue
            }
            zeroReads = 0
            offset += read
        }
        if (offset != signature.size) throw LocalBackupException(LocalBackupFailure.INVALID_FORMAT)
        return signature
    }

    private fun detectFormat(signature: ByteArray): LocalBackupFormat {
        return when {
            signature.contentEquals(OCBACKUP_MAGIC) -> LocalBackupFormat.ENCRYPTED_CONTAINER
            ZIP_MAGICS.any(signature::contentEquals) -> LocalBackupFormat.LEGACY_ZIP
            else -> throw LocalBackupException(LocalBackupFailure.INVALID_FORMAT)
        }
    }

    private fun createCacheFile(prefix: String, suffix: String): File {
        if (!cacheDirectory.isDirectory) throw LocalBackupException(LocalBackupFailure.IO_FAILURE)
        return try {
            File.createTempFile(prefix, suffix, cacheDirectory)
        } catch (_: Exception) {
            throw LocalBackupException(LocalBackupFailure.IO_FAILURE)
        }
    }

    private fun requirePrivateCacheFile(file: File) {
        val cacheRoot = try {
            cacheDirectory.canonicalFile.toPath()
        } catch (_: Exception) {
            throw LocalBackupException(LocalBackupFailure.IO_FAILURE)
        }
        val candidate = try {
            file.canonicalFile.toPath()
        } catch (_: Exception) {
            throw LocalBackupException(LocalBackupFailure.IO_FAILURE)
        }
        if (!file.isFile || candidate == cacheRoot || !candidate.startsWith(cacheRoot)) {
            throw LocalBackupException(LocalBackupFailure.IO_FAILURE)
        }
    }

    private fun acquire(flag: AtomicBoolean) {
        if (!flag.compareAndSet(false, true)) {
            throw LocalBackupException(LocalBackupFailure.OPERATION_IN_PROGRESS)
        }
    }

    private fun copyLimited(
        input: InputStream,
        output: OutputStream,
        maximum: Long,
        cancellationCheck: () -> Unit,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        var zeroReads = 0
        try {
            while (true) {
                cancellationCheck()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) {
                    if (++zeroReads > 16) throw LocalBackupException(LocalBackupFailure.IO_FAILURE)
                    continue
                }
                zeroReads = 0
                if (read > maximum - total) throw BackupArchiveException(BackupArchiveFailure.ARCHIVE_TOO_LARGE)
                output.write(buffer, 0, read)
                total += read
            }
        } finally {
            buffer.fill(0)
        }
    }

    private fun safelyDeleteIncomplete(deleteIncompleteDestination: () -> Unit) {
        try {
            deleteIncompleteDestination()
        } catch (_: Exception) {
            // The SAF provider may not support deletion. Never replace the original safe failure with cleanup details.
        }
    }

    companion object {
        private val OCBACKUP_MAGIC = byteArrayOf(0x4f, 0x43, 0x42, 0x4b)
        private val ZIP_MAGICS = listOf(
            byteArrayOf(0x50, 0x4b, 0x03, 0x04),
            byteArrayOf(0x50, 0x4b, 0x05, 0x06),
            byteArrayOf(0x50, 0x4b, 0x07, 0x08),
        )
    }
}

internal fun validateLocalBackupExportPassword(
    password: String,
    confirmation: String,
): LocalBackupPasswordFailure? = when {
    password.isBlank() -> LocalBackupPasswordFailure.EMPTY
    password.codePointCount(0, password.length) < MIN_LOCAL_BACKUP_PASSWORD_CODE_POINTS -> {
        LocalBackupPasswordFailure.TOO_SHORT
    }
    password != confirmation -> LocalBackupPasswordFailure.MISMATCH
    else -> null
}
