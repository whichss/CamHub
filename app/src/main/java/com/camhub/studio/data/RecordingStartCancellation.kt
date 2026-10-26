package com.camhub.studio.data

import java.util.concurrent.atomic.AtomicBoolean

/** Cancellation signal owned by the caller for one recording-start attempt. */
class RecordingStartCancellation {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    internal fun throwIfCancelled() {
        check(!cancelled.get()) { "Recording start cancelled" }
    }

    internal val isCancelled: Boolean
        get() = cancelled.get()
}
