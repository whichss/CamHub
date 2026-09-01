package com.camhub.studio.data.network

enum class UdpFallbackReason(val logLabel: String) {
    FIRST_FRAME_TIMEOUT("first_frame_timeout"),
    STREAM_STALLED("stream_stalled")
}
/** Pure timing policy shared by the runtime watchdog and unit tests. */
object UdpStreamFallbackPolicy {
    const val FIRST_FRAME_TIMEOUT_MS = 1_500L
    const val STREAM_STALL_TIMEOUT_MS = 2_000L

    fun reason(
        nowMs: Long,
        receiverStartedAtMs: Long,
        lastCompleteFrameAtMs: Long
    ): UdpFallbackReason? {
        return if (lastCompleteFrameAtMs <= 0L) {
            if (nowMs - receiverStartedAtMs >= FIRST_FRAME_TIMEOUT_MS) {
                UdpFallbackReason.FIRST_FRAME_TIMEOUT
            } else null
        } else if (nowMs - lastCompleteFrameAtMs >= STREAM_STALL_TIMEOUT_MS) {
            UdpFallbackReason.STREAM_STALLED
        } else null
    }
}
