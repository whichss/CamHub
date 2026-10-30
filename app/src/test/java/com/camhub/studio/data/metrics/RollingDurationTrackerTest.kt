package com.camhub.studio.data.metrics

import org.junit.Assert.assertEquals
import org.junit.Test

class RollingDurationTrackerTest {
    @Test
    fun `retains microsecond resolution for submillisecond durations`() {
        val tracker = RollingDurationTracker(capacity = 5)
        listOf(90_000L, 210_000L, 450_000L, 1_200_000L, 2_400_000L)
            .forEach(tracker::recordNanos)

        val snapshot = tracker.snapshot()
        assertEquals(5, snapshot.sampleCount)
        assertEquals(2_400L, snapshot.latestUs)
        assertEquals(450L, snapshot.p50Us)
        assertEquals(2_400L, snapshot.p95Us)
        assertEquals(2_400L, snapshot.p99Us)
        assertEquals(90L, snapshot.minUs)
        assertEquals(2_400L, snapshot.maxUs)
    }

    @Test
    fun `keeps only the latest bounded duration samples`() {
        val tracker = RollingDurationTracker(capacity = 3)
        listOf(100_000L, 200_000L, 300_000L, 400_000L).forEach(tracker::recordNanos)

        val snapshot = tracker.snapshot()
        assertEquals(3, snapshot.sampleCount)
        assertEquals(300L, snapshot.p50Us)
        assertEquals(400L, snapshot.maxUs)
    }
}
