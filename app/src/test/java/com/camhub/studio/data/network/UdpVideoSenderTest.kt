package com.camhub.studio.data.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpVideoSenderTest {

    @Test
    fun `failed initial config is retried before video with increasing RTP sequence`() {
        val failed = CountDownLatch(1)
        val delivered = CountDownLatch(2)
        val first = AtomicBoolean(true)
        val sent = Collections.synchronizedList(mutableListOf<ByteArray>())
        val socket = object : DatagramSocket(0, InetAddress.getLoopbackAddress()) {
            override fun send(packet: DatagramPacket) {
                if (first.compareAndSet(true, false)) {
                    failed.countDown()
                    throw IOException("simulated initial config failure")
                }
                sent.add(packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
                delivered.countDown()
            }
        }
        val sender = UdpVideoSender(socketFactory = { socket })
        try {
            sender.start()
            // Cache a config before a client registers; registration queues it.
            assertFalse(sender.enqueueFrame(byteArrayOf(1, 2, 3), isCodecConfig = true))
            sender.registerClient("hub", "127.0.0.1", 12345)
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertTrue(sender.enqueueFrame(byteArrayOf(4, 5, 6), isKeyFrame = true))
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            val config = RtpVideoPacketCodec.decode(sent[0])!!
            val frame = RtpVideoPacketCodec.decode(sent[1])!!
            assertTrue(config.isCodecConfig)
            assertTrue(frame.isKeyFrame)
            assertEquals((config.rtpSequence + 1) and 0xffff, frame.rtpSequence)
            val deadlineNs = System.nanoTime() + 2_000_000_000L
            while (sender.sendMetrics().socketAcceptedDatagrams < 2 && System.nanoTime() < deadlineNs) {
                Thread.yield()
            }
            val metrics = sender.sendMetrics()
            assertEquals(1L, metrics.socketSendFailures)
            assertEquals(2L, metrics.socketAcceptedDatagrams)
            assertEquals(1L, metrics.latestKeyFramePacketCount)
        } finally {
            sender.stop()
        }
    }

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
            // The receiver may finish just before the sender records its final timing.
            val deadlineNs = System.nanoTime() + 2_000_000_000L
            while (sender.sendMetrics().packetizeAndSend.sampleCount == 0 && System.nanoTime() < deadlineNs) {
                Thread.yield()
            }
            val metrics = sender.sendMetrics()
            assertEquals(1, metrics.queueResidence.sampleCount)
            assertEquals(1, metrics.packetizeAndSend.sampleCount)
            assertEquals(1, metrics.socketSend.sampleCount)
            assertEquals(1, metrics.keyFramePacketizeAndSend.sampleCount)
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
