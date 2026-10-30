package com.camhub.studio.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveBitrateControllerTest {
    @Test
    fun `sporadic high delay never counts as healthy recovery evidence`() {
        val controller = AdaptiveBitrateController(ceilingMbps = 6)
        controller.evaluate(AdaptiveBitrateSample(nowMs = 1_000, recentPacketLossPercent = 6f))
        for (index in 0 until 8) {
            val decision = controller.evaluate(AdaptiveBitrateSample(
                nowMs = 4_000L + index * 3_000L,
                encodeToReceiveMs = 150,
                networkFrameSequence = index.toLong() + 1
            ))
            assertNull(decision)
        }
        assertEquals(3, controller.currentTargetMbps)
    }
    @Test
    fun `local decode backlog alone does not masquerade as network congestion`() {
        val controller = AdaptiveBitrateController(ceilingMbps = 6)
        val decision = controller.evaluate(AdaptiveBitrateSample(
            nowMs = 1_000, latencyP95Ms = 250, latencyP99Ms = 300,
            hasLatencySample = true, encodeToReceiveMs = 20, receiveToReadyMs = 220,
            recentPacketLossPercent = 0f, droppedFrameDelta = 12, actualFps = 10
        ))
        assertNull(decision)
        assertEquals(6, controller.currentTargetMbps)
    }

    @Test
    fun `delay must persist across distinct frames before bitrate reduction`() {
        val controller = AdaptiveBitrateController(ceilingMbps = 6)
        val sample = AdaptiveBitrateSample(nowMs = 1_000, encodeToReceiveMs = 200,
            receiveToReadyMs = 5, networkFrameSequence = 1)
        assertNull(controller.evaluate(sample))
        assertNull(controller.evaluate(sample.copy(nowMs = 2_000))) // stale frame
        assertNull(controller.evaluate(sample.copy(nowMs = 3_000, networkFrameSequence = 2)))
        val decision = controller.evaluate(sample.copy(nowMs = 4_000, networkFrameSequence = 3))
        assertEquals(3, decision?.targetMbps)
        assertEquals("payload-ready→frame-received 200ms", decision?.reason)
    }

    @Test
    fun `missing network evidence cannot trigger recovery`() {
        val controller = AdaptiveBitrateController(ceilingMbps = 6)
        controller.evaluate(AdaptiveBitrateSample(nowMs = 1_000, recentPacketLossPercent = 6f))
        assertNull(controller.evaluate(AdaptiveBitrateSample(nowMs = 3_000)))
        assertNull(controller.evaluate(AdaptiveBitrateSample(nowMs = 30_000)))
        assertEquals(3, controller.currentTargetMbps)
    }

    @Test
    fun `stale confirmed delay cannot trigger repeated reductions`() {
        val controller = AdaptiveBitrateController(ceilingMbps = 8)
        val sample = AdaptiveBitrateSample(nowMs = 1_000, encodeToReceiveMs = 200,
            networkFrameSequence = 1)
        controller.evaluate(sample)
        controller.evaluate(sample.copy(nowMs = 2_000, networkFrameSequence = 2))
        assertEquals(5, controller.evaluate(sample.copy(nowMs = 3_000, networkFrameSequence = 3))?.targetMbps)
        assertNull(controller.evaluate(sample.copy(nowMs = 6_000, networkFrameSequence = 3)))
        assertEquals(5, controller.currentTargetMbps)
    }

    @Test
    fun `delay confirmations reset when samples are separated by a long gap`() {
        val controller = AdaptiveBitrateController(ceilingMbps = 8)
        val sample = AdaptiveBitrateSample(nowMs = 1_000, encodeToReceiveMs = 200,
            networkFrameSequence = 1)
        controller.evaluate(sample)
        controller.evaluate(sample.copy(nowMs = 2_000, networkFrameSequence = 2))
        assertNull(controller.evaluate(sample.copy(nowMs = 6_000, networkFrameSequence = 3)))
        assertEquals(8, controller.currentTargetMbps)
    }

    @Test
    fun `recovery waits for its shared network probe slot`() {
        val controller = AdaptiveBitrateController(ceilingMbps = 6)
        controller.evaluate(AdaptiveBitrateSample(nowMs = 1_000, recentPacketLossPercent = 6f))
        val sample = AdaptiveBitrateSample(nowMs = 3_000, recentPacketLossPercent = 0f,
            allowRecovery = false)
        assertNull(controller.evaluate(sample))
        assertNull(controller.evaluate(sample.copy(nowMs = 15_000)))
        assertEquals(4, controller.evaluate(sample.copy(nowMs = 16_000, allowRecovery = true))?.targetMbps)
    }

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
    fun `submillisecond latency rounded to zero remains healthy for bitrate recovery`() {
        val controller = AdaptiveBitrateController(ceilingMbps = 6)
        controller.evaluate(
            AdaptiveBitrateSample(nowMs = 1_000, recentPacketLossPercent = 6f)
        )
        val healthy = AdaptiveBitrateSample(
            nowMs = 3_000,
            latencyP95Ms = 0,
            latencyP99Ms = 0,
            hasLatencySample = true,
            recentPacketLossPercent = 0f,
            actualFps = 30
        )

        assertNull(controller.evaluate(healthy))
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
