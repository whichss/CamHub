package com.camhub.studio.data.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpVideoSenderTest {

    @Test
    fun `sender delivers packetized frame on dedicated UDP path`() {
        val receiver = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply {
            soTimeout = 2_000
        }
        val sender = UdpVideoSender()
        try {
            assertTrue(sender.start() > 0)
            assertTrue(
                sender.registerClient(
                    clientId = "hub",
                    host = InetAddress.getLoopbackAddress().hostAddress ?: "127.0.0.1",
                    port = receiver.localPort
                )
            )

            val source = ByteArray(5_555) { (it % 239).toByte() }
            assertTrue(sender.enqueueFrame(source, isKeyFrame = true))

            val reassembler = DeadlineFrameReassembler(frameDeadlineMs = 2_000)
            var completed: DeadlineFrameReassembler.CompletedFrame? = null
            while (completed == null) {
                val buffer = ByteArray(RtpVideoPacketCodec.MAX_DATAGRAM_BYTES)
                val datagram = DatagramPacket(buffer, buffer.size)
                receiver.receive(datagram)
                val bytes = datagram.data.copyOf(datagram.length)
                val result = reassembler.offer(bytes, nowMs = System.currentTimeMillis())
                if (result is DeadlineFrameReassembler.OfferResult.Complete) {
                    completed = result.frame
                }
            }

            assertArrayEquals(source, completed.bytes)
            assertTrue(completed.isKeyFrame)
            assertEquals(1, sender.clientCount)
        } finally {
            sender.stop()
            receiver.close()
        }
    }

    @Test
    fun `sender stays idle until a client is registered`() {
        val sender = UdpVideoSender()
        try {
            sender.start()
            assertFalse(sender.enqueueFrame(ByteArray(100)))
            assertEquals(0, sender.clientCount)
        } finally {
            sender.stop()
        }
    }
}
