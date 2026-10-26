package com.camhub.studio.data.gl

import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.camhub.studio.data.ptz.HubPtzTransform
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class PgmGpuCompositorMetrics(
    val submittedFrames: Long,
    val composedFrames: Long,
    val droppedFrames: Long,
    val texturePoolStarvations: Long
)

/** Composes PTZ, transitions and optional reconstruction once into a shared PGM texture. */
class PgmGpuCompositor {
    private data class Request(
        val source: SharedGpuVideoFrame,
        val transform: HubPtzTransform,
        val transition: PgmGpuTransitionFrame?,
        val outputWidth: Int,
        val outputHeight: Int,
        val enableSpatialReconstruction: Boolean,
        val onComposed: (SharedGpuVideoFrame) -> Unit
    )

    private val accepting = AtomicBoolean(false)
    private val pending = AtomicReference<Request?>(null)
    private val renderPosted = AtomicBoolean(false)
    private val submittedFrameCount = AtomicLong(0L)
    private val composedFrameCount = AtomicLong(0L)
    private val droppedFrameCount = AtomicLong(0L)
    private val texturePoolStarvationCount = AtomicLong(0L)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var egl: EglHelper? = null
    private var pbuffer: android.opengl.EGLSurface? = null
    private var fbo = 0
    private var program: SpatialUpscaleGlProgram? = null
    private var texturePool: SharedGpuTexturePool? = null
    private var poolWidth = 0
    private var poolHeight = 0

    fun metricsSnapshot() = PgmGpuCompositorMetrics(
        submittedFrames = submittedFrameCount.get(),
        composedFrames = composedFrameCount.get(),
        droppedFrames = droppedFrameCount.get(),
        texturePoolStarvations = texturePoolStarvationCount.get()
    )

