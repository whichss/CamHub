package com.camhub.studio.data.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.camhub.studio.data.ptz.HubPtzTransform
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Renders spatially upscaled frames directly into a MediaCodec input Surface. */
class SpatialUpscaleWindowRenderer(
    private val surface: Surface,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val onFrameRendered: (Boolean) -> Unit
) {
    private data class PendingFrame(
        val bitmap: Bitmap,
        val presentationTimeNs: Long,
        val ptzTransform: HubPtzTransform
    )

    private val latestFrame = AtomicReference<PendingFrame?>()
    private val renderQueued = AtomicBoolean(false)
    private val acceptingFrames = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var eglHelper: EglHelper? = null
    private var eglSurface: android.opengl.EGLSurface? = null
    private var glProgram: SpatialUpscaleGlProgram? = null

    fun start(timeoutMs: Long = START_TIMEOUT_MS): Boolean {
        val ready = CountDownLatch(1)
        val started = AtomicBoolean(false)
        val glThread = HandlerThread("PgmRecordingGL").also { it.start() }
        thread = glThread
        val glHandler = Handler(glThread.looper)
        handler = glHandler
        glHandler.post {
            try {
                val egl = EglHelper().also { it.init() }
                val windowSurface = egl.createWindowSurface(surface)
                egl.makeCurrent(windowSurface)
                val program = SpatialUpscaleGlProgram()
                if (!program.create()) throw IllegalStateException("Unable to create upscale shader")
                eglHelper = egl
                eglSurface = windowSurface
                glProgram = program
                acceptingFrames.set(true)
                started.set(true)
            } catch (e: Exception) {
                Log.e(TAG, "Recording GL initialization failed", e)
                releaseInternal()
            } finally {
                ready.countDown()
            }
        }
        if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS) || !started.get()) {
            release()
            return false
        }
        return true
    }

    fun render(
        bitmap: Bitmap,
        presentationTimeNs: Long,
        ptzTransform: HubPtzTransform
    ): Boolean {
        if (!acceptingFrames.get() || bitmap.isRecycled) return false
        latestFrame.set(PendingFrame(bitmap, presentationTimeNs, ptzTransform))
        if (renderQueued.compareAndSet(false, true)) {
            handler?.post { drainLatestFrame() }
        }
        return true
    }

    fun release(timeoutMs: Long = RELEASE_TIMEOUT_MS) {
        acceptingFrames.set(false)
        latestFrame.set(null)
        val released = CountDownLatch(1)
        val glHandler = handler
        if (glHandler != null) {
            glHandler.post {
                releaseInternal()
                released.countDown()
            }
            released.await(timeoutMs, TimeUnit.MILLISECONDS)
        } else {
            releaseInternal()
        }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun drainLatestFrame() {
        val frame = latestFrame.getAndSet(null)
        if (frame != null && acceptingFrames.get()) {
            var rendered = false
            val egl = eglHelper
            val target = eglSurface
            val program = glProgram
            if (egl != null && target != null && program != null) {
                try {
                    egl.makeCurrent(target)
                    if (program.render(
                            frame.bitmap,
                            outputWidth,
                            outputHeight,
                            frame.ptzTransform
                        )
                    ) {
                        egl.setPresentationTime(target, frame.presentationTimeNs)
                        if (egl.swapBuffers(target)) {
                            rendered = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Recording GL render failed", e)
                }
            }
            onFrameRendered(rendered)
        }

        if (latestFrame.get() != null && acceptingFrames.get()) {
            handler?.post { drainLatestFrame() }
        } else {
            renderQueued.set(false)
            if (
                latestFrame.get() != null &&
                acceptingFrames.get() &&
                renderQueued.compareAndSet(false, true)
            ) {
                handler?.post { drainLatestFrame() }
            }
        }
    }

    private fun releaseInternal() {
        try { glProgram?.release() } catch (_: Exception) {}
        glProgram = null
        val egl = eglHelper
        if (egl != null) {
            try {
                eglSurface?.let { EGL14.eglDestroySurface(egl.eglDisplay, it) }
            } catch (_: Exception) {}
            eglSurface = null
            try { egl.release() } catch (_: Exception) {}
        }
        eglHelper = null
        renderQueued.set(false)
    }

    companion object {
        private const val TAG = "SpatialUpscaleRecord"
        private const val START_TIMEOUT_MS = 1_000L
        private const val RELEASE_TIMEOUT_MS = 1_000L
    }
}
