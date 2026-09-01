package com.camhub.studio.data.capability

enum class RuntimePressure {
    NORMAL,
    ELEVATED,
    SEVERE,
    CRITICAL
}

data class HubPerformanceSample(
    val timestampMs: Long,
    val maxP95LatencyMs: Int,
    val droppedFrameDelta: Int,
    val minActualFps: Int,
    val targetFps: Int,
    val thermalStatus: Int,
    val connectedCameraCount: Int
)

data class HubRuntimeRecommendation(
    val baseProfile: HubRuntimeProfile,
    val recommendedProfile: HubRuntimeProfile,
    val pressure: RuntimePressure,
    val reason: String
) {
    val isDegraded: Boolean
        get() = recommendedProfile.tier != baseProfile.tier

    val shortLabel: String
        get() = when {
            baseProfile.tier == HubPerformanceTier.UNSUPPORTED -> "UNSUPPORTED"
            isDegraded -> "${baseProfile.tier.name}→${recommendedProfile.tier.name}"
            pressure != RuntimePressure.NORMAL -> "${baseProfile.tier.name} · WATCH"
            else -> baseProfile.shortLabel
        }
}

/**
 * Advises a whole-hub workload profile from measured runtime pressure.
 *
 * It intentionally does not apply stream settings. The caller may expose the recommendation to
 * the operator and decide separately whether and when to apply it.
 */
