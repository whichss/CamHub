package com.camhub.studio.data.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class DecoderGlRenderer(val width: Int, val height: Int) {

    companion object {
        private const val TAG = "DecoderGlRenderer"

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

    private var pixelBuffer: ByteBuffer? = null

    var surface: Surface? = null
        private set

    var onBitmapReady: ((Bitmap) -> Unit)? = null

    fun start() {
        val thread = HandlerThread("DecoderGL").also { it.start() }
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

                // Allocate pixel read buffer
                pixelBuffer = ByteBuffer.allocateDirect(width * height * 4)
                    .order(ByteOrder.nativeOrder())

                // Create OES texture + SurfaceTexture for decoder output
                oesTexId = EglHelper.createTexture()
                val st = SurfaceTexture(oesTexId)
                st.setDefaultBufferSize(width, height)
                surfaceTexture = st
                surface = Surface(st)

                st.setOnFrameAvailableListener({ _ ->
                    glHandler?.post { renderAndReadBitmap() }
                }, handler)

                Log.d(TAG, "DecoderGlRenderer started: ${width}x${height}")
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

    private fun renderAndReadBitmap() {
        val egl = eglHelper ?: return
        val st = surfaceTexture ?: return
        val pbuf = pbufferSurface ?: return
        val pxBuf = pixelBuffer ?: return

        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)

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

            // Read pixels
            pxBuf.clear()
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pxBuf)
            pxBuf.rewind()

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            // Create bitmap from pixels
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(pxBuf)

            onBitmapReady?.invoke(bitmap)
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
        pixelBuffer = null
        onBitmapReady = null
        Log.d(TAG, "DecoderGlRenderer released")
    }
}
