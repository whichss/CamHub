package com.camhub.studio.data.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.AttributeSet
import com.camhub.studio.data.ptz.HubPtzTransform
import com.camhub.studio.data.ptz.HybridPtzController
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

data class SpatialUpscaleFrame(
    val bitmap: Bitmap,
    val cameraName: String,
    val frameSequence: Long,
    val ptzTransform: HubPtzTransform,
    val transition: PgmTransitionFrame?,
    val enableSpatialReconstruction: Boolean,
    val onFrameSubmitted: (String, Long, Long) -> Unit
)

/**
 * A direct GPU PGM output surface. It reconstructs the source at the output surface resolution
 * with an edge-adaptive spatial pass and a conservative sharpening pass.
 *
 * This is FSR-like rather than a byte-for-byte FSR 1 implementation. It has no temporal history,
 * motion vectors, or AI model, so it stays suitable for low-latency live video.
 */
class SpatialUpscaleSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {
    private val upscaleRenderer = SpatialUpscaleRenderer()
    private var fixedOutputWidth = 0
    private var fixedOutputHeight = 0

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        setRenderer(upscaleRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true
    }

    fun displayFrame(
        bitmap: Bitmap,
        cameraName: String,
        frameSequence: Long,
        ptzTransform: HubPtzTransform = HybridPtzController.IDENTITY_TRANSFORM,
        transition: PgmTransitionFrame? = null,
        enableSpatialReconstruction: Boolean = true,
        onFrameSubmitted: (String, Long, Long) -> Unit
    ) {
        if (bitmap.isRecycled || frameSequence <= 0L) return
        if (!upscaleRenderer.offerFrame(
                bitmap,
                cameraName,
                frameSequence,
                ptzTransform,
                transition,
                enableSpatialReconstruction,
                onFrameSubmitted
            )
        ) return
        requestRender()
    }

    /**
     * A compact in-app PGM preview should render at its real view size. Forcing a
     * 1920x1080 Surface behind a small Compose card wastes fill-rate and texture
     * bandwidth. A clean external feed may opt into a fixed output buffer because
     * its surface is the actual programme output.
     */
    fun setOutputSize(
        shortEdge: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        forceFixedBuffer: Boolean = false
    ) {
        if (!forceFixedBuffer || shortEdge <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            if (fixedOutputWidth != 0 || fixedOutputHeight != 0) {
                fixedOutputWidth = 0
                fixedOutputHeight = 0
                holder.setSizeFromLayout()
            }
            return
        }
        val evenShortEdge = shortEdge.coerceAtLeast(2) and 0x7FFFFFFE
        val sourceShortEdge = minOf(sourceWidth, sourceHeight)
        val sourceLongEdge = maxOf(sourceWidth, sourceHeight)
        val evenLongEdge = (
            evenShortEdge.toLong() * sourceLongEdge / sourceShortEdge
        ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() and 0x7FFFFFFE
        val outputWidth = if (sourceWidth >= sourceHeight) evenLongEdge else evenShortEdge
        val outputHeight = if (sourceHeight > sourceWidth) evenLongEdge else evenShortEdge
        if (outputWidth == fixedOutputWidth && outputHeight == fixedOutputHeight) return
        fixedOutputWidth = outputWidth
        fixedOutputHeight = outputHeight
        holder.setFixedSize(outputWidth, outputHeight)
    }
}

private class SpatialUpscaleRenderer : GLSurfaceView.Renderer {
    private val pendingFrame = AtomicReference<SpatialUpscaleFrame?>()
    private val glProgram = SpatialUpscaleGlProgram()
    private var outputWidth = 1
    private var outputHeight = 1
    private var lastOfferedCameraName = ""
    private var lastOfferedFrameSequence = 0L
    private var lastDrawnCameraName = ""
    private var lastDrawnFrameSequence = 0L
    private var lastDrawnTransitionCameraName = ""
    private var lastDrawnTransitionFrameSequence = 0L

    @Synchronized
    fun offerFrame(
        bitmap: Bitmap,
        cameraName: String,
        frameSequence: Long,
        ptzTransform: HubPtzTransform,
        transition: PgmTransitionFrame?,
        enableSpatialReconstruction: Boolean,
        onFrameSubmitted: (String, Long, Long) -> Unit
    ): Boolean {
        if (
            transition == null &&
            cameraName == lastOfferedCameraName &&
            frameSequence <= lastOfferedFrameSequence
        ) {
            return false
        }
        lastOfferedCameraName = cameraName
        lastOfferedFrameSequence = frameSequence
        pendingFrame.set(
            SpatialUpscaleFrame(
                bitmap,
                cameraName,
                frameSequence,
                ptzTransform,
                transition,
                enableSpatialReconstruction,
                onFrameSubmitted
            )
        )
        return true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glProgram.create()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        outputWidth = width.coerceAtLeast(1)
        outputHeight = height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        val frame = pendingFrame.get() ?: return
        if (!glProgram.render(
                frame.bitmap,
                outputWidth,
                outputHeight,
                frame.ptzTransform,
                frame.transition,
                frame.enableSpatialReconstruction
            )
        ) return

        if (
            frame.cameraName != lastDrawnCameraName ||
            frame.frameSequence > lastDrawnFrameSequence
        ) {
            lastDrawnCameraName = frame.cameraName
            lastDrawnFrameSequence = frame.frameSequence
            frame.onFrameSubmitted(
                frame.cameraName,
                frame.frameSequence,
                SystemClock.elapsedRealtime()
            )
        }
        frame.transition?.takeIf {
            it.frameSequence > 0L && (
                it.cameraName != lastDrawnTransitionCameraName ||
                    it.frameSequence > lastDrawnTransitionFrameSequence
                )
        }?.let { transition ->
            lastDrawnTransitionCameraName = transition.cameraName
            lastDrawnTransitionFrameSequence = transition.frameSequence
            frame.onFrameSubmitted(
                transition.cameraName,
                transition.frameSequence,
                SystemClock.elapsedRealtime()
            )
        }
    }

}
