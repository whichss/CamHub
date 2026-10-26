package com.camhub.studio.data

import android.graphics.Bitmap
import android.os.SystemClock
import com.camhub.studio.data.gl.PgmGpuTransitionFrame
import com.camhub.studio.data.gl.PgmTransitionFrame
import com.camhub.studio.data.gl.PgmTransitionMode
import com.camhub.studio.data.gl.SharedGpuVideoFrame
import com.camhub.studio.data.ptz.HubPtzTransform
import java.util.concurrent.ConcurrentHashMap

/** Coordinates PGM recording inputs independently from UI StateFlow emissions. */
class PgmOutputManager(
    recorder: DirectorRecorder,
    private val externalDisplayManager: ExternalDisplayManager
) {
    private val recordingFrames = PgmRecordingFrameDispatcher(recorder)
    private val latestGpuFrames = ConcurrentHashMap<String, SharedGpuVideoFrame>()
    private val externalBitmapSequenceGate = FrameSequenceGate()
    private val externalGpuSequenceGate = FrameSequenceGate()

    fun dispatchBitmap(
        recording: Boolean,
        cameraName: String,
        frameSequence: Long,
        bitmap: Bitmap,
        ptzTransform: HubPtzTransform,
        transition: PgmTransitionFrame?
    ) = recordingFrames.dispatch(
        recording,
        cameraName,
        frameSequence,
        bitmap,
        ptzTransform,
        transition
    )

    fun dispatchExternalBitmap(
        cameraName: String,
        frameSequence: Long,
        bitmap: Bitmap,
        enableSpatialUpscaling: Boolean,
        spatialUpscaleOutputHeight: Int,
        ptzTransform: HubPtzTransform,
        transition: PgmTransitionFrame?,
        onFrameDrawn: (String, Long, Long) -> Unit
    ): Boolean {
        if (!externalDisplayManager.isOutputEnabled.value || frameSequence <= 0L || bitmap.isRecycled) {
            return false
        }
        if (!externalBitmapSequenceGate.accept(cameraName, frameSequence)) return false
        externalDisplayManager.updateFrame(
            bitmap = bitmap,
            cameraName = cameraName,
            frameSequence = frameSequence,
            enableSpatialUpscaling = enableSpatialUpscaling,
            spatialUpscaleOutputHeight = spatialUpscaleOutputHeight,
            ptzTransform = ptzTransform,
            transition = transition,
            onFrameDrawn = onFrameDrawn
        )
        return true
    }

    /** Retains the main texture and transfers it, plus the optional transition lease, to output. */
    fun dispatchExternalGpu(
        frame: SharedGpuVideoFrame,
        cameraName: String,
        ptzTransform: HubPtzTransform,
        enableSpatialReconstruction: Boolean,
        transition: PgmGpuTransitionFrame?,
        onFrameDrawn: (String, Long, Long) -> Unit
    ): Boolean {
        if (!externalDisplayManager.isOutputEnabled.value) {
            transition?.frame?.close()
            return false
        }
        if (!externalGpuSequenceGate.accept(cameraName, frame.frameSequence)) {
            transition?.frame?.close()
            return false
        }
        val lease = runCatching { frame.retain() }.getOrNull()
        if (lease == null) {
            transition?.frame?.close()
            return false
        }
        externalDisplayManager.updateGpuFrame(
            frame = lease,
            cameraName = cameraName,
            ptzTransform = ptzTransform,
            enableSpatialReconstruction = enableSpatialReconstruction,
            transition = transition,
            onFrameDrawn = onFrameDrawn
        )
        return true
    }

    fun cacheGpuFrame(cameraName: String, frame: SharedGpuVideoFrame) {
        val lease = runCatching { frame.retain() }.getOrNull() ?: return
        latestGpuFrames.put(cameraName, lease)?.close()
    }

    fun retainFreshTransitionFrame(
        cameraName: String,
        ptzTransform: HubPtzTransform,
        mode: PgmTransitionMode,
        progress: Float,
        maxAgeMs: Long = DEFAULT_TRANSITION_FRAME_MAX_AGE_MS
    ): PgmGpuTransitionFrame? {
        val incoming = latestGpuFrames[cameraName]
            ?.takeIf { SystemClock.elapsedRealtime() - it.decodedAtElapsedMs <= maxAgeMs }
            ?: return null
        val lease = runCatching { incoming.retain() }.getOrNull() ?: return null
        return PgmGpuTransitionFrame(
            frame = lease,
            cameraName = cameraName,
            frameSequence = incoming.frameSequence,
            ptzTransform = ptzTransform,
            mode = mode,
            progress = progress
        )
    }

    fun discardGpuFramesExcept(cameraName: String?) {
        latestGpuFrames.keys
            .filter { it != cameraName }
            .forEach { stale -> latestGpuFrames.remove(stale)?.close() }
    }

    fun dispatchGpu(
        recording: Boolean,
        cameraName: String,
        frameSequence: Long,
        frame: SharedGpuVideoFrame,
        ptzTransform: HubPtzTransform,
        transition: PgmGpuTransitionFrame? = null
    ): Boolean = recordingFrames.dispatchGpu(
        recording,
        cameraName,
        frameSequence,
        frame,
        ptzTransform,
        transition
    )

    fun release() {
        recordingFrames.reset()
        latestGpuFrames.values.forEach { runCatching { it.close() } }
        latestGpuFrames.clear()
        externalBitmapSequenceGate.reset()
        externalGpuSequenceGate.reset()
    }

    companion object {
        private const val DEFAULT_TRANSITION_FRAME_MAX_AGE_MS = 250L
    }
}
