package com.camhub.studio.ui.director

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Guards asynchronous stream callbacks so an old connection cannot alter its replacement. */
internal class StreamConnectionEpochs {
    private val nextEpoch = AtomicLong(0L)
    private val activeEpochs = ConcurrentHashMap<String, Long>()

    fun begin(cameraName: String): Long {
        val epoch = nextEpoch.incrementAndGet()
        activeEpochs[cameraName] = epoch
        return epoch
    }

    fun isCurrent(cameraName: String, epoch: Long): Boolean =
        activeEpochs[cameraName] == epoch

    fun invalidate(cameraName: String) {
        activeEpochs.remove(cameraName)
    }

    fun clear() {
        activeEpochs.clear()
    }
}
