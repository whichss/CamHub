package com.camhub.studio.data.gl

import android.content.Context
import android.opengl.EGL14
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.util.Log
import com.camhub.studio.data.ptz.HubPtzTransform
import com.camhub.studio.data.ptz.HybridPtzController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Displays a decoder-owned shared texture without materializing a Bitmap. */
class SharedGpuVideoSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    private data class Pending(
        val frame: SharedGpuVideoFrame,
        val cameraName: String,
        val transform: HubPtzTransform,
        val enableSpatialReconstruction: Boolean,
        val onFrameDrawn: (String, Long, Long) -> Unit
    )

    private val lock = Any()
    private val pending = AtomicReference<Pending?>()
    private val renderPosted = AtomicBoolean(false)
    @Volatile private var acceptingFrames = false
    @Volatile private var cameraName: String = ""
    @Volatile private var ptzTransform = HybridPtzController.IDENTITY_TRANSFORM
    @Volatile private var enableSpatialReconstruction = false
    @Volatile private var drawnCallback: (String, Long, Long) -> Unit = { _, _, _ -> }
    @Volatile private var outputWidth = 1
    @Volatile private var outputHeight = 1
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var egl: EglHelper? = null
    private var eglSurface: android.opengl.EGLSurface? = null
    private var program: SpatialUpscaleGlProgram? = null

    init {
        holder.addCallback(this)
        setBackgroundColor(android.graphics.Color.BLACK)
    }

    /** Takes ownership of [frame] only when this returns true. */
    fun displayFrame(
        frame: SharedGpuVideoFrame,
        ptzTransform: HubPtzTransform = this.ptzTransform,
        onFrameDrawn: (String, Long, Long) -> Unit = drawnCallback
    ): Boolean = synchronized(lock) {
        val glHandler = handler
        if (!acceptingFrames || glHandler == null) return@synchronized false
        pending.getAndSet(
            Pending(frame, cameraName, ptzTransform, enableSpatialReconstruction, onFrameDrawn)
        )?.frame?.close()
        queueRender(glHandler)
        true
    }

    fun updateDisplayConfig(
        cameraName: String,
        transform: HubPtzTransform,
        enableSpatialReconstruction: Boolean = false,
        onFrameDrawn: (String, Long, Long) -> Unit = { _, _, _ -> }
    ) {
        this.cameraName = cameraName
        ptzTransform = transform
        this.enableSpatialReconstruction = enableSpatialReconstruction
        drawnCallback = onFrameDrawn
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val glThread = HandlerThread("SharedGpuPreviewGL").also { it.start() }
        val glHandler = Handler(glThread.looper)
        synchronized(lock) {
            thread = glThread
            handler = glHandler
            acceptingFrames = true
        }
        glHandler.post {
            try {
                val helper = EglHelper().also { it.init() }
                val window = helper.createWindowSurface(holder.surface)
                helper.makeCurrent(window)
                egl = helper
                eglSurface = window
                val renderer = SpatialUpscaleGlProgram()
                check(renderer.create()) { "Unable to initialize shared GPU preview shader" }
                program = renderer
                queueRender(glHandler)
            } catch (error: Exception) {
                Log.e(TAG, "Shared GPU preview initialization failed", error)
                pending.getAndSet(null)?.frame?.close()
                releaseGlResources()
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        outputWidth = width.coerceAtLeast(1)
        outputHeight = height.coerceAtLeast(1)
        handler?.let(::queueRender)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val glHandler: Handler?
        val glThread: HandlerThread?
        synchronized(lock) {
            acceptingFrames = false
            glHandler = handler
            glThread = thread
            handler = null
            thread = null
        }
        if (glHandler != null && glThread != null) {
            val finished = CountDownLatch(1)
            glHandler.post {
                pending.getAndSet(null)?.frame?.close()
                releaseGlResources()
                finished.countDown()
            }
            finished.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            glThread.quitSafely()
        } else {
            pending.getAndSet(null)?.frame?.close()
        }
    }

    private fun queueRender(glHandler: Handler) {
        if (renderPosted.compareAndSet(false, true)) {
            glHandler.post(::renderPending)
        }
    }

    private fun renderPending() {
        val item = pending.getAndSet(null)
        if (item != null) {
            var submitted = false
            try {
                val helper = egl
                val window = eglSurface
                val renderer = program
                if (helper != null && window != null && renderer != null) {
                    helper.makeCurrent(window)
                    item.frame.waitForProducerCommands()
                    val drawn = renderer.renderTexture(
                        sourceTextureId = item.frame.textureId,
                        sourceWidth = item.frame.width,
                        sourceHeight = item.frame.height,
                        outputWidth = outputWidth,
                        outputHeight = outputHeight,
                        ptzTransform = item.transform,
                        enableSpatialReconstruction = item.enableSpatialReconstruction,
                        sourceRotationDegrees = item.frame.rotationDegrees
                    )
                    item.frame.releaseAfterCurrentGlCommands()
                    submitted = true
                    if (drawn && helper.swapBuffers(window)) {
                        item.onFrameDrawn(item.cameraName, item.frame.frameSequence, android.os.SystemClock.elapsedRealtime())
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Shared GPU preview frame failed", error)
            } finally {
                if (!submitted) item.frame.close()
            }
        }

        renderPosted.set(false)
        val glHandler = handler
        if (pending.get() != null && acceptingFrames && glHandler != null) queueRender(glHandler)
    }

    private fun releaseGlResources() {
        try { program?.release() } catch (_: Exception) {}
        program = null
        val helper = egl
        if (helper != null) {
            try {
                eglSurface?.let { EGL14.eglDestroySurface(helper.eglDisplay, it) }
            } catch (_: Exception) {}
            eglSurface = null
            try { helper.release() } catch (_: Exception) {}
        }
        egl = null
        renderPosted.set(false)
    }

    companion object {
        private const val TAG = "SharedGpuPreview"
        private const val RELEASE_TIMEOUT_MS = 1_000L
    }
}
