package com.camhub.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStallWatchdogTest {
    @Test
    fun `frame resets the stall deadline`() {
        var now = 100L
        val watchdog = VideoStallWatchdog(timeoutNanos = 8, nowNanos = { now })
        now = 108
        assertFalse(watchdog.isStalled())
        watchdog.onDecodedFrame()
        now = 116
        assertFalse(watchdog.isStalled())
        now = 117
        assertTrue(watchdog.isStalled())
        watchdog.onDecodedFrame()
        assertFalse(watchdog.isStalled())
    }
}