class HubPerformanceGovernor(
    private val baseProfile: HubRuntimeProfile,
    private val stableRecoveryMs: Long = DEFAULT_STABLE_RECOVERY_MS
) {
    private var activeTier = baseProfile.tier
    private var pressureSampleCount = 0
    private var stableSinceMs: Long? = null

    fun evaluate(sample: HubPerformanceSample): HubRuntimeRecommendation {
        if (baseProfile.tier == HubPerformanceTier.UNSUPPORTED) {
            return recommendation(
                pressure = RuntimePressure.CRITICAL,
                reason = "Required decoder or OpenGL capability is unavailable"
            )
        }

        if (sample.connectedCameraCount <= 0) {
            pressureSampleCount = 0
            stableSinceMs = null
            return recommendation(RuntimePressure.NORMAL, "Waiting for a live camera sample")
        }

        val pressure = classifyPressure(sample)
        val reason = pressureReason(sample, pressure)

        when (pressure) {
            RuntimePressure.CRITICAL -> {
                pressureSampleCount = 0
                stableSinceMs = null
                activeTier = HubPerformanceTier.CONSTRAINED
            }

            RuntimePressure.SEVERE -> {
                stableSinceMs = null
                pressureSampleCount += 1
                if (pressureSampleCount >= SEVERE_SAMPLES_TO_DEGRADE) {
                    activeTier = oneTierLower(activeTier)
                    pressureSampleCount = 0
                }
            }

            RuntimePressure.ELEVATED -> {
                stableSinceMs = null
                pressureSampleCount += 1
                if (pressureSampleCount >= ELEVATED_SAMPLES_TO_DEGRADE) {
                    activeTier = oneTierLower(activeTier)
                    pressureSampleCount = 0
                }
            }

            RuntimePressure.NORMAL -> {
                pressureSampleCount = 0
                if (activeTier != baseProfile.tier) {
                    val stableStart = stableSinceMs
                    if (stableStart == null) {
                        stableSinceMs = sample.timestampMs
                    } else if (sample.timestampMs - stableStart >= stableRecoveryMs) {
                        activeTier = oneTierHigherTowardBase(activeTier)
                        stableSinceMs = sample.timestampMs
                    }
                } else {
                    stableSinceMs = sample.timestampMs
                }
            }
        }

        return recommendation(pressure, reason)
    }

    private fun recommendation(
        pressure: RuntimePressure,
        reason: String
    ) = HubRuntimeRecommendation(
        baseProfile = baseProfile,
        recommendedProfile = profileForTier(activeTier),
        pressure = pressure,
        reason = reason
    )

    private fun classifyPressure(sample: HubPerformanceSample): RuntimePressure {
        val fpsRatio = if (sample.minActualFps > 0 && sample.targetFps > 0) {
            sample.minActualFps.toFloat() / sample.targetFps
        } else {
            1f
        }

        return when {
            sample.thermalStatus >= THERMAL_STATUS_SEVERE -> RuntimePressure.CRITICAL
            sample.maxP95LatencyMs >= SEVERE_LATENCY_MS ||
                sample.droppedFrameDelta >= SEVERE_DROPPED_FRAMES ||
                fpsRatio < SEVERE_FPS_RATIO -> RuntimePressure.SEVERE
            sample.thermalStatus >= THERMAL_STATUS_MODERATE ||
                sample.maxP95LatencyMs >= ELEVATED_LATENCY_MS ||
                sample.droppedFrameDelta >= ELEVATED_DROPPED_FRAMES ||
                fpsRatio < ELEVATED_FPS_RATIO -> RuntimePressure.ELEVATED
            else -> RuntimePressure.NORMAL
        }
    }

    private fun pressureReason(
        sample: HubPerformanceSample,
        pressure: RuntimePressure
    ): String = when {
        sample.thermalStatus >= THERMAL_STATUS_SEVERE -> "Device thermal status is severe"
        sample.thermalStatus >= THERMAL_STATUS_MODERATE -> "Device thermal status is moderate"
        sample.maxP95LatencyMs >= ELEVATED_LATENCY_MS ->
            "CAP→DRAW P95 is ${sample.maxP95LatencyMs}ms"
        sample.droppedFrameDelta >= ELEVATED_DROPPED_FRAMES ->
            "Dropped frames increased by ${sample.droppedFrameDelta}"
        pressure != RuntimePressure.NORMAL ->
            "Minimum stream rate is ${sample.minActualFps}/${sample.targetFps}fps"
        else -> "Runtime measurements are stable"
    }

    private fun oneTierLower(tier: HubPerformanceTier): HubPerformanceTier = when (tier) {
        HubPerformanceTier.HIGH -> HubPerformanceTier.BALANCED
        HubPerformanceTier.BALANCED -> HubPerformanceTier.CONSTRAINED
        HubPerformanceTier.CONSTRAINED,
        HubPerformanceTier.UNSUPPORTED -> tier
    }

    private fun oneTierHigherTowardBase(tier: HubPerformanceTier): HubPerformanceTier {
        val candidate = when (tier) {
            HubPerformanceTier.CONSTRAINED -> HubPerformanceTier.BALANCED
            HubPerformanceTier.BALANCED -> HubPerformanceTier.HIGH
            HubPerformanceTier.HIGH,
            HubPerformanceTier.UNSUPPORTED -> tier
        }
        return if (tierRank(candidate) >= tierRank(baseProfile.tier)) candidate else baseProfile.tier
    }

    private fun profileForTier(tier: HubPerformanceTier): HubRuntimeProfile = when (tier) {
        baseProfile.tier -> baseProfile
        HubPerformanceTier.HIGH -> baseProfile
        HubPerformanceTier.BALANCED -> HubRuntimeProfile(
            tier = HubPerformanceTier.BALANCED,
            recommendedCameraCount = baseProfile.recommendedCameraCount.coerceAtMost(4),
            multiviewHeight = baseProfile.multiviewHeight.coerceAtMost(720),
            multiviewFps = baseProfile.multiviewFps.coerceAtMost(24),
            pgmHeight = baseProfile.pgmHeight.coerceAtMost(1080),
            pgmFps = baseProfile.pgmFps.coerceAtMost(30),
            enableSpatialUpscaling = false
        )
        HubPerformanceTier.CONSTRAINED -> HubRuntimeProfile(
            tier = HubPerformanceTier.CONSTRAINED,
            recommendedCameraCount = baseProfile.recommendedCameraCount.coerceAtMost(2),
            multiviewHeight = baseProfile.multiviewHeight.coerceAtMost(540),
            multiviewFps = baseProfile.multiviewFps.coerceAtMost(15),
            pgmHeight = baseProfile.pgmHeight.coerceAtMost(720),
            pgmFps = baseProfile.pgmFps.coerceAtMost(30),
            enableSpatialUpscaling = false
        )
        HubPerformanceTier.UNSUPPORTED -> baseProfile
    }

    private fun tierRank(tier: HubPerformanceTier): Int = when (tier) {
        HubPerformanceTier.HIGH -> 0
        HubPerformanceTier.BALANCED -> 1
        HubPerformanceTier.CONSTRAINED -> 2
        HubPerformanceTier.UNSUPPORTED -> 3
    }

    companion object {
        private const val ELEVATED_LATENCY_MS = 120
        private const val SEVERE_LATENCY_MS = 250
        private const val ELEVATED_DROPPED_FRAMES = 3
        private const val SEVERE_DROPPED_FRAMES = 10
        private const val ELEVATED_FPS_RATIO = 0.8f
        private const val SEVERE_FPS_RATIO = 0.6f
        private const val THERMAL_STATUS_MODERATE = 2
        private const val THERMAL_STATUS_SEVERE = 3
        private const val SEVERE_SAMPLES_TO_DEGRADE = 2
        private const val ELEVATED_SAMPLES_TO_DEGRADE = 3
        private const val DEFAULT_STABLE_RECOVERY_MS = 30_000L
    }
}
