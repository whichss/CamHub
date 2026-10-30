package com.camhub.studio.data.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFacingCycleTest {
    @Test
    fun `cycles internal cameras when no external camera is available`() {
        assertEquals(CameraInputFacing.FRONT, CameraFacingCycle.next(CameraInputFacing.BACK, false))
        assertEquals(CameraInputFacing.BACK, CameraFacingCycle.next(CameraInputFacing.FRONT, false))
        assertEquals(CameraInputFacing.BACK, CameraFacingCycle.next(CameraInputFacing.EXTERNAL, false))
    }

    @Test
    fun `includes CameraX external camera when available`() {
        assertEquals(CameraInputFacing.FRONT, CameraFacingCycle.next(CameraInputFacing.BACK, true))
        assertEquals(CameraInputFacing.EXTERNAL, CameraFacingCycle.next(CameraInputFacing.FRONT, true))
        assertEquals(CameraInputFacing.BACK, CameraFacingCycle.next(CameraInputFacing.EXTERNAL, true))
    }
}
