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
    private var secondTexture = 0
    private var secondWidth = 0
    private var secondHeight = 0
    private var secondTextureLocation = -1
    private var secondTexelSizeLocation = -1
    private var secondScaleLocation = -1
    private var secondPtzScaleLocation = -1
    private var secondPtzCenterLocation = -1
    private var transitionModeLocation = -1
    private var transitionProgressLocation = -1
    private var reconstructionEnabledLocation = -1
    private var sourceRotationLocation = -1

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
        secondTexture = createTexture()
        textureLocation = GLES30.glGetUniformLocation(program, "uTexture")
        texelSizeLocation = GLES30.glGetUniformLocation(program, "uTexelSize")
        scaleLocation = GLES30.glGetUniformLocation(program, "uScale")
        ptzScaleLocation = GLES30.glGetUniformLocation(program, "uPtzScale")
        ptzCenterLocation = GLES30.glGetUniformLocation(program, "uPtzCenter")
        secondTextureLocation = GLES30.glGetUniformLocation(program, "uSecondTexture")
        secondTexelSizeLocation = GLES30.glGetUniformLocation(program, "uSecondTexelSize")
        secondScaleLocation = GLES30.glGetUniformLocation(program, "uSecondScale")
        secondPtzScaleLocation = GLES30.glGetUniformLocation(program, "uSecondPtzScale")
        secondPtzCenterLocation = GLES30.glGetUniformLocation(program, "uSecondPtzCenter")
        transitionModeLocation = GLES30.glGetUniformLocation(program, "uTransitionMode")
        transitionProgressLocation = GLES30.glGetUniformLocation(program, "uTransitionProgress")
        reconstructionEnabledLocation = GLES30.glGetUniformLocation(program, "uReconstructionEnabled")
        sourceRotationLocation = GLES30.glGetUniformLocation(program, "uSourceRotation")
        return texture != 0 && secondTexture != 0
    }

    fun render(
        bitmap: Bitmap,
        outputWidth: Int,
        outputHeight: Int,
        ptzTransform: HubPtzTransform = HybridPtzController.IDENTITY_TRANSFORM,
        transition: PgmTransitionFrame? = null,
        enableSpatialReconstruction: Boolean = true,
        sourceRotationDegrees: Int = 0
    ): Boolean {
        if (bitmap.isRecycled || program == 0 || texture == 0) return false
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        uploadBitmap(bitmap)
        return renderTexture(
            sourceTextureId = texture,
            sourceWidth = inputWidth,
            sourceHeight = inputHeight,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            ptzTransform = ptzTransform,
            transition = transition,
            enableSpatialReconstruction = enableSpatialReconstruction,
            sourceRotationDegrees = sourceRotationDegrees
        )
    }

    /**
     * Composites a 2D texture created by another EglHelper context in the shared EGL group.
     * The caller owns the source texture and must keep it alive until this draw has been
     * submitted. This path avoids both glReadPixels-to-Bitmap and GLUtils bitmap upload.
     */
    fun renderTexture(
        sourceTextureId: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        ptzTransform: HubPtzTransform = HybridPtzController.IDENTITY_TRANSFORM,
        transition: PgmTransitionFrame? = null,
        enableSpatialReconstruction: Boolean = true,
        sourceRotationDegrees: Int = 0
    ): Boolean {
        if (sourceTextureId == 0 || sourceWidth <= 0 || sourceHeight <= 0 || program == 0) return false
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            // Clear a stale error from earlier work in this context before measuring this draw.
        }

        GLES30.glViewport(0, 0, outputWidth.coerceAtLeast(1), outputHeight.coerceAtLeast(1))
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTextureId)
        GLES30.glUniform1i(textureLocation, 0)
        GLES30.glUniform2f(
            texelSizeLocation,
            1f / sourceWidth,
            1f / sourceHeight
        )
        val normalizedRotation = ((sourceRotationDegrees % 360) + 360) % 360
        val isQuarterTurn = normalizedRotation == 90 || normalizedRotation == 270
        val scale = fitScale(
            if (isQuarterTurn) sourceHeight else sourceWidth,
            if (isQuarterTurn) sourceWidth else sourceHeight,
            outputWidth,
            outputHeight
        )
        GLES30.glUniform2f(scaleLocation, scale.first, scale.second)
        GLES30.glUniform1f(ptzScaleLocation, ptzTransform.scale)
        GLES30.glUniform2f(ptzCenterLocation, ptzTransform.centerX, ptzTransform.centerY)
        GLES30.glUniform1i(reconstructionEnabledLocation, if (enableSpatialReconstruction) 1 else 0)
        GLES30.glUniform1i(sourceRotationLocation, normalizedRotation)
        val activeTransition = transition?.takeIf {
            !it.bitmap.isRecycled && it.mode != PgmTransitionMode.NONE
        }
        if (activeTransition != null) {
            uploadSecondBitmap(activeTransition.bitmap)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, secondTexture)
            GLES30.glUniform1i(secondTextureLocation, 1)
            GLES30.glUniform2f(
                secondTexelSizeLocation,
                1f / secondWidth.coerceAtLeast(1),
                1f / secondHeight.coerceAtLeast(1)
            )
            val secondScale = fitScale(
                secondWidth,
                secondHeight,
                outputWidth,
                outputHeight
            )
            GLES30.glUniform2f(secondScaleLocation, secondScale.first, secondScale.second)
            GLES30.glUniform1f(secondPtzScaleLocation, activeTransition.ptzTransform.scale)
            GLES30.glUniform2f(
                secondPtzCenterLocation,
                activeTransition.ptzTransform.centerX,
                activeTransition.ptzTransform.centerY
            )
            GLES30.glUniform1i(transitionModeLocation, activeTransition.mode.shaderValue)
            GLES30.glUniform1f(
                transitionProgressLocation,
                activeTransition.progress.coerceIn(0f, 1f)
            )
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        } else {
            GLES30.glUniform1i(transitionModeLocation, PgmTransitionMode.NONE.shaderValue)
            GLES30.glUniform1f(transitionProgressLocation, 0f)
        }

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
        if (secondTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(secondTexture), 0)
            secondTexture = 0
        }
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
        inputWidth = 0
        inputHeight = 0
        secondWidth = 0
        secondHeight = 0
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

    private fun uploadSecondBitmap(bitmap: Bitmap) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, secondTexture)
        if (bitmap.width != secondWidth || bitmap.height != secondHeight) {
            secondWidth = bitmap.width
            secondHeight = bitmap.height
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
            out vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform sampler2D uTexture;
            uniform vec2 uTexelSize;
            uniform float uPtzScale;
            uniform vec2 uPtzCenter;
            uniform sampler2D uSecondTexture;
            uniform vec2 uSecondTexelSize;
            uniform vec2 uSecondScale;
            uniform float uSecondPtzScale;
            uniform vec2 uSecondPtzCenter;
            uniform int uTransitionMode;
            uniform float uTransitionProgress;
            uniform int uReconstructionEnabled;
            uniform int uSourceRotation;
            in vec2 vTexCoord;
            out vec4 outColor;

            float luma(vec3 color) {
                return dot(color, vec3(0.299, 0.587, 0.114));
            }

            vec3 reconstruct(sampler2D source, vec2 uv, vec2 texelSize) {
                vec3 c = texture(source, uv).rgb;
                vec3 n = texture(source, uv + vec2(0.0, -texelSize.y)).rgb;
                vec3 s = texture(source, uv + vec2(0.0,  texelSize.y)).rgb;
                vec3 w = texture(source, uv + vec2(-texelSize.x, 0.0)).rgb;
                vec3 e = texture(source, uv + vec2( texelSize.x, 0.0)).rgb;
                vec2 gradient = vec2(luma(e) - luma(w), luma(s) - luma(n));
                float edge = clamp(length(gradient) * 3.0, 0.0, 1.0);
                vec2 tangent = normalize(vec2(-gradient.y, gradient.x) + vec2(0.0001));
                vec2 adaptiveOffset = tangent * texelSize * 0.55;
                vec3 alongEdge = 0.5 * (
                    texture(source, uv + adaptiveOffset).rgb +
                    texture(source, uv - adaptiveOffset).rgb
                );
                vec3 reconstructed = mix(c, alongEdge, edge * 0.35);
                vec3 neighborhood = (n + s + w + e) * 0.25;
                vec3 sharpened = reconstructed + (reconstructed - neighborhood) * (0.12 + edge * 0.10);
                vec3 localMin = min(c, min(min(n, s), min(w, e)));
                vec3 localMax = max(c, max(max(n, s), max(w, e)));
                return clamp(sharpened, localMin, localMax);
            }

            void main() {
                vec2 primaryCoord = (vTexCoord - vec2(0.5)) / uScale + vec2(0.5);
                bool primaryOutside = any(lessThan(primaryCoord, vec2(0.0))) ||
                    any(greaterThan(primaryCoord, vec2(1.0)));
                vec2 uv = (primaryCoord - vec2(0.5)) /
                    max(uPtzScale, 1.0) + uPtzCenter;
                if (uSourceRotation == 90) uv = vec2(uv.y, 1.0 - uv.x);
                else if (uSourceRotation == 180) uv = vec2(1.0 - uv.x, 1.0 - uv.y);
                else if (uSourceRotation == 270) uv = vec2(1.0 - uv.y, uv.x);
                vec3 primary = primaryOutside
                    ? vec3(0.0)
                    : (uReconstructionEnabled == 1
                        ? reconstruct(uTexture, uv, uTexelSize)
                        : texture(uTexture, uv).rgb);
                if (uTransitionMode == 0) {
                    outColor = vec4(primary, 1.0);
                    return;
                }
                vec2 secondCoord = (vTexCoord - vec2(0.5)) /
                    uSecondScale + vec2(0.5);
                bool secondOutside = any(lessThan(secondCoord, vec2(0.0))) ||
                    any(greaterThan(secondCoord, vec2(1.0)));
                vec2 secondUv = (secondCoord - vec2(0.5)) /
                    max(uSecondPtzScale, 1.0) + uSecondPtzCenter;
                vec3 incoming = secondOutside
                    ? vec3(0.0)
                    : (uReconstructionEnabled == 1
                        ? reconstruct(uSecondTexture, secondUv, uSecondTexelSize)
                        : texture(uSecondTexture, secondUv).rgb);
                vec3 result = primary;
                if (uTransitionMode == 1) {
                    result = mix(primary, incoming, uTransitionProgress);
                } else if (uTransitionMode == 2) {
                    result = uTransitionProgress <= 0.5
                        ? mix(primary, vec3(0.0), uTransitionProgress * 2.0)
                        : mix(vec3(0.0), incoming, (uTransitionProgress - 0.5) * 2.0);
                } else if (uTransitionMode == 3) {
                    result = mix(primary, incoming, step(vTexCoord.x, uTransitionProgress));
                }
                outColor = vec4(result, 1.0);
            }
        """
    }
}
