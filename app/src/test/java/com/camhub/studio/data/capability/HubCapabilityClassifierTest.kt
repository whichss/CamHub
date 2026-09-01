package com.camhub.studio.data.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubCapabilityClassifierTest {
    @Test
    fun `high tier recommends four 720p cameras and 1080p pgm`() {
        val profile = HubCapabilityClassifier.classify(inputs())

        assertEquals(HubPerformanceTier.HIGH, profile.tier)
        assertEquals(4, profile.recommendedCameraCount)
        assertEquals(720, profile.multiviewHeight)
        assertEquals(1080, profile.pgmHeight)
        assertTrue(profile.enableSpatialUpscaling)
    }

    @Test
    fun `balanced tier keeps four cameras without assuming flagship resources`() {
        val profile = HubCapabilityClassifier.classify(
            inputs(cpuCoreCount = 4, totalMemoryMb = 4_096, glEsVersion = 0x00030000)
        )

        assertEquals(HubPerformanceTier.BALANCED, profile.tier)
        assertEquals(4, profile.recommendedCameraCount)
        assertFalse(profile.enableSpatialUpscaling)
    }

    @Test
    fun `constrained tier limits simultaneous cameras`() {
        val profile = HubCapabilityClassifier.classify(
            inputs(avcDecoderMaxInstances = 2, totalMemoryMb = 2_048)
        )

        assertEquals(HubPerformanceTier.CONSTRAINED, profile.tier)
        assertEquals(2, profile.recommendedCameraCount)
        assertEquals(540, profile.multiviewHeight)
    }

    @Test
    fun `missing 720p decoder is unsupported`() {
        val profile = HubCapabilityClassifier.classify(
            inputs(supportsAvcDecode720p30 = false)
        )

        assertEquals(HubPerformanceTier.UNSUPPORTED, profile.tier)
        assertEquals(0, profile.recommendedCameraCount)
    }

    private fun inputs(
        cpuCoreCount: Int = 8,
        totalMemoryMb: Int = 8_192,
        glEsVersion: Int = 0x00030002,
        avcDecoderMaxInstances: Int = 8,
        supportsAvcDecode720p30: Boolean = true
    ) = HubCapabilityInputs(
        cpuCoreCount = cpuCoreCount,
        totalMemoryMb = totalMemoryMb,
        glEsVersion = glEsVersion,
        avcDecoderMaxInstances = avcDecoderMaxInstances,
        supportsAvcDecode720p30 = supportsAvcDecode720p30,
        supportsAvcDecode1080p30 = true,
        supportsAvcEncode720p30 = true,
        supportsLowLatencyDecode = true
    )
}
