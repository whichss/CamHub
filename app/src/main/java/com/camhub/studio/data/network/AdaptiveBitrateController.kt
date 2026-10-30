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
    // Starts when the encoded payload is constructed; includes sender queue, transport and frame assembly.
    val encodeToReceiveMs: Int? = null,
    val networkFrameSequence: Long? = null,
    val receiveToReadyMs: Int? = null,
    val allowRecovery: Boolean = true,
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
        private const val CONGESTED_P95_MS = 100
        private const val CONGESTED_P99_MS = 160
        private const val HEALTHY_P95_MS = 70
        private const val HEALTHY_P99_MS = 110
        private const val CONGESTED_DROP_DELTA = 3
        private const val DOWN_CHANGE_COOLDOWN_MS = 2_000L
        private const val STABLE_RECOVERY_MS = 12_000L
        private const val NETWORK_DELAY_CONFIRMATIONS = 3
        private const val NETWORK_SAMPLE_MAX_AGE_MS = 2_500L
    }

    var ceilingMbps: Int = ceilingMbps.coerceAtLeast(minimumMbps)
        private set
    var currentTargetMbps: Int = this.ceilingMbps
        private set

    private var lastChangeAtMs = Long.MIN_VALUE
    private var stableSinceMs = 0L
    private var lastNetworkFrameSequence: Long? = null
    private var highNetworkDelaySamples = 0
    private var lastNetworkSampleAtMs: Long? = null

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
        val senderToReceiveDelay = sample.encodeToReceiveMs
        val isNewNetworkFrame = senderToReceiveDelay != null && sample.networkFrameSequence != null &&
            sample.networkFrameSequence != lastNetworkFrameSequence
        if (senderToReceiveDelay == null || sample.networkFrameSequence == null) {
            highNetworkDelaySamples = 0
            lastNetworkFrameSequence = null
            lastNetworkSampleAtMs = null
        } else if (isNewNetworkFrame) {
            lastNetworkFrameSequence = sample.networkFrameSequence
            val previousSampleAtMs = lastNetworkSampleAtMs
            if (previousSampleAtMs == null || sample.nowMs < previousSampleAtMs ||
                sample.nowMs - previousSampleAtMs > NETWORK_SAMPLE_MAX_AGE_MS) {
                highNetworkDelaySamples = 0
            }
            lastNetworkSampleAtMs = sample.nowMs
            highNetworkDelaySamples = if (senderToReceiveDelay >= CONGESTED_P95_MS) {
                (highNetworkDelaySamples + 1).coerceAtMost(NETWORK_DELAY_CONFIRMATIONS)
            } else 0
        }
        val networkSampleFresh = lastNetworkSampleAtMs?.let {
            sample.nowMs >= it && sample.nowMs - it <= NETWORK_SAMPLE_MAX_AGE_MS
        } == true
        if (!networkSampleFresh) highNetworkDelaySamples = 0
        val networkDelayConfirmed = isNewNetworkFrame && networkSampleFresh &&
            highNetworkDelaySamples >= NETWORK_DELAY_CONFIRMATIONS
        val localPressure = sample.receiveToReadyMs?.let { it >= CONGESTED_P95_MS } == true
        val severe =
            (loss != null && loss >= SEVERE_PACKET_LOSS_PERCENT) ||
            (networkDelayConfirmed && senderToReceiveDelay >= SEVERE_P95_MS)
        val congested = severe ||
            (loss != null && loss >= CONGESTED_PACKET_LOSS_PERCENT) ||
            networkDelayConfirmed

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
            (sample.latencyP95Ms in 0 until HEALTHY_P95_MS &&
                sample.latencyP99Ms in 0 until HEALTHY_P99_MS)
        val lossHealthy = loss == null || loss < HEALTHY_PACKET_LOSS_PERCENT
        val fpsHealthy = sample.actualFps == 0 || sample.actualFps >= targetFps * 0.9f
        val hasNetworkEvidence = loss != null ||
            (networkSampleFresh && sample.networkFrameSequence != null && senderToReceiveDelay != null)
        // An isolated high sample is not enough to reduce bitrate, but it is
        // also not evidence that increasing bitrate is safe.
        val networkDelayHealthy = !networkSampleFresh || senderToReceiveDelay == null ||
            senderToReceiveDelay < CONGESTED_P95_MS
        val healthy = hasNetworkEvidence && networkDelayHealthy && latencyHealthy && lossHealthy && !localPressure &&
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
        if (!sample.allowRecovery) return null

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
        sample.encodeToReceiveMs != null && sample.encodeToReceiveMs >= CONGESTED_P95_MS ->
            "payload-ready→frame-received ${sample.encodeToReceiveMs}ms"
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
