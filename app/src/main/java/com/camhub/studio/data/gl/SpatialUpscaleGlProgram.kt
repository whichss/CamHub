package com.camhub.studio.data.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.camhub.studio.data.ptz.HubPtzTransform
import com.camhub.studio.data.ptz.HybridPtzController
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** OpenGL state shared by display and encoder-surface spatial upscaling paths. */
class SpatialUpscaleGlProgram {
    private var program = 0
    private var texture = 0
    private var inputWidth = 0
    private var inputHeight = 0
    private var textureLocation = -1
    private var texelSizeLocation = -1
    private var scaleLocation = -1
    private var ptzScaleLocation = -1
    private var ptzCenterLocation = -1

    private val vertices: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(QUAD)
        .apply { position(0) }

    fun create(): Boolean {
        release()
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) return false
        texture = createTexture()
        textureLocation = GLES30.glGetUniformLocation(program, "uTexture")
        texelSizeLocation = GLES30.glGetUniformLocation(program, "uTexelSize")
        scaleLocation = GLES30.glGetUniformLocation(program, "uScale")
        ptzScaleLocation = GLES30.glGetUniformLocation(program, "uPtzScale")
        ptzCenterLocation = GLES30.glGetUniformLocation(program, "uPtzCenter")
        return texture != 0
    }

    fun render(
        bitmap: Bitmap,
        outputWidth: Int,
        outputHeight: Int,
        ptzTransform: HubPtzTransform = HybridPtzController.IDENTITY_TRANSFORM
    ): Boolean {
        if (bitmap.isRecycled || program == 0 || texture == 0) return false
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            // Clear a stale error from earlier work in this context before measuring this draw.
        }
        uploadBitmap(bitmap)

        GLES30.glViewport(0, 0, outputWidth.coerceAtLeast(1), outputHeight.coerceAtLeast(1))
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(textureLocation, 0)
        GLES30.glUniform2f(
            texelSizeLocation,
            1f / inputWidth.coerceAtLeast(1),
            1f / inputHeight.coerceAtLeast(1)
        )
        val scale = fitScale(inputWidth, inputHeight, outputWidth, outputHeight)
        GLES30.glUniform2f(scaleLocation, scale.first, scale.second)
        GLES30.glUniform1f(ptzScaleLocation, ptzTransform.scale)
        GLES30.glUniform2f(ptzCenterLocation, ptzTransform.centerX, ptzTransform.centerY)

        val stride = 4 * Float.SIZE_BYTES
        vertices.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, vertices)
        vertices.position(2)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, vertices)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glFlush()
        return GLES30.glGetError() == GLES30.GL_NO_ERROR
    }

    fun release() {
        if (texture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            texture = 0
        }
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
        inputWidth = 0
        inputHeight = 0
    }

    private fun uploadBitmap(bitmap: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        if (bitmap.width != inputWidth || bitmap.height != inputHeight) {
            inputWidth = bitmap.width
            inputHeight = bitmap.height
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        } else {
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
    }

    private fun fitScale(
        inputWidth: Int,
        inputHeight: Int,
        outputWidth: Int,
        outputHeight: Int
    ): Pair<Float, Float> {
        val inputAspect = inputWidth.toFloat() / inputHeight.coerceAtLeast(1)
        val outputAspect = outputWidth.toFloat() / outputHeight.coerceAtLeast(1)
        return if (inputAspect > outputAspect) {
            1f to (outputAspect / inputAspect)
        } else {
            (inputAspect / outputAspect) to 1f
        }
    }

    private fun createTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertex == 0 || fragment == 0) return 0
        val result = GLES30.glCreateProgram()
        GLES30.glAttachShader(result, vertex)
        GLES30.glAttachShader(result, fragment)
        GLES30.glLinkProgram(result)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(result, GLES30.GL_LINK_STATUS, linked, 0)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        if (linked[0] == 0) {
            Log.e(TAG, "Upscale program link failed: ${GLES30.glGetProgramInfoLog(result)}")
            GLES30.glDeleteProgram(result)
            return 0
        }
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Upscale shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val TAG = "SpatialUpscale"

        private val QUAD = floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f
        )

        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            uniform vec2 uScale;
            out vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition * uScale, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform sampler2D uTexture;
            uniform vec2 uTexelSize;
            uniform float uPtzScale;
            uniform vec2 uPtzCenter;
            in vec2 vTexCoord;
            out vec4 outColor;

            float luma(vec3 color) {
                return dot(color, vec3(0.299, 0.587, 0.114));
            }

            void main() {
                vec2 uv = (vTexCoord - vec2(0.5)) / max(uPtzScale, 1.0) + uPtzCenter;
                vec3 c = texture(uTexture, uv).rgb;
                vec3 n = texture(uTexture, uv + vec2(0.0, -uTexelSize.y)).rgb;
                vec3 s = texture(uTexture, uv + vec2(0.0,  uTexelSize.y)).rgb;
                vec3 w = texture(uTexture, uv + vec2(-uTexelSize.x, 0.0)).rgb;
                vec3 e = texture(uTexture, uv + vec2( uTexelSize.x, 0.0)).rgb;

                vec2 gradient = vec2(luma(e) - luma(w), luma(s) - luma(n));
                float edge = clamp(length(gradient) * 3.0, 0.0, 1.0);
                vec2 tangent = normalize(vec2(-gradient.y, gradient.x) + vec2(0.0001));
                vec2 adaptiveOffset = tangent * uTexelSize * 0.55;
                vec3 alongEdge = 0.5 * (
                    texture(uTexture, uv + adaptiveOffset).rgb +
                    texture(uTexture, uv - adaptiveOffset).rgb
                );

                vec3 reconstructed = mix(c, alongEdge, edge * 0.35);
                vec3 neighborhood = (n + s + w + e) * 0.25;
                vec3 sharpened = reconstructed + (reconstructed - neighborhood) * (0.12 + edge * 0.10);
                vec3 localMin = min(c, min(min(n, s), min(w, e)));
                vec3 localMax = max(c, max(max(n, s), max(w, e)));
                outColor = vec4(clamp(sharpened, localMin, localMax), 1.0);
            }
        """
    }
}
