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

/** Composites and optionally spatially reconstructs PGM frames into a MediaCodec input Surface. */
class SpatialUpscaleWindowRenderer(
    private val surface: Surface,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val enableSpatialReconstruction: Boolean = true,
    private val onFrameRendered: (Boolean) -> Unit
) {
    private data class PendingFrame(
        val bitmap: Bitmap?,
        val gpuFrame: SharedGpuVideoFrame?,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val presentationTimeNs: Long,
        val ptzTransform: HubPtzTransform,
        val transition: PgmTransitionFrame?
    )

    private val latestFrame = AtomicReference<PendingFrame?>()
    private val frameLock = Any()
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
        ptzTransform: HubPtzTransform,
        transition: PgmTransitionFrame? = null
    ): Boolean {
        if (bitmap.isRecycled) return false
        synchronized(frameLock) {
            if (!acceptingFrames.get()) return false
            val previous = latestFrame.getAndSet(
                PendingFrame(
                    bitmap = bitmap,
                    gpuFrame = null,
                    sourceWidth = bitmap.width,
                    sourceHeight = bitmap.height,
                    presentationTimeNs = presentationTimeNs,
                    ptzTransform = ptzTransform,
                    transition = transition
                )
            )
            previous?.gpuFrame?.close()
            scheduleFrame()
        }
        return true
    }

    /** Queues a shared-context 2D texture directly, with no intermediate Bitmap upload. */
    fun renderTexture(
        gpuFrame: SharedGpuVideoFrame,
        presentationTimeNs: Long,
        ptzTransform: HubPtzTransform,
        transition: PgmTransitionFrame? = null
    ): Boolean {
        synchronized(frameLock) {
            if (!acceptingFrames.get()) return false
            val previous = latestFrame.getAndSet(
                PendingFrame(
                    bitmap = null,
                    gpuFrame = gpuFrame,
                    sourceWidth = gpuFrame.width,
                    sourceHeight = gpuFrame.height,
                    presentationTimeNs = presentationTimeNs,
                    ptzTransform = ptzTransform,
                    transition = transition
                )
            )
            previous?.gpuFrame?.close()
            scheduleFrame()
        }
        return true
    }

    private fun scheduleFrame() {
        if (renderQueued.compareAndSet(false, true)) {
            handler?.post { drainLatestFrame() }
        }
    }

    fun release(timeoutMs: Long = RELEASE_TIMEOUT_MS) {
        synchronized(frameLock) {
            acceptingFrames.set(false)
        }
        val released = CountDownLatch(1)
        val glHandler = handler
        if (glHandler != null) {
            glHandler.post {
                latestFrame.getAndSet(null)?.gpuFrame?.close()
                releaseInternal()
                released.countDown()
            }
            released.await(timeoutMs, TimeUnit.MILLISECONDS)
        } else {
            latestFrame.getAndSet(null)?.gpuFrame?.close()
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
            var gpuReferenceReleased = false
            val egl = eglHelper
            val target = eglSurface
            val program = glProgram
            if (egl != null && target != null && program != null) {
                try {
                    egl.makeCurrent(target)
                    val drawSucceeded = if (frame.gpuFrame != null) {
                        frame.gpuFrame.waitForProducerCommands()
                        program.renderTexture(
                            sourceTextureId = frame.gpuFrame.textureId,
                            sourceWidth = frame.sourceWidth,
                            sourceHeight = frame.sourceHeight,
                            outputWidth = outputWidth,
                            outputHeight = outputHeight,
                            ptzTransform = frame.ptzTransform,
                            transition = frame.transition,
                            enableSpatialReconstruction = enableSpatialReconstruction
                        )
                    } else {
                        frame.bitmap?.let {
                            program.render(
                                it,
                                outputWidth,
                                outputHeight,
                                frame.ptzTransform,
                                frame.transition,
                                enableSpatialReconstruction
                            )
                        } ?: false
                    }
                    frame.gpuFrame?.releaseAfterCurrentGlCommands()
                    gpuReferenceReleased = frame.gpuFrame != null
                    if (drawSucceeded) {
                        egl.setPresentationTime(target, frame.presentationTimeNs)
                        if (egl.swapBuffers(target)) {
                            rendered = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Recording GL render failed", e)
                }
            }
            if (!gpuReferenceReleased) frame.gpuFrame?.close()
            onFrameRendered(rendered)
        } else {
            frame?.gpuFrame?.close()
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
