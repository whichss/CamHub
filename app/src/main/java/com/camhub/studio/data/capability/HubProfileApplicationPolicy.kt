package com.camhub.studio.data.capability

data class HubStreamLimits(
    val streamHeight: Int,
    val streamFps: Int,
    val cameraLimit: Int,
    val enableSpatialUpscaling: Boolean
)

object HubProfileApplicationPolicy {
    fun limitsFor(
        profile: HubRuntimeProfile,
        configuredMaxResolution: Int,
        configuredFps: Int
    ): HubStreamLimits = HubStreamLimits(
        streamHeight = configuredMaxResolution.coerceAtMost(profile.multiviewHeight)
            .coerceAtLeast(MIN_STREAM_HEIGHT),
        streamFps = configuredFps.coerceAtMost(profile.multiviewFps)
            .coerceAtLeast(MIN_STREAM_FPS),
        cameraLimit = profile.recommendedCameraCount,
        enableSpatialUpscaling = profile.enableSpatialUpscaling
    )

    private const val MIN_STREAM_HEIGHT = 360
    private const val MIN_STREAM_FPS = 1
}
