package com.camhub.studio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderLifecycleTest {
    @Test
    fun `cannot restart until stopping session is finalized`() {
        val lifecycle = RecorderLifecycle()

        assertTrue(lifecycle.beginStart())
        assertTrue(lifecycle.completeStart())
        assertEquals(RecorderLifecycle.StopRequest.STOP_RECORDING, lifecycle.requestStop())
        assertFalse(lifecycle.beginStart())

        lifecycle.completeStop()
        assertTrue(lifecycle.beginStart())
    }

    @Test
    fun `stop during startup cancels start and allows recovery`() {
        val lifecycle = RecorderLifecycle()

        assertTrue(lifecycle.beginStart())
        assertEquals(RecorderLifecycle.StopRequest.CANCEL_START, lifecycle.requestStop())
        assertFalse(lifecycle.completeStart())

        lifecycle.abortStart()
        assertTrue(lifecycle.beginStart())
    }

    @Test
    fun `duplicate stop while stopping is ignored`() {
        val lifecycle = RecorderLifecycle()

        lifecycle.beginStart()
        lifecycle.completeStart()
        assertEquals(RecorderLifecycle.StopRequest.STOP_RECORDING, lifecycle.requestStop())
        assertEquals(RecorderLifecycle.StopRequest.IGNORE, lifecycle.requestStop())
    }

    @Test
    fun `encoder failure transitions only an active recording`() {
        val lifecycle = RecorderLifecycle()

        assertFalse(lifecycle.failRecording())
        lifecycle.beginStart()
        lifecycle.completeStart()
        assertTrue(lifecycle.failRecording())
        assertFalse(lifecycle.failRecording())
    }
}
