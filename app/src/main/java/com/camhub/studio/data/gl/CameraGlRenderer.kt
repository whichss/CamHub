package com.camhub.studio.data.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.camhub.studio.data.metrics.DurationPercentilesUs
import com.camhub.studio.data.metrics.RollingDurationTracker
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class CameraGlRenderer {
    data class OutputTimings(
        val previewSubmission: DurationPercentilesUs,
        val encoderSubmission: DurationPercentilesUs
    )

    private val previewSubmission = RollingDurationTracker()
    private val encoderSubmission = RollingDurationTracker()

    /** CPU wall time including swapBuffers; not GPU execution time. */
    fun outputTimings() = OutputTimings(previewSubmission.snapshot(), encoderSubmission.snapshot())

    companion object {
        private const val TAG = "CameraGlRenderer"
        private const val MAX_TRUSTED_CAPTURE_AGE_NS = 10_000_000_000L

        private val QUAD_COORDS = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        )
        private val TEX_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )
    }

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglHelper: EglHelper? = null
    private var viewfinderEglSurface: android.opengl.EGLSurface? = null
    private var encoderEglSurface: android.opengl.EGLSurface? = null

    private var oesTexId = 0
    private var program = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)
    private val rotationMatrix = FloatArray(16)
    private val combinedMatrix = FloatArray(16)
    /** Reused by viewport calculations, which run only on the CameraGL thread. */
    private val viewportResult = IntArray(4)
    private var matrixRotationDegrees = Int.MIN_VALUE

    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null

    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTexMatrixLoc = 0

    @Volatile
    private var stopped = false

    @Volatile
    private var rotationDegrees = 0
    private var renderWidth = 0
    private var renderHeight = 0
    private var viewfinderWidth = 0
    private var viewfinderHeight = 0
    private var bufferWidth = 0
    private var bufferHeight = 0
    private var useCenterCrop = false

    var cameraSurface: Surface? = null
        private set

    val currentRotation: Int get() = rotationDegrees

    /** Called before the camera frame is submitted to MediaCodec's input surface. */
    var onFrameSubmitted: ((presentationTimeUs: Long, captureAtWallMs: Long) -> Unit)? = null

    fun start(
        width: Int,
        height: Int,
        viewfinderSurface: Surface,
        encoderSurface: Surface,
        vfWidth: Int = width,
        vfHeight: Int = height,
        bufW: Int = width,
        bufH: Int = height,
        centerCrop: Boolean = false,
        onReady: (Surface?) -> Unit = {}
    ) {
        stopped = false
        val thread = HandlerThread("CameraGL").also { it.start() }
        glThread = thread
        val handler = Handler(thread.looper)
        glHandler = handler

        handler.post {
            try {
                val egl = EglHelper()
                egl.init()
                eglHelper = egl

                viewfinderEglSurface = egl.createWindowSurface(viewfinderSurface)
                encoderEglSurface = egl.createWindowSurface(encoderSurface)

                egl.makeCurrent(viewfinderEglSurface!!)

                program = EglHelper.createOesProgram()
                if (program == 0) throw RuntimeException("Failed to create OES program")

                aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
                aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
                uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")

                vertexBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer().put(QUAD_COORDS).also { it.position(0) }
                texCoordBuffer = ByteBuffer.allocateDirect(TEX_COORDS.size * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer().put(TEX_COORDS).also { it.position(0) }
                renderWidth = width
                renderHeight = height
                viewfinderWidth = vfWidth
                viewfinderHeight = vfHeight
                bufferWidth = bufW
                bufferHeight = bufH
                useCenterCrop = centerCrop

                oesTexId = EglHelper.createTexture()
                val st = SurfaceTexture(oesTexId)
                st.setDefaultBufferSize(bufW, bufH)
                surfaceTexture = st
                cameraSurface = Surface(st)

                st.setOnFrameAvailableListener({ _ ->
                    glHandler?.post { drawFrame() }
                }, handler)

                onReady(cameraSurface)
                Log.d(TAG, "CameraGlRenderer started: ${width}x${height}, vf=${vfWidth}x${vfHeight}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start CameraGlRenderer", e)
                releaseInternal()
                onReady(null)
            }
        }
    }

    fun stop() {
        stopped = true
        glHandler?.post { releaseInternal() }
        glThread?.quitSafely()
        glThread = null
        glHandler = null
    }

    fun updateRotation(degrees: Int) {
        rotationDegrees = degrees
    }

    private fun drawFrame() {
        if (stopped) return
        val egl = eglHelper ?: return
        val st = surfaceTexture ?: return
        val vfSurface = viewfinderEglSurface ?: return
        val encSurface = encoderEglSurface ?: return

        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)

            // Apply rotation to texMatrix
            if (rotationDegrees != 0) {
                val degrees = rotationDegrees
                if (matrixRotationDegrees != degrees) {
                    Matrix.setIdentityM(rotationMatrix, 0)
                    Matrix.translateM(rotationMatrix, 0, 0.5f, 0.5f, 0f)
                    Matrix.rotateM(rotationMatrix, 0, degrees.toFloat(), 0f, 0f, 1f)
                    Matrix.translateM(rotationMatrix, 0, -0.5f, -0.5f, 0f)
                    matrixRotationDegrees = degrees
                }
                Matrix.multiplyMM(combinedMatrix, 0, rotationMatrix, 0, texMatrix, 0)
                System.arraycopy(combinedMatrix, 0, texMatrix, 0, 16)
            }

            // Draw to viewfinder
            val previewStartedNs = System.nanoTime()
            egl.makeCurrent(vfSurface)
            GLES20.glViewport(0, 0, viewfinderWidth, viewfinderHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val vp = if (useCenterCrop) calculateCropViewport(viewfinderWidth, viewfinderHeight)
                     else calculateViewport(viewfinderWidth, viewfinderHeight)
            GLES20.glViewport(vp[0], vp[1], vp[2], vp[3])
            drawQuad()
            egl.swapBuffers(vfSurface)
            previewSubmission.recordNanos((System.nanoTime() - previewStartedNs).coerceAtLeast(0L))

            // Draw to encoder
            val encoderStartedNs = System.nanoTime()
            egl.makeCurrent(encSurface)
            GLES20.glViewport(0, 0, renderWidth, renderHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (useCenterCrop) {
                val encVp = calculateCropViewport(renderWidth, renderHeight)
                GLES20.glViewport(encVp[0], encVp[1], encVp[2], encVp[3])
            }
            drawQuad()
            val captureTimestampNs = st.timestamp.takeIf { it > 0L }
                ?: SystemClock.elapsedRealtimeNanos()
            val captureAtWallMs = monotonicTimestampToWallMs(captureTimestampNs)
            egl.setPresentationTime(encSurface, captureTimestampNs)
            onFrameSubmitted?.invoke(captureTimestampNs / 1_000L, captureAtWallMs)
            egl.swapBuffers(encSurface)
            encoderSubmission.recordNanos((System.nanoTime() - encoderStartedNs).coerceAtLeast(0L))
        } catch (e: Exception) {
            Log.e(TAG, "drawFrame error", e)
        }
    }

    private fun monotonicTimestampToWallMs(timestampNs: Long): Long {
        val elapsedNowNs = SystemClock.elapsedRealtimeNanos()
        val ageNs = elapsedNowNs - timestampNs
        // Camera timestamp sources are vendor dependent. Use the sensor timestamp only
        // when it is clearly on Android's elapsed-realtime time base.
        return if (ageNs in 0L..MAX_TRUSTED_CAPTURE_AGE_NS) {
            System.currentTimeMillis() - ageNs / 1_000_000L
        } else {
            System.currentTimeMillis()
        }
    }

    /**
     * Calculate aspect-ratio-preserving viewport for the viewfinder.
     * Detects if the combined texMatrix includes a 90°/270° rotation
     * (from SurfaceTexture's sensor orientation transform) and adjusts
     * the viewport to avoid stretching/squishing.
     *
     * @return IntArray of [x, y, width, height] for glViewport
     */
    private fun calculateViewport(surfaceW: Int, surfaceH: Int): IntArray {
        // Detect if the combined texMatrix rotates content by ~90°/270°
        // by checking if the diagonal elements (m00, m11) are near zero.
        val isContentRotated = kotlin.math.abs(texMatrix[0]) < 0.3f &&
                kotlin.math.abs(texMatrix[5]) < 0.3f

        // Determine the effective content aspect ratio (width/height)
        val contentAspect = if (isContentRotated) {
            bufferHeight.toFloat() / bufferWidth.toFloat()
        } else {
            bufferWidth.toFloat() / bufferHeight.toFloat()
        }

        val viewAspect = surfaceW.toFloat() / surfaceH.toFloat()

        // If aspect ratios are close enough, use full viewport
        if (kotlin.math.abs(contentAspect - viewAspect) < 0.05f) {
            return setViewport(0, 0, surfaceW, surfaceH)
        }

        return if (contentAspect < viewAspect) {
            // Content is narrower than viewport → pillarbox (black bars on sides)
            val fitW = (surfaceH * contentAspect).toInt()
            val offsetX = (surfaceW - fitW) / 2
            setViewport(offsetX, 0, fitW, surfaceH)
        } else {
            // Content is wider than viewport → letterbox (black bars top/bottom)
            val fitH = (surfaceW / contentAspect).toInt()
            val offsetY = (surfaceH - fitH) / 2
            setViewport(0, offsetY, surfaceW, fitH)
        }
    }

    /**
     * Calculate center-crop viewport that fills the target surface.
     * Content is scaled to fill (not fit), cropping overflow.
     */
    private fun calculateCropViewport(surfaceW: Int, surfaceH: Int): IntArray {
        val isContentRotated = kotlin.math.abs(texMatrix[0]) < 0.3f &&
                kotlin.math.abs(texMatrix[5]) < 0.3f

        val contentAspect = if (isContentRotated) {
            bufferHeight.toFloat() / bufferWidth.toFloat()
        } else {
            bufferWidth.toFloat() / bufferHeight.toFloat()
        }

        val viewAspect = surfaceW.toFloat() / surfaceH.toFloat()

        if (kotlin.math.abs(contentAspect - viewAspect) < 0.05f) {
            return setViewport(0, 0, surfaceW, surfaceH)
        }

        return if (contentAspect < viewAspect) {
            // Content narrower than viewport → fill width, crop top/bottom
            val fitH = (surfaceW / contentAspect).toInt()
            val offsetY = (surfaceH - fitH) / 2
            setViewport(0, offsetY, surfaceW, fitH)
        } else {
            // Content wider than viewport → fill height, crop left/right
            val fitW = (surfaceH * contentAspect).toInt()
            val offsetX = (surfaceW - fitW) / 2
            setViewport(offsetX, 0, fitW, surfaceH)
        }
    }

    private fun setViewport(x: Int, y: Int, width: Int, height: Int): IntArray =
        viewportResult.apply {
            this[0] = x
            this[1] = y
            this[2] = width
            this[3] = height
        }

    private fun drawQuad() {
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
    }

    private fun releaseInternal() {
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null
        cameraSurface = null

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
            viewfinderEglSurface?.let { EGL14.eglDestroySurface(egl.eglDisplay, it) }
            encoderEglSurface?.let { EGL14.eglDestroySurface(egl.eglDisplay, it) }
            viewfinderEglSurface = null
            encoderEglSurface = null
            egl.release()
        }
        eglHelper = null
        onFrameSubmitted = null
        Log.d(TAG, "CameraGlRenderer released")
    }
}
