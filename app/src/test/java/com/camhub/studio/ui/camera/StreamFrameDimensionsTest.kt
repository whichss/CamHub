package com.camhub.studio.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFrameDimensionsTest {

    @Test
    fun `landscape uses 16 by 9 dimensions`() {
        assertEquals(1280 to 720, calculateStreamFrameDimensions(720, isPortrait = false))
        assertEquals(1920 to 1080, calculateStreamFrameDimensions(1080, isPortrait = false))
    }

    @Test
    fun `portrait uses 9 by 16 dimensions`() {
        assertEquals(720 to 1280, calculateStreamFrameDimensions(720, isPortrait = true))
        assertEquals(1080 to 1920, calculateStreamFrameDimensions(1080, isPortrait = true))
    }

    @Test
    fun `zoom velocity dead zone holds the current ratio`() {
        assertEquals(
            2f,
            calculateVelocityZoomRatio(2f, 1f, 10f, 0.1f, 0.5f),
            0.0001f
        )
    }

    @Test
    fun `zoom velocity direction and throw control zoom speed`() {
        val slowIn = calculateVelocityZoomRatio(2f, 1f, 10f, 0.35f, 0.25f)
        val fastIn = calculateVelocityZoomRatio(2f, 1f, 10f, 1f, 0.25f)
        val zoomOut = calculateVelocityZoomRatio(2f, 1f, 10f, -1f, 0.25f)

        assertTrue(slowIn > 2f)
        assertTrue(fastIn > slowIn)
        assertTrue(zoomOut < 2f)
    }

    @Test
    fun `zoom velocity respects camera zoom bounds`() {
        assertEquals(
            4f,
            calculateVelocityZoomRatio(3.99f, 1f, 4f, 1f, 1f),
            0.0001f
        )
        assertEquals(
            1f,
            calculateVelocityZoomRatio(1.01f, 1f, 4f, -1f, 1f),
            0.0001f
        )
    }
}
