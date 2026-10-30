package com.camhub.studio.data.metrics

import kotlin.math.ceil

data class DurationPercentilesUs(
    val sampleCount: Int = 0,
    val latestUs: Long = 0L,
    val p50Us: Long = 0L,
    val p95Us: Long = 0L,
    val p99Us: Long = 0L,
    val minUs: Long = 0L,
    val maxUs: Long = 0L
)

/** Bounded microsecond-duration window; records without allocation and sorts only on snapshot. */
class RollingDurationTracker(
    private val capacity: Int = DEFAULT_CAPACITY
) {
    init {
        require(capacity > 0) { "capacity must be greater than zero" }
    }

    private val samples = LongArray(capacity)
    private var nextIndex = 0
    private var size = 0
    private var latestUs = 0L

    @Synchronized
    fun recordNanos(durationNs: Long) {
        require(durationNs >= 0L) { "durationNs must not be negative" }
        val durationUs = durationNs / NANOS_PER_MICRO
        samples[nextIndex] = durationUs
        nextIndex = (nextIndex + 1) % capacity
        if (size < capacity) size++
        latestUs = durationUs
    }

    @Synchronized
    fun snapshot(): DurationPercentilesUs {
        if (size == 0) return DurationPercentilesUs()
        val sorted = samples.copyOf(size).apply { sort() }
        return DurationPercentilesUs(
            sampleCount = size,
            latestUs = latestUs,
            p50Us = sorted.nearestRank(0.50),
            p95Us = sorted.nearestRank(0.95),
            p99Us = sorted.nearestRank(0.99),
            minUs = sorted.first(),
            maxUs = sorted.last()
        )
    }

    @Synchronized
    fun clear() {
        nextIndex = 0
        size = 0
        latestUs = 0L
    }

    private fun LongArray.nearestRank(percentile: Double): Long {
        val rank = ceil(percentile * size).toInt().coerceIn(1, size)
        return this[rank - 1]
    }

    companion object {
        const val DEFAULT_CAPACITY = 300
        private const val NANOS_PER_MICRO = 1_000L
    }
}
