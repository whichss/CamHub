package com.camhub.studio.data.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HubProfileApplicationPolicyTest {
    @Test
    fun `high profile uses 720p30 camera inputs`() {
        val limits = HubProfileApplicationPolicy.limitsFor(
            profile = profile(HubPerformanceTier.HIGH, 720, 30, 4, true),
            configuredMaxResolution = 1080,
            configuredFps = 30
        )

        assertEquals(720, limits.streamHeight)
        assertEquals(30, limits.streamFps)
        assertEquals(4, limits.cameraLimit)
    }

    @Test
    fun `constrained profile lowers common input without enabling upscaling`() {
        val limits = HubProfileApplicationPolicy.limitsFor(
            profile = profile(HubPerformanceTier.CONSTRAINED, 540, 15, 2, false),
            configuredMaxResolution = 1080,
            configuredFps = 30
        )

        assertEquals(540, limits.streamHeight)
        assertEquals(15, limits.streamFps)
        assertEquals(2, limits.cameraLimit)
        assertFalse(limits.enableSpatialUpscaling)
    }

    @Test
    fun `automatic limits never exceed user configured ceilings`() {
        val limits = HubProfileApplicationPolicy.limitsFor(
            profile = profile(HubPerformanceTier.HIGH, 720, 30, 4, true),
            configuredMaxResolution = 480,
            configuredFps = 24
        )

        assertEquals(480, limits.streamHeight)
        assertEquals(24, limits.streamFps)
    }

    private fun profile(
        tier: HubPerformanceTier,
        height: Int,
        fps: Int,
        cameras: Int,
        upscaling: Boolean
    ) = HubRuntimeProfile(
        tier = tier,
        recommendedCameraCount = cameras,
        multiviewHeight = height,
        multiviewFps = fps,
        pgmHeight = if (tier == HubPerformanceTier.HIGH) 1080 else 720,
        pgmFps = 30,
        enableSpatialUpscaling = upscaling
    )
}
