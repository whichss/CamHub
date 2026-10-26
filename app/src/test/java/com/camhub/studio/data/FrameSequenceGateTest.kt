package com.camhub.studio.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSequenceGateTest {
    private val gate = FrameSequenceGate()

    @Test
    fun `same camera accepts each frame only once`() {
        assertTrue(gate.accept("Camera A", 1L))
        assertFalse(gate.accept("Camera A", 1L))
        assertTrue(gate.accept("Camera A", 2L))
        assertFalse(gate.accept("Camera A", 1L))
    }

    @Test
    fun `camera change starts a new sequence`() {
        assertTrue(gate.accept("Camera A", 88L))
        assertTrue(gate.accept("Camera B", 1L))
    }

    @Test
    fun `decoder restart at low sequence does not stall output`() {
        assertTrue(gate.accept("Camera A", 120L))
        assertTrue(gate.accept("Camera A", 1L))
        assertTrue(gate.accept("Camera A", 2L))
        assertFalse(gate.accept("Camera A", 1L))
    }

    @Test
    fun `invalid sequence is rejected`() {
        assertFalse(gate.accept("Camera A", 0L))
        assertFalse(gate.accept("Camera A", -1L))
    }
}
