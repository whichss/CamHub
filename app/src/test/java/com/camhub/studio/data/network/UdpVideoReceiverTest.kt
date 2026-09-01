package com.camhub.studio.data.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpVideoReceiverTest {

    @Test
    fun `receiver completes out of order frame on its camera thread`() {
        val loopback = InetAddress.getLoopbackAddress()
        val senderSocket = DatagramSocket(0, loopback)
        val received = AtomicReference<ByteArray>()
        val callbackThread = AtomicReference<String>()
        val completed = CountDownLatch(1)
        val receiver = UdpVideoReceiver(
            cameraName = "Camera 1",
            expectedHost = loopback.hostAddress ?: "127.0.0.1",
            expectedSourcePort = senderSocket.localPort,
            frameDeadlineMs = 500,
            onFrame = { bytes, _, _ ->
                received.set(bytes)
                callbackThread.set(Thread.currentThread().name)
                completed.countDown()
            }
        )

        try {
            val port = receiver.start()
            val source = ByteArray(4_321) { (it % 197).toByte() }
            val packets = RtpVideoPacketCodec.packetize(
                frame = source,
                transportFrameId = 1,
                rtpTimestamp = 90_000,
                ssrc = 99,
                firstRtpSequence = 100,
                isKeyFrame = true
            )
            packets.reversed().forEach { bytes ->
                senderSocket.send(
                    DatagramPacket(bytes, bytes.size, loopback, port)
                )
            }

            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertArrayEquals(source, received.get())
            assertEquals("HubUdpVideoReceive-Camera_1", callbackThread.get())
        } finally {
            receiver.stop()
            senderSocket.close()
        }
    }

    @Test
    fun `receiver drops an incomplete frame at its deadline`() {
        val loopback = InetAddress.getLoopbackAddress()
        val senderSocket = DatagramSocket(0, loopback)
        val dropped = AtomicInteger(0)
        val dropObserved = CountDownLatch(1)
        val statsObserved = CountDownLatch(1)
        val latestStats = AtomicReference<UdpReceiverStats>()
        val receiver = UdpVideoReceiver(
            cameraName = "Camera 2",
            expectedHost = loopback.hostAddress ?: "127.0.0.1",
            expectedSourcePort = senderSocket.localPort,
            frameDeadlineMs = 35,
            statsIntervalMs = 1,
            onFrame = { _, _, _ -> },
            onDroppedFrames = { count ->
                dropped.addAndGet(count)
                dropObserved.countDown()
            },
            onStats = { stats ->
                if (stats.deadlineDroppedFrames > 0) {
                    latestStats.set(stats)
                    statsObserved.countDown()
                }
            }
        )

        try {
            val port = receiver.start()
            val firstPacket = RtpVideoPacketCodec.packetize(
                frame = ByteArray(3_000),
                transportFrameId = 2,
                rtpTimestamp = 180_000,
                ssrc = 100,
                firstRtpSequence = 200
            ).first()
            senderSocket.send(
                DatagramPacket(firstPacket, firstPacket.size, loopback, port)
            )

            assertTrue(dropObserved.await(1, TimeUnit.SECONDS))
            assertTrue(statsObserved.await(1, TimeUnit.SECONDS))
            assertEquals(1, dropped.get())
            assertEquals(1L, latestStats.get().deadlineDroppedFrames)
            assertTrue(latestStats.get().estimatedMissingPackets > 0L)
            assertTrue(latestStats.get().estimatedPacketLossPercent > 0f)
        } finally {
            receiver.stop()
            senderSocket.close()
        }
    }
}
