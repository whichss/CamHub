package com.camhub.studio.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveBitrateControllerTest {

    @Test
    fun `severe UDP loss reduces bitrate multiplicatively`() {
        val controller = AdaptiveBitrateController(ceilingMbps = 6)
        val decision = controller.evaluate(
            AdaptiveBitrateSample(
                nowMs = 1_000,
                recentPacketLossPercent = 6f,
                actualFps = 30
            )
        )
        assertEquals(3, decision?.targetMbps)
        assertEquals(BitratePressure.SEVERE, decision?.pressure)
    }

    @Test
    fun `moderate pressure steps down once per cooldown`() {
        val controller = AdaptiveBitrateController(ceilingMbps = 6)
        assertEquals(
            5,
            controller.evaluate(
                AdaptiveBitrateSample(nowMs = 1_000, recentPacketLossPercent = 1.5f)
            )?.targetMbps
        )
        assertNull(
            controller.evaluate(
                AdaptiveBitrateSample(nowMs = 2_000, recentPacketLossPercent = 1.5f)
            )
        )
        assertEquals(
            4,
            controller.evaluate(
                AdaptiveBitrateSample(nowMs = 3_000, recentPacketLossPercent = 1.5f)
            )?.targetMbps
        )
    }

    @Test
    fun `stable stream recovers slowly one megabit at a time`() {
        val controller = AdaptiveBitrateController(ceilingMbps = 6)
        controller.evaluate(
            AdaptiveBitrateSample(nowMs = 1_000, recentPacketLossPercent = 6f)
        )
        val healthy = AdaptiveBitrateSample(
            nowMs = 3_000,
            latencyP95Ms = 50,
            latencyP99Ms = 80,
            hasLatencySample = true,
            recentPacketLossPercent = 0f,
            actualFps = 30
        )
        assertNull(controller.evaluate(healthy))
        assertNull(controller.evaluate(healthy.copy(nowMs = 14_999)))
        assertEquals(4, controller.evaluate(healthy.copy(nowMs = 15_000))?.targetMbps)
    }

    @Test
    fun `lowered user ceiling is applied immediately`() {
        val controller = AdaptiveBitrateController(ceilingMbps = 8)
        val decision = controller.updateCeiling(newCeilingMbps = 5, nowMs = 100)
        assertEquals(5, decision?.targetMbps)
        assertEquals(BitratePressure.CEILING, decision?.pressure)
    }
}
