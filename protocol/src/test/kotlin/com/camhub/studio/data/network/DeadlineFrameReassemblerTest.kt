package com.camhub.studio.data.network

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadlineFrameReassemblerTest {
    @Test
    fun `deadline expiry reports all missing fragments across pending frames`() {
        val assembler = DeadlineFrameReassembler(frameDeadlineMs = 35)
        val first = RtpVideoPacketCodec.packetize(ByteArray(3000), 10, 2, 3, 4)
        val second = RtpVideoPacketCodec.packetize(ByteArray(5000), 11, 2, 3, 4)
        assembler.offer(first[0], 0)
        assembler.offer(second[0], 1)
        assertEquals(DeadlineFrameReassembler.ExpirationStats(2, first.size + second.size - 2),
            assembler.expireDetailed(36))
        assertEquals(0, assembler.pendingFrameCount())
        assertEquals(DeadlineFrameReassembler.OfferResult.Dropped(
            DeadlineFrameReassembler.DropReason.ALREADY_FINISHED), assembler.offer(first[1], 37))
    }

    @Test
    fun `changed fragment metadata invalidates the frame`() {
        val assembler = DeadlineFrameReassembler()
        val first = RtpVideoPacketCodec.packetize(ByteArray(3000), 12, 2, 3, 4)
        val conflict = RtpVideoPacketCodec.packetize(ByteArray(3000), 12, 9, 3, 4)
        assembler.offer(first[0], 0)
        assertEquals(DeadlineFrameReassembler.OfferResult.Dropped(
            DeadlineFrameReassembler.DropReason.CONFLICTING_METADATA), assembler.offer(conflict[1], 1))
        assertEquals(0, assembler.pendingFrameCount())
    }
    @Test
    fun `reused receive buffer cannot mutate assembled or completed frames`() {
        val source = ByteArray(5000) { (it % 251).toByte() }
        val packets = RtpVideoPacketCodec.packetize(source, 1, 2, 3, 4)
        val buffer = ByteArray(RtpVideoPacketCodec.MAX_DATAGRAM_BYTES)
        val assembler = DeadlineFrameReassembler()
        var complete: DeadlineFrameReassembler.CompletedFrame? = null
        packets.reversed().forEachIndexed { index, packet ->
            packet.copyInto(buffer)
            val result = assembler.offer(buffer, index.toLong(), packet.size)
            buffer.fill(0)
            if (result is DeadlineFrameReassembler.OfferResult.Complete) complete = result.frame
        }
        assertArrayEquals(source, complete?.bytes)
    }

    @Test
    fun `oversized advertised frame is rejected before allocation`() {
        val packet = RtpVideoPacketCodec.packetize(ByteArray(3000), 2, 2, 3, 4).first()
        val assembler = DeadlineFrameReassembler(maxFrameBytes = 1000)
        assertEquals(DeadlineFrameReassembler.OfferResult.Dropped(
            DeadlineFrameReassembler.DropReason.FRAME_TOO_LARGE
        ), assembler.offer(packet, 0))
        assertEquals(0, assembler.pendingFrameCount())
    }

    @Test
    fun `advertised integer overflow is rejected`() {
        val packet = RtpVideoPacketCodec.packetize(ByteArray(1200), 3, 2, 3, 4).first()
        ByteBuffer.wrap(packet).putInt(28, Int.MAX_VALUE)
        assertTrue(DeadlineFrameReassembler().offer(packet, 0) is DeadlineFrameReassembler.OfferResult.Dropped)
    }
}
