package com.camhub.studio.ui.camera

import kotlin.math.abs
import kotlin.math.pow

/** Returns a standard, even-sized 16:9 frame for the current device orientation. */
internal fun calculateStreamFrameDimensions(
    maxResolution: Int,
    isPortrait: Boolean
): Pair<Int, Int> {
    val shortEdge = maxResolution.coerceAtLeast(2).let { it - it % 2 }
    val longEdge = ((shortEdge * 16f / 9f).toInt()).let { it - it % 2 }
    return if (isPortrait) {
        shortEdge to longEdge
    } else {
        longEdge to shortEdge
    }
}

/**
 * Advances zoom for a spring-loaded velocity lever.
 * A dead zone prevents drift near center, while a squared curve gives precise
 * slow movement near center and progressively faster movement near the ends.
 */
internal fun calculateVelocityZoomRatio(
    currentRatio: Float,
    minRatio: Float,
    maxRatio: Float,
    leverPosition: Float,
    deltaSeconds: Float
): Float {
    if (maxRatio <= minRatio) return minRatio

    val deadZone = 0.12f
    val magnitude = ((abs(leverPosition.coerceIn(-1f, 1f)) - deadZone) /
        (1f - deadZone)).coerceIn(0f, 1f)
    if (magnitude == 0f || deltaSeconds <= 0f) {
        return currentRatio.coerceIn(minRatio, maxRatio)
    }

    val direction = if (leverPosition < 0f) -1f else 1f
    val shapedVelocity = direction * magnitude * magnitude
    val maxOctavesPerSecond = 1.7f
    val multiplier = 2.0.pow(
        (shapedVelocity * maxOctavesPerSecond * deltaSeconds.coerceAtMost(0.1f)).toDouble()
    ).toFloat()
    return (currentRatio * multiplier).coerceIn(minRatio, maxRatio)
}
