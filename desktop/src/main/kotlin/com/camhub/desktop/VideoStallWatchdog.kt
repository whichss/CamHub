package com.camhub.desktop

import java.util.concurrent.atomic.AtomicLong

/** Uses monotonic time so a wall-clock adjustment cannot trigger a reconnect. */
internal class VideoStallWatchdog(
    private val timeoutNanos: Long = 8_000_000_000L,
    private val nowNanos: () -> Long = System::nanoTime
) {
    init { require(timeoutNanos > 0) }
    private val lastFrameAt = AtomicLong(nowNanos())

    fun onDecodedFrame() { lastFrameAt.set(nowNanos()) }

    fun isStalled(): Boolean = nowNanos() - lastFrameAt.get() > timeoutNanos
}
