package com.camhub.studio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingClockTest {
    private val clock = RecordingClock()

    @Test
    fun `video and audio share one presentation timeline`() {
        clock.start(1_000_000_000L)

        assertEquals(250_000_000L, clock.presentationTimeNs(1_250_000_000L))
        assertEquals(500_000L, clock.presentationTimeNs(1_500_000_000L)!! / 1_000L)
    }

    @Test
    fun `pause duration is removed from timestamps after resume`() {
        clock.start(2_000_000_000L)
        assertEquals(1_000_000_000L, clock.presentationTimeNs(3_000_000_000L))
        assertTrue(clock.pause(3_000_000_000L))
        assertEquals(1_000_000_000L, clock.presentationTimeNs(4_000_000_000L))
        assertEquals(2_000L, clock.resume(5_000_000_000L))
        assertEquals(1_000_000_000L, clock.presentationTimeNs(5_000_000_000L))
        assertEquals(1_500_000_000L, clock.presentationTimeNs(5_500_000_000L))
    }

    @Test
    fun `duplicate pause and resume operations do not change timeline`() {
        clock.start(10L)
        assertTrue(clock.pause(20L))
        assertFalse(clock.pause(30L))
        assertNull(clock.resume(19L))
        assertEquals(100L, clock.resume(100_000_020L))
        assertNull(clock.resume(200_000_020L))
    }

    @Test
    fun `reset disables timestamps until next session starts`() {
        clock.start(10L)
        assertEquals(10L, clock.presentationTimeNs(20L))
        clock.reset()
        assertNull(clock.presentationTimeNs(30L))
    }
}
