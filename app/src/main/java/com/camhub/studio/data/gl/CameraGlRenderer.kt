package com.camhub.studio.data.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class CameraGlRenderer {

    companion object {
        private const val TAG = "CameraGlRenderer"

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

    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null

    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTexMatrixLoc = 0

    private var rotationDegrees = 0
    private var renderWidth = 0
    private var renderHeight = 0

    var cameraSurface: Surface? = null
        private set

    val currentRotation: Int get() = rotationDegrees

    var onFrameEncoded: (() -> Unit)? = null

    fun start(
        width: Int,
        height: Int,
        viewfinderSurface: Surface,
        encoderSurface: Surface
    ) {
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

                oesTexId = EglHelper.createTexture()
                val st = SurfaceTexture(oesTexId)
                st.setDefaultBufferSize(width, height)
                surfaceTexture = st
                cameraSurface = Surface(st)

                st.setOnFrameAvailableListener({ _ ->
                    glHandler?.post { drawFrame() }
                }, handler)

                Log.d(TAG, "CameraGlRenderer started: ${width}x${height}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start CameraGlRenderer", e)
                releaseInternal()
            }
        }
    }

    fun stop() {
        glHandler?.post { releaseInternal() }
        glThread?.quitSafely()
        glThread = null
        glHandler = null
    }

    fun updateRotation(degrees: Int) {
        rotationDegrees = degrees
    }

    private fun drawFrame() {
        val egl = eglHelper ?: return
        val st = surfaceTexture ?: return
        val vfSurface = viewfinderEglSurface ?: return
        val encSurface = encoderEglSurface ?: return

        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)

            // Apply rotation to make content upright
            if (rotationDegrees != 0) {
                val rotMatrix = FloatArray(16)
                Matrix.setIdentityM(rotMatrix, 0)
                Matrix.translateM(rotMatrix, 0, 0.5f, 0.5f, 0f)
                Matrix.rotateM(rotMatrix, 0, -rotationDegrees.toFloat(), 0f, 0f, 1f)
                Matrix.translateM(rotMatrix, 0, -0.5f, -0.5f, 0f)
                val combined = FloatArray(16)
                Matrix.multiplyMM(combined, 0, texMatrix, 0, rotMatrix, 0)
                System.arraycopy(combined, 0, texMatrix, 0, 16)
            }

            // Draw to viewfinder (with rotation)
            egl.makeCurrent(vfSurface)
            GLES20.glViewport(0, 0, renderWidth, renderHeight)
            drawTexture()
            egl.swapBuffers(vfSurface)

            // Draw to encoder (same rotation — content already upright, no metadata needed)
            egl.makeCurrent(encSurface)
            GLES20.glViewport(0, 0, renderWidth, renderHeight)
            drawTexture()
            egl.swapBuffers(encSurface)

            onFrameEncoded?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "drawFrame error", e)
        }
    }

    private fun drawTexture() {
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
        onFrameEncoded = null
        Log.d(TAG, "CameraGlRenderer released")
    }
}
