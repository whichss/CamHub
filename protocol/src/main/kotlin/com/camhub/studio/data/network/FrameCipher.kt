package com.camhub.studio.data.network

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM frame encryption/decryption.
 *
 * Wire format: [12-byte IV][ciphertext + 16-byte GCM tag]
 *
 * IV structure: [4-byte random prefix][8-byte counter]
 * The random prefix is generated once per session, counter increments per frame.
 */
class FrameCipher(key: ByteArray) {

    companion object {
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
        private const val ALGORITHM = "AES/GCM/NoPadding"
    }

    init {
        require(key.size == 32) { "AES-256 requires 32-byte key" }
    }

    private val secretKey = SecretKeySpec(key, "AES")
    private val ivPrefix = ByteArray(4).also { SecureRandom().nextBytes(it) }
    // AtomicLong counter ensures unique IVs. At 30fps, overflow takes ~9.7 billion years.
    private val counter = AtomicLong(0L)

    /**
     * Encrypt a plaintext frame.
     * @return [12-byte IV][encrypted data + 16-byte GCM tag]
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = buildIv(counter.getAndIncrement())
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
        val output = ByteArray(IV_SIZE + cipher.getOutputSize(plaintext.size))
        iv.copyInto(output)
        val written = cipher.doFinal(plaintext, 0, plaintext.size, output, IV_SIZE)
        check(written == output.size - IV_SIZE)
        return output
    }

    /**
     * Decrypt a frame produced by [encrypt].
     * @param data [12-byte IV][encrypted data + 16-byte GCM tag]
     * @return decrypted plaintext
     */
    fun decrypt(data: ByteArray): ByteArray {
        require(data.size > IV_SIZE) { "Data too short" }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            GCMParameterSpec(TAG_BITS, data, 0, IV_SIZE)
        )
        return cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE)
    }

    private fun buildIv(counter: Long): ByteArray {
        return ByteArray(IV_SIZE).also { iv ->
            ivPrefix.copyInto(iv)
            for (index in 0 until Long.SIZE_BYTES) {
                val shift = (Long.SIZE_BYTES - 1 - index) * Byte.SIZE_BITS
                iv[ivPrefix.size + index] = (counter ushr shift).toByte()
            }
        }
    }
}
