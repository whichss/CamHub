package com.camhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopCaptureLatencyWindowTest {
    @Test
    fun `percentiles use nearest rank and retain only the bounded recent window`() {
        val window = DesktopCaptureLatencyWindow(capacity = 5)
        listOf(10L, 20L, 30L, 40L, 100L).forEach(window::record)

        val first = window.snapshot()
        assertEquals(5, first.sampleCount)
        assertEquals(100L, first.latestMs)
        assertEquals(30L, first.p50Ms)
        assertEquals(100L, first.p95Ms)
        assertEquals(100L, first.p99Ms)

        window.record(200L)
        val bounded = window.snapshot()
        assertEquals(5, bounded.sampleCount)
        assertEquals(40L, bounded.p50Ms)
        assertEquals(200L, bounded.p95Ms)
        assertEquals(200L, bounded.p99Ms)

        window.clear()
        assertEquals(0, window.snapshot().sampleCount)
        assertNull(window.snapshot().latestMs)
    }
}
