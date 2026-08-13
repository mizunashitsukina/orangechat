/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupContainerAndroidCompatibilityTest {
    @Test
    fun apiProviderDecryptsIndependentVersionOneReferenceVector() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val container = File.createTempFile("provider-vector-", ".ocbackup", cache)
        val destination = File.createTempFile("provider-output-", ".zip", cache).also { it.delete() }
        val password = PASSWORD.copyOf()
        try {
            container.writeBytes(INDEPENDENT_REFERENCE_VECTOR.hexToBytes())

            BackupContainer.decrypt(container, destination, password, TEST_LIMITS)

            assertArrayEquals("provider-compatibility".toByteArray(), destination.readBytes())
            assertTrue(password.all { it == '\u0000' })
        } finally {
            password.fill('\u0000')
            container.delete()
            destination.delete()
        }
    }

    @Test
    fun api26ProviderRunsProductionMultiChunkEncryptionAndRejectsUnauthenticatedOutput() {
        assertEquals(26, Build.VERSION.SDK_INT)
        assertEquals("PBKDF2WithHmacSHA256", SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").algorithm)
        assertEquals("AES/GCM/NoPadding", Cipher.getInstance("AES/GCM/NoPadding").algorithm)

        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val source = File.createTempFile("provider-source-", ".zip", cache)
        val container = File.createTempFile("provider-container-", BACKUP_CONTAINER_EXTENSION, cache).also { it.delete() }
        val decrypted = File.createTempFile("provider-decrypted-", ".zip", cache).also { it.delete() }
        val wrongOutput = File.createTempFile("provider-wrong-", ".zip", cache).also { it.delete() }
        val tampered = File.createTempFile("provider-tampered-", BACKUP_CONTAINER_EXTENSION, cache)
        val tamperedOutput = File.createTempFile("provider-tampered-output-", ".zip", cache).also { it.delete() }
        val encryptionPassword = PASSWORD.copyOf()
        val decryptionPassword = PASSWORD.copyOf()
        val wrongPassword = WRONG_PASSWORD.copyOf()
        val tamperedPassword = PASSWORD.copyOf()
        try {
            val plaintext = ByteArray(DEFAULT_BACKUP_CONTAINER_CHUNK_BYTES + 257) { index ->
                (index * 31 + 17).toByte()
            }
            source.writeBytes(plaintext)

            BackupContainer.encrypt(source, container, encryptionPassword)
            assertTrue(encryptionPassword.all { it == '\u0000' })
            BackupContainer.decrypt(container, decrypted, decryptionPassword)
            assertArrayEquals(plaintext, decrypted.readBytes())
            assertTrue(decryptionPassword.all { it == '\u0000' })

            expectAuthenticationFailure {
                BackupContainer.decrypt(container, wrongOutput, wrongPassword)
            }
            assertFalse(wrongOutput.exists())
            assertTrue(wrongPassword.all { it == '\u0000' })

            container.copyTo(tampered, overwrite = true)
            RandomAccessFile(tampered, "rw").use { file ->
                file.seek(file.length() - 1)
                val original = file.readByte().toInt()
                file.seek(file.length() - 1)
                file.writeByte(original xor 0x01)
            }
            expectAuthenticationFailure {
                BackupContainer.decrypt(tampered, tamperedOutput, tamperedPassword)
            }
            assertFalse(tamperedOutput.exists())
            assertTrue(tamperedPassword.all { it == '\u0000' })
        } finally {
            encryptionPassword.fill('\u0000')
            decryptionPassword.fill('\u0000')
            wrongPassword.fill('\u0000')
            tamperedPassword.fill('\u0000')
            source.delete()
            container.delete()
            decrypted.delete()
            wrongOutput.delete()
            tampered.delete()
            tamperedOutput.delete()
            cache.listFiles().orEmpty()
                .filter { it.name.startsWith(".ocbackup-") }
                .forEach { it.delete() }
        }
    }

    private fun expectAuthenticationFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected authentication failure")
        } catch (failure: BackupContainerException) {
            assertEquals(BackupContainerFailure.AUTHENTICATION_FAILED, failure.reason)
            assertEquals(BackupContainerFailure.AUTHENTICATION_FAILED.safeMessage, failure.message)
        }
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        private val PASSWORD = "unit-test-password".toCharArray()
        private val WRONG_PASSWORD = "wrong-unit-test-password".toCharArray()
        private val TEST_LIMITS = BackupContainerLimits(
            maxContainerBytes = 1024 * 1024,
            maxPlaintextBytes = 512 * 1024,
            minChunkBytes = 4 * 1024,
            maxChunkBytes = 4 * 1024,
            maxDataChunks = 128,
            minKdfIterations = 1,
            maxKdfIterations = 10,
        )
        // Generated independently with Python cryptography 50.0.0 from the documented version 1 byte format.
        private const val INDEPENDENT_REFERENCE_VECTOR =
            "4f43424b0101010000000034000000020000100000100008000000000102030405060708090a0b0c0d0e0f10" +
                "1112131415161718010000000000000016000000260369d6138ddb3af3349decc2731da6e5f525350415e503" +
                "a778d00705d8155246626b0689361002000000010000000c0000001c86d5b3c4654ec1148ca59b21b976662" +
                "f546de30279a40063fc119325"
    }
}
