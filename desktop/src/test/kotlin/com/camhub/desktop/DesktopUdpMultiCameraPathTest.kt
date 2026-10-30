package com.camhub.desktop

import com.camhub.studio.data.network.CamHubVideoPayload
import com.camhub.studio.data.network.CamHubVideoPayloadCodec
import com.camhub.studio.data.network.FrameCipher
import com.camhub.studio.data.network.RtpVideoPacketCodec
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.bytedeco.javacv.FFmpegFrameGrabber

class DesktopUdpMultiCameraPathTest {
    @Test
    fun `four encrypted UDP streams isolate reorder duplicate loss deadline and decoded output`() {
        fun source(seed: Long) = BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB).apply {
            // Block texture stays encodable at the configured bitrate while making the
            // keyframe large enough to exercise RTP fragmentation.
            val random = java.util.Random(seed)
            val colors = Array(80 * 45) {
                Color(random.nextInt(256), random.nextInt(256), random.nextInt(256)).rgb
            }
            for (y in 0 until height) for (x in 0 until width) {
                setRGB(x, y, colors[(y / 8) * 80 + x / 8])
            }
        }
        val encoder = DesktopScreenH264Encoder(640, 360, 30, 6_000_000)
        val encodedFrames = try {
            buildList {
                for (index in 0 until 4) {
                    encoder.requestKeyFrame()
                    add(requireNotNull(encoder.encode(
                        source(7L + index),
                        captureAtWallMs = 1_700_000_000_000L + index * 33L
                    )))
                }
            }
        } finally {
            encoder.close()
        }
        val encodedStream = encodedFrames.fold(ByteArray(0)) { bytes, frame -> bytes + frame.bytes }
        val directGrabber = FFmpegFrameGrabber(ByteArrayInputStream(encodedStream)).apply {
            format = "h264"
            setOption("probesize", "2048")
            setOption("analyzeduration", "0")
        }
        try {
            directGrabber.start()
            assertNotNull("encoded access units must decode from a finite stream", directGrabber.grabImage())
        } finally {
            runCatching { directGrabber.stop() }
            runCatching { directGrabber.release() }
        }
        val loopback = InetAddress.getByName("127.0.0.1")
        val keys = List(4) { camera -> ByteArray(32) { index -> (index + camera * 19).toByte() } }
        val senders = List(4) { DatagramSocket(0, loopback) }
        val delivered = Array(4) { AtomicReference<CamHubVideoPayload?>() }
        val streamFrames = encodedFrames
        val validPayloadsReceived = CountDownLatch((streamFrames.size + 1) * 2)
        val displayed = Array(2) { CountDownLatch(1) }
        val expired = Array(4) { CountDownLatch(if (it >= 2) 1 else 0) }
        val dropCounts = AtomicIntegerArray(4)
        val unexpectedFrameCount = AtomicIntegerArray(4)
        val images = Array(2) { AtomicReference<BufferedImage?>() }
        val failure = AtomicReference<Throwable?>()
        val decoders = (0..1).map { camera ->
            H264FrameDecoder(
                cameraName = "multi-path-$camera",
                onImage = { image, _ ->
                    images[camera].set(image)
                    displayed[camera].countDown()
                },
                onError = failure::set
            ).apply { start() }
        }
        val receivers = (0..3).map { camera ->
            DesktopUdpVideoReceiver(
                cameraName = "multi-path-$camera",
                expectedHost = loopback.hostAddress,
                expectedSourcePort = senders[camera].localPort,
                sessionKey = keys[camera],
                onFrame = { payload ->
                    delivered[camera].set(payload)
                    if (camera < 2) {
                        decoders[camera].submit(payload)
                        validPayloadsReceived.countDown()
                    }
                    else unexpectedFrameCount.incrementAndGet(camera)
                },
                onStats = {},
                onError = failure::set,
                onDroppedFrames = { count ->
                    dropCounts.addAndGet(camera, count)
                    expired[camera].countDown()
                }
            )
        }

