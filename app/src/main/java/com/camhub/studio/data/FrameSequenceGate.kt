package com.camhub.studio.data

/** Deduplicates frames while allowing a decoder restart to begin a fresh low sequence. */
internal class FrameSequenceGate(
    private val restartSequenceWindow: Long = 2L
) {
    private var cameraName: String? = null
    private var lastSequence = Long.MIN_VALUE

    @Synchronized
    fun accept(camera: String, sequence: Long): Boolean {
        if (sequence <= 0L) return false
        if (cameraName != camera) {
            cameraName = camera
            lastSequence = Long.MIN_VALUE
        } else if (sequence <= lastSequence) {
            val isDecoderRestart = sequence <= restartSequenceWindow &&
                lastSequence > restartSequenceWindow
            if (!isDecoderRestart) return false
            lastSequence = Long.MIN_VALUE
        }
        lastSequence = sequence
        return true
    }

    @Synchronized
    fun reset(camera: String? = null) {
        if (camera == null || cameraName == camera) {
            cameraName = null
            lastSequence = Long.MIN_VALUE
        }
    }
}
