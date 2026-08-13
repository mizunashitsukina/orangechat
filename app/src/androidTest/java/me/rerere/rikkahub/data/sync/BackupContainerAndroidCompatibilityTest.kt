/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupContainerAndroidCompatibilityTest {
    @Test
    fun apiProviderDecryptsIndependentVersionOneReferenceVector() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val container = File.createTempFile("provider-vector-", ".ocbackup", cache)
        val destination = File.createTempFile("provider-output-", ".zip", cache).also { it.delete() }
        try {
            container.writeBytes(INDEPENDENT_REFERENCE_VECTOR.hexToBytes())

            BackupContainer.decrypt(container, destination, PASSWORD.copyOf(), TEST_LIMITS)

            assertArrayEquals("provider-compatibility".toByteArray(), destination.readBytes())
        } finally {
            container.delete()
            destination.delete()
        }
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        private val PASSWORD = "unit-test-password".toCharArray()
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
