/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal const val BACKUP_AES_KEY_BITS = 256
internal const val BACKUP_GCM_TAG_BITS = 128
internal const val BACKUP_GCM_TAG_BYTES = BACKUP_GCM_TAG_BITS / Byte.SIZE_BITS
internal const val BACKUP_SALT_BYTES = 16
internal const val BACKUP_NONCE_PREFIX_BYTES = 8
internal const val BACKUP_NONCE_BYTES = 12

internal fun interface BackupRandom {
    fun nextBytes(target: ByteArray)
}

internal object SecureBackupRandom : BackupRandom {
    private val secureRandom = SecureRandom()

    override fun nextBytes(target: ByteArray) = secureRandom.nextBytes(target)
}

internal object BackupCrypto {
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"

    fun <T> withDerivedKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        block: (SecretKeySpec) -> T,
    ): T {
        val passwordCopy = password.copyOf()
        val keySpec = PBEKeySpec(passwordCopy, salt, iterations, BACKUP_AES_KEY_BITS)
        var encodedKey: ByteArray? = null
        try {
            val keyBytes = SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(keySpec).encoded
            encodedKey = keyBytes
            return block(SecretKeySpec(keyBytes, "AES"))
        } finally {
            encodedKey?.fill(0)
            keySpec.clearPassword()
            passwordCopy.fill('\u0000')
        }
    }

    fun encrypt(
        key: SecretKeySpec,
        nonce: ByteArray,
        aadHeader: ByteArray,
        aadRecord: ByteArray,
        plaintext: ByteArray,
        plaintextLength: Int,
    ): ByteArray = cipher(Cipher.ENCRYPT_MODE, key, nonce, aadHeader, aadRecord)
        .doFinal(plaintext, 0, plaintextLength)

    fun decrypt(
        key: SecretKeySpec,
        nonce: ByteArray,
        aadHeader: ByteArray,
        aadRecord: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray = cipher(Cipher.DECRYPT_MODE, key, nonce, aadHeader, aadRecord)
        .doFinal(ciphertext)

    fun nonce(prefix: ByteArray, counter: Int): ByteArray {
        require(prefix.size == BACKUP_NONCE_PREFIX_BYTES)
        require(counter >= 0)
        return ByteBuffer.allocate(BACKUP_NONCE_BYTES)
            .put(prefix)
            .putInt(counter)
            .array()
    }

    private fun cipher(
        mode: Int,
        key: SecretKeySpec,
        nonce: ByteArray,
        aadHeader: ByteArray,
        aadRecord: ByteArray,
    ): Cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
        init(mode, key, GCMParameterSpec(BACKUP_GCM_TAG_BITS, nonce))
        updateAAD(aadHeader)
        updateAAD(aadRecord)
    }
}
