package com.camhub.studio.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PgmFrameSourceArbiterTest {
    @Test
    fun `direct gpu output suppresses bitmap round trips`() {
        val arbiter = PgmFrameSourceArbiter(gpuQuietWindowMs = 500L)

        arbiter.noteGpuAccepted("camera-a", nowMs = 1_000L)

        assertFalse(arbiter.allowBitmapFallback("camera-a", nowMs = 1_250L))
        assertTrue(arbiter.allowBitmapFallback("camera-a", nowMs = 1_500L))
    }

    @Test
    fun `bitmap fallback remains available when gpu path is absent or changes camera`() {
        val arbiter = PgmFrameSourceArbiter(gpuQuietWindowMs = 500L)
        arbiter.noteGpuAccepted("camera-a", nowMs = 1_000L)

        assertTrue(arbiter.allowBitmapFallback("camera-b", nowMs = 1_100L))
        assertTrue(arbiter.allowBitmapFallback("camera-a", nowMs = 1_501L))
    }

    @Test
    fun `reset restores bitmap fallback`() {
        val arbiter = PgmFrameSourceArbiter()
        arbiter.noteGpuAccepted("camera-a", nowMs = 1_000L)
        arbiter.reset()

        assertTrue(arbiter.allowBitmapFallback("camera-a", nowMs = 1_001L))
    }
}
