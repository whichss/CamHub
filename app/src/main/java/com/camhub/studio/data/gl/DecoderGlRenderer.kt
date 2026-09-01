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
import java.util.concurrent.atomic.AtomicBoolean

data class RenderedVideoFrame(
    val bitmap: Bitmap,
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
    private var renderBuffer = 0

    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null

    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTexMatrixLoc = 0

    // PBO double-buffering for async pixel readback
    private val pbos = IntArray(2)
    private var pboIndex = 0
    private var pboInitialized = false
    private val pboPresentationTimeUs = LongArray(2)
    private val pboDecodedAtElapsedMs = LongArray(2)
    private val pixelByteSize get() = width * height * 4

    private val reusableBitmaps = arrayOfNulls<Bitmap>(BITMAP_POOL_SIZE)
    private var reusableBitmapIndex = 0
    private val renderQueued = AtomicBoolean(false)
    private val renderAgain = AtomicBoolean(false)
    @Volatile private var outputFpsLimit = 30
    private var lastReadbackAtElapsedMs = 0L

    var surface: Surface? = null
        private set

    var onBitmapReady: ((RenderedVideoFrame) -> Unit)? = null

    /** Limits costly GPU-to-CPU Bitmap readback without slowing MediaCodec decode. */
    fun setOutputFpsLimit(fps: Int) {
        outputFpsLimit = fps.coerceIn(1, 60)
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

                // Create FBO + renderbuffer
                val fbos = IntArray(1)
                GLES20.glGenFramebuffers(1, fbos, 0)
                fbo = fbos[0]

                val rbs = IntArray(1)
                GLES20.glGenRenderbuffers(1, rbs, 0)
                renderBuffer = rbs[0]

                GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, renderBuffer)
                GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_RGBA4, width, height)

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
                GLES20.glFramebufferRenderbuffer(
                    GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_RENDERBUFFER, renderBuffer
                )

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
                pboInitialized = false

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

        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            val presentationTimeUs = st.timestamp / 1_000L
            val decodedAtElapsedMs = SystemClock.elapsedRealtime()
            val minimumIntervalMs = 1_000L / outputFpsLimit.coerceAtLeast(1)
            if (decodedAtElapsedMs - lastReadbackAtElapsedMs < minimumIntervalMs) {
                return
            }
            lastReadbackAtElapsedMs = decodedAtElapsedMs

            egl.makeCurrent(pbuf)

            // Render OES texture to FBO
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
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

            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

            // --- PBO async readback (ping-pong) ---
            // Step 1: Initiate async DMA read of current frame into PBO[pboIndex]
            //         glReadPixels with a bound PBO returns IMMEDIATELY (no GPU stall)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[pboIndex])
            GLES30.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            pboPresentationTimeUs[pboIndex] = presentationTimeUs
            pboDecodedAtElapsedMs[pboIndex] = decodedAtElapsedMs

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            // Step 2: Map the OTHER PBO (previous frame, DMA already complete) → zero-wait read
            if (pboInitialized) {
                val readIndex = 1 - pboIndex
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbos[readIndex])
                val mappedBuffer = GLES30.glMapBufferRange(
                    GLES30.GL_PIXEL_PACK_BUFFER, 0, pixelByteSize, GLES30.GL_MAP_READ_BIT
                )
                if (mappedBuffer is ByteBuffer) {
                    val bitmap = nextReusableBitmap()
                    bitmap.copyPixelsFromBuffer(mappedBuffer)
                    onBitmapReady?.invoke(
                        RenderedVideoFrame(
                            bitmap = bitmap,
                            presentationTimeUs = pboPresentationTimeUs[readIndex],
                            decodedAtElapsedMs = pboDecodedAtElapsedMs[readIndex],
                            readyAtElapsedMs = SystemClock.elapsedRealtime()
                        )
                    )
                }
                GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            }

            // Step 3: Swap PBO index for next frame
            pboIndex = 1 - pboIndex
            pboInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "renderAndReadBitmap error", e)
        }
    }

    private fun releaseInternal() {
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null
        surface = null

        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            fbo = 0
        }
        if (renderBuffer != 0) {
            GLES20.glDeleteRenderbuffers(1, intArrayOf(renderBuffer), 0)
            renderBuffer = 0
        }
        if (pbos[0] != 0 || pbos[1] != 0) {
            GLES30.glDeleteBuffers(2, pbos, 0)
            pbos[0] = 0
            pbos[1] = 0
        }
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
        pboInitialized = false
        pboPresentationTimeUs.fill(0L)
        pboDecodedAtElapsedMs.fill(0L)
        renderQueued.set(false)
        renderAgain.set(false)
        lastReadbackAtElapsedMs = 0L
        for (i in reusableBitmaps.indices) {
            reusableBitmaps[i]?.recycle()
            reusableBitmaps[i] = null
        }
        reusableBitmapIndex = 0
        onBitmapReady = null
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
