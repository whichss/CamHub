package com.camhub.studio.data.network

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/** Cross-camera UDP arrival burst summary over the most recent one-second window. */
data class UdpIngressBurstMetrics(
    val activeCameraCount: Int = 0,
    val bytesPerSecond: Long = 0L,
    val packetsPerSecond: Long = 0L,
    val p95BytesPer5Ms: Long = 0L,
    val p99BytesPer5Ms: Long = 0L,
    val maxBytesPer5Ms: Long = 0L,
    val p95PacketsPer5Ms: Long = 0L,
    val maxPacketsPer5Ms: Long = 0L,
    val maxBytesPer20Ms: Long = 0L
)

/**
 * Aggregates datagram arrivals from all camera receiver threads using aligned
 * 5 ms buckets. Writes are isolated per camera; aggregation happens on demand.
 */
class UdpIngressBurstTracker {
    companion object {
        private const val BUCKET_MS = 5L
        private const val WINDOW_BUCKETS = 200 // one second
        private const val BURST_BUCKETS = 4 // 20 ms
    }

    private val cameras = ConcurrentHashMap<String, CameraBuckets>()

    fun record(cameraName: String, datagramBytes: Int, elapsedMs: Long) {
        if (cameraName.isBlank() || datagramBytes <= 0 || elapsedMs < 0L) return
        cameras.computeIfAbsent(cameraName) { CameraBuckets() }
            .record(elapsedMs / BUCKET_MS, datagramBytes.toLong())
    }

    fun remove(cameraName: String) {
        cameras.remove(cameraName)
    }

    fun clear() {
        cameras.clear()
    }

    fun snapshot(nowElapsedMs: Long): UdpIngressBurstMetrics {
        if (nowElapsedMs < 0L) return UdpIngressBurstMetrics()
        val firstBucket = nowElapsedMs / BUCKET_MS - WINDOW_BUCKETS + 1
        val byteBuckets = LongArray(WINDOW_BUCKETS)
        val packetBuckets = LongArray(WINDOW_BUCKETS)
        var activeCameras = 0

        cameras.values.forEach { camera ->
            if (camera.accumulate(firstBucket, byteBuckets, packetBuckets)) activeCameras++
        }

        val sortedBytes = byteBuckets.copyOf().apply { sort() }
        val sortedPackets = packetBuckets.copyOf().apply { sort() }
        var maxBytesPer20Ms = 0L
        for (start in 0..WINDOW_BUCKETS - BURST_BUCKETS) {
            var total = 0L
            for (offset in 0 until BURST_BUCKETS) total += byteBuckets[start + offset]
            if (total > maxBytesPer20Ms) maxBytesPer20Ms = total
        }

        return UdpIngressBurstMetrics(
            activeCameraCount = activeCameras,
            bytesPerSecond = byteBuckets.sum(),
            packetsPerSecond = packetBuckets.sum(),
            p95BytesPer5Ms = sortedBytes.nearestRank(0.95),
            p99BytesPer5Ms = sortedBytes.nearestRank(0.99),
            maxBytesPer5Ms = byteBuckets.maxOrNull() ?: 0L,
            p95PacketsPer5Ms = sortedPackets.nearestRank(0.95),
            maxPacketsPer5Ms = packetBuckets.maxOrNull() ?: 0L,
            maxBytesPer20Ms = maxBytesPer20Ms
        )
    }

    private class CameraBuckets {
        private val bucketIds = LongArray(WINDOW_BUCKETS) { Long.MIN_VALUE }
        private val bytes = LongArray(WINDOW_BUCKETS)
        private val packets = LongArray(WINDOW_BUCKETS)

        @Synchronized
        fun record(bucketId: Long, datagramBytes: Long) {
            val slot = Math.floorMod(bucketId, WINDOW_BUCKETS.toLong()).toInt()
            if (bucketIds[slot] != bucketId) {
                bucketIds[slot] = bucketId
                bytes[slot] = 0L
                packets[slot] = 0L
            }
            bytes[slot] += datagramBytes
            packets[slot]++
        }

        @Synchronized
        fun accumulate(firstBucket: Long, outputBytes: LongArray, outputPackets: LongArray): Boolean {
            var active = false
            for (index in 0 until WINDOW_BUCKETS) {
                val bucketId = firstBucket + index
                val slot = Math.floorMod(bucketId, WINDOW_BUCKETS.toLong()).toInt()
                if (bucketIds[slot] != bucketId) continue
                val byteCount = bytes[slot]
                val packetCount = packets[slot]
                outputBytes[index] += byteCount
                outputPackets[index] += packetCount
                if (packetCount > 0L) active = true
            }
            return active
        }
    }

    private fun LongArray.nearestRank(percentile: Double): Long {
        val rank = ceil(percentile * size).toInt().coerceIn(1, size)
        return this[rank - 1]
    }
}
