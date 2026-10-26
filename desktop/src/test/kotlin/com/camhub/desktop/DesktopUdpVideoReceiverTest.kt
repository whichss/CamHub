package com.camhub.desktop

import com.camhub.studio.data.network.FrameCipher
import com.camhub.studio.data.network.RtpVideoPacketCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopUdpVideoReceiverTest {
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