    fun start(timeoutMs: Long = START_TIMEOUT_MS): Boolean {
        if (accepting.get()) return true
        val ready = CountDownLatch(1)
        val started = AtomicBoolean(false)
        val glThread = HandlerThread("PgmCompositorGL").also { it.start() }
        thread = glThread
        val glHandler = Handler(glThread.looper)
        handler = glHandler
        glHandler.post {
            try {
                val helper = EglHelper().also { it.init() }
                egl = helper
                val offscreen = helper.createPbufferSurface(16, 16)
                pbuffer = offscreen
                helper.makeCurrent(offscreen)
                val shader = SpatialUpscaleGlProgram()
                check(shader.create()) { "Unable to initialize PGM compositor shader" }
                val framebuffer = IntArray(1)
                GLES20.glGenFramebuffers(1, framebuffer, 0)
                check(framebuffer[0] != 0) { "Unable to create PGM compositor framebuffer" }
                program = shader
                fbo = framebuffer[0]
                accepting.set(true)
                started.set(true)
            } catch (error: Exception) {
                Log.e(TAG, "PGM compositor initialization failed", error)
                releaseGlResources()
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

    /** Takes ownership of source and transition leases; replaces stale queued work. */
    fun submit(
        source: SharedGpuVideoFrame,
        transform: HubPtzTransform,
        transition: PgmGpuTransitionFrame?,
        outputWidth: Int,
        outputHeight: Int,
        enableSpatialReconstruction: Boolean,
        onComposed: (SharedGpuVideoFrame) -> Unit
    ): Boolean {
        val glHandler = handler
        if (!accepting.get() || glHandler == null) return false
        submittedFrameCount.incrementAndGet()
        pending.getAndSet(
            Request(
                source = source,
                transform = transform,
                transition = transition,
                outputWidth = outputWidth.coerceAtLeast(2) and 0x7ffffffe,
                outputHeight = outputHeight.coerceAtLeast(2) and 0x7ffffffe,
                enableSpatialReconstruction = enableSpatialReconstruction,
                onComposed = onComposed
            )
        )?.let { stale ->
            stale.closeLeases()
            droppedFrameCount.incrementAndGet()
        }
        schedule(glHandler)
        return true
    }

    private fun schedule(glHandler: Handler) {
        if (renderPosted.compareAndSet(false, true)) glHandler.post(::drainLatest)
    }

    private fun drainLatest() {
        try {
            val request = pending.getAndSet(null) ?: return
            render(request)
        } finally {
            renderPosted.set(false)
            if (pending.get() != null) handler?.let(::schedule)
        }
    }

    private fun render(request: Request) {
        var slot: SharedGpuTexturePool.Slot? = null
        var sourceReleased = false
        var transitionReleased = false
        var outputComposed = false
        try {
            val helper = egl ?: return
            val surface = pbuffer ?: return
            val shader = program ?: return
            helper.makeCurrent(surface)
            ensureTexturePool(request.outputWidth, request.outputHeight)
            val writeSlot = texturePool?.acquireForWrite()
            if (writeSlot == null) {
                texturePoolStarvationCount.incrementAndGet()
                return
            }
            slot = writeSlot

            request.source.waitForProducerCommands()
            request.transition?.frame?.waitForProducerCommands()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                writeSlot.textureId,
                0
            )
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                "PGM compositor framebuffer is incomplete"
            }
            val rendered = shader.renderTexture(
                sourceTextureId = request.source.textureId,
                sourceWidth = request.source.width,
                sourceHeight = request.source.height,
                outputWidth = request.outputWidth,
                outputHeight = request.outputHeight,
                ptzTransform = request.transform,
                enableSpatialReconstruction = request.enableSpatialReconstruction,
                sourceRotationDegrees = request.source.rotationDegrees,
                gpuTransition = request.transition
            )
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            request.source.releaseAfterCurrentGlCommands()
            sourceReleased = true
            request.transition?.frame?.releaseAfterCurrentGlCommands()
            transitionReleased = request.transition != null
            if (!rendered) return

            val composed = texturePool?.publish(
                slot = writeSlot,
                width = request.outputWidth,
                height = request.outputHeight,
                frameSequence = request.source.frameSequence,
                presentationTimeUs = request.source.presentationTimeUs,
                decodedAtElapsedMs = request.source.decodedAtElapsedMs
            ) ?: return
            slot = null
            outputComposed = true
            composedFrameCount.incrementAndGet()
            try {
                request.onComposed(composed)
            } catch (error: Throwable) {
                composed.close()
                throw error
            }
        } catch (error: Exception) {
            Log.w(TAG, "PGM composition skipped: ${error.message}")
        } finally {
            if (!outputComposed) droppedFrameCount.incrementAndGet()
            if (!sourceReleased) request.source.close()
            if (request.transition != null && !transitionReleased) request.transition.frame.close()
            slot?.let { texturePool?.abandonWrite(it) }
        }
    }

    private fun ensureTexturePool(width: Int, height: Int) {
        if (texturePool != null && poolWidth == width && poolHeight == height) return
        texturePool?.close()
        val textures = IntArray(POOL_SIZE)
        GLES30.glGenTextures(textures.size, textures, 0)
        textures.forEach { texture ->
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
            )
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        texturePool = SharedGpuTexturePool(textures)
        poolWidth = width
        poolHeight = height
    }

    fun release(timeoutMs: Long = RELEASE_TIMEOUT_MS) {
        accepting.set(false)
        pending.getAndSet(null)?.let { stale ->
            stale.closeLeases()
            droppedFrameCount.incrementAndGet()
        }
        val glHandler = handler
        val glThread = thread
        if (glHandler != null && glThread != null) {
            val released = CountDownLatch(1)
            glHandler.post {
                releaseGlResources()
                released.countDown()
            }
            released.await(timeoutMs, TimeUnit.MILLISECONDS)
            glThread.quitSafely()
        } else {
            releaseGlResources()
        }
        handler = null
        thread = null
    }

    private fun releaseGlResources() {
        texturePool?.close()
        texturePool = null
        poolWidth = 0
        poolHeight = 0
        if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        fbo = 0
        program?.release()
        program = null
        val helper = egl
        if (helper != null) {
            pbuffer?.let { EGL14.eglDestroySurface(helper.eglDisplay, it) }
            pbuffer = null
            helper.release()
        }
        egl = null
    }

    private fun Request.closeLeases() {
        source.close()
        transition?.frame?.close()
    }

    companion object {
        private const val TAG = "PgmGpuCompositor"
        private const val POOL_SIZE = 4
        private const val START_TIMEOUT_MS = 2_000L
        private const val RELEASE_TIMEOUT_MS = 1_000L
    }
}
