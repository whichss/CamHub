package com.camhub.desktop

import kotlin.math.ceil

data class DesktopCaptureLatencyPercentiles(
    val sampleCount: Int = 0,
    val latestMs: Long? = null,
    val p50Ms: Long? = null,
    val p95Ms: Long? = null,
    val p99Ms: Long? = null
)

/** Bounded capture-to-decode sample window; it does not include display presentation. */
internal class DesktopCaptureLatencyWindow(
    private val capacity: Int = DEFAULT_CAPACITY
) {
    init {
        require(capacity > 0)
    }

    private val samples = LongArray(capacity)
    private var nextIndex = 0
    private var size = 0
    private var latestMs: Long? = null

    @Synchronized
    fun record(sampleMs: Long) {
        require(sampleMs >= 0L)
        samples[nextIndex] = sampleMs
        nextIndex = (nextIndex + 1) % capacity
        if (size < capacity) size++
        latestMs = sampleMs
    }

    @Synchronized
    fun snapshot(): DesktopCaptureLatencyPercentiles {
        if (size == 0) return DesktopCaptureLatencyPercentiles()
        val sorted = samples.copyOf(size).apply { sort() }
        return DesktopCaptureLatencyPercentiles(
            sampleCount = size,
            latestMs = latestMs,
            p50Ms = sorted.nearestRank(0.50),
            p95Ms = sorted.nearestRank(0.95),
            p99Ms = sorted.nearestRank(0.99)
        )
    }

    @Synchronized
    fun clear() {
        nextIndex = 0
        size = 0
        latestMs = null
    }

    private fun LongArray.nearestRank(percentile: Double): Long {
        val rank = ceil(percentile * size).toInt().coerceIn(1, size)
        return this[rank - 1]
    }

    companion object {
        const val DEFAULT_CAPACITY = 300
    }
}
