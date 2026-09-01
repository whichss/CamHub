package com.camhub.studio.ui.camera

import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.os.Build
import com.camhub.studio.data.DeviceMonitor
import com.camhub.studio.data.StreamingConfig
import com.camhub.studio.data.audio.AudioCaptureService
import com.camhub.studio.data.camera.CameraController
import com.camhub.studio.data.camera.AppliedCameraPtz
import com.camhub.studio.data.camera.CameraValueMapper
import com.camhub.studio.data.gl.CameraGlRenderer
import com.camhub.studio.data.network.H264Encoder
import com.camhub.studio.data.network.HandshakeMessage
import com.camhub.studio.data.network.PeerConnectionManager
import com.camhub.studio.data.network.NetworkTransportManager
import com.camhub.studio.data.network.StreamServer
import com.camhub.studio.ui.camera.model.CameraUiState
import com.camhub.studio.ui.camera.model.LensInfo
import com.camhub.studio.ui.camera.model.MicDirection
import com.camhub.studio.ui.camera.model.ToolMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

@HiltViewModel
class CameraHudViewModel @Inject constructor(
    private val cameraController: CameraController,
    private val streamServer: StreamServer,
    private val connectionManager: PeerConnectionManager,
    private val deviceMonitor: DeviceMonitor,
    private val audioCaptureService: AudioCaptureService,
    private val networkTransportManager: NetworkTransportManager,
    private val streamingConfig: StreamingConfig
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CameraUiState(
            nodeName = Build.MODEL,
            format = "",
            codec = "",
            lens = LensInfo(),
            shutterValues = CameraValueMapper.generateShutterSpeeds(),
            selectedShutterIndex = 0,
            whiteBalanceValues = CameraValueMapper.generateWhiteBalanceSteps(),
            audioLevels = listOf(0f, 0f),
            streamFps = streamingConfig.fps,
            streamMaxResolution = streamingConfig.maxResolution,
            streamBitrateMbps = streamingConfig.bitrateMbps
        )
    )
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            networkTransportManager.state.collect { networkState ->
                _uiState.update {
                    it.copy(networkTransportLabel = networkState.displayLabel)
                }
            }
        }
    }

    private fun streamStatusText(
        isLive: Boolean = streamServer.getPort() > 0,
        videoClientCount: Int = _uiState.value.videoClientCount,
        bitrateMbps: Int = streamingConfig.bitrateMbps,
        label: String? = null
    ): String {
        val prefix = label ?: if (isLive) "LIVE" else "OFF"
        return "$prefix ${streamingConfig.maxResolution}p${streamingConfig.fps} ${bitrateMbps}M V$videoClientCount"
    }

    private fun handleRemoteCommand(msg: HandshakeMessage) {
        viewModelScope.launch {
            when (msg.command) {
                "set_zoom" -> setZoom(msg.value)
                "set_ptz" -> {
                    val center = msg.stringValue.split(',')
                    val centerX = center.getOrNull(0)?.toFloatOrNull() ?: 0.5f
                    val centerY = center.getOrNull(1)?.toFloatOrNull() ?: 0.5f
                    val applied = applyRemotePtz(msg.value, centerX, centerY)
                    connectionManager.sendPtzAppliedToAll(
                        requestId = msg.requestId,
                        zoom = applied.zoomRatio,
                        centerX = applied.centerX,
                        centerY = applied.centerY
                    )
                }
                "set_iso" -> {
                    val idx = _uiState.value.isoValues.indexOf(msg.stringValue)
                    if (idx >= 0) updateIso(idx)
                }
                "set_shutter" -> {
                    val idx = _uiState.value.shutterValues.indexOf(msg.stringValue)
                    if (idx >= 0) updateShutter(idx)
                }
                "set_focus" -> {
                    val idx = _uiState.value.focusDistances.indexOf(msg.stringValue)
                    if (idx >= 0) updateFocus(idx)
                }
                "start_recording" -> {
                    if (!cameraController.hardwareState.value.isRecording) {
                        cameraController.startRecording(filePrefix = msg.stringValue)
                    }
                }
                "stop_recording" -> {
                    if (cameraController.hardwareState.value.isRecording) {
                        cameraController.stopRecording()
                    }
                }
                "set_stream_bitrate" -> {
                    val mbps = msg.value.toInt().coerceIn(1, 20)
                    applyRemoteStreamBitrate(mbps)
                }
                "set_stream_fps" -> {
                    val fps = msg.value.toInt().coerceIn(1, 60)
                    updateStreamFps(fps)
                }
                "set_stream_resolution" -> {
                    val resolution = msg.value.toInt().coerceIn(360, 2160)
                    updateStreamResolution(resolution)
                }
            }
            _uiState.update { it.copy(isRemoteOverride = true) }
            delay(3000)
            _uiState.update { it.copy(isRemoteOverride = false) }
        }
    }

    init {
        deviceMonitor.startMonitoring()

        // Listen for remote commands from director
        connectionManager.onCommandReceived = { msg -> handleRemoteCommand(msg) }

        // Observe hardware state and map to UI
        viewModelScope.launch {
            try {
                cameraController.hardwareState.collect { hw ->
                    connectionManager.updateLocalCameraCapabilities(
                        supportsRemotePtz = hw.supportsRemotePtz,
                        minZoomRatio = hw.minZoomRatio,
                        maxZoomRatio = hw.maxZoomRatio
                    )
                    _uiState.update { ui ->
                        ui.copy(
                            isCameraBound = hw.isBound,
                            isRecording = hw.isRecording,
                            isManualExposureSupported = hw.isManualExposureSupported,
                            cameraError = hw.error,
                            format = if (hw.isBound) "Camera Active" else "No Camera",
                            codec = if (hw.isBound) "H.264" else "",
                            isoValues = if (hw.isoRange != null) {
                                CameraValueMapper.generateIsoStops(hw.isoRange.lower, hw.isoRange.upper)
                            } else ui.isoValues,
                            focusDistances = if (hw.minFocusDistance > 0f) {
                                CameraValueMapper.generateFocusDistances(hw.minFocusDistance)
                            } else ui.focusDistances,
                            isFrontCamera = cameraController.isFrontCamera(),
                            zoomRatio = cameraController.getCurrentZoomRatio(),
                            minZoomRatio = cameraController.getMinZoomRatio(),
                            maxZoomRatio = cameraController.getMaxZoomRatio(),
                            zoomSteps = CameraValueMapper.generateZoomSteps(
                                cameraController.getMinZoomRatio(),
                                cameraController.getMaxZoomRatio()
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        // Track connection state
        viewModelScope.launch {
            try {
                connectionManager.connectedPeers.collect { peers ->
                    _uiState.update { it.copy(isPgm = peers.isNotEmpty()) }
                }
            } catch (_: Exception) {}
        }

        // Video stream clients connected to this camera.
        viewModelScope.launch {
            streamServer.clientCount.collect { count ->
                _uiState.update {
                    it.copy(
                        videoClientCount = count,
                        bitrate = streamStatusText(
                            isLive = streamServer.getPort() > 0,
                            videoClientCount = count
                        )
                    )
                }
            }
        }

        // Real-time device status (battery, wifi, storage)
        viewModelScope.launch {
            try {
                deviceMonitor.status.collect { device ->
                    _uiState.update {
                        it.copy(
                            batteryPercent = device.batteryPercent,
                            wifiStrength = device.wifiStrength,
                            storageUsedGb = device.storageUsedGb,
                            storageTotalGb = device.storageTotalGb,
                            bitrate = streamStatusText()
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        // Real-time audio levels from microphone capture
        viewModelScope.launch {
            audioCaptureService.audioLevels.collect { levels ->
                _uiState.update { it.copy(audioLevels = levels) }
            }
        }

        // Audio capture health for on-camera troubleshooting
        viewModelScope.launch {
            audioCaptureService.captureStatus.collect { status ->
                _uiState.update {
                    it.copy(
                        audioCaptureStatus = status.statusText,
                        audioClientCount = status.clientCount,
                        audioRestartCount = status.restartCount
                    )
                }
            }
        }

        // Timecode ticker while recording
        viewModelScope.launch {
            while (isActive) {
                delay(33)
                val hw = cameraController.hardwareState.value
                if (hw.isRecording && hw.recordingStartTimeMs > 0L) {
                    val elapsed = System.currentTimeMillis() - hw.recordingStartTimeMs
                    _uiState.update {
                        it.copy(timecode = CameraValueMapper.formatTimecode(elapsed))
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "CameraHudViewModel"
    }

    /** Called after RECORD_AUDIO permission is confirmed — retries mic capture if needed */
    fun ensureAudioCapture() {
        audioCaptureService.ensureCapture()
    }

    private var cameraGlRenderer: CameraGlRenderer? = null
    private var surfaceEncoder: H264Encoder? = null
    private var usingSurfaceMode = false
    private var lastLifecycleOwner: LifecycleOwner? = null
    private var lastViewfinderSurface: Surface? = null
    private var lastSurfaceSize: Size? = null
    private var lastIsDevicePortrait: Boolean = false
    private var lastIsPortraitOutput: Boolean = false
    private var lastTargetRotation: Int = Surface.ROTATION_0
    private var streamRestartJob: Job? = null
    private var zoomDriveJob: Job? = null
    private var zoomVelocity: Float = 0f

    @ExperimentalCamera2Interop
    fun onViewfinderSurfaceReady(
        lifecycleOwner: LifecycleOwner,
        viewfinderSurface: Surface,
        size: Size,
        isDevicePortrait: Boolean = false,
        isPortraitOutput: Boolean = isDevicePortrait,
        targetRotation: Int = Surface.ROTATION_0
    ) {
        lastLifecycleOwner = lifecycleOwner
        lastViewfinderSurface = viewfinderSurface
        lastSurfaceSize = size
        lastIsDevicePortrait = isDevicePortrait
        lastIsPortraitOutput = isPortraitOutput
        lastTargetRotation = targetRotation
        try {
            val viewW = size.width
            val viewH = size.height

            // Stream dimensions follow device orientation rather than the transient
            // TextureView size reported while Android is rotating the layout.
            val maxRes = streamingConfig.maxResolution
            val (encW, encH) = calculateStreamFrameDimensions(
                maxResolution = maxRes,
                isPortrait = isPortraitOutput
            )
            // CameraX still receives an upright portrait buffer while the phone is
            // held vertically. A 16:9 portrait-UI mode crops that upright source
            // into the landscape encoder surface instead of rotating the UI.
            val (bufW, bufH) = if (isDevicePortrait && !isPortraitOutput) {
                encH to encW
            } else {
                encW to encH
            }

            // 1. Start Surface-mode encoder at 16:9
            val fps = streamingConfig.fps
            val bitrate = streamingConfig.bitrateBytes
            val encoder = H264Encoder(encW, encH, bitrate = bitrate, frameRate = fps)
            if (!encoder.startSurface()) {
                Log.w(TAG, "Surface encoder failed, falling back to buffer mode")
                encoder.release()
                fallbackToBufferMode(lifecycleOwner, viewfinderSurface, size)
                return
            }

            val encoderInputSurface = encoder.inputSurface
            if (encoderInputSurface == null) {
                Log.w(TAG, "Encoder input surface is null, falling back to buffer mode")
                encoder.release()
                fallbackToBufferMode(lifecycleOwner, viewfinderSurface, size)
                return
            }

            // 2. Start GL renderer: camera buffer at 16:9 → [viewfinder, encoder]
            //    Viewfinder uses actual surface dimensions; encoder uses 16-aligned dims
            val glRenderer = CameraGlRenderer()
            glRenderer.start(encW, encH, viewfinderSurface, encoderInputSurface, viewW, viewH, bufW, bufH, isDevicePortrait)
            glRenderer.updateRotation(surfaceRotationToDegrees(targetRotation))
            glRenderer.onFrameSubmitted = { presentationTimeUs, captureAtWallMs ->
                encoder.registerInputTiming(presentationTimeUs, captureAtWallMs)
            }

            // Wait for GL thread to create cameraSurface (poll instead of fixed sleep)
            var waitMs = 0
            while (glRenderer.cameraSurface == null && waitMs < 200) {
                Thread.sleep(5)
                waitMs += 5
            }

            val cameraSurface = glRenderer.cameraSurface
            if (cameraSurface == null) {
                Log.w(TAG, "GL renderer cameraSurface is null, falling back to buffer mode")
                glRenderer.stop()
                encoder.release()
                fallbackToBufferMode(lifecycleOwner, viewfinderSurface, size)
                return
            }

            // 3. Async encoding callback: encoder delivers frames via callback (zero-latency)
            // Rotation is applied in GL renderer, so always send rotation=0
            encoder.onEncodedFrame = { frame ->
                streamServer.broadcastEncodedFrame(frame, encW, encH, 0)
            }

            // 4. Bind CameraX to GL renderer's surface
            // SurfaceTexture.getTransformMatrix() already includes sensor→display rotation,
            // so no additional rotation is needed from TransformationInfo.
            // Don't hardcode targetRotation — CameraController uses actual display rotation
            cameraController.onPreviewTransformChanged = { rotationDegrees ->
                glRenderer.updateRotation(rotationDegrees)
            }
            cameraController.bindCameraWithSurface(
                lifecycleOwner = lifecycleOwner,
                cameraSurface = cameraSurface,
                resolution = Size(bufW, bufH),
                targetRotation = targetRotation
            )

            cameraGlRenderer = glRenderer
            surfaceEncoder = encoder
            streamServer.keyFrameRequester = {
                surfaceEncoder?.requestKeyFrame()
            }
            usingSurfaceMode = true
            _uiState.update {
                it.copy(
                    bitrate = streamStatusText(isLive = true),
                    codec = "H.264",
                    format = "${encW}x${encH}"
                )
            }
            Log.d(TAG, "Surface encoding pipeline started: ${encW}x${encH} (16:9)")
        } catch (e: Exception) {
            Log.e(TAG, "Surface pipeline setup failed", e)
            cleanupSurfacePipeline()
            fallbackToBufferMode(lifecycleOwner, viewfinderSurface, size)
        }
    }

    @ExperimentalCamera2Interop
    private fun fallbackToBufferMode(lifecycleOwner: LifecycleOwner, viewfinderSurface: Surface, size: Size) {
        Log.d(TAG, "Using buffer mode fallback — surface preview with ImageAnalysis encoding")
        usingSurfaceMode = false
        // Use the viewfinder surface for preview, but rely on the old onFrame path for encoding
        // Note: this fallback won't have preview in the TextureView since bindCamera needs PreviewView
        // The full fallback is handled at the UI level if onViewfinderSurfaceReady fails
    }

    fun onViewfinderSurfaceDestroyed() {
        streamRestartJob?.cancel()
        streamRestartJob = null
        lastLifecycleOwner = null
        lastViewfinderSurface = null
        lastSurfaceSize = null
        cameraController.unbindCamera()
        cleanupSurfacePipeline()
    }

    private fun cleanupSurfacePipeline() {
        cameraGlRenderer?.stop()
        cameraGlRenderer = null
        surfaceEncoder?.release()
        surfaceEncoder = null
        streamServer.keyFrameRequester = null
        usingSurfaceMode = false
    }

    @ExperimentalCamera2Interop
    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        cameraController.onFrameCallback = { imageProxy ->
            streamServer.onFrame(imageProxy)
        }
        cameraController.bindCamera(lifecycleOwner, previewView)
    }

    fun unbindCamera() {
        streamRestartJob?.cancel()
        streamRestartJob = null
        lastLifecycleOwner = null
        lastViewfinderSurface = null
        lastSurfaceSize = null
        cleanupSurfacePipeline()
        cameraController.unbindCamera()
    }

    private fun scheduleSurfacePipelineRestart() {
        if (!usingSurfaceMode) return
        if (lastLifecycleOwner == null || lastViewfinderSurface == null || lastSurfaceSize == null) return
        streamRestartJob?.cancel()
        _uiState.update {
            it.copy(bitrate = streamStatusText(label = "APPLYING"))
        }
        streamRestartJob = viewModelScope.launch {
            delay(150)
            restartSurfacePipelineIfActive()
        }
    }

    private fun restartSurfacePipelineIfActive() {
        if (!usingSurfaceMode) return
        val lifecycleOwner = lastLifecycleOwner ?: return
        val surface = lastViewfinderSurface ?: return
        val size = lastSurfaceSize ?: return

        Log.d(TAG, "Restarting surface pipeline for stream quality change")
        cleanupSurfacePipeline()
        onViewfinderSurfaceReady(
            lifecycleOwner = lifecycleOwner,
            viewfinderSurface = surface,
            size = size,
            isDevicePortrait = lastIsDevicePortrait,
            isPortraitOutput = lastIsPortraitOutput,
            targetRotation = lastTargetRotation
        )
    }

    private fun surfaceRotationToDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    @ExperimentalCamera2Interop
    fun updateIso(index: Int) {
        _uiState.update { it.copy(selectedIsoIndex = index) }
        val isoStr = _uiState.value.isoValues.getOrNull(index) ?: return
        if (isoStr == "Auto") {
            checkAutoExposure()
        } else {
            cameraController.setIso(CameraValueMapper.isoToInt(isoStr))
        }
    }

    @ExperimentalCamera2Interop
    fun updateShutter(index: Int) {
        _uiState.update { it.copy(selectedShutterIndex = index) }
        val shutterStr = _uiState.value.shutterValues.getOrNull(index) ?: return
        if (shutterStr == "Auto") {
            checkAutoExposure()
        } else {
            cameraController.setShutterSpeed(CameraValueMapper.shutterToNanos(shutterStr))
        }
    }

    /** If both ISO and shutter are "Auto", restore full auto exposure */
    @ExperimentalCamera2Interop
    private fun checkAutoExposure() {
        val isoAuto = _uiState.value.isoValues.getOrNull(_uiState.value.selectedIsoIndex) == "Auto"
        val shutterAuto = _uiState.value.shutterValues.getOrNull(_uiState.value.selectedShutterIndex) == "Auto"
        if (isoAuto && shutterAuto) {
            cameraController.enableAutoExposure()
        }
    }

    @ExperimentalCamera2Interop
    fun updateFocus(index: Int) {
        _uiState.update { it.copy(selectedFocusIndex = index) }
        val focusStr = _uiState.value.focusDistances.getOrNull(index) ?: return
        if (focusStr == "AF") {
            cameraController.enableContinuousAf()
        } else {
            cameraController.setFocusDistance(CameraValueMapper.focusToDiopter(focusStr))
        }
    }

    fun togglePeaking() {
        _uiState.update { it.copy(isPeakingEnabled = !it.isPeakingEnabled) }
    }

    fun toggleTool(mode: ToolMode) {
        _uiState.update {
            it.copy(activeToolMode = if (it.activeToolMode == mode) ToolMode.NONE else mode)
        }
    }

    @ExperimentalCamera2Interop
    fun resetAllToAuto() {
        cameraController.resetAllToAuto()
        _uiState.update {
            it.copy(
                selectedIsoIndex = 0,       // "Auto"
                selectedShutterIndex = 0,   // "Auto"
                selectedFocusIndex = 0,     // "AF"
                selectedWhiteBalanceIndex = 0, // "Auto"
                activeToolMode = ToolMode.NONE
            )
        }
    }

    @ExperimentalCamera2Interop
    fun updateWhiteBalance(index: Int) {
        _uiState.update { it.copy(selectedWhiteBalanceIndex = index) }
        val wbStr = _uiState.value.whiteBalanceValues.getOrNull(index) ?: return
        val kelvin = CameraValueMapper.whiteBalanceToKelvin(wbStr)
        if (kelvin != null) {
            cameraController.setWhiteBalance(kelvin)
        } else {
            cameraController.enableAutoWhiteBalance()
        }
    }

    fun setMicDirection(direction: MicDirection) {
        _uiState.update { it.copy(micDirection = direction, activeToolMode = ToolMode.NONE) }
        val micDir = when (direction) {
            MicDirection.FRONT -> android.media.MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER
            MicDirection.BACK -> android.media.MicrophoneDirection.MIC_DIRECTION_AWAY_FROM_USER
            MicDirection.EXTERNAL -> android.media.MicrophoneDirection.MIC_DIRECTION_EXTERNAL
        }
        audioCaptureService.setPreferredMicrophoneDirection(micDir)
    }

    fun setZoom(ratio: Float) {
        val clamped = ratio.coerceIn(_uiState.value.minZoomRatio, _uiState.value.maxZoomRatio)
        cameraController.setZoomRatio(clamped)
        val steps = _uiState.value.zoomSteps
        val closestIndex = steps.indices.minByOrNull {
            kotlin.math.abs(CameraValueMapper.zoomStepToFloat(steps[it]) - clamped)
        } ?: 0
        _uiState.update { it.copy(zoomRatio = clamped, selectedZoomIndex = closestIndex) }
    }

    /**
     * Drives zoom like a spring-loaded rocker. The signed input is -1..1:
     * farther from center means faster zoom, and zero immediately stops it.
     */
    fun setZoomVelocity(velocity: Float) {
        zoomVelocity = velocity.coerceIn(-1f, 1f)
        if (abs(zoomVelocity) < 0.01f) {
            zoomDriveJob?.cancel()
            zoomDriveJob = null
            return
        }
        if (zoomDriveJob?.isActive == true) return

        zoomDriveJob = viewModelScope.launch {
            var previousNs = System.nanoTime()
            while (isActive) {
                val nowNs = System.nanoTime()
                val deltaSeconds = ((nowNs - previousNs) / 1_000_000_000f)
                    .coerceIn(0f, 0.1f)
                previousNs = nowNs
                val state = _uiState.value
                val nextRatio = calculateVelocityZoomRatio(
                    currentRatio = state.zoomRatio,
                    minRatio = state.minZoomRatio,
                    maxRatio = state.maxZoomRatio,
                    leverPosition = zoomVelocity,
                    deltaSeconds = deltaSeconds
                )
                if (nextRatio != state.zoomRatio) setZoom(nextRatio)
                delay(32)
            }
        }
    }

    @ExperimentalCamera2Interop
    private fun applyRemotePtz(
        ratio: Float,
        centerX: Float,
        centerY: Float
    ): AppliedCameraPtz {
        val clamped = ratio.coerceIn(_uiState.value.minZoomRatio, _uiState.value.maxZoomRatio)
        val applied = cameraController.setPtz(clamped, centerX, centerY)
        if (applied == null) {
            cameraController.setZoomRatio(clamped)
        }
        _uiState.update { it.copy(zoomRatio = clamped) }
        return applied ?: AppliedCameraPtz(
            zoomRatio = clamped,
            centerX = 0.5f,
            centerY = 0.5f
        )
    }

    fun setZoomByIndex(index: Int) {
        val zoomStr = _uiState.value.zoomSteps.getOrNull(index) ?: return
        val ratio = CameraValueMapper.zoomStepToFloat(zoomStr)
        setZoom(ratio)
    }

    fun onPinchZoom(scaleFactor: Float) {
        val newRatio = _uiState.value.zoomRatio * scaleFactor
        setZoom(newRatio)
    }

    fun tapToFocus(x: Float, y: Float, viewWidth: Float, viewHeight: Float) {
        cameraController.tapToFocus(x, y, viewWidth, viewHeight)
        _uiState.update { it.copy(focusPointX = x, focusPointY = y) }
        viewModelScope.launch {
            delay(1500)
            _uiState.update { it.copy(focusPointX = null, focusPointY = null) }
        }
    }

    fun toggleSettingsPanel() {
        _uiState.update { it.copy(showSettingsPanel = !it.showSettingsPanel) }
    }

    fun updateStreamFps(fps: Int) {
        streamingConfig.fps = fps
        streamServer.maxFps = fps
        _uiState.update {
            it.copy(
                streamFps = fps,
                bitrate = streamStatusText()
            )
        }
        scheduleSurfacePipelineRestart()
    }

    fun updateStreamResolution(resolution: Int) {
        streamingConfig.maxResolution = resolution
        _uiState.update {
            it.copy(
                streamMaxResolution = resolution,
                bitrate = streamStatusText()
            )
        }
        scheduleSurfacePipelineRestart()
    }

    fun updateStreamBitrate(mbps: Int) {
        streamingConfig.bitrateMbps = mbps
        val bitrate = streamingConfig.bitrateBytes
        surfaceEncoder?.setBitrate(bitrate)
        streamServer.updateBitrate(bitrate)
        _uiState.update {
            it.copy(
                streamBitrateMbps = mbps,
                bitrate = streamStatusText(bitrateMbps = mbps)
            )
        }
    }

    /** Apply hub ABR for this session without overwriting the camera's saved ceiling. */
    private fun applyRemoteStreamBitrate(mbps: Int) {
        val clamped = mbps.coerceIn(1, 20)
        val bitrate = clamped * 1_000_000
        surfaceEncoder?.setBitrate(bitrate)
        streamServer.updateBitrate(bitrate)
        _uiState.update {
            it.copy(
                streamBitrateMbps = clamped,
                bitrate = streamStatusText(bitrateMbps = clamped)
            )
        }
    }

    fun togglePreviewAspect() {
        _uiState.update { it.copy(isPortraitFullPreview = !it.isPortraitFullPreview) }
    }

    fun toggleRecording() {
        cameraController.toggleRecording()
    }

    @ExperimentalCamera2Interop
    fun switchCamera() {
        cameraController.switchCamera()
        _uiState.update { it.copy(isFrontCamera = cameraController.isFrontCamera()) }
    }

    override fun onCleared() {
        super.onCleared()
        zoomDriveJob?.cancel()
        connectionManager.onCommandReceived = null
        try { cleanupSurfacePipeline() } catch (_: Exception) {}
        try { cameraController.unbindCamera() } catch (_: Exception) {}
        try { deviceMonitor.stopMonitoring() } catch (_: Exception) {}
        try { audioCaptureService.stop() } catch (_: Exception) {}
    }
}
