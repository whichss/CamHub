package com.camhub.studio.data.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PgmRecordingPolicyTest {
    @Test
    fun `720p pgm records at 1080p when spatial upscaling is enabled`() {
        val plan = PgmRecordingPolicy.plan(1280, 720, true, 1080)

        assertEquals(1920, plan.width)
        assertEquals(1080, plan.height)
        assertTrue(plan.useSpatialUpscaling)
    }

    @Test
    fun `native size is retained when spatial upscaling is disabled`() {
        val plan = PgmRecordingPolicy.plan(1280, 720, false, 1080)

        assertEquals(1280, plan.width)
        assertEquals(720, plan.height)
        assertFalse(plan.useSpatialUpscaling)
    }

    @Test
    fun `native 1080p input is not needlessly upscaled`() {
        val plan = PgmRecordingPolicy.plan(1920, 1080, true, 1080)

        assertEquals(1920, plan.width)
        assertEquals(1080, plan.height)
        assertFalse(plan.useSpatialUpscaling)
    }

    @Test
    fun `portrait 720p source retains portrait orientation when upscaled`() {
        val plan = PgmRecordingPolicy.plan(720, 1280, true, 1080)

        assertEquals(1080, plan.width)
        assertEquals(1920, plan.height)
        assertTrue(plan.useSpatialUpscaling)
    }
}
