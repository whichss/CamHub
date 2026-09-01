package com.camhub.studio.data.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockOffsetTrackerTest {
    @Test
    fun estimateCalculatesRemoteClockOffsetAndNetworkRoundTrip() {
        // Remote clock is 100ms ahead. Network takes 10ms each way and the
        // remote endpoint spends 2ms preparing the pong.
        val estimate = ClockOffsetTracker.estimate(
            t0 = 1_000,
            t1 = 1_110,
            t2 = 1_112,
            t3 = 1_022
        )

        assertEquals(100L, estimate?.remoteClockOffsetMs)
        assertEquals(20L, estimate?.roundTripMs)
    }

    @Test
    fun trackerRetainsOffsetFromLowestRoundTripSample() {
        val tracker = ClockOffsetTracker()

        val slow = tracker.record(t0 = 1_000, t1 = 1_130, t2 = 1_132, t3 = 1_062)
        val fast = tracker.record(t0 = 2_000, t1 = 2_110, t2 = 2_112, t3 = 2_022)
        val slowerAgain = tracker.record(t0 = 3_000, t1 = 3_120, t2 = 3_122, t3 = 3_042)

        assertTrue(slow.isSynchronized)
        assertEquals(100L, fast.remoteClockOffsetMs)
        assertEquals(20L, fast.bestRoundTripMs)
        assertEquals(100L, slowerAgain.remoteClockOffsetMs)
        assertEquals(20L, slowerAgain.bestRoundTripMs)
        assertEquals(3, slowerAgain.sampleCount)
    }

    @Test
    fun invalidOrExcessiveRoundTripsAreIgnored() {
        assertNull(ClockOffsetTracker.estimate(t0 = 0, t1 = 10, t2 = 11, t3 = 20))
        assertNull(
            ClockOffsetTracker.estimate(
                t0 = 1_000,
                t1 = 7_000,
                t2 = 7_001,
                t3 = 7_001,
                maxAcceptedRoundTripMs = 5_000
            )
        )

        val state = ClockOffsetTracker().snapshot()
        assertFalse(state.isSynchronized)
    }
}
