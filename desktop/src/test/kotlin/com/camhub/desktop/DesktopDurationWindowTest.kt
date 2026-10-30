package com.camhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopDurationWindowTest {
    @Test
    fun `duration percentiles use microseconds and keep only recent samples`() {
        val window = DesktopDurationWindow(capacity = 4)
        listOf(1_000L, 2_000L, 3_000L, 10_000L, 20_000L).forEach(window::recordNanos)

        val snapshot = window.snapshot()
        assertEquals(4, snapshot.sampleCount)
        assertEquals(20L, snapshot.latestMicros)
        assertEquals(3L, snapshot.p50Micros)
        assertEquals(20L, snapshot.p95Micros)
        assertEquals(20L, snapshot.p99Micros)
    }
}
