package com.camhub.studio.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpIngressBurstTrackerTest {
    @Test
    fun `aggregates synchronized multi-camera bursts into shared time buckets`() {
        val tracker = UdpIngressBurstTracker()
        repeat(4) { camera ->
            repeat(4) {
                tracker.record("camera-$camera", datagramBytes = 1_200, elapsedMs = 1_000)
            }
        }

        val metrics = tracker.snapshot(nowElapsedMs = 1_000)

        assertEquals(4, metrics.activeCameraCount)
        assertEquals(16L, metrics.packetsPerSecond)
        assertEquals(19_200L, metrics.bytesPerSecond)
        assertEquals(19_200L, metrics.maxBytesPer5Ms)
        assertEquals(16L, metrics.maxPacketsPer5Ms)
        assertEquals(19_200L, metrics.maxBytesPer20Ms)
    }

    @Test
    fun `separates a synchronized burst from evenly spread camera arrivals`() {
        val synchronized = UdpIngressBurstTracker()
        val spread = UdpIngressBurstTracker()
        repeat(4) { camera ->
            repeat(4) { packet ->
                synchronized.record("camera-$camera", 1_200, 1_000)
                spread.record("camera-$camera", 1_200, 1_000 + packet * 5L)
            }
        }

        val synchronizedMetrics = synchronized.snapshot(nowElapsedMs = 1_020)
        val spreadMetrics = spread.snapshot(nowElapsedMs = 1_020)

        assertTrue(synchronizedMetrics.maxBytesPer5Ms > spreadMetrics.maxBytesPer5Ms)
        assertEquals(4_800L, spreadMetrics.maxBytesPer5Ms)
        assertEquals(19_200L, synchronizedMetrics.maxBytesPer20Ms)
        assertEquals(19_200L, spreadMetrics.maxBytesPer20Ms)
    }

    @Test
    fun `removes disconnected camera buckets from the aggregate`() {
        val tracker = UdpIngressBurstTracker()
        tracker.record("camera-a", 1_200, 1_000)
        tracker.record("camera-b", 1_200, 1_000)
        tracker.remove("camera-b")

        val metrics = tracker.snapshot(nowElapsedMs = 1_000)

        assertEquals(1, metrics.activeCameraCount)
        assertEquals(1_200L, metrics.bytesPerSecond)
    }
}
