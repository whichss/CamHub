package com.camhub.studio.data.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises four independent sender sockets into one hub-like UDP ingress socket. */
class UdpVideoSenderBurstTest {
    @Test
    fun `aggregates simultaneous four-camera sender packets at shared UDP ingress`() {
        val receiver = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply {
            soTimeout = 3_000
            receiveBufferSize = 1 shl 20
        }
        val senders = List(CAMERA_COUNT) { UdpVideoSender() }
        val tracker = UdpIngressBurstTracker()
        val executor = Executors.newFixedThreadPool(CAMERA_COUNT)
        val enqueueGate = CountDownLatch(1)
        val enqueued = BooleanArray(CAMERA_COUNT)
        val frame = ByteArray(FRAME_BYTES) { (it * 31).toByte() }

        try {
            val receiverAddress = InetAddress.getLoopbackAddress().hostAddress ?: "127.0.0.1"
            senders.forEachIndexed { index, sender ->
                sender.start()
                assertTrue(
                    sender.registerClient(
                        clientId = "hub",
                        host = receiverAddress,
                        port = receiver.localPort
                    )
                )
            }
            val cameraBySourcePort = senders.mapIndexed { index, sender ->
                sender.localPort to "camera-$index"
            }.toMap()

            val queued = CountDownLatch(CAMERA_COUNT)
            senders.forEachIndexed { index, sender ->
                executor.execute {
                    enqueueGate.await()
                    enqueued[index] = sender.enqueueFrame(frame, isKeyFrame = true)
                    queued.countDown()
                }
            }
            enqueueGate.countDown()
            assertTrue(queued.await(2, TimeUnit.SECONDS))
            assertTrue(enqueued.all { it })

            val packetCountPerCamera =
                (FRAME_BYTES + RtpVideoPacketCodec.MAX_FRAGMENT_PAYLOAD_BYTES - 1) /
                    RtpVideoPacketCodec.MAX_FRAGMENT_PAYLOAD_BYTES
            val expectedPackets = packetCountPerCamera * CAMERA_COUNT
            val packetBuffer = ByteArray(RtpVideoPacketCodec.MAX_DATAGRAM_BYTES)
            val datagram = DatagramPacket(packetBuffer, packetBuffer.size)
            repeat(expectedPackets) {
                datagram.length = packetBuffer.size
                receiver.receive(datagram)
                val cameraName = cameraBySourcePort[datagram.port]
                assertTrue("unexpected UDP source port ${datagram.port}", cameraName != null)
                tracker.record(
                    cameraName = requireNotNull(cameraName),
                    datagramBytes = datagram.length,
                    elapsedMs = System.nanoTime() / 1_000_000L
                )
            }

            val metrics = tracker.snapshot(System.nanoTime() / 1_000_000L)
            assertEquals(CAMERA_COUNT, metrics.activeCameraCount)
            assertEquals(expectedPackets.toLong(), metrics.packetsPerSecond)
            assertEquals(
                (FRAME_BYTES + packetCountPerCamera * RtpVideoPacketCodec.HEADER_BYTES).toLong() *
                    CAMERA_COUNT,
                metrics.bytesPerSecond
            )
            assertTrue(metrics.maxBytesPer5Ms > 0L)
        } finally {
            executor.shutdownNow()
            senders.forEach(UdpVideoSender::stop)
            receiver.close()
        }
    }

    companion object {
        private const val CAMERA_COUNT = 4
        private const val FRAME_BYTES = 50_000
    }
}
