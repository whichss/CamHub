package com.camhub.studio.data.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubPerformanceGovernorTest {
    @Test
    fun `single latency spike does not downgrade profile`() {
        val governor = HubPerformanceGovernor(highProfile())

        val result = governor.evaluate(sample(timestampMs = 0, latencyMs = 300))

        assertEquals(HubPerformanceTier.HIGH, result.recommendedProfile.tier)
        assertEquals(RuntimePressure.SEVERE, result.pressure)
        assertFalse(result.isDegraded)
    }

    @Test
    fun `sustained severe pressure downgrades one tier`() {
        val governor = HubPerformanceGovernor(highProfile())

        governor.evaluate(sample(timestampMs = 0, latencyMs = 300))
        val result = governor.evaluate(sample(timestampMs = 3_000, latencyMs = 300))

        assertEquals(HubPerformanceTier.BALANCED, result.recommendedProfile.tier)
        assertTrue(result.isDegraded)
        assertFalse(result.recommendedProfile.enableSpatialUpscaling)
    }

    @Test
    fun `severe thermal state immediately recommends constrained profile`() {
        val governor = HubPerformanceGovernor(highProfile())

        val result = governor.evaluate(sample(timestampMs = 0, thermalStatus = 3))

        assertEquals(HubPerformanceTier.CONSTRAINED, result.recommendedProfile.tier)
        assertEquals(RuntimePressure.CRITICAL, result.pressure)
        assertEquals(2, result.recommendedProfile.recommendedCameraCount)
    }

    @Test
    fun `recovery waits for stable window and advances one tier at a time`() {
        val governor = HubPerformanceGovernor(highProfile(), stableRecoveryMs = 30_000)
        governor.evaluate(sample(timestampMs = 0, thermalStatus = 3))

        governor.evaluate(sample(timestampMs = 3_000))
        val beforeRecovery = governor.evaluate(sample(timestampMs = 32_999))
        val firstRecovery = governor.evaluate(sample(timestampMs = 33_000))
        val secondRecovery = governor.evaluate(sample(timestampMs = 63_000))

        assertEquals(HubPerformanceTier.CONSTRAINED, beforeRecovery.recommendedProfile.tier)
        assertEquals(HubPerformanceTier.BALANCED, firstRecovery.recommendedProfile.tier)
        assertEquals(HubPerformanceTier.HIGH, secondRecovery.recommendedProfile.tier)
    }

    @Test
    fun `governor never promotes beyond device base capability`() {
        val governor = HubPerformanceGovernor(constrainedProfile(), stableRecoveryMs = 1_000)

        governor.evaluate(sample(timestampMs = 0))
        val result = governor.evaluate(sample(timestampMs = 10_000))

        assertEquals(HubPerformanceTier.CONSTRAINED, result.recommendedProfile.tier)
        assertFalse(result.isDegraded)
    }

    private fun sample(
        timestampMs: Long,
        latencyMs: Int = 50,
        droppedFrames: Int = 0,
        fps: Int = 30,
        thermalStatus: Int = 0
    ) = HubPerformanceSample(
        timestampMs = timestampMs,
        maxP95LatencyMs = latencyMs,
        droppedFrameDelta = droppedFrames,
        minActualFps = fps,
        targetFps = 30,
        thermalStatus = thermalStatus,
        connectedCameraCount = 4
    )

    private fun highProfile() = HubRuntimeProfile(
        tier = HubPerformanceTier.HIGH,
        recommendedCameraCount = 4,
        multiviewHeight = 720,
        multiviewFps = 30,
        pgmHeight = 1080,
        pgmFps = 30,
        enableSpatialUpscaling = true
    )

    private fun constrainedProfile() = HubRuntimeProfile(
        tier = HubPerformanceTier.CONSTRAINED,
        recommendedCameraCount = 2,
        multiviewHeight = 540,
        multiviewFps = 15,
        pgmHeight = 720,
        pgmFps = 30,
        enableSpatialUpscaling = false
    )
}
