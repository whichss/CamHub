package com.camhub.studio.data.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RollingLatencyTrackerTest {
    @Test
    fun snapshotUsesNearestRankPercentiles() {
        val tracker = RollingLatencyTracker(capacity = 100)

        for (sample in 1..100) {
            tracker.record(sample)
        }

        assertEquals(
            LatencyPercentiles(
                sampleCount = 100,
                latestMs = 100,
                p50Ms = 50,
                p95Ms = 95,
                p99Ms = 99,
                minMs = 1,
                maxMs = 100
            ),
            tracker.snapshot()
        )
    }

    @Test
    fun recordKeepsOnlyNewestSamplesWhenWindowIsFull() {
        val tracker = RollingLatencyTracker(capacity = 3)

        tracker.record(10)
        tracker.record(20)
        tracker.record(30)
        tracker.record(40)

        assertEquals(
            LatencyPercentiles(
                sampleCount = 3,
                latestMs = 40,
                p50Ms = 30,
                p95Ms = 40,
                p99Ms = 40,
                minMs = 20,
                maxMs = 40
            ),
            tracker.snapshot()
        )
    }

    @Test
    fun clearRemovesAllSamples() {
        val tracker = RollingLatencyTracker()
        tracker.record(25)

        tracker.clear()

        assertEquals(LatencyPercentiles(), tracker.snapshot())
    }

    @Test
    fun invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RollingLatencyTracker(capacity = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RollingLatencyTracker().record(-1)
        }
    }
}
