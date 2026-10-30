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
    fun `v4 payload matches cross-platform golden vector`() {
        val encoded = CamHubVideoPayloadCodec.encode(
            width = 2,
            height = 3,
            rotationDegrees = 270,
            isKeyFrame = true,
            isCodecConfig = true,
            frameSequence = 1,
            captureAtWallMs = 2,
            encodedAtWallMs = 3,
            h264Bytes = byteArrayOf(0xaa.toByte(), 0xbb.toByte())
        )

        assertArrayEquals(
            WireGoldenVectors.bytes("payload_v4"),
            encoded
        )
    }

    @Test
    fun `encodes borrowed buffer range identically to owned array without changing source position`() {
        val encoded = byteArrayOf(9, 8, 7, 6, 5)
        val source = ByteBuffer.allocate(12).apply {
            position(3)
            put(encoded)
            position(1)
        }

        val fromBuffer = CamHubVideoPayloadCodec.encodeFromBuffer(
            width = 1920,
            height = 1080,
            rotationDegrees = 270,
            isKeyFrame = true,
            isCodecConfig = false,
            frameSequence = 987L,
            captureAtWallMs = 1_000L,
            encodedAtWallMs = 1_015L,
            source = source,
            offset = 3,
            length = encoded.size
        )
        val fromArray = CamHubVideoPayloadCodec.encode(
            width = 1920,
            height = 1080,
            rotationDegrees = 270,
            isKeyFrame = true,
            isCodecConfig = false,
            frameSequence = 987L,
            captureAtWallMs = 1_000L,
            encodedAtWallMs = 1_015L,
            h264Bytes = encoded
        )

        assertArrayEquals(fromArray, fromBuffer)
        assertEquals(1, source.position())
        val decoded = requireNotNull(CamHubVideoPayloadCodec.decode(fromBuffer))
        assertArrayEquals(encoded, decoded.h264Bytes)
        assertEquals(987L, decoded.frameSequence)
    }

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
