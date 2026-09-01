package com.camhub.studio.data.network

import kotlin.math.floor

enum class BitratePressure {
    SEVERE,
    CONGESTED,
    RECOVERY,
    CEILING
}
data class AdaptiveBitrateSample(
    val nowMs: Long,
    val latencyP95Ms: Int = 0,
    val latencyP99Ms: Int = 0,
    val hasLatencySample: Boolean = false,
    val recentPacketLossPercent: Float? = null,
    val droppedFrameDelta: Int = 0,
    val actualFps: Int = 0,
    val targetFps: Int = 30
)

data class AdaptiveBitrateDecision(
    val targetMbps: Int,
    val pressure: BitratePressure,
    val reason: String
)

/**
 * Per-camera additive-increase/multiplicative-decrease bitrate controller.
 * Congestion reduces quality quickly, while recovery requires a sustained stable
 * window so bitrate commands do not oscillate every second.
 */
class AdaptiveBitrateController(
    ceilingMbps: Int,
    private val minimumMbps: Int = 1
) {
    companion object {
        private const val SEVERE_PACKET_LOSS_PERCENT = 5f
        private const val CONGESTED_PACKET_LOSS_PERCENT = 1f
        private const val HEALTHY_PACKET_LOSS_PERCENT = 0.3f
        private const val SEVERE_P95_MS = 180
        private const val SEVERE_P99_MS = 260
        private const val CONGESTED_P95_MS = 100
        private const val CONGESTED_P99_MS = 160
        private const val HEALTHY_P95_MS = 70
        private const val HEALTHY_P99_MS = 110
        private const val SEVERE_DROP_DELTA = 10
        private const val CONGESTED_DROP_DELTA = 3
        private const val DOWN_CHANGE_COOLDOWN_MS = 2_000L
        private const val STABLE_RECOVERY_MS = 12_000L
    }

    var ceilingMbps: Int = ceilingMbps.coerceAtLeast(minimumMbps)
        private set
    var currentTargetMbps: Int = this.ceilingMbps
        private set

    private var lastChangeAtMs = Long.MIN_VALUE
    private var stableSinceMs = 0L

    fun updateCeiling(newCeilingMbps: Int, nowMs: Long): AdaptiveBitrateDecision? {
        ceilingMbps = newCeilingMbps.coerceAtLeast(minimumMbps)
        if (currentTargetMbps <= ceilingMbps) return null
        currentTargetMbps = ceilingMbps
        lastChangeAtMs = nowMs
        stableSinceMs = 0L
        return AdaptiveBitrateDecision(
            targetMbps = currentTargetMbps,
            pressure = BitratePressure.CEILING,
            reason = "ceiling ${currentTargetMbps}Mbps"
        )
    }

    fun evaluate(sample: AdaptiveBitrateSample): AdaptiveBitrateDecision? {
        val loss = sample.recentPacketLossPercent
        val targetFps = sample.targetFps.coerceAtLeast(1)
        val severeFpsPressure = sample.actualFps > 0 &&
            sample.actualFps < targetFps * 0.6f
        val fpsPressure = sample.actualFps > 0 &&
            sample.actualFps < targetFps * 0.8f
        val severe =
            (loss != null && loss >= SEVERE_PACKET_LOSS_PERCENT) ||
            (sample.hasLatencySample && (
                sample.latencyP95Ms >= SEVERE_P95_MS ||
                    sample.latencyP99Ms >= SEVERE_P99_MS
                )) ||
            sample.droppedFrameDelta >= SEVERE_DROP_DELTA ||
            severeFpsPressure
        val congested = severe ||
            (loss != null && loss >= CONGESTED_PACKET_LOSS_PERCENT) ||
            (sample.hasLatencySample && (
                sample.latencyP95Ms >= CONGESTED_P95_MS ||
                    sample.latencyP99Ms >= CONGESTED_P99_MS
                )) ||
            sample.droppedFrameDelta >= CONGESTED_DROP_DELTA ||
            fpsPressure

        if (congested) {
            stableSinceMs = 0L
            if (currentTargetMbps <= minimumMbps || !canChangeDown(sample.nowMs)) return null
            val next = if (severe) {
                floor(currentTargetMbps * 0.65f).toInt()
                    .coerceAtMost(currentTargetMbps - 1)
            } else {
                currentTargetMbps - 1
            }.coerceIn(minimumMbps, ceilingMbps)
            if (next == currentTargetMbps) return null
            currentTargetMbps = next
            lastChangeAtMs = sample.nowMs
            return AdaptiveBitrateDecision(
                targetMbps = next,
                pressure = if (severe) BitratePressure.SEVERE else BitratePressure.CONGESTED,
                reason = pressureReason(sample, loss)
            )
        }

        val latencyHealthy = !sample.hasLatencySample ||
            (sample.latencyP95Ms in 1 until HEALTHY_P95_MS &&
                sample.latencyP99Ms in 1 until HEALTHY_P99_MS)
        val lossHealthy = loss == null || loss < HEALTHY_PACKET_LOSS_PERCENT
        val fpsHealthy = sample.actualFps == 0 || sample.actualFps >= targetFps * 0.9f
        val healthy = latencyHealthy && lossHealthy &&
            sample.droppedFrameDelta == 0 && fpsHealthy
        if (!healthy) {
            stableSinceMs = 0L
            return null
        }

        if (currentTargetMbps >= ceilingMbps) {
            stableSinceMs = sample.nowMs
            return null
        }
        if (stableSinceMs == 0L) {
            stableSinceMs = sample.nowMs
            return null
        }
        if (sample.nowMs - stableSinceMs < STABLE_RECOVERY_MS) return null

        currentTargetMbps = (currentTargetMbps + 1).coerceAtMost(ceilingMbps)
        lastChangeAtMs = sample.nowMs
        stableSinceMs = sample.nowMs
        return AdaptiveBitrateDecision(
            targetMbps = currentTargetMbps,
            pressure = BitratePressure.RECOVERY,
            reason = "stable recovery"
        )
    }

    private fun canChangeDown(nowMs: Long): Boolean =
        lastChangeAtMs == Long.MIN_VALUE || nowMs - lastChangeAtMs >= DOWN_CHANGE_COOLDOWN_MS

    private fun pressureReason(sample: AdaptiveBitrateSample, loss: Float?): String = when {
        loss != null && loss >= CONGESTED_PACKET_LOSS_PERCENT ->
            "UDP loss ${formatOneDecimal(loss)}%"
        sample.hasLatencySample && sample.latencyP99Ms >= CONGESTED_P99_MS ->
            "P99 ${sample.latencyP99Ms}ms"
        sample.hasLatencySample && sample.latencyP95Ms >= CONGESTED_P95_MS ->
            "P95 ${sample.latencyP95Ms}ms"
        sample.droppedFrameDelta >= CONGESTED_DROP_DELTA ->
            "drops +${sample.droppedFrameDelta}"
        else -> "FPS ${sample.actualFps}/${sample.targetFps}"
    }

    private fun formatOneDecimal(value: Float): String {
        val tenths = (value * 10f).toInt().coerceAtLeast(0)
        return "${tenths / 10}.${tenths % 10}"
    }
}
