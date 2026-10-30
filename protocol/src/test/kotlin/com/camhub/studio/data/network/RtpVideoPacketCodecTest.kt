package com.camhub.studio.data.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpVideoPacketCodecTest {
    @Test
    fun `single-fragment packet matches cross-platform golden vector`() {
        val packet = RtpVideoPacketCodec.packetize(
            frame = byteArrayOf(0xaa.toByte()),
            transportFrameId = 1,
            rtpTimestamp = 2,
            ssrc = 3,
            firstRtpSequence = 4,
            isKeyFrame = true,
            isCodecConfig = true
        ).single()

        assertArrayEquals(
            WireGoldenVectors.bytes("rtp_single_fragment"),
            packet
        )
    }

    @Test
    fun `packetizeEach matches owned packet output while reusing scratch`() {
        val frame = ByteArray(RtpVideoPacketCodec.MAX_FRAGMENT_PAYLOAD_BYTES * 2 + 37) {
            (it * 13).toByte()
        }
        val expected = RtpVideoPacketCodec.packetize(
            frame = frame,
            transportFrameId = 77,
            rtpTimestamp = 0x1_0000_0002L,
            ssrc = 0x1_0000_0003L,
            firstRtpSequence = 65_535,
            isKeyFrame = true,
            isCodecConfig = true
        )
        val scratch = ByteArray(RtpVideoPacketCodec.MAX_DATAGRAM_BYTES)
        val actual = mutableListOf<ByteArray>()
        var callbackBuffer: ByteArray? = null

        val count = RtpVideoPacketCodec.packetizeEach(
            frame = frame,
            transportFrameId = 77,
            rtpTimestamp = 0x1_0000_0002L,
            ssrc = 0x1_0000_0003L,
            firstRtpSequence = 65_535,
            scratch = scratch,
            isKeyFrame = true,
            isCodecConfig = true
        ) { bytes, length ->
            if (callbackBuffer == null) callbackBuffer = bytes
            else assertSame(callbackBuffer, bytes)
            actual += bytes.copyOf(length)
        }

        assertEquals(expected.size, count)
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (expectedPacket, actualPacket) ->
            assertArrayEquals(expectedPacket, actualPacket)
        }
    }

    @Test
    fun `decodeInto fills reusable packet view with decoded header values`() {
        val packets = RtpVideoPacketCodec.packetize(
            frame = ByteArray(2_500) { (it * 7).toByte() },
            transportFrameId = 0x1020_3040L,
            rtpTimestamp = 0xfedc_ba98L,
            ssrc = 0x8765_4321L,
            firstRtpSequence = 65_535,
            isKeyFrame = true
        )
        val view = RtpVideoPacketCodec.ReusablePacketView()

        packets.forEach { bytes ->
            val expected = RtpVideoPacketCodec.decode(bytes)!!
            assertTrue(RtpVideoPacketCodec.decodeInto(bytes, bytes.size, view))
            assertSame(bytes, view.payload)
            assertEquals(expected.rtpSequence, view.rtpSequence)
            assertEquals(expected.rtpTimestamp, view.rtpTimestamp)
            assertEquals(expected.ssrc, view.ssrc)
            assertEquals(expected.transportFrameId, view.transportFrameId)
            assertEquals(expected.fragmentIndex, view.fragmentIndex)
            assertEquals(expected.fragmentCount, view.fragmentCount)
            assertEquals(expected.totalFrameBytes, view.totalFrameBytes)
            assertEquals(expected.isMarker, view.isMarker)
            assertEquals(expected.isKeyFrame, view.isKeyFrame)
            assertEquals(expected.isCodecConfig, view.isCodecConfig)
        }
    }
}
