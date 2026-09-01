package com.camhub.studio.data.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpVideoPacketTest {

    @Test
    fun `packetized frame stays below MTU and reassembles out of order`() {
        val source = ByteArray(8_123) { (it % 251).toByte() }
        val packets = RtpVideoPacketCodec.packetize(
            frame = source,
            transportFrameId = 42,
            rtpTimestamp = 90_000,
            ssrc = 0x1234,
            firstRtpSequence = 65_534,
            isKeyFrame = true
        )

        assertTrue(packets.size > 1)
        assertTrue(packets.all { it.size <= RtpVideoPacketCodec.MAX_DATAGRAM_BYTES })
        assertEquals(65_534, RtpVideoPacketCodec.decode(packets.first())?.rtpSequence)
        assertEquals(0, RtpVideoPacketCodec.decode(packets[2])?.rtpSequence)

        val reassembler = DeadlineFrameReassembler(frameDeadlineMs = 35)
        var completed: DeadlineFrameReassembler.CompletedFrame? = null
        packets.reversed().forEachIndexed { index, packet ->
            val result = reassembler.offer(packet, nowMs = 1_000L + index)
            if (result is DeadlineFrameReassembler.OfferResult.Complete) {
                completed = result.frame
            }
        }

        assertArrayEquals(source, completed?.bytes)
        assertEquals(42L, completed?.transportFrameId)
        assertEquals(true, completed?.isKeyFrame)
        assertEquals(0, reassembler.pendingFrameCount())
    }

    @Test
    fun `incomplete frame expires and late fragments are ignored`() {
        val packets = RtpVideoPacketCodec.packetize(
            frame = ByteArray(3_000) { 7 },
            transportFrameId = 9,
            rtpTimestamp = 12,
            ssrc = 34,
            firstRtpSequence = 1
        )
        val reassembler = DeadlineFrameReassembler(frameDeadlineMs = 35)

        assertEquals(
            DeadlineFrameReassembler.OfferResult.Accepted,
            reassembler.offer(packets.first(), nowMs = 100)
        )
        val expiration = reassembler.expireDetailed(nowMs = 135)
        assertEquals(1, expiration.frameCount)
        assertEquals(packets.size - 1, expiration.missingFragmentCount)
        assertEquals(
            DeadlineFrameReassembler.OfferResult.Dropped(
                DeadlineFrameReassembler.DropReason.ALREADY_FINISHED
            ),
            reassembler.offer(packets.last(), nowMs = 136)
        )
    }

    @Test
    fun `duplicate fragment does not finish frame early`() {
        val packets = RtpVideoPacketCodec.packetize(
            frame = ByteArray(2_000) { 3 },
            transportFrameId = 10,
            rtpTimestamp = 90,
            ssrc = 11,
            firstRtpSequence = 10
        )
        val reassembler = DeadlineFrameReassembler()

        assertEquals(
            DeadlineFrameReassembler.OfferResult.Accepted,
            reassembler.offer(packets.first(), nowMs = 0)
        )
        assertEquals(
            DeadlineFrameReassembler.OfferResult.Duplicate,
            reassembler.offer(packets.first(), nowMs = 1)
        )
        assertTrue(reassembler.offer(packets.last(), nowMs = 2) is
            DeadlineFrameReassembler.OfferResult.Complete)
    }

    @Test
    fun `malformed or oversized datagram is rejected`() {
        val reassembler = DeadlineFrameReassembler()
        assertEquals(
            DeadlineFrameReassembler.OfferResult.Dropped(
                DeadlineFrameReassembler.DropReason.MALFORMED_PACKET
            ),
            reassembler.offer(byteArrayOf(1, 2, 3), nowMs = 0)
        )

        val valid = RtpVideoPacketCodec.packetize(
            frame = ByteArray(100),
            transportFrameId = 1,
            rtpTimestamp = 1,
            ssrc = 1,
            firstRtpSequence = 1
        ).single()
        val oversized = valid + ByteArray(RtpVideoPacketCodec.MAX_DATAGRAM_BYTES)
        assertEquals(
            DeadlineFrameReassembler.OfferResult.Dropped(
                DeadlineFrameReassembler.DropReason.MALFORMED_PACKET
            ),
            reassembler.offer(oversized, nowMs = 0)
        )
    }
}
