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
class PgmOutputManager(recorder: DirectorRecorder) {
    private val recordingFrames = PgmRecordingFrameDispatcher(recorder)
    private val latestGpuFrames = ConcurrentHashMap<String, SharedGpuVideoFrame>()

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
        latestGpuFrames.values.forEach { runCatching { it.close() } }
        latestGpuFrames.clear()
    }

    companion object {
        private const val DEFAULT_TRANSITION_FRAME_MAX_AGE_MS = 250L
    }
}
