package com.camhub.studio.data

import android.graphics.Bitmap
import android.os.SystemClock
import com.camhub.studio.data.gl.PgmTransitionFrame
import com.camhub.studio.data.gl.PgmGpuTransitionFrame
import com.camhub.studio.data.gl.SharedGpuVideoFrame
import com.camhub.studio.data.ptz.HubPtzTransform

/** Sends each decoded PGM frame to the recorder once, independent of UI state emissions. */
class PgmRecordingFrameDispatcher(
    private val recorder: DirectorRecorder
) {
    private val frameSequenceGate = FrameSequenceGate()
    private val sourceArbiter = PgmFrameSourceArbiter()

    @Synchronized
    fun dispatch(
        recording: Boolean,
        camera: String,
        frameSequence: Long,
        bitmap: Bitmap,
        ptzTransform: HubPtzTransform,
        transition: PgmTransitionFrame?
    ) {
        if (!recording || bitmap.isRecycled) return
        if (!sourceArbiter.allowBitmapFallback(camera, SystemClock.elapsedRealtime())) return
        if (!frameSequenceGate.accept(camera, frameSequence)) return
        recorder.onFrame(bitmap, ptzTransform, transition)
    }

    /** Transfers the lease to the recorder only when it accepts the direct GPU path. */
    @Synchronized
    fun dispatchGpu(
        recording: Boolean,
        camera: String,
        frameSequence: Long,
        frame: SharedGpuVideoFrame,
        ptzTransform: HubPtzTransform,
        gpuTransition: PgmGpuTransitionFrame? = null,
        enableSpatialReconstruction: Boolean? = null
    ): Boolean {
        if (!recording || !frameSequenceGate.accept(camera, frameSequence)) {
            return false
        }
        if (!recorder.onSharedGpuFrame(
                frame,
                ptzTransform,
                gpuTransition,
                enableSpatialReconstruction
            )
        ) return false
        sourceArbiter.noteGpuAccepted(camera, SystemClock.elapsedRealtime())
        return true
    }

    @Synchronized
    fun reset() {
        frameSequenceGate.reset()
        sourceArbiter.reset()
    }
}
