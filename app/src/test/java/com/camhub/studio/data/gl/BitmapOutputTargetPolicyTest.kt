package com.camhub.studio.data.gl

import org.junit.Assert.assertEquals
import org.junit.Test

class BitmapOutputTargetPolicyTest {
    @Test
    fun `only active program and preview need bitmap readback bootstrap`() {
        val targets = BitmapOutputTargetPolicy.select(
            activeCameraNames = setOf("cam-1", "cam-2", "cam-3", "cam-4"),
            sharedGpuTargets = setOf("cam-1"),
            pgmCameraName = "cam-1",
            pvwCameraName = "cam-2"
        )

        assertEquals(setOf("cam-2"), targets)
    }

    @Test
    fun `external bitmap output keeps its explicit targets even with a gpu preview`() {
        val targets = BitmapOutputTargetPolicy.select(
            activeCameraNames = setOf("cam-1", "cam-2", "cam-3"),
            sharedGpuTargets = setOf("cam-1", "cam-2"),
            pgmCameraName = "cam-1",
            pvwCameraName = "cam-2",
            externalBitmapTargets = setOf("cam-1", "cam-2")
        )

        assertEquals(setOf("cam-1", "cam-2"), targets)
    }

    @Test
    fun `missing or disconnected program cameras are ignored`() {
        val targets = BitmapOutputTargetPolicy.select(
            activeCameraNames = setOf("cam-1"),
            sharedGpuTargets = emptySet(),
            pgmCameraName = "cam-1",
            pvwCameraName = "offline-camera",
            externalBitmapTargets = setOf("offline-camera")
        )

        assertEquals(setOf("cam-1"), targets)
    }
}
