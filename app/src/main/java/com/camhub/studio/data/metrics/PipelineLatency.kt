package com.camhub.studio.data.metrics

data class PipelineLatencyBreakdown(
    val frameSequence: Long,
    val captureToEncodeMs: Int,
    val encodeToReceiveMs: Int,
    val receiveToDecodeMs: Int,
    val decodeToReadyMs: Int,
    val totalToReadyMs: Int,
    val readyToDrawMs: Int? = null,
    val totalToDrawMs: Int? = null
)

data class PipelineTimingPoints(
    val frameSequence: Long,
    val captureAtRemoteWallMs: Long,
    val encodedAtRemoteWallMs: Long,
    val receivedAtLocalWallMs: Long,
    val receivedAtLocalElapsedMs: Long,
    val decodedAtLocalElapsedMs: Long,
    val readyAtLocalElapsedMs: Long
)

data class FrameSinkLatencyBreakdown(
    val frameSequence: Long,
    val readyToSinkDrawMs: Int,
    val totalToSinkDrawMs: Int
)

/**
 * Converts a frame's cross-device timestamps into comparable stage durations.
 * [remoteClockOffsetMs] follows the NTP convention used by [ClockOffsetTracker]:
 * remote wall clock minus local wall clock.
 */
object PipelineLatencyCalculator {
    private const val MAX_STAGE_MS = 10_000L

    fun calculate(
        points: PipelineTimingPoints,
        remoteClockOffsetMs: Long
    ): PipelineLatencyBreakdown? {
        if (points.frameSequence <= 0L) return null

        val captureToEncode = points.encodedAtRemoteWallMs - points.captureAtRemoteWallMs
        val encodeToReceive = points.receivedAtLocalWallMs -
            points.encodedAtRemoteWallMs + remoteClockOffsetMs
        val receiveToDecode = points.decodedAtLocalElapsedMs - points.receivedAtLocalElapsedMs
        val decodeToReady = points.readyAtLocalElapsedMs - points.decodedAtLocalElapsedMs

        val stages = longArrayOf(
            captureToEncode,
            encodeToReceive,
            receiveToDecode,
            decodeToReady
        )
        if (stages.any { it !in 0..MAX_STAGE_MS }) return null

        val total = stages.sum()
        if (total !in 0..MAX_STAGE_MS) return null

        return PipelineLatencyBreakdown(
            frameSequence = points.frameSequence,
            captureToEncodeMs = captureToEncode.toInt(),
            encodeToReceiveMs = encodeToReceive.toInt(),
            receiveToDecodeMs = receiveToDecode.toInt(),
            decodeToReadyMs = decodeToReady.toInt(),
            totalToReadyMs = total.toInt()
        )
    }

    fun includeDraw(
        ready: PipelineLatencyBreakdown,
        readyAtLocalElapsedMs: Long,
        drawnAtLocalElapsedMs: Long
    ): PipelineLatencyBreakdown? {
        val readyToDraw = drawnAtLocalElapsedMs - readyAtLocalElapsedMs
        if (readyToDraw !in 0..MAX_STAGE_MS) return null
        val totalToDraw = ready.totalToReadyMs.toLong() + readyToDraw
        if (totalToDraw !in 0..MAX_STAGE_MS) return null

        return ready.copy(
            readyToDrawMs = readyToDraw.toInt(),
            totalToDrawMs = totalToDraw.toInt()
        )
    }

    fun calculateSinkDraw(
        ready: PipelineLatencyBreakdown,
        readyAtLocalElapsedMs: Long,
        sinkDrawnAtLocalElapsedMs: Long
    ): FrameSinkLatencyBreakdown? {
        val readyToSink = sinkDrawnAtLocalElapsedMs - readyAtLocalElapsedMs
        if (readyToSink !in 0..MAX_STAGE_MS) return null
        val totalToSink = ready.totalToReadyMs.toLong() + readyToSink
        if (totalToSink !in 0..MAX_STAGE_MS) return null

        return FrameSinkLatencyBreakdown(
            frameSequence = ready.frameSequence,
            readyToSinkDrawMs = readyToSink.toInt(),
            totalToSinkDrawMs = totalToSink.toInt()
        )
    }
}
