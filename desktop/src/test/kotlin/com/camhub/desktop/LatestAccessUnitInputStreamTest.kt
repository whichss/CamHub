package com.camhub.desktop

import com.camhub.studio.data.network.CamHubVideoPayload
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestAccessUnitInputStreamTest {
    @Test
    fun `decoder output expires only after the 100ms freshness budget`() {
        assertFalse(DesktopDecodeFrameBudget.isExpired(0L))
        assertFalse(DesktopDecodeFrameBudget.isExpired(100L))
        assertTrue(DesktopDecodeFrameBudget.isExpired(101L))
    }

    @Test
    fun `decoder startup waits for key frame and is released by close`() {
        val input = LatestAccessUnitInputStream()
        val waiting = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        val worker = Thread {
            waiting.countDown()
            result.set(input.awaitInitialKeyFrame())
        }.apply {
            isDaemon = true
            start()
        }

        assertTrue(waiting.await(1, TimeUnit.SECONDS))
        worker.join(100)
        assertTrue("decoder should not initialize FFmpeg without a key frame", worker.isAlive)

        input.close()
        worker.join(1_000)
        assertFalse("closing the stream should release the startup wait", worker.isAlive)
        assertFalse(requireNotNull(result.get()))
    }

    @Test
    fun `queued key frame releases decoder startup`() {
        val input = LatestAccessUnitInputStream()
        val waiting = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        val worker = Thread {
            waiting.countDown()
            result.set(input.awaitInitialKeyFrame())
        }.apply {
            isDaemon = true
            start()
        }

        assertTrue(waiting.await(1, TimeUnit.SECONDS))
        input.submit(frame(sequence = 1, capturedAt = 1, keyFrame = true))
        worker.join(1_000)
        assertFalse("key frame should release the startup wait", worker.isAlive)
        assertTrue(requireNotNull(result.get()))
        input.close()
    }

    @Test
    fun `queue overflow discards delta backlog and resumes at key frame`() {
        val now = 10_000L
        val input = LatestAccessUnitInputStream { now }

        input.submit(frame(sequence = 1, capturedAt = now))
        input.submit(frame(sequence = 2, capturedAt = now))
        input.submit(frame(sequence = 3, capturedAt = now))
        input.submit(frame(sequence = 4, capturedAt = now))
        input.submit(frame(sequence = 5, capturedAt = now, keyFrame = true))

        val bytes = ByteArray(1)
        assertEquals(1, input.read(bytes))
        assertArrayEquals(byteArrayOf(5), bytes)
        assertEquals(5L, input.takeOutputMetadata()?.payload?.frameSequence)
        assertEquals(2L, input.droppedFrameCount)
    }

    @Test
    fun `decoder residence time uses local monotonic clock`() {
        var now = 20_000L
        val input = LatestAccessUnitInputStream { now }
        input.submit(frame(sequence = 1, capturedAt = 1L, keyFrame = true))

        val bytes = ByteArray(1)
        assertEquals(1, input.read(bytes))
        now += 250L
        val metadata = input.takeOutputMetadata()
        assertEquals(1L, metadata?.payload?.frameSequence)
        assertEquals(250L, metadata?.decoderResidenceMs)
    }

    @Test
    fun `network discontinuity skips deltas until the next key frame`() {
        val now = 30_000L
        val input = LatestAccessUnitInputStream { now }
        input.submit(frame(sequence = 1, capturedAt = now, keyFrame = true))
        input.recoverFromDiscontinuity()
        input.submit(frame(sequence = 2, capturedAt = now))
        input.submit(frame(sequence = 3, capturedAt = now, keyFrame = true))

        val bytes = ByteArray(1)
        assertEquals(1, input.read(bytes))
        assertArrayEquals(byteArrayOf(3), bytes)
        assertEquals(3L, input.takeOutputMetadata()?.payload?.frameSequence)
    }

    private fun frame(
        sequence: Long,
        capturedAt: Long,
        keyFrame: Boolean = false
    ) = CamHubVideoPayload(
        width = 1280,
        height = 720,
        rotationDegrees = 0,
        isKeyFrame = keyFrame,
        isCodecConfig = false,
        frameSequence = sequence,
        captureAtWallMs = capturedAt,
        encodedAtWallMs = capturedAt,
        h264Bytes = byteArrayOf(sequence.toByte())
    )
}
