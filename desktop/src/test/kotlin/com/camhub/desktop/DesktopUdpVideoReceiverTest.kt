package com.camhub.desktop

import com.camhub.studio.data.network.FrameCipher
import com.camhub.studio.data.network.RtpVideoPacketCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopUdpVideoReceiverTest {
    @Test
    fun `concurrent start and close leave no listening socket`() {
        repeat(30) {
            val errors = AtomicReference<Throwable?>()
            val receiver = DesktopUdpVideoReceiver(
                cameraName = "concurrent lifecycle",
                expectedHost = "127.0.0.1",
                expectedSourcePort = 0,
                sessionKey = ByteArray(32),
                onFrame = {}, onStats = {}, onError = { errors.set(it) }
            )
            val gate = CountDownLatch(1)
            val starter = Thread {
                gate.await()
                try { receiver.start() } catch (_: IllegalStateException) { }
            }
            val closer = Thread { gate.await(); receiver.close() }
            starter.start()
            closer.start()
            gate.countDown()
            starter.join(2_000)
            closer.join(2_000)
            assertTrue("start did not finish", !starter.isAlive)
            assertTrue("close did not finish", !closer.isAlive)
            assertEquals(0, receiver.localPort)
            assertEquals(null, errors.get())
        }
    }

    @Test
    fun `closed receiver cannot be restarted`() {
        val receiver = DesktopUdpVideoReceiver(
            cameraName = "closed",
            expectedHost = "127.0.0.1",
            expectedSourcePort = 0,
            sessionKey = ByteArray(32),
            onFrame = {}, onStats = {}, onError = {}
        )
        receiver.close()
        try {
            receiver.start()
            throw AssertionError("closed receiver restarted")
        } catch (_: IllegalStateException) {
            // A new connection must construct a new receiver.
        }
        assertEquals(0, receiver.localPort)
    }

    @Test
    fun `reports incomplete frames after their playout deadline`() {
        val sender = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val key = ByteArray(32) { it.toByte() }
        val dropped = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val receiver = DesktopUdpVideoReceiver(
            cameraName = "loss recovery",
            expectedHost = InetAddress.getLoopbackAddress().hostAddress,
            expectedSourcePort = sender.localPort,
            sessionKey = key,
            onFrame = {},
            onStats = {},
            onError = { throw AssertionError(it) },
            onDroppedFrames = { count ->
                dropped.addAndGet(count)
                latch.countDown()
            }
        )

        try {
            val receivePort = receiver.start()
            val packets = RtpVideoPacketCodec.packetize(
                frame = ByteArray(RtpVideoPacketCodec.MAX_FRAGMENT_PAYLOAD_BYTES + 1),
                transportFrameId = 8,
                rtpTimestamp = 720,
                ssrc = 5,
                firstRtpSequence = 10,
                isKeyFrame = true
            )
            sender.send(
                DatagramPacket(
                    packets.last(),
                    packets.last().size,
                    InetAddress.getLoopbackAddress(),
                    receivePort
                )
            )

            assertTrue("incomplete frame was not expired", latch.await(2, TimeUnit.SECONDS))
            assertEquals(1, dropped.get())
        } finally {
            receiver.close()
            sender.close()
        }
    }

    @Test
    fun `receives an IPv6 UDP RTP frame from an IPv6 camera endpoint`() {
        val loopback = InetAddress.getByName("::1")
        val sender = DatagramSocket(0, loopback)
        val key = ByteArray(32) { (it + 3).toByte() }
        val received = AtomicReference<ByteArray>()
        val latch = CountDownLatch(1)
        val receiver = DesktopUdpVideoReceiver(
            cameraName = "IPv6 test",
            expectedHost = loopback.hostAddress,
            expectedSourcePort = sender.localPort,
            sessionKey = key,
            onFrame = {
                received.set(it.h264Bytes)
                latch.countDown()
            },
            onStats = {},
            onError = { throw AssertionError(it) }
        )

        try {
            val port = receiver.start()
            val h264 = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
            val payload = ByteBuffer.allocate(31 + h264.size).apply {
                put(4)
                putShort(1280)
                putShort(720)
                put(0)
                put(1)
                putLong(1)
                putLong(1_000)
                putLong(1_010)
                put(h264)
            }.array()
            val encrypted = FrameCipher(key).encrypt(payload)
            val packet = RtpVideoPacketCodec.packetize(
                frame = encrypted,
                transportFrameId = 1,
                rtpTimestamp = 90,
                ssrc = 3,
                firstRtpSequence = 4,
                isKeyFrame = true
            ).single()
            sender.send(DatagramPacket(packet, packet.size, loopback, port))

            assertTrue("IPv6 frame was not received", latch.await(2, TimeUnit.SECONDS))
            assertArrayEquals(h264, received.get())
        } finally {
            receiver.close()
            sender.close()
        }
    }

    @Test
    fun `receives decrypts and parses camera RTP frame`() {
        val key = ByteArray(32) { it.toByte() }
        val h264 = byteArrayOf(0, 0, 0, 1, 0x65, 4, 5, 6)
        val payload = ByteBuffer.allocate(31 + h264.size).apply {
            put(4)
            putShort(1280)
            putShort(720)
            put(0)
            put(1)
            putLong(9)
            putLong(1_000)
            putLong(1_010)
            put(h264)
        }.array()
        val sender = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val received = AtomicReference<ByteArray>()
        val latch = CountDownLatch(1)
        val receiver = DesktopUdpVideoReceiver(
            cameraName = "test",
            expectedHost = InetAddress.getLoopbackAddress().hostAddress,
            expectedSourcePort = sender.localPort,
            sessionKey = key,
            onFrame = {
                received.set(it.h264Bytes)
                latch.countDown()
            },
            onStats = {},
            onError = { throw AssertionError(it) }
        )

        try {
            val receivePort = receiver.start()
            val encrypted = FrameCipher(key).encrypt(payload)
            val packets = RtpVideoPacketCodec.packetize(
                frame = encrypted,
                transportFrameId = 1,
                rtpTimestamp = 90,
                ssrc = 2,
                firstRtpSequence = 3,
                isKeyFrame = true
            )
            packets.forEach {
                sender.send(
                    DatagramPacket(
                        it,
                        it.size,
                        InetAddress.getLoopbackAddress(),
                        receivePort
                    )
                )
            }

            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertArrayEquals(h264, received.get())
        } finally {
            receiver.close()
            sender.close()
        }
    }
}
