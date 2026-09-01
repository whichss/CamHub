package com.camhub.studio.data.ptz

enum class PtzMode {
    HUB,
    REMOTE_PENDING,
    REMOTE
}

data class HybridPtzState(
    val zoom: Float = 1f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val ptzMode: PtzMode = PtzMode.HUB,
    val remoteAppliedZoom: Float = 1f,
    val remoteAppliedCenterX: Float = 0.5f,
    val remoteAppliedCenterY: Float = 0.5f
)

data class HubPtzTransform(
    val scale: Float,
    val centerX: Float,
    val centerY: Float
)

object HybridPtzController {
    fun applyGesture(
        state: HybridPtzState,
        zoomFactor: Float,
        panX: Float,
        panY: Float,
        maxZoom: Float,
        supportsRemotePtz: Boolean
    ): HybridPtzState {
        val zoom = (state.zoom * zoomFactor).coerceIn(1f, maxZoom.coerceAtLeast(1f))
        val centerX = clampCenter(state.centerX - panX / zoom, zoom)
        val centerY = clampCenter(state.centerY - panY / zoom, zoom)
        return state.copy(
            zoom = zoom,
            centerX = centerX,
            centerY = centerY,
            ptzMode = if (supportsRemotePtz) PtzMode.REMOTE_PENDING else PtzMode.HUB
        )
    }

    fun doubleTap(
        state: HybridPtzState,
        tapX: Float,
        tapY: Float,
        maxZoom: Float,
        supportsRemotePtz: Boolean
    ): HybridPtzState {
        if (state.zoom > 1.05f) {
            return state.copy(
                zoom = 1f,
                centerX = 0.5f,
                centerY = 0.5f,
                ptzMode = if (supportsRemotePtz) PtzMode.REMOTE_PENDING else PtzMode.HUB
            )
        }
        val targetZoom = minOf(2f, maxZoom.coerceAtLeast(1f))
        return state.copy(
            zoom = targetZoom,
            centerX = clampCenter(tapX, targetZoom),
            centerY = clampCenter(tapY, targetZoom),
            ptzMode = if (supportsRemotePtz) PtzMode.REMOTE_PENDING else PtzMode.HUB
        )
    }

    fun markRemoteApplied(
        state: HybridPtzState,
        appliedZoom: Float = state.zoom,
        appliedCenterX: Float = state.centerX,
        appliedCenterY: Float = state.centerY
    ): HybridPtzState = state.copy(
        ptzMode = PtzMode.REMOTE,
        zoom = appliedZoom,
        centerX = appliedCenterX,
        centerY = appliedCenterY,
        remoteAppliedZoom = appliedZoom,
        remoteAppliedCenterX = appliedCenterX,
        remoteAppliedCenterY = appliedCenterY
    )

    fun hubTransform(state: HybridPtzState): HubPtzTransform = when (state.ptzMode) {
        PtzMode.HUB -> HubPtzTransform(
            scale = state.zoom,
            centerX = state.centerX,
            centerY = state.centerY
        )
        PtzMode.REMOTE_PENDING -> {
            // The hub can crop further while the camera catches up, but it cannot reconstruct
            // pixels outside a crop that the remote camera has already applied.
            val scale = (state.zoom / state.remoteAppliedZoom.coerceAtLeast(1f))
                .coerceAtLeast(1f)
            HubPtzTransform(
                scale = scale,
                centerX = clampCenter(
                    0.5f + (state.centerX - state.remoteAppliedCenterX) * state.remoteAppliedZoom,
                    scale
                ),
                centerY = clampCenter(
                    0.5f + (state.centerY - state.remoteAppliedCenterY) * state.remoteAppliedZoom,
                    scale
                )
            )
        }
        PtzMode.REMOTE -> IDENTITY_TRANSFORM
    }

    private fun clampCenter(center: Float, zoom: Float): Float {
        val halfVisible = 0.5f / zoom.coerceAtLeast(1f)
        return center.coerceIn(halfVisible, 1f - halfVisible)
    }

    val IDENTITY_TRANSFORM = HubPtzTransform(1f, 0.5f, 0.5f)
}
