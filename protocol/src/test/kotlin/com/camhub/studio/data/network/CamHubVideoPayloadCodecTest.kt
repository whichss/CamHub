package com.camhub.studio.data.network

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CamHubVideoPayloadCodecTest {
    @Test
    fun `decodes current v4 camera payload`() {
        val h264 = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        val payload = ByteBuffer.allocate(31 + h264.size).apply {
            put(4)
            putShort(1280)
            putShort(720)
            put(1)
            put(0x03)
            putLong(42)
            putLong(1_000)
            putLong(1_009)
            put(h264)
        }.array()

        val decoded = requireNotNull(CamHubVideoPayloadCodec.decode(payload))

        assertEquals(1280, decoded.width)
        assertEquals(720, decoded.height)
        assertEquals(90, decoded.rotationDegrees)
        assertEquals(42L, decoded.frameSequence)
        assertEquals(1_000L, decoded.captureAtWallMs)
        assertEquals(1_009L, decoded.encodedAtWallMs)
        assertTrue(decoded.isKeyFrame)
        assertTrue(decoded.isCodecConfig)
        assertArrayEquals(h264, decoded.h264Bytes)
    }

    @Test
    fun `decodes legacy v2 camera payload`() {
        val payload = byteArrayOf(2, 0x02, 0x80.toByte(), 0x01, 0x68, 0, 0, 1, 2)
        val decoded = requireNotNull(CamHubVideoPayloadCodec.decode(payload))

        assertEquals(640, decoded.width)
        assertEquals(360, decoded.height)
        assertFalse(decoded.isKeyFrame)
        assertArrayEquals(byteArrayOf(1, 2), decoded.h264Bytes)
    }

    @Test
    fun `rejects malformed payload`() {
        assertNull(CamHubVideoPayloadCodec.decode(byteArrayOf(9, 0, 1, 0, 1, 0, 0, 1)))
    }
}
