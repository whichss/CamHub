package com.camhub.desktop

import kotlin.math.ceil

data class DesktopDurationPercentiles(
    val sampleCount: Int = 0,
    val latestMicros: Long? = null,
    val p50Micros: Long? = null,
    val p95Micros: Long? = null,
    val p99Micros: Long? = null
)

/** Bounded recent window for measuring local processing stages without including presentation. */
internal class DesktopDurationWindow(
    private val capacity: Int = DEFAULT_CAPACITY
) {
    init {
        require(capacity > 0)
    }

    private val samples = LongArray(capacity)
    private var nextIndex = 0
    private var size = 0
    private var latestMicros: Long? = null

    @Synchronized
    fun recordNanos(durationNanos: Long) {
        require(durationNanos >= 0L)
        val durationMicros = durationNanos / 1_000L
        samples[nextIndex] = durationMicros
        nextIndex = (nextIndex + 1) % capacity
        if (size < capacity) size++
        latestMicros = durationMicros
    }

    @Synchronized
    fun snapshot(): DesktopDurationPercentiles {
        if (size == 0) return DesktopDurationPercentiles()
        val sorted = samples.copyOf(size).apply { sort() }
        return DesktopDurationPercentiles(
            sampleCount = size,
            latestMicros = latestMicros,
            p50Micros = sorted.nearestRank(0.50),
            p95Micros = sorted.nearestRank(0.95),
            p99Micros = sorted.nearestRank(0.99)
        )
    }

    private fun LongArray.nearestRank(percentile: Double): Long {
        val rank = ceil(percentile * size).toInt().coerceIn(1, size)
        return this[rank - 1]
    }

    companion object {
        const val DEFAULT_CAPACITY = 300
    }
}
