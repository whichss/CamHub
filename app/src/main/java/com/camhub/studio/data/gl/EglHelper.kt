package com.camhub.studio.data.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface

class EglHelper {

    companion object {
        private const val TAG = "EglHelper"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            uniform mat4 uTexMatrix;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        fun createOesProgram(): Int {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_OES)
            if (vertexShader == 0 || fragmentShader == 0) return 0

            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                val info = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                Log.e(TAG, "Program link failed: $info")
                return 0
            }

            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return program
        }

        fun createTexture(): Int {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val texId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            return texId
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] != GLES20.GL_TRUE) {
                val info = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                Log.e(TAG, "Shader compile failed: $info")
                return 0
            }
            return shader
        }
    }

    var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private set
    var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private set
    private var eglConfig: EGLConfig? = null
    private var sharedContextLease: EglSharedContextPool.Lease? = null

    fun init() {
        check(sharedContextLease == null) { "EGL helper is already initialized" }
        val lease = EglSharedContextPool.acquire()
        sharedContextLease = lease
        eglDisplay = lease.display
        eglConfig = lease.config
        eglContext = lease.context
        Log.d(TAG, "EGL initialized in shared texture group")
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
        return eglSurface
    }

    fun createPbufferSurface(width: Int, height: Int): EGLSurface {
        val attribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        val eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, attribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreatePbufferSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
        return eglSurface
    }

    fun makeCurrent(eglSurface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
    }

    fun swapBuffers(eglSurface: EGLSurface): Boolean {
        return EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun setPresentationTime(eglSurface: EGLSurface, timestampNs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs)
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
        }
        sharedContextLease?.let(EglSharedContextPool::release)
        sharedContextLease = null
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
        Log.d(TAG, "EGL released")
    }
}

/**
 * Owns a root EGL context and gives each GL worker a child context in the same share group.
 * This makes 2D textures created by decoder workers visible to PGM encoder workers without
 * routing pixels through a CPU Bitmap. The root context is never made current and is retained
 * until the last child context is released.
 */
private object EglSharedContextPool {
    data class Lease(
        val display: EGLDisplay,
        val config: EGLConfig,
        val context: EGLContext
    )

    private const val EGL_RECORDABLE_ANDROID = 0x3142
    private const val EGL_OPENGL_ES3_BIT_KHR = 0x40

    private val lock = Any()
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var config: EGLConfig? = null
    private var rootContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var childCount = 0

    fun acquire(): Lease = synchronized(lock) {
        ensureRootContext()
        val sharedConfig = checkNotNull(config)
        val childContext = EGL14.eglCreateContext(
            display,
            sharedConfig,
            rootContext,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0
        )
        check(childContext != EGL14.EGL_NO_CONTEXT) {
            "eglCreateContext failed for shared worker: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }
        childCount++
        Lease(display, sharedConfig, childContext)
    }

    fun release(lease: Lease) = synchronized(lock) {
        if (lease.display != display || childCount <= 0) return@synchronized
        childCount--
        if (childCount == 0) {
            if (rootContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, rootContext)
            }
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
            config = null
            rootContext = EGL14.EGL_NO_CONTEXT
        }
    }

    private fun ensureRootContext() {
        if (rootContext != EGL14.EGL_NO_CONTEXT) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        try {
            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            check(EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, configCount, 0)) {
                "eglChooseConfig failed"
            }
            config = configs[0] ?: error("No suitable EGL config found")
            rootContext = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0
            )
            check(rootContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed for shared root" }
        } catch (error: Throwable) {
            if (rootContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, rootContext)
            }
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
            config = null
            rootContext = EGL14.EGL_NO_CONTEXT
            throw error
        }
    }
}
