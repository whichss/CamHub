package com.camhub.studio.data.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class DecoderRenderMetrics(
    val renderedFrames: Long,
    val texturePoolStarvations: Long,
    val bitmapReadsSubmitted: Long,
    val bitmapReadsCompleted: Long,
    val bitmapReadsDropped: Long,
    /** GL submission-to-fence wall time (includes queued work, not pure shader time). */
    val gpuCompletionP95Us: Int,
    val gpuCompletionSampleCount: Int
)

data class RenderedVideoFrame(
    val bitmap: Bitmap,
    val frameSequence: Long,
    val presentationTimeUs: Long,
    val decodedAtElapsedMs: Long,
    val readyAtElapsedMs: Long
)

/**
 * Renders H.264 decoder output (via SurfaceTexture) to an offscreen FBO
 * and reads pixels back to a Bitmap using PBO double-buffering for async readback.
 *
 * PBO async readback eliminates the 20-50ms GPU→CPU stall of synchronous glReadPixels.
 * Two PBOs ping-pong: while the GPU DMA-transfers the current frame into PBO_A,
 * the CPU maps PBO_B (previous frame, already transferred) with zero wait.
 */
class DecoderGlRenderer(
    val width: Int,
    val height: Int,
    private val threadName: String = "DecoderGL"
) {

    companion object {
        private const val TAG = "DecoderGlRenderer"
        private const val BITMAP_POOL_SIZE = 3
        private const val SHARED_TEXTURE_POOL_SIZE = 3
        private const val SHARED_GPU_BITMAP_FPS = 1
        private const val GPU_COMPLETION_SAMPLE_INTERVAL = 10L
        private const val GPU_COMPLETION_MAX_PENDING = 8
        private const val GPU_COMPLETION_WINDOW_SIZE = 120

        private val QUAD_COORDS = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        )
        // Flip Y for glReadPixels (bottom-up → top-down)
        private val TEX_COORDS = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )
    }

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglHelper: EglHelper? = null
    private var pbufferSurface: android.opengl.EGLSurface? = null

    private var oesTexId = 0
    private var program = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)

    private var fbo = 0
    private var sharedTexturePool: SharedGpuTexturePool? = null
    private var scratchTexture = 0
    private var frameSequence = 0L

    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null

    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTexMatrixLoc = 0

    // PBO double-buffering for async pixel readback
    private val pbos = IntArray(2)
    private var pboIndex = 0
    private val pboFences = LongArray(2)
    private val pboHasPendingRead = BooleanArray(2)
    private val pboPresentationTimeUs = LongArray(2)
    private val pboDecodedAtElapsedMs = LongArray(2)
    private val pboFrameSequences = LongArray(2)
    private val pixelByteSize get() = width * height * 4

    private val reusableBitmaps = arrayOfNulls<Bitmap>(BITMAP_POOL_SIZE)
    private var reusableBitmapIndex = 0
    private val renderQueued = AtomicBoolean(false)
    private val renderAgain = AtomicBoolean(false)
    private val renderedFrameCount = AtomicLong(0L)
    private val texturePoolStarvationCount = AtomicLong(0L)
    private val bitmapReadSubmittedCount = AtomicLong(0L)
    private val bitmapReadCompletedCount = AtomicLong(0L)
    private val bitmapReadDroppedCount = AtomicLong(0L)
    private data class GpuCompletionSample(val fence: Long, val submittedAtNs: Long)
    private val gpuCompletionFences = ArrayDeque<GpuCompletionSample>()
    private val gpuCompletionDurationsUs = ArrayDeque<Int>()
    private val gpuCompletionP95Us = AtomicInteger(0)
    private val gpuCompletionSampleCount = AtomicInteger(0)
    @Volatile private var outputFpsLimit = 30
    @Volatile private var sharedGpuOutputEnabled = false
    @Volatile private var bitmapOutputEnabled = true
    @Volatile private var bitmapOutputFpsLimit = 30
    private var lastRenderAtElapsedMs = 0L
    private var lastReadbackAtElapsedMs = 0L

    var surface: Surface? = null
        private set

    var onBitmapReady: ((RenderedVideoFrame) -> Unit)? = null
    /** Ownership of each emitted lease transfers to the callback. */
    var onGpuFrameReady: ((SharedGpuVideoFrame) -> Unit)? = null

    fun metricsSnapshot() = DecoderRenderMetrics(
        renderedFrames = renderedFrameCount.get(),
        texturePoolStarvations = texturePoolStarvationCount.get(),
        bitmapReadsSubmitted = bitmapReadSubmittedCount.get(),
        bitmapReadsCompleted = bitmapReadCompletedCount.get(),
        bitmapReadsDropped = bitmapReadDroppedCount.get(),
        gpuCompletionP95Us = gpuCompletionP95Us.get(),
        gpuCompletionSampleCount = gpuCompletionSampleCount.get()
    )

    /** Limits GL output cadence without slowing MediaCodec decode. */
    fun setOutputFpsLimit(fps: Int) {
        outputFpsLimit = fps.coerceIn(1, 60)
        updateBitmapOutputCadence()
    }

    /** Enables/disables Bitmap readback based on active CPU-side consumers. */
    fun setBitmapOutputEnabled(enabled: Boolean) {
        bitmapOutputEnabled = enabled
        updateBitmapOutputCadence()
    }

    /** Enables shared GPU output and applies a low-rate Bitmap fallback when it is demanded. */
    fun setSharedGpuOutputEnabled(enabled: Boolean) {
        sharedGpuOutputEnabled = enabled
        updateBitmapOutputCadence()
        glHandler?.post {
            if (enabled && sharedTexturePool == null && glThread != null) {
                sharedTexturePool = createSharedTexturePool()
            } else if (!enabled) {
                sharedTexturePool?.close()
                sharedTexturePool = null
            }
        }
    }

    private fun updateBitmapOutputCadence() {
        bitmapOutputFpsLimit = when {
            !bitmapOutputEnabled -> 0
            sharedGpuOutputEnabled -> SHARED_GPU_BITMAP_FPS
            else -> outputFpsLimit
        }
    }

    fun start() {
        val thread = HandlerThread(threadName).also { it.start() }
        glThread = thread
        val handler = Handler(thread.looper)
        glHandler = handler

        handler.post {
            try {
                val egl = EglHelper()
                egl.init()
                eglHelper = egl

                pbufferSurface = egl.createPbufferSurface(width, height)
                egl.makeCurrent(pbufferSurface!!)

                program = EglHelper.createOesProgram()
                if (program == 0) throw RuntimeException("Failed to create OES program")

                aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
                aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
                uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")

                vertexBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer().put(QUAD_COORDS).also { it.position(0) }
                texCoordBuffer = ByteBuffer.allocateDirect(TEX_COORDS.size * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer().put(TEX_COORDS).also { it.position(0) }

                // Render decoded frames into a shareable texture. The same GL object can be
                // sampled by a PGM encoder context in the EglHelper share group; PBO readback
                // remains temporarily enabled for the existing multiview Bitmap consumers.
                val fbos = IntArray(1)
                GLES20.glGenFramebuffers(1, fbos, 0)
                fbo = fbos[0]

                if (sharedGpuOutputEnabled) sharedTexturePool = createSharedTexturePool()
                scratchTexture = createRgbaTexture()
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, scratchTexture, 0)

                val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    throw RuntimeException("FBO incomplete: 0x${Integer.toHexString(status)}")
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

                // Initialize two PBOs for async pixel readback (ping-pong)
                GLES30.glGenBuffers(2, pbos, 0)
                for (i in 0..1) {
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[i])
                    GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, pixelByteSize, null, GLES30.GL_STREAM_READ)
                }
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
                pboIndex = 0
                pboHasPendingRead.fill(false)

                // Create OES texture + SurfaceTexture for decoder output
                oesTexId = EglHelper.createTexture()
                val st = SurfaceTexture(oesTexId)
                st.setDefaultBufferSize(width, height)
                surfaceTexture = st
                surface = Surface(st)

                st.setOnFrameAvailableListener({ _ ->
                    scheduleRender()
                }, handler)

                Log.d(TAG, "DecoderGlRenderer started: ${width}x${height} (PBO async readback)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start DecoderGlRenderer", e)
                releaseInternal()
            }
        }
    }

    fun release() {
        glHandler?.post { releaseInternal() }
        glThread?.quitSafely()
        glThread = null
        glHandler = null
    }

    private fun scheduleRender() {
        val handler = glHandler ?: return
        if (renderQueued.compareAndSet(false, true)) {
            handler.post { processRenderRequest() }
        } else {
            // Coalesce bursts into one extra pass instead of queueing every old frame.
            renderAgain.set(true)
        }
    }

    private fun processRenderRequest() {
        renderAgain.set(false)
        renderAndReadBitmap()

        if (renderAgain.getAndSet(false)) {
            glHandler?.post { processRenderRequest() }
        } else {
            renderQueued.set(false)
            // Close the tiny race where a frame arrived after the check but before queued=false.
            if (renderAgain.getAndSet(false) && renderQueued.compareAndSet(false, true)) {
                glHandler?.post { processRenderRequest() }
            }
        }
    }

    private fun renderAndReadBitmap() {
        val egl = eglHelper ?: return
        val st = surfaceTexture ?: return
        val pbuf = pbufferSurface ?: return

        var acquiredSlot: SharedGpuTexturePool.Slot? = null
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            val presentationTimeUs = st.timestamp / 1_000L
            val decodedAtElapsedMs = SystemClock.elapsedRealtime()
            val minimumRenderIntervalMs = 1_000L / outputFpsLimit.coerceAtLeast(1)
            if (decodedAtElapsedMs - lastRenderAtElapsedMs < minimumRenderIntervalMs) {
                return
            }
            lastRenderAtElapsedMs = decodedAtElapsedMs
            val minimumReadbackIntervalMs = 1_000L / bitmapOutputFpsLimit.coerceAtLeast(1)
            val readbackDue = bitmapOutputFpsLimit > 0 &&
                decodedAtElapsedMs - lastReadbackAtElapsedMs >= minimumReadbackIntervalMs
            if (readbackDue) lastReadbackAtElapsedMs = decodedAtElapsedMs
            // With no shared texture consumer, render only on frames that will be
            // read back for an explicit Bitmap consumer.
            if (!sharedGpuOutputEnabled && !readbackDue) return
            egl.makeCurrent(pbuf)
            acquiredSlot = if (sharedGpuOutputEnabled) sharedTexturePool?.acquireForWrite() else null
            if (sharedGpuOutputEnabled && acquiredSlot == null) {
                texturePoolStarvationCount.incrementAndGet()
            }
            // When every shared texture is still leased, skip this frame unless a CPU
            // consumer explicitly needs a Bitmap. Drawing into scratchTexture without
            // either consumer would spend GPU time only to discard the result.
            if (sharedGpuOutputEnabled && acquiredSlot == null && !readbackDue) return
            val nextSequence = ++frameSequence
            val renderTexture = acquiredSlot?.textureId ?: scratchTexture

            // Render OES texture to FBO
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                renderTexture,
                0
            )
            GLES20.glViewport(0, 0, width, height)

            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)

            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

            GLES20.glEnableVertexAttribArray(aPositionLoc)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            GLES20.glEnableVertexAttribArray(aTexCoordLoc)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            renderedFrameCount.incrementAndGet()
            sampleGpuCompletion()

            // Read from the rendered FBO while it is still bound. Reading after the
            // unbind would sample the pbuffer's default framebuffer instead.
            drainReadyBitmapReadback()
            if (readbackDue) {
                val writeIndex = when {
                    !pboHasPendingRead[pboIndex] -> pboIndex
                    !pboHasPendingRead[1 - pboIndex] -> 1 - pboIndex
                    else -> -1
                }
                if (writeIndex >= 0) {
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[writeIndex])
                    GLES30.glReadPixels(
                        0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0
                    )
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
                    pboPresentationTimeUs[writeIndex] = presentationTimeUs
                    pboDecodedAtElapsedMs[writeIndex] = decodedAtElapsedMs
                    pboFrameSequences[writeIndex] = nextSequence
                    val fence = GLES30.glFenceSync(
                        GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0
                    )
                    pboFences[writeIndex] = fence
                    pboHasPendingRead[writeIndex] = fence != 0L
                    if (fence == 0L) bitmapReadDroppedCount.incrementAndGet()
                    else bitmapReadSubmittedCount.incrementAndGet()
                    pboIndex = 1 - writeIndex
                    GLES30.glFlush()
                } else {
                    bitmapReadDroppedCount.incrementAndGet()
                }
            }

            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            acquiredSlot?.let { slot ->
                val gpuFrame = sharedTexturePool?.publish(
                    slot = slot,
                    width = width,
                    height = height,
                    frameSequence = nextSequence,
                    presentationTimeUs = presentationTimeUs,
                    decodedAtElapsedMs = decodedAtElapsedMs
                )
                acquiredSlot = null
                if (gpuFrame != null) {
                    try {
                        onGpuFrameReady?.invoke(gpuFrame) ?: gpuFrame.close()
                    } catch (error: Throwable) {
                        gpuFrame.close()
                        throw error
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "renderAndReadBitmap error", e)
        } finally {
            acquiredSlot?.let { sharedTexturePool?.abandonWrite(it) }
        }
    }

    private fun drainReadyBitmapReadback() {
        var readyIndex = -1
        for (index in pbos.indices) {
            if (!pboHasPendingRead[index]) continue
            val fence = pboFences[index]
            if (fence == 0L) continue
            when (GLES30.glClientWaitSync(fence, 0, 0L)) {
                GLES30.GL_ALREADY_SIGNALED, GLES30.GL_CONDITION_SATISFIED -> {
                    if (readyIndex < 0 || pboFrameSequences[index] > pboFrameSequences[readyIndex]) {
                        readyIndex = index
                    }
                }
                GLES30.GL_WAIT_FAILED -> {
                    GLES30.glDeleteSync(fence)
                    pboFences[index] = 0L
                    pboHasPendingRead[index] = false
                    bitmapReadDroppedCount.incrementAndGet()
                }
            }
        }
        if (readyIndex < 0) return

        // This output is latest-frame oriented: release older completed reads instead
        // of converting stale frames into Bitmaps after a GPU backlog.
        for (index in pbos.indices) {
            if (index == readyIndex || !pboHasPendingRead[index]) continue
            val fence = pboFences[index]
            if (fence == 0L) continue
            val status = GLES30.glClientWaitSync(fence, 0, 0L)
            if (status == GLES30.GL_ALREADY_SIGNALED || status == GLES30.GL_CONDITION_SATISFIED) {
                GLES30.glDeleteSync(fence)
                pboFences[index] = 0L
                pboHasPendingRead[index] = false
            }
        }

        val fence = pboFences[readyIndex]
        if (fence != 0L) GLES30.glDeleteSync(fence)
        pboFences[readyIndex] = 0L
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[readyIndex])
        val mappedBuffer = GLES30.glMapBufferRange(
            GLES30.GL_PIXEL_PACK_BUFFER, 0, pixelByteSize, GLES30.GL_MAP_READ_BIT
        )
        if (mappedBuffer is ByteBuffer) {
            try {
                val bitmap = nextReusableBitmap()
                bitmap.copyPixelsFromBuffer(mappedBuffer)
                bitmapReadCompletedCount.incrementAndGet()
                onBitmapReady?.invoke(
                    RenderedVideoFrame(
                        bitmap = bitmap,
                        frameSequence = pboFrameSequences[readyIndex],
                        presentationTimeUs = pboPresentationTimeUs[readyIndex],
                        decodedAtElapsedMs = pboDecodedAtElapsedMs[readyIndex],
                        readyAtElapsedMs = SystemClock.elapsedRealtime()
                    )
                )
            } finally {
                GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
                pboHasPendingRead[readyIndex] = false
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            }
        } else {
            pboHasPendingRead[readyIndex] = false
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            bitmapReadDroppedCount.incrementAndGet()
        }
    }

    /** Polls sampled frame fences without blocking the decoder GL thread. */
    private fun sampleGpuCompletion() {
        val iterator = gpuCompletionFences.iterator()
        while (iterator.hasNext()) {
            val sample = iterator.next()
            when (GLES30.glClientWaitSync(sample.fence, 0, 0L)) {
                GLES30.GL_ALREADY_SIGNALED, GLES30.GL_CONDITION_SATISFIED -> {
                    GLES30.glDeleteSync(sample.fence)
                    val elapsedUs = ((SystemClock.elapsedRealtimeNanos() - sample.submittedAtNs)
                        .coerceAtLeast(0L) / 1_000L).toInt()
                    gpuCompletionDurationsUs.addLast(elapsedUs)
                    while (gpuCompletionDurationsUs.size > GPU_COMPLETION_WINDOW_SIZE) {
                        gpuCompletionDurationsUs.removeFirst()
                    }
                    val sorted = gpuCompletionDurationsUs.sorted()
                    val p95Index = ((sorted.size * 95 + 99) / 100 - 1)
                        .coerceIn(0, sorted.lastIndex)
                    gpuCompletionP95Us.set(sorted[p95Index])
                    gpuCompletionSampleCount.set(sorted.size)
                    iterator.remove()
                }
                GLES30.GL_WAIT_FAILED -> {
                    GLES30.glDeleteSync(sample.fence)
                    iterator.remove()
                }
            }
        }

        if (renderedFrameCount.get() % GPU_COMPLETION_SAMPLE_INTERVAL != 0L ||
            gpuCompletionFences.size >= GPU_COMPLETION_MAX_PENDING
        ) return
        val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        if (fence == 0L) return
        gpuCompletionFences.addLast(
            GpuCompletionSample(fence, SystemClock.elapsedRealtimeNanos())
        )
        GLES30.glFlush()
    }

    private fun createRgbaTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        configureRgbaTexture(textures[0])
        return textures[0]
    }

    private fun createSharedTexturePool(): SharedGpuTexturePool {
        val sharedTextures = IntArray(SHARED_TEXTURE_POOL_SIZE)
        GLES20.glGenTextures(sharedTextures.size, sharedTextures, 0)
        sharedTextures.forEach(::configureRgbaTexture)
        return SharedGpuTexturePool(sharedTextures)
    }

    private fun configureRgbaTexture(textureId: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun releaseInternal() {
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null
        surface = null

        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            fbo = 0
        }
        sharedTexturePool?.close()
        sharedTexturePool = null
        if (scratchTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(scratchTexture), 0)
            scratchTexture = 0
        }
        if (pbos[0] != 0 || pbos[1] != 0) {
            pboFences.forEach { fence -> if (fence != 0L) GLES30.glDeleteSync(fence) }
            pboFences.fill(0L)
            pboHasPendingRead.fill(false)
            GLES30.glDeleteBuffers(2, pbos, 0)
            pbos[0] = 0
            pbos[1] = 0
        }
        gpuCompletionFences.forEach { GLES30.glDeleteSync(it.fence) }
        gpuCompletionFences.clear()
        gpuCompletionDurationsUs.clear()
        gpuCompletionP95Us.set(0)
        gpuCompletionSampleCount.set(0)
        if (oesTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTexId), 0)
            oesTexId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }

        val egl = eglHelper
        if (egl != null) {
            pbufferSurface?.let { EGL14.eglDestroySurface(egl.eglDisplay, it) }
            pbufferSurface = null
            egl.release()
        }
        eglHelper = null
        pboFences.fill(0L)
        pboHasPendingRead.fill(false)
        pboIndex = 0
        pboPresentationTimeUs.fill(0L)
        pboDecodedAtElapsedMs.fill(0L)
        pboFrameSequences.fill(0L)
        renderQueued.set(false)
        renderAgain.set(false)
        lastRenderAtElapsedMs = 0L
        lastReadbackAtElapsedMs = 0L
        for (i in reusableBitmaps.indices) {
            reusableBitmaps[i]?.recycle()
            reusableBitmaps[i] = null
        }
        reusableBitmapIndex = 0
        onBitmapReady = null
        onGpuFrameReady = null
        Log.d(TAG, "DecoderGlRenderer released")
    }

    private fun nextReusableBitmap(): Bitmap {
        val index = reusableBitmapIndex
        reusableBitmapIndex = (reusableBitmapIndex + 1) % BITMAP_POOL_SIZE
        return reusableBitmaps[index]
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                .also { reusableBitmaps[index] = it }
    }
}
