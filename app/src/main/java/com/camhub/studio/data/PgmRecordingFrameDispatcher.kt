package com.camhub.studio.data

import android.graphics.Bitmap
import com.camhub.studio.data.gl.PgmTransitionFrame
import com.camhub.studio.data.ptz.HubPtzTransform

/** Sends each decoded PGM frame to the recorder once, independent of UI state emissions. */
class PgmRecordingFrameDispatcher(
    private val recorder: DirectorRecorder
) {
    private var cameraName: String? = null
    private var lastSequence = Long.MIN_VALUE

    @Synchronized
    fun dispatch(
        recording: Boolean,
        camera: String,
        frameSequence: Long,
        bitmap: Bitmap,
        ptzTransform: HubPtzTransform,
        transition: PgmTransitionFrame?
    ) {
        if (frameSequence <= 0L || bitmap.isRecycled) return
        if (cameraName != camera) {
            cameraName = camera
            lastSequence = Long.MIN_VALUE
        }
        if (frameSequence <= lastSequence) return
        lastSequence = frameSequence
        if (recording) recorder.onFrame(bitmap, ptzTransform, transition)
    }
}
