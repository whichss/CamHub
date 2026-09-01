package com.camhub.studio.data.metrics

import kotlin.math.ceil

data class LatencyPercentiles(
    val sampleCount: Int = 0,
    val latestMs: Int = 0,
    val p50Ms: Int = 0,
    val p95Ms: Int = 0,
    val p99Ms: Int = 0,
    val minMs: Int = 0,
    val maxMs: Int = 0
)

/**
 * Keeps a bounded window of latency samples without allocating on record.
 *
 * A snapshot sorts a copy of the active window and uses the nearest-rank
 * percentile definition. The class is synchronized because samples can arrive
 * from codec, network, and rendering callbacks.
 */
class RollingLatencyTracker(
    private val capacity: Int = DEFAULT_CAPACITY
) {
    init {
        require(capacity > 0) { "capacity must be greater than zero" }
    }

    private val samples = IntArray(capacity)
    private var nextIndex = 0
    private var size = 0
    private var latestMs = 0

    @Synchronized
    fun record(latencyMs: Int) {
        require(latencyMs >= 0) { "latencyMs must not be negative" }
        samples[nextIndex] = latencyMs
        nextIndex = (nextIndex + 1) % capacity
        if (size < capacity) size++
        latestMs = latencyMs
    }

    @Synchronized
    fun snapshot(): LatencyPercentiles = snapshotLocked()

    @Synchronized
    fun clear() {
        nextIndex = 0
        size = 0
        latestMs = 0
    }

    private fun snapshotLocked(): LatencyPercentiles {
        if (size == 0) return LatencyPercentiles()

        val sorted = samples.copyOf(size).apply { sort() }
        return LatencyPercentiles(
            sampleCount = size,
            latestMs = latestMs,
            p50Ms = sorted.nearestRank(0.50),
            p95Ms = sorted.nearestRank(0.95),
            p99Ms = sorted.nearestRank(0.99),
            minMs = sorted.first(),
            maxMs = sorted.last()
        )
    }

    private fun IntArray.nearestRank(percentile: Double): Int {
        val rank = ceil(percentile * size).toInt().coerceIn(1, size)
        return this[rank - 1]
    }

    companion object {
        const val DEFAULT_CAPACITY = 300
    }
}
