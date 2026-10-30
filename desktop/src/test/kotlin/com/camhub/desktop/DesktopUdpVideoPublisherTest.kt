package com.camhub.desktop

import com.camhub.studio.data.network.CamHubVideoPayloadCodec
import com.camhub.studio.data.network.FrameCipher
import com.camhub.studio.data.network.RtpVideoPacketCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopUdpVideoPublisherTest {
    @Test
    fun `concurrent subscribers never exceed the four hub limit`() {
        val publisher = DesktopUdpVideoPublisher(ByteArray(32) { 9 })
        val workers = Executors.newFixedThreadPool(16)
        val ready = CountDownLatch(16)
        val start = CountDownLatch(1)
        try {
            publisher.start()
            val attempts = (0 until 16).map { index ->
                workers.submit<Boolean> {
                    ready.countDown()
                    check(start.await(2, TimeUnit.SECONDS))
                    publisher.subscribe("hub-$index", "127.0.0.1", 19000 + index)
                }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(4, attempts.count { it.get(3, TimeUnit.SECONDS) })
            assertEquals(4, publisher.destinationCount)
        } finally {
            start.countDown()
            workers.shutdownNow()
            publisher.close()
        }
    }

    @Test
    fun `publisher sends RTP to an IPv6 hub endpoint`() {
        val loopback = InetAddress.getByName("::1")
        val receiver = DatagramSocket(null).apply {
            reuseAddress = true
            soTimeout = 2_000
            bind(InetSocketAddress(loopback, 0))
        }
        val publisher = DesktopUdpVideoPublisher(ByteArray(32) { 9 })
        try {
            publisher.start()
            assertTrue(publisher.subscribe("ipv6-hub", loopback.hostAddress, receiver.localPort))
            assertTrue(publisher.publish(accessUnit(isKeyFrame = true, sequence = 1)))

            val datagram = receive(receiver)
            val packet = RtpVideoPacketCodec.decode(datagram.data, datagram.length)
            assertTrue(packet?.isKeyFrame == true)
        } finally {
            publisher.close()
            receiver.close()
        }
    }

    @Test
    fun `unsubscribing one hub leaves other hub receiving video`() {
        val firstHub = DatagramSocket(null).apply {
            reuseAddress = true
            soTimeout = 2_000
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        }
        val secondHub = DatagramSocket(null).apply {
            reuseAddress = true
            soTimeout = 2_000
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        }
        val publisher = DesktopUdpVideoPublisher(ByteArray(32) { 7 })
        try {
            publisher.start()
            assertTrue(publisher.subscribe("hub-one", "127.0.0.1", firstHub.localPort))
            assertTrue(publisher.subscribe("hub-two", "127.0.0.1", secondHub.localPort))

            assertTrue(publisher.publish(accessUnit(isKeyFrame = true, sequence = 1)))
            receive(firstHub)
            receive(secondHub)

            publisher.unsubscribe("hub-one")
            assertTrue(publisher.publish(accessUnit(isKeyFrame = false, sequence = 2)))
            val secondHubPacket = receive(secondHub)
            assertTrue(RtpVideoPacketCodec.decode(secondHubPacket.data, secondHubPacket.length) != null)

            firstHub.soTimeout = 200
            val firstHubStillReceiving = try {
                receive(firstHub)
                true
            } catch (_: SocketTimeoutException) {
                false
            }
            assertTrue("unsubscribed hub received another datagram", !firstHubStillReceiving)
        } finally {
            publisher.close()
            firstHub.close()
            secondHub.close()
        }
    }

    @Test
    fun `publisher emits decryptable CamHub RTP video to subscribed endpoint`() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val receiver = DatagramSocket(null).apply {
            reuseAddress = true
            soTimeout = 3_000
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        }
        val publisher = DesktopUdpVideoPublisher(key)
        try {
            val keyFrameRequested = AtomicBoolean(false)
            publisher.onKeyFrameRequired = { keyFrameRequested.set(true) }
            publisher.start()
            assertTrue(publisher.subscribe("local-hub", "127.0.0.1", receiver.localPort))
            assertTrue(keyFrameRequested.get())

            val h264 = byteArrayOf(
                0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21,
                0xA0.toByte(), 0x10, 0x02, 0xFF.toByte()
            )
            assertTrue(
                publisher.publish(
                    DesktopScreenH264Encoder.EncodedAccessUnit(
                        bytes = h264,
                        width = 1280,
                        height = 720,
                        isKeyFrame = true,
                        captureAtWallMs = 1_700_000_000_000L,
                        encodedAtWallMs = 1_700_000_000_004L,
                        sequence = 1L
                    )
                )
            )

            val datagram = DatagramPacket(ByteArray(RtpVideoPacketCodec.MAX_DATAGRAM_BYTES),
                RtpVideoPacketCodec.MAX_DATAGRAM_BYTES)
            receiver.receive(datagram)
            val packet = RtpVideoPacketCodec.decode(datagram.data, datagram.length)
            assertNotNull(packet)
            assertTrue(packet!!.isMarker)
            assertTrue(packet.isKeyFrame)
            val clearPayload = FrameCipher(key).decrypt(packet.payload)
            val payload = CamHubVideoPayloadCodec.decode(clearPayload)
            assertNotNull(payload)
            assertEquals(1280, payload!!.width)
            assertEquals(720, payload.height)
            assertTrue(payload.isKeyFrame)
            assertEquals(1L, payload.frameSequence)
            assertEquals(1_700_000_000_000L, payload.captureAtWallMs)
            assertArrayEquals(h264, payload.h264Bytes)
        } finally {
            publisher.close()
            receiver.close()
        }
    }

    @Test
    fun `local datagram send failure requests a key frame and rejects dependent frames`() {
        val receiver = DatagramSocket(null).apply {
            reuseAddress = true
            soTimeout = 2_000
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        }
        val publisher = DesktopUdpVideoPublisher(ByteArray(32) { 5 })
        val recoveryRequested = CountDownLatch(1)
        try {
            publisher.start()
            assertTrue(publisher.subscribe("recovering-hub", "127.0.0.1", receiver.localPort))
            publisher.onKeyFrameRequired = { recoveryRequested.countDown() }
            publisher.datagramSender = { _, _ -> throw java.net.SocketException("simulated send failure") }

            assertTrue(publisher.publish(accessUnit(isKeyFrame = true, sequence = 1)))
            assertTrue("sender did not request a recovery key frame", recoveryRequested.await(2, TimeUnit.SECONDS))
            assertTrue("delta frame was accepted before recovery", !publisher.publish(accessUnit(isKeyFrame = false, sequence = 2)))

            publisher.datagramSender = { socket, packet -> socket.send(packet) }
            assertTrue(publisher.publish(accessUnit(isKeyFrame = true, sequence = 3)))
            val recovered = receive(receiver)
            assertTrue(RtpVideoPacketCodec.decode(recovered.data, recovered.length)?.isKeyFrame == true)
        } finally {
            publisher.close()
            receiver.close()
        }
    }

    @Test
    fun `changing a subscriber port waits for a fresh key frame`() {
        val publisher = DesktopUdpVideoPublisher(ByteArray(32) { 3 })
        try {
            publisher.start()
            assertTrue(publisher.subscribe("hub", "127.0.0.1", 19001))
            assertTrue(publisher.publish(accessUnit(true, 1)))
            assertTrue(publisher.subscribe("hub", "127.0.0.1", 19002))
            assertTrue(!publisher.publish(accessUnit(false, 2)))
            assertTrue(publisher.publish(accessUnit(true, 3)))
        } finally {
            publisher.close()
        }
    }

    @Test
    fun `new subscriber discards queued frames and waits for a fresh key frame`() {
        val publisher = DesktopUdpVideoPublisher(ByteArray(32) { 3 })
        val sending = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            publisher.start()
            assertTrue(publisher.subscribe("first", "127.0.0.1", 19001))
            publisher.datagramSender = { _, _ ->
                sending.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            assertTrue(publisher.publish(accessUnit(true, 1)))
            assertTrue(sending.await(2, TimeUnit.SECONDS))
            assertTrue(publisher.publish(accessUnit(true, 2)))
            assertTrue(publisher.publish(accessUnit(false, 3)))
            assertTrue(publisher.subscribe("second", "127.0.0.1", 19002))
            assertTrue("delta accepted before fresh key frame", !publisher.publish(accessUnit(false, 4)))
            assertEquals(2L, publisher.stats().droppedAccessUnits)
            assertTrue(publisher.publish(accessUnit(true, 5)))
        } finally {
            release.countDown()
            publisher.close()
        }
    }

    private fun accessUnit(isKeyFrame: Boolean, sequence: Long) =
        DesktopScreenH264Encoder.EncodedAccessUnit(
            bytes = byteArrayOf(0, 0, 0, 1, (if (isKeyFrame) 0x65 else 0x41).toByte(), 1, 2, 3),
            width = 1280,
            height = 720,
            isKeyFrame = isKeyFrame,
            captureAtWallMs = 1_700_000_000_000L + sequence,
            encodedAtWallMs = 1_700_000_000_004L + sequence,
            sequence = sequence
        )

    private fun receive(socket: DatagramSocket): DatagramPacket {
        val datagram = DatagramPacket(
            ByteArray(RtpVideoPacketCodec.MAX_DATAGRAM_BYTES),
            RtpVideoPacketCodec.MAX_DATAGRAM_BYTES
        )
        socket.receive(datagram)
        return datagram
    }
}
