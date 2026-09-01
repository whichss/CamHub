package com.camhub.studio.data

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.camhub.studio.data.gl.SpatialUpscaleSurfaceView
import com.camhub.studio.data.ptz.HubPtzTransform
import com.camhub.studio.data.ptz.HybridPtzController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExternalDisplayManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var presentation: PgmPresentation? = null

    private val _isExternalDisplayConnected = MutableStateFlow(false)
    val isExternalDisplayConnected: StateFlow<Boolean> = _isExternalDisplayConnected.asStateFlow()

    private val _isOutputEnabled = MutableStateFlow(false)
    val isOutputEnabled: StateFlow<Boolean> = _isOutputEnabled.asStateFlow()

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { checkDisplays() }
        override fun onDisplayRemoved(displayId: Int) {
            checkDisplays()
            if (!_isExternalDisplayConnected.value) {
                dismiss()
            }
        }
        override fun onDisplayChanged(displayId: Int) {}
    }

    fun startListening() {
        displayManager.registerDisplayListener(displayListener, null)
        checkDisplays()
    }

    fun stopListening() {
        displayManager.unregisterDisplayListener(displayListener)
    }

    private fun checkDisplays(): Display? {
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        _isExternalDisplayConnected.value = displays.isNotEmpty()
        return displays.firstOrNull()
    }

    fun enableOutput(activity: Activity) {
        val externalDisplay = checkDisplays() ?: return
        presentation?.dismiss()
        presentation = PgmPresentation(activity, externalDisplay).apply { show() }
        _isOutputEnabled.value = true
    }

    fun disableOutput() {
        dismiss()
        _isOutputEnabled.value = false
    }

    fun updateFrame(
        bitmap: Bitmap,
        cameraName: String,
        frameSequence: Long,
        enableSpatialUpscaling: Boolean,
        spatialUpscaleOutputHeight: Int,
        ptzTransform: HubPtzTransform = HybridPtzController.IDENTITY_TRANSFORM,
        onFrameDrawn: (String, Long, Long) -> Unit
    ) {
        if (frameSequence <= 0L) return
        presentation?.updateFrame(
            PgmOutputFrame(
                bitmap,
                cameraName,
                frameSequence,
                enableSpatialUpscaling,
                spatialUpscaleOutputHeight,
                ptzTransform,
                onFrameDrawn
            )
        )
    }

    private fun dismiss() {
        try { presentation?.dismiss() } catch (_: Exception) {}
        presentation = null
        _isOutputEnabled.value = false
    }
}

private data class PgmOutputFrame(
    val bitmap: Bitmap,
    val cameraName: String,
    val frameSequence: Long,
    val enableSpatialUpscaling: Boolean,
    val spatialUpscaleOutputHeight: Int,
    val ptzTransform: HubPtzTransform,
    val onFrameDrawn: (String, Long, Long) -> Unit
)

/** Fullscreen clean PGM feed — no UI overlays */
private class PgmPresentation(
    context: Context,
    display: Display
) : Presentation(context, display) {

    private var imageView: MeasuredPgmImageView? = null
    private var upscaleView: SpatialUpscaleSurfaceView? = null
    private var container: FrameLayout? = null
    @Volatile private var latestFrame: PgmOutputFrame? = null
    private val framePostPending = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val iv = MeasuredPgmImageView(context).apply {
            scaleType = ImageView.ScaleType.MATRIX
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        val frameContainer = FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(
                iv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        setContentView(frameContainer)
        imageView = iv
        container = frameContainer
    }

    fun updateFrame(frame: PgmOutputFrame) {
        latestFrame = frame
        val iv = imageView ?: return
        if (framePostPending.compareAndSet(false, true)) {
            iv.post { publishLatestFrame() }
        }
    }

    private fun publishLatestFrame() {
        val displayed = latestFrame
        if (displayed != null) {
            if (displayed.enableSpatialUpscaling) {
                imageView?.visibility = View.GONE
                val glView = getOrCreateUpscaleView()
                glView.visibility = View.VISIBLE
                glView.setOutputHeight(displayed.spatialUpscaleOutputHeight)
                glView.displayFrame(
                    bitmap = displayed.bitmap,
                    cameraName = displayed.cameraName,
                    frameSequence = displayed.frameSequence,
                    ptzTransform = displayed.ptzTransform,
                    onFrameSubmitted = displayed.onFrameDrawn
                )
            } else {
                upscaleView?.visibility = View.GONE
                imageView?.visibility = View.VISIBLE
                imageView?.displayFrame(displayed)
            }
        }
        framePostPending.set(false)

        // If a newer frame arrived during this UI pass, schedule exactly one more pass.
        if (latestFrame !== displayed && framePostPending.compareAndSet(false, true)) {
            imageView?.post { publishLatestFrame() }
        }
    }

    private fun getOrCreateUpscaleView(): SpatialUpscaleSurfaceView {
        upscaleView?.let { return it }
        val view = SpatialUpscaleSurfaceView(context).apply {
            visibility = View.GONE
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        container?.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        upscaleView = view
        return view
    }
}

private class MeasuredPgmImageView(context: Context) : ImageView(context) {
    @Volatile private var pendingFrame: PgmOutputFrame? = null
    private var lastDrawnCameraName: String = ""
    private var lastDrawnFrameSequence: Long = 0L

    fun displayFrame(frame: PgmOutputFrame) {
        pendingFrame = frame
        setImageBitmap(frame.bitmap)
        updatePtzMatrix(frame)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        pendingFrame?.let(::updatePtzMatrix)
    }

    private fun updatePtzMatrix(frame: PgmOutputFrame) {
        if (width <= 0 || height <= 0 || frame.bitmap.width <= 0 || frame.bitmap.height <= 0) {
            return
        }
        val fitScale = minOf(
            width.toFloat() / frame.bitmap.width,
            height.toFloat() / frame.bitmap.height
        )
        val outputScale = fitScale * frame.ptzTransform.scale
        imageMatrix = Matrix().apply {
            setScale(outputScale, outputScale)
            postTranslate(
                width / 2f - frame.ptzTransform.centerX * frame.bitmap.width * outputScale,
                height / 2f - frame.ptzTransform.centerY * frame.bitmap.height * outputScale
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = pendingFrame ?: return
        if (
            frame.cameraName == lastDrawnCameraName &&
            frame.frameSequence <= lastDrawnFrameSequence
        ) {
            return
        }
        lastDrawnCameraName = frame.cameraName
        lastDrawnFrameSequence = frame.frameSequence
        frame.onFrameDrawn(
            frame.cameraName,
            frame.frameSequence,
            SystemClock.elapsedRealtime()
        )
    }
}
