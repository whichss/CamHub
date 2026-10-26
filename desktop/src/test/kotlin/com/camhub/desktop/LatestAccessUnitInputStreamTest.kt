package com.camhub.desktop

import com.camhub.studio.data.network.CamHubVideoPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LatestAccessUnitInputStreamTest {
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
