package com.camhub.studio.data

import android.graphics.Bitmap
import com.camhub.studio.data.gl.PgmTransitionFrame
import com.camhub.studio.data.gl.PgmGpuTransitionFrame
import com.camhub.studio.data.gl.SharedGpuVideoFrame
import com.camhub.studio.data.ptz.HubPtzTransform

/** Sends each decoded PGM frame to the recorder once, independent of UI state emissions. */
class PgmRecordingFrameDispatcher(
    private val recorder: DirectorRecorder
) {
    private val frameSequenceGate = FrameSequenceGate()

    @Synchronized
    fun dispatch(
        recording: Boolean,
        camera: String,
        frameSequence: Long,
        bitmap: Bitmap,
        ptzTransform: HubPtzTransform,
        transition: PgmTransitionFrame?
    ) {
        if (bitmap.isRecycled || !frameSequenceGate.accept(camera, frameSequence)) return
        if (recording) recorder.onFrame(bitmap, ptzTransform, transition)
    }

    /** Transfers the lease to the recorder only when it accepts the direct GPU path. */
    @Synchronized
    fun dispatchGpu(
        recording: Boolean,
        camera: String,
        frameSequence: Long,
        frame: SharedGpuVideoFrame,
        ptzTransform: HubPtzTransform,
        gpuTransition: PgmGpuTransitionFrame? = null
    ): Boolean {
        if (!recording || !frameSequenceGate.accept(camera, frameSequence)) {
            return false
        }
        if (!recorder.onSharedGpuFrame(frame, ptzTransform, gpuTransition)) return false
        return true
    }
}
