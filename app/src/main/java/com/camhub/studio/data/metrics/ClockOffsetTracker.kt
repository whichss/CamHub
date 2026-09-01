package com.camhub.studio.data.metrics

data class ClockSyncEstimate(
    /** Remote wall clock minus local wall clock. */
    val remoteClockOffsetMs: Long,
    val roundTripMs: Long
)

data class ClockSyncState(
    val remoteClockOffsetMs: Long = 0L,
    val bestRoundTripMs: Long = Long.MAX_VALUE,
    val sampleCount: Int = 0,
    val lastUpdatedWallMs: Long = 0L
) {
    val isSynchronized: Boolean get() = sampleCount > 0
}

/**
 * NTP-style clock offset estimator for the existing CamHub ping/pong channel.
 *
 * t0: local ping send, t1: remote ping receive, t2: remote pong send,
 * t3: local pong receive. The lowest-RTT estimate is retained because it is
 * normally the sample least affected by asymmetric queueing.
 */
class ClockOffsetTracker(
    private val maxAcceptedRoundTripMs: Long = DEFAULT_MAX_RTT_MS
) {
    init {
        require(maxAcceptedRoundTripMs > 0) {
            "maxAcceptedRoundTripMs must be greater than zero"
        }
    }

    private var state = ClockSyncState()

    @Synchronized
    fun record(t0: Long, t1: Long, t2: Long, t3: Long): ClockSyncState {
        val estimate = estimate(t0, t1, t2, t3, maxAcceptedRoundTripMs)
            ?: return state
        val nextSampleCount = state.sampleCount + 1

        state = if (!state.isSynchronized || estimate.roundTripMs < state.bestRoundTripMs) {
            ClockSyncState(
                remoteClockOffsetMs = estimate.remoteClockOffsetMs,
                bestRoundTripMs = estimate.roundTripMs,
                sampleCount = nextSampleCount,
                lastUpdatedWallMs = t3
            )
        } else {
            state.copy(
                sampleCount = nextSampleCount,
                lastUpdatedWallMs = t3
            )
        }
        return state
    }

    @Synchronized
    fun snapshot(): ClockSyncState = state

    companion object {
        const val DEFAULT_MAX_RTT_MS = 5_000L

        fun estimate(
            t0: Long,
            t1: Long,
            t2: Long,
            t3: Long,
            maxAcceptedRoundTripMs: Long = DEFAULT_MAX_RTT_MS
        ): ClockSyncEstimate? {
            if (t0 <= 0L || t1 <= 0L || t2 < t1 || t3 < t0) return null

            val remoteProcessingMs = t2 - t1
            val roundTripMs = (t3 - t0) - remoteProcessingMs
            if (roundTripMs !in 0..maxAcceptedRoundTripMs) return null

            val remoteClockOffsetMs = ((t1 - t0) + (t2 - t3)) / 2L
            return ClockSyncEstimate(
                remoteClockOffsetMs = remoteClockOffsetMs,
                roundTripMs = roundTripMs
            )
        }
    }
}
