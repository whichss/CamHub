package com.camhub.studio.data.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FrameCipherTest {
    @Test
    fun `AES GCM known-answer envelope decrypts`() {
        val encryptedEmptyPlaintext = WireGoldenVectors.bytes("aes_gcm_empty")
        assertArrayEquals(ByteArray(0), FrameCipher(ByteArray(32)).decrypt(encryptedEmptyPlaintext))
    }

    @Test
    fun `tampered frames are rejected`() {
        val cipher = FrameCipher(ByteArray(32) { it.toByte() })
        val encrypted = cipher.encrypt(ByteArray(1024) { 7 })
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertTrue(runCatching { cipher.decrypt(encrypted) }.isFailure)
    }

    @Test
    fun `concurrent encryption uses unique nonces and decrypts correctly`() {
        val cipher = FrameCipher(ByteArray(32) { it.toByte() })
        val executor = Executors.newFixedThreadPool(4)
        try {
            val frames = (0 until 256).map { index ->
                executor.submit<ByteArray> { cipher.encrypt(ByteArray(32) { index.toByte() }) }
            }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(frames.size, frames.map { it.take(12) }.toSet().size)
            frames.forEachIndexed { index, frame ->
                assertArrayEquals(ByteArray(32) { index.toByte() }, cipher.decrypt(frame))
            }
        } finally {
            executor.shutdownNow()
        }
    }
    @Test
    fun `encrypted frame decrypts across independent endpoints`() {
        val key = ByteArray(32) { index -> (index * 7).toByte() }
        val source = ByteArray(4_096) { index -> (index % 251).toByte() }

        val encrypted = FrameCipher(key).encrypt(source)

        assertFalse(source.contentEquals(encrypted))
        assertArrayEquals(source, FrameCipher(key).decrypt(encrypted))
    }
}
