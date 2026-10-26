package com.camhub.studio.data.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FrameCipherTest {
    @Test
    fun `encrypted frame decrypts across independent endpoints`() {
        val key = ByteArray(32) { index -> (index * 7).toByte() }
        val source = ByteArray(4_096) { index -> (index % 251).toByte() }

        val encrypted = FrameCipher(key).encrypt(source)

        assertFalse(source.contentEquals(encrypted))
        assertArrayEquals(source, FrameCipher(key).decrypt(encrypted))
    }
}
