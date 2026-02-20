package com.camhub.studio.data.camera

import kotlin.math.roundToLong

object CameraValueMapper {

    /**
     * Convert shutter speed string like "1/48" to nanoseconds for SENSOR_EXPOSURE_TIME.
     */
    fun shutterToNanos(shutter: String): Long {
        val trimmed = shutter.trim()
        if (trimmed.startsWith("1/")) {
            val denominator = trimmed.removePrefix("1/").toDoubleOrNull() ?: return 20_000_000L
            return (1_000_000_000.0 / denominator).roundToLong()
        }
        // Whole-second values like "2" → 2 seconds
        val seconds = trimmed.toDoubleOrNull() ?: return 20_000_000L
        return (seconds * 1_000_000_000.0).roundToLong()
    }

    /**
     * Convert focus distance string like "2.0m" to diopter (1/distance_in_meters)
     * for LENS_FOCUS_DISTANCE. "∞" maps to 0.0f (infinity focus).
     */
    fun focusToDiopter(focus: String): Float {
        val trimmed = focus.trim()
        if (trimmed == "∞" || trimmed.equals("inf", ignoreCase = true)) return 0f
        val meters = trimmed.removeSuffix("m").toFloatOrNull() ?: return 0f
        if (meters <= 0f) return 0f
        return 1f / meters
    }

    /**
     * Parse ISO string to integer value for SENSOR_SENSITIVITY.
     */
    fun isoToInt(iso: String): Int {
        return iso.trim().toIntOrNull() ?: 100
    }

    /**
     * Generate a standard ISO stop list between [minIso] and [maxIso].
     * Uses 1/3-stop increments.
     */
    fun generateIsoStops(minIso: Int, maxIso: Int): List<String> {
        val fullStops = listOf(
            50, 64, 80, 100, 125, 160, 200, 250, 320, 400,
            500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200,
            4000, 5000, 6400, 8000, 10000, 12800, 25600, 51200, 102400
        )
        return listOf("Auto") + fullStops.filter { it in minIso..maxIso }.map { it.toString() }
    }

    /**
     * Generate a standard shutter speed list.
     */
    fun generateShutterSpeeds(): List<String> {
        return listOf(
            "Auto", "1/24", "1/30", "1/48", "1/50", "1/60", "1/96",
            "1/100", "1/120", "1/240", "1/500", "1/1000"
        )
    }

    /**
     * Generate focus distance list based on min focus distance (diopter).
     */
    fun generateFocusDistances(minFocusDiopter: Float): List<String> {
        val distances = listOf(0.3f, 0.5f, 0.8f, 1.0f, 1.5f, 2.0f, 3.0f, 5.0f, 8.0f)
        val minMeters = if (minFocusDiopter > 0f) 1f / minFocusDiopter else 0.1f
        val filtered = distances.filter { it >= minMeters }
        return listOf("AF") + filtered.map { "${it}m" } + "∞"
    }

    /**
     * Generate discrete zoom steps from [minZoom] to [maxZoom] in 0.5x increments.
     */
    fun generateZoomSteps(minZoom: Float, maxZoom: Float): List<String> {
        if (maxZoom <= minZoom) return listOf("${minZoom}x")
        val steps = mutableListOf<String>()
        var z = minZoom
        while (z <= maxZoom + 0.01f) {
            steps.add(String.format("%.1fx", z))
            z += 0.5f
        }
        // Ensure max is included if it doesn't land on a 0.5 boundary
        val lastStep = String.format("%.1fx", maxZoom)
        if (steps.last() != lastStep) {
            steps.add(lastStep)
        }
        return steps
    }

    /**
     * Parse zoom step string like "2.5x" to float value.
     */
    fun zoomStepToFloat(step: String): Float {
        return step.removeSuffix("x").toFloatOrNull() ?: 1f
    }

    /**
     * Format elapsed recording time in milliseconds to broadcast timecode HH:MM:SS:FF at given fps.
     */
    fun formatTimecode(elapsedMs: Long, fps: Int = 30): String {
        val totalFrames = (elapsedMs * fps / 1000)
        val ff = (totalFrames % fps).toInt()
        val totalSeconds = totalFrames / fps
        val ss = (totalSeconds % 60).toInt()
        val totalMinutes = totalSeconds / 60
        val mm = (totalMinutes % 60).toInt()
        val hh = (totalMinutes / 60).toInt()
        return "%02d:%02d:%02d:%02d".format(hh, mm, ss, ff)
    }
}
