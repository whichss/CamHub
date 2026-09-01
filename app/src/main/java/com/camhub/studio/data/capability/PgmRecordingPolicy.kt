package com.camhub.studio.data.capability

data class PgmRecordingPlan(
    val width: Int,
    val height: Int,
    val useSpatialUpscaling: Boolean
)

object PgmRecordingPolicy {
    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
        spatialUpscalingEnabled: Boolean,
        targetHeight: Int
    ): PgmRecordingPlan {
        val sourceShortEdge = minOf(sourceWidth, sourceHeight)
        if (spatialUpscalingEnabled && targetHeight > sourceShortEdge) {
            val evenShortEdge = targetHeight.coerceAtLeast(2) and 0x7FFFFFFE
            val evenLongEdge = (evenShortEdge * 16 / 9) and 0x7FFFFFFE
            val isPortrait = sourceHeight > sourceWidth
            return if (isPortrait) {
                PgmRecordingPlan(evenShortEdge, evenLongEdge, true)
            } else {
                PgmRecordingPlan(evenLongEdge, evenShortEdge, true)
            }
        }
        return PgmRecordingPlan(
            width = sourceWidth.coerceAtLeast(2) and 0x7FFFFFFE,
            height = sourceHeight.coerceAtLeast(2) and 0x7FFFFFFE,
            useSpatialUpscaling = false
        )
    }
}
