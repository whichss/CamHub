package com.camhub.studio.data.ptz

import org.junit.Assert.assertEquals
import org.junit.Test

class HybridPtzControllerTest {
    @Test
    fun `unsupported camera keeps composition on hub`() {
        val state = HybridPtzController.applyGesture(
            state = HybridPtzState(),
            zoomFactor = 2f,
            panX = 0.1f,
            panY = 0f,
            maxZoom = 4f,
            supportsRemotePtz = false
        )

        assertEquals(PtzMode.HUB, state.ptzMode)
        assertEquals(2f, state.zoom)
        assertEquals(2f, HybridPtzController.hubTransform(state).scale)
    }

    @Test
    fun `remote camera uses temporary delta until application`() {
        val pending = HybridPtzController.applyGesture(
            state = HybridPtzState(ptzMode = PtzMode.REMOTE),
            zoomFactor = 2f,
            panX = 0f,
            panY = 0f,
            maxZoom = 8f,
            supportsRemotePtz = true
        )

        assertEquals(PtzMode.REMOTE_PENDING, pending.ptzMode)
        assertEquals(2f, HybridPtzController.hubTransform(pending).scale)

        val applied = HybridPtzController.markRemoteApplied(pending)
        assertEquals(PtzMode.REMOTE, applied.ptzMode)
        assertEquals(1f, HybridPtzController.hubTransform(applied).scale)
    }

    @Test
    fun `remote zoom out does not shrink the temporary hub image`() {
        val pending = HybridPtzController.applyGesture(
            state = HybridPtzState(
                zoom = 2f,
                ptzMode = PtzMode.REMOTE,
                remoteAppliedZoom = 2f
            ),
            zoomFactor = 0.5f,
            panX = 0f,
            panY = 0f,
            maxZoom = 8f,
            supportsRemotePtz = true
        )

        val transform = HybridPtzController.hubTransform(pending)
        assertEquals(PtzMode.REMOTE_PENDING, pending.ptzMode)
        assertEquals(1f, transform.scale)
        assertEquals(0.5f, transform.centerX)
        assertEquals(0.5f, transform.centerY)
    }

    @Test
    fun `remote acknowledgement records the crop actually applied by camera`() {
        val pending = HybridPtzState(
            zoom = 4f,
            centerX = 0.8f,
            centerY = 0.2f,
            ptzMode = PtzMode.REMOTE_PENDING
        )

        val applied = HybridPtzController.markRemoteApplied(
            state = pending,
            appliedZoom = 3.999f,
            appliedCenterX = 0.875f,
            appliedCenterY = 0.125f
        )

        assertEquals(PtzMode.REMOTE, applied.ptzMode)
        assertEquals(3.999f, applied.remoteAppliedZoom)
        assertEquals(0.875f, applied.centerX)
        assertEquals(0.125f, applied.centerY)
        assertEquals(HybridPtzController.IDENTITY_TRANSFORM, HybridPtzController.hubTransform(applied))
    }

    @Test
    fun `center remains inside crop bounds`() {
        val state = HybridPtzController.applyGesture(
            state = HybridPtzState(),
            zoomFactor = 4f,
            panX = 10f,
            panY = -10f,
            maxZoom = 4f,
            supportsRemotePtz = false
        )

        assertEquals(0.125f, state.centerX)
        assertEquals(0.875f, state.centerY)
    }

    @Test
    fun `double tap zooms around selected position and second tap resets`() {
        val zoomed = HybridPtzController.doubleTap(
            state = HybridPtzState(),
            tapX = 0.8f,
            tapY = 0.3f,
            maxZoom = 5f,
            supportsRemotePtz = false
        )
        val reset = HybridPtzController.doubleTap(
            state = zoomed,
            tapX = 0.2f,
            tapY = 0.2f,
            maxZoom = 5f,
            supportsRemotePtz = false
        )

        assertEquals(2f, zoomed.zoom)
        assertEquals(0.75f, zoomed.centerX)
        assertEquals(1f, reset.zoom)
        assertEquals(0.5f, reset.centerX)
    }
}
