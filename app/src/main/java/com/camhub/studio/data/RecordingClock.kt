package com.camhub.studio.data

/** Shared monotonic presentation clock for audio and video in one recording session. */
internal class RecordingClock {
    private var startedAtNs = 0L
    private var accumulatedPauseNs = 0L
    private var pauseStartedAtNs = 0L

    @Synchronized
    fun start(timestampNs: Long) {
        require(timestampNs > 0L)
        startedAtNs = timestampNs
        accumulatedPauseNs = 0L
        pauseStartedAtNs = 0L
    }

    @Synchronized
    fun pause(timestampNs: Long): Boolean {
        if (startedAtNs == 0L || pauseStartedAtNs != 0L || timestampNs < startedAtNs) return false
        pauseStartedAtNs = timestampNs
        return true
    }

    @Synchronized
    fun resume(timestampNs: Long): Long? {
        val pausedAt = pauseStartedAtNs
        if (startedAtNs == 0L || pausedAt == 0L || timestampNs < pausedAt) return null
        val pauseDurationNs = timestampNs - pausedAt
        accumulatedPauseNs += pauseDurationNs
        pauseStartedAtNs = 0L
        return pauseDurationNs / 1_000_000L
    }

    @Synchronized
    fun presentationTimeNs(timestampNs: Long): Long? {
        if (startedAtNs == 0L || timestampNs < startedAtNs) return null
        val pausedAt = pauseStartedAtNs
        val pauseInProgressNs = if (pausedAt > 0L) {
            (timestampNs - pausedAt).coerceAtLeast(0L)
        } else {
            0L
        }
        return (timestampNs - startedAtNs - accumulatedPauseNs - pauseInProgressNs).coerceAtLeast(0L)
    }

    @Synchronized
    fun elapsedMs(timestampNs: Long): Long? = presentationTimeNs(timestampNs)?.div(1_000_000L)

    @Synchronized
    fun reset() {
        startedAtNs = 0L
        accumulatedPauseNs = 0L
        pauseStartedAtNs = 0L
    }
}