        try {
            val ports = receivers.map(DesktopUdpVideoReceiver::start)
            val parameterSets = extractParameterSets(encodedFrames.first().bytes)
            assertTrue("encoder output must include SPS/PPS", parameterSets.isNotEmpty())
            val configPackets = (0..1).map { camera ->
                val config = CamHubVideoPayloadCodec.encode(
                    width = 640,
                    height = 360,
                    rotationDegrees = 0,
                    isKeyFrame = false,
                    isCodecConfig = true,
                    frameSequence = camera * 10L,
                    captureAtWallMs = 0L,
                    encodedAtWallMs = 0L,
                    h264Bytes = parameterSets
                )
                RtpVideoPacketCodec.packetize(
                    frame = FrameCipher(keys[camera]).encrypt(config),
                    transportFrameId = camera * 10L + 9,
                    rtpTimestamp = 90L * (camera * 10 + 9),
                    ssrc = camera.toLong() + 10,
                    firstRtpSequence = camera * 1000 + 900,
                    isKeyFrame = false,
                    isCodecConfig = true
                )
            }
            val packetSets = (0..3).map { camera ->
                streamFrames.mapIndexed { frameIndex, encoded ->
                    val envelope = CamHubVideoPayloadCodec.encode(
                        width = encoded.width,
                        height = encoded.height,
                        rotationDegrees = 0,
                        isKeyFrame = encoded.isKeyFrame,
                        isCodecConfig = false,
                        frameSequence = camera * 10L + frameIndex + 1L,
                        captureAtWallMs = 1_700_000_000_000L + camera * 100L + frameIndex * 33L,
                        encodedAtWallMs = 1_700_000_000_010L + camera * 100L + frameIndex * 33L,
                        h264Bytes = encoded.bytes
                    )
                    val encrypted = FrameCipher(keys[camera]).encrypt(envelope)
                    RtpVideoPacketCodec.packetize(
                        frame = encrypted,
                        transportFrameId = camera * 10L + frameIndex + 1L,
                        rtpTimestamp = 90L * (camera * 10 + frameIndex + 1),
                        ssrc = camera.toLong() + 10,
                        firstRtpSequence = camera * 1000 + frameIndex * 100,
                        isKeyFrame = encoded.isKeyFrame
                    )
                }
            }
            assertTrue("test keyframe must exercise fragmentation", packetSets.all { it.first().size >= 3 })

            // Camera 0 receives reverse order and a duplicate; camera 1 receives ordinary order.
            configPackets[0].forEach { senders[0].send(DatagramPacket(it, it.size, loopback, ports[0])) }
            val reversed = packetSets[0][0].reversed()
            senders[0].send(DatagramPacket(reversed.first(), reversed.first().size, loopback, ports[0]))
            senders[0].send(DatagramPacket(reversed.first(), reversed.first().size, loopback, ports[0]))
            reversed.drop(1).forEach { senders[0].send(DatagramPacket(it, it.size, loopback, ports[0])) }
            packetSets[0].drop(1).dropLast(1).forEach { framePackets ->
                framePackets.forEach { senders[0].send(DatagramPacket(it, it.size, loopback, ports[0])) }
                Thread.sleep(50)
            }
            configPackets[1].forEach { senders[1].send(DatagramPacket(it, it.size, loopback, ports[1])) }
            packetSets[1].dropLast(1).forEach { framePackets ->
                framePackets.forEach { senders[1].send(DatagramPacket(it, it.size, loopback, ports[1])) }
                Thread.sleep(50)
            }
            // Give each live decoder time to finish opening its input before a
            // fresh recovery keyframe arrives, as it would after startup latency.
            Thread.sleep(250)
            listOf(0, 1).forEach { camera ->
                packetSets[camera].last().forEach {
                    senders[camera].send(DatagramPacket(it, it.size, loopback, ports[camera]))
                }
            }

            // Camera 2 loses the remaining fragments. Camera 3's rest arrives only after expiry.
            val missingTail = packetSets[2][0].first()
            senders[2].send(DatagramPacket(missingTail, missingTail.size, loopback, ports[2]))
            val lateTail = packetSets[3][0].first()
            senders[3].send(DatagramPacket(lateTail, lateTail.size, loopback, ports[3]))
            assertTrue("incomplete frames did not expire", expired[2].await(2, TimeUnit.SECONDS))
            assertTrue("late-frame deadline did not expire", expired[3].await(2, TimeUnit.SECONDS))
            packetSets[3][0].drop(1).forEach { senders[3].send(DatagramPacket(it, it.size, loopback, ports[3])) }

            assertTrue("valid camera payloads did not arrive", validPayloadsReceived.await(5, TimeUnit.SECONDS))
            assertTrue("camera 0 did not deliver a decodable frame", displayed[0].await(5, TimeUnit.SECONDS))
            assertTrue("camera 1 did not deliver a decodable frame", displayed[1].await(5, TimeUnit.SECONDS))
            assertNull("decoder failed", failure.get())

            (0..1).forEach { camera ->
                val payload = requireNotNull(delivered[camera].get())
                assertEquals(640, payload.width)
                assertEquals(360, payload.height)
                assertEquals(camera * 10L + streamFrames.size.toLong(), payload.frameSequence)
                assertArrayEquals(streamFrames.last().bytes, payload.h264Bytes)
                assertEquals(640, requireNotNull(images[camera].get()).width)
                assertEquals(360, requireNotNull(images[camera].get()).height)
            }
            (2..3).forEach { camera ->
                assertNull("damaged camera $camera frame escaped reassembly", delivered[camera].get())
                assertEquals(0, unexpectedFrameCount.get(camera))
                assertEquals(1, dropCounts.get(camera))
            }
            assertEquals(0, dropCounts.get(0))
            assertEquals(0, dropCounts.get(1))
        } finally {
            receivers.forEach(DesktopUdpVideoReceiver::close)
            decoders.forEach(H264FrameDecoder::close)
            senders.forEach(DatagramSocket::close)
        }
    }

    private fun extractParameterSets(accessUnit: ByteArray): ByteArray {
        val starts = mutableListOf<Pair<Int, Int>>()
        var index = 0
        while (index + 3 < accessUnit.size) {
            val prefixSize = when {
                accessUnit[index] == 0.toByte() && accessUnit[index + 1] == 0.toByte() &&
                    accessUnit[index + 2] == 1.toByte() -> 3
                accessUnit[index] == 0.toByte() && accessUnit[index + 1] == 0.toByte() &&
                    accessUnit[index + 2] == 0.toByte() && accessUnit[index + 3] == 1.toByte() -> 4
                else -> {
                    index++
                    continue
                }
            }
            starts += index to prefixSize
            index += prefixSize
        }
        return starts.mapIndexedNotNull { position, (start, prefixSize) ->
            val end = starts.getOrNull(position + 1)?.first ?: accessUnit.size
            val nalIndex = start + prefixSize
            if (nalIndex < end && (accessUnit[nalIndex].toInt() and 0x1f) in setOf(7, 8)) {
                accessUnit.copyOfRange(start, end)
            } else null
        }.fold(ByteArray(0)) { result, bytes -> result + bytes }
    }
}
