package com.camhub.studio.data.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

data class CameraHardwareState(
    val isBound: Boolean = false,
    val isRecording: Boolean = false,
    val recordingStartTimeMs: Long = 0L,
    val isoRange: Range<Int>? = null,
    val exposureTimeRange: Range<Long>? = null,
    val minFocusDistance: Float = 0f,
    val isManualExposureSupported: Boolean = false,
    val error: String? = null
)

@Singleton
class CameraController @Inject constructor(
    private val context: Context
) {
    private val _hardwareState = MutableStateFlow(CameraHardwareState())
    val hardwareState: StateFlow<CameraHardwareState> = _hardwareState.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var camera2Control: Camera2CameraControl? = null
    private var activeRecording: Recording? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    var onFrameCallback: ((ImageProxy) -> Unit)? = null

    private var currentLifecycleOwner: LifecycleOwner? = null
    private var currentPreview: Preview? = null
    private var currentPreviewView: PreviewView? = null
    private var orientationListener: OrientationEventListener? = null

    var selectedCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        private set

    private var currentCameraSurface: Surface? = null
    private var currentSurfaceResolution: Size? = null
    private var currentSensorOrientation: Int = 90
    var onRotationChanged: ((Int) -> Unit)? = null
    /**
     * Callback with device rotation degrees for GL renderer.
     * The SurfaceTexture's getTransformMatrix() already handles sensor orientation,
     * so we only pass the device rotation to compensate for orientation changes.
     */
    var onPreviewTransformChanged: ((Int) -> Unit)? = null

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun bindCameraWithSurface(
        lifecycleOwner: LifecycleOwner,
        cameraSurface: Surface,
        resolution: Size
    ) {
        currentLifecycleOwner = lifecycleOwner
        currentCameraSurface = cameraSurface
        currentSurfaceResolution = resolution
        currentPreviewView = null

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    return@addListener
                }

                val provider = providerFuture.get()
                cameraProvider = provider

                // Use actual display rotation so CameraX selects correct resolution
                val display = (context as? android.app.Activity)?.windowManager?.defaultDisplay
                val actualDisplayRotation = display?.rotation ?: Surface.ROTATION_0

                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(resolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    )
                    .build()

                val preview = Preview.Builder()
                    .setTargetRotation(actualDisplayRotation)
                    .setResolutionSelector(resolutionSelector)
                    .build().also { prev ->
                        prev.surfaceProvider = Preview.SurfaceProvider { request ->
                            request.provideSurface(cameraSurface, ContextCompat.getMainExecutor(context)) { }
                        }
                    }
                currentPreview = preview

                // No ImageAnalysis needed — Surface mode uses GL pipeline
                imageAnalysis = null

                orientationListener?.disable()
                orientationListener = object : OrientationEventListener(context) {
                    override fun onOrientationChanged(orientation: Int) {
                        if (orientation == ORIENTATION_UNKNOWN) return
                        val surfaceRotation = when {
                            orientation < 45 || orientation >= 315 -> Surface.ROTATION_0
                            orientation < 135 -> Surface.ROTATION_270
                            orientation < 225 -> Surface.ROTATION_180
                            else -> Surface.ROTATION_90
                        }
                        currentPreview?.targetRotation = surfaceRotation
                        // SurfaceTexture's getTransformMatrix() already handles sensor orientation.
                        // Only pass device rotation so GL renderer compensates for orientation changes.
                        onPreviewTransformChanged?.invoke(surfaceRotationToDegrees(surfaceRotation))
                        onRotationChanged?.invoke(orientation)
                    }
                }.also { it.enable() }

                provider.unbindAll()
                val cameraSelector = selectedCameraSelector

                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview
                )

                // Try to add VideoCapture
                try {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                        .build()
                    videoCapture = VideoCapture.withOutput(recorder)

                    provider.unbindAll()
                    camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        videoCapture
                    )
                } catch (_: Exception) {
                    videoCapture = null
                }

                camera?.let { cam ->
                    camera2Control = Camera2CameraControl.from(cam.cameraControl)
                    val camera2Info = Camera2CameraInfo.from(cam.cameraInfo)

                    val isoRange = camera2Info.getCameraCharacteristic(
                        CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
                    )
                    val exposureRange = camera2Info.getCameraCharacteristic(
                        CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
                    )
                    val minFocusDist = camera2Info.getCameraCharacteristic(
                        CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
                    ) ?: 0f

                    // Enable continuous AF by default
                    enableContinuousAf()

                    // Store sensor orientation for reference
                    currentSensorOrientation = camera2Info.getCameraCharacteristic(
                        CameraCharacteristics.SENSOR_ORIENTATION
                    ) ?: 90
                    // SurfaceTexture's getTransformMatrix() handles sensor orientation.
                    // Only pass device rotation for orientation change compensation.
                    onPreviewTransformChanged?.invoke(surfaceRotationToDegrees(actualDisplayRotation))

                    _hardwareState.value = CameraHardwareState(
                        isBound = true,
                        isoRange = isoRange,
                        exposureTimeRange = exposureRange,
                        minFocusDistance = minFocusDist,
                        isManualExposureSupported = isoRange != null && exposureRange != null
                    )
                }
            } catch (e: Exception) {
                _hardwareState.value = _hardwareState.value.copy(
                    error = e.message ?: "Failed to bind camera with surface"
                )
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        currentLifecycleOwner = lifecycleOwner
        currentPreviewView = previewView
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                // Check lifecycle is still valid before binding
                if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    return@addListener
                }

                val provider = providerFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                currentPreview = preview

                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            val callback = onFrameCallback
                            if (callback != null) {
                                callback(imageProxy)
                            } else {
                                imageProxy.close()
                            }
                        }
                    }

                // Update target rotation when device orientation changes
                // (needed because configChanges prevents Activity recreation)
                orientationListener?.disable()
                orientationListener = object : OrientationEventListener(context) {
                    override fun onOrientationChanged(orientation: Int) {
                        if (orientation == ORIENTATION_UNKNOWN) return
                        val rotation = when {
                            orientation < 45 || orientation >= 315 -> Surface.ROTATION_0
                            orientation < 135 -> Surface.ROTATION_270
                            orientation < 225 -> Surface.ROTATION_180
                            else -> Surface.ROTATION_90
                        }
                        imageAnalysis?.targetRotation = rotation
                    }
                }.also { it.enable() }

                provider.unbindAll()

                val cameraSelector = selectedCameraSelector

                // Bind Preview + ImageAnalysis first (2 use cases for broad device compatibility)
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                // Try to add VideoCapture as a third use case
                try {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                        .build()
                    videoCapture = VideoCapture.withOutput(recorder)

                    provider.unbindAll()
                    camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        videoCapture,
                        imageAnalysis
                    )
                } catch (_: Exception) {
                    // Device doesn't support 3 use cases; recording won't be available
                    videoCapture = null
                }

                camera?.let { cam ->
                    camera2Control = Camera2CameraControl.from(cam.cameraControl)
                    val camera2Info = Camera2CameraInfo.from(cam.cameraInfo)

                    val isoRange = camera2Info.getCameraCharacteristic(
                        CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
                    )
                    val exposureRange = camera2Info.getCameraCharacteristic(
                        CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
                    )
                    val minFocusDist = camera2Info.getCameraCharacteristic(
                        CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
                    ) ?: 0f

                    // Enable continuous AF by default
                    enableContinuousAf()

                    _hardwareState.value = CameraHardwareState(
                        isBound = true,
                        isoRange = isoRange,
                        exposureTimeRange = exposureRange,
                        minFocusDistance = minFocusDist,
                        isManualExposureSupported = isoRange != null && exposureRange != null
                    )
                }
            } catch (e: Exception) {
                _hardwareState.value = _hardwareState.value.copy(
                    error = e.message ?: "Failed to bind camera"
                )
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun switchCamera() {
        selectedCameraSelector = if (selectedCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        // Rebind with new selector — handle both PreviewView and Surface modes
        val lifecycle = currentLifecycleOwner ?: return
        val preview = currentPreviewView
        val surface = currentCameraSurface
        val resolution = currentSurfaceResolution
        if (preview != null) {
            bindCamera(lifecycle, preview)
        } else if (surface != null && resolution != null) {
            bindCameraWithSurface(lifecycle, surface, resolution)
        }
    }

    fun isFrontCamera(): Boolean = selectedCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA

    fun unbindCamera() {
        stopRecording()
        onFrameCallback = null
        onRotationChanged = null
        onPreviewTransformChanged = null
        orientationListener?.disable()
        orientationListener = null
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        camera = null
        camera2Control = null
        imageAnalysis = null
        currentPreview = null
        currentPreviewView = null
        currentCameraSurface = null
        currentSurfaceResolution = null
        currentLifecycleOwner = null
        lastManualIso = null
        lastManualShutterNanos = null
        _hardwareState.value = CameraHardwareState()
    }

    // Track current manual values to avoid flickering (set both together)
    private var lastManualIso: Int? = null
    private var lastManualShutterNanos: Long? = null

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun setIso(isoValue: Int) {
        lastManualIso = isoValue
        applyManualExposure()
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun setShutterSpeed(nanos: Long) {
        lastManualShutterNanos = nanos
        applyManualExposure()
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun applyManualExposure() {
        camera2Control?.let { ctrl ->
            val builder = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            lastManualIso?.let {
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, it)
            }
            lastManualShutterNanos?.let {
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, it)
            }
            ctrl.addCaptureRequestOptions(builder.build())
        }
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun enableAutoExposure() {
        lastManualIso = null
        lastManualShutterNanos = null
        camera2Control?.let { ctrl ->
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                .build()
            ctrl.addCaptureRequestOptions(options)
        }
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun setFocusDistance(diopter: Float) {
        camera2Control?.let { ctrl ->
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopter)
                .build()
            ctrl.addCaptureRequestOptions(options)
        }
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun enableContinuousAf() {
        camera2Control?.let { ctrl ->
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
                .build()
            ctrl.addCaptureRequestOptions(options)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.video.ExperimentalPersistentRecording::class)
    fun startRecording() {
        val vc = videoCapture ?: return
        if (activeRecording != null) return

        val name = "CamHub_${System.currentTimeMillis()}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CamHub")
            }
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        activeRecording = vc.output
            .prepareRecording(context, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        _hardwareState.value = _hardwareState.value.copy(
                            isRecording = true,
                            recordingStartTimeMs = System.currentTimeMillis()
                        )
                    }
                    is VideoRecordEvent.Finalize -> {
                        _hardwareState.value = _hardwareState.value.copy(
                            isRecording = false,
                            recordingStartTimeMs = 0L
                        )
                        activeRecording = null
                    }
                }
            }
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    fun toggleRecording() {
        if (_hardwareState.value.isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    fun tapToFocus(x: Float, y: Float, viewWidth: Float, viewHeight: Float) {
        val cam = camera ?: return
        val factory = SurfaceOrientedMeteringPointFactory(viewWidth, viewHeight)
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point)
            .disableAutoCancel()
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun setZoomRatio(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    fun getMinZoomRatio(): Float =
        camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f

    fun getMaxZoomRatio(): Float =
        camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f

    fun getCurrentZoomRatio(): Float =
        camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

    private fun surfaceRotationToDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_0 -> 0
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

}
