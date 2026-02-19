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
import com.camhub.studio.data.audio.AudioCaptureService
import com.camhub.studio.data.camera.CameraController
import com.camhub.studio.data.camera.CameraValueMapper
import com.camhub.studio.data.gl.CameraGlRenderer
import com.camhub.studio.data.network.H264Encoder
import com.camhub.studio.data.network.HandshakeMessage
import com.camhub.studio.data.network.PeerConnectionManager
import com.camhub.studio.data.network.StreamServer
import com.camhub.studio.ui.camera.model.CameraUiState
import com.camhub.studio.ui.camera.model.LensInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CameraHudViewModel @Inject constructor(
    private val cameraController: CameraController,
    private val streamServer: StreamServer,
    private val connectionManager: PeerConnectionManager,
    private val deviceMonitor: DeviceMonitor,
    private val audioCaptureService: AudioCaptureService
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CameraUiState(
            nodeName = Build.MODEL,
            format = "",
            codec = "",
            lens = LensInfo(),
            shutterValues = CameraValueMapper.generateShutterSpeeds(),
            selectedShutterIndex = 0,
            audioLevels = listOf(0f, 0f)
        )
    )
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    @OptIn(ExperimentalCamera2Interop::class)
    private fun handleRemoteCommand(msg: HandshakeMessage) {
        viewModelScope.launch {
            when (msg.command) {
                "set_zoom" -> setZoom(msg.value)
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
                            maxZoomRatio = cameraController.getMaxZoomRatio()
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
                            bitrate = "${streamServer.getPort().let { p -> if (p > 0) "LIVE" else "OFF" }}"
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

    @ExperimentalCamera2Interop
    fun onViewfinderSurfaceReady(lifecycleOwner: LifecycleOwner, viewfinderSurface: Surface, size: Size) {
        try {
            val viewW = size.width
            val viewH = size.height

            // Force 16:9 encode resolution, aligned to 16 for H.264 encoder compatibility
            val encW: Int
            val encH: Int
            if (viewW >= viewH) {
                // Landscape: 16:9
                encH = (minOf(viewH, 1080) / 16) * 16
                encW = (encH * 16 / 9 + 15) / 16 * 16
            } else {
                // Portrait: 9:16
                encW = (minOf(viewW, 1080) / 16) * 16
                encH = (encW * 16 / 9 + 15) / 16 * 16
            }

            // 1. Start Surface-mode encoder at 16:9
            val encoder = H264Encoder(encW, encH, frameRate = 30)
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
            val glRenderer = CameraGlRenderer()
            glRenderer.start(encW, encH, viewfinderSurface, encoderInputSurface)

            // Wait briefly for GL thread to create cameraSurface
            Thread.sleep(100)

            val cameraSurface = glRenderer.cameraSurface
            if (cameraSurface == null) {
                Log.w(TAG, "GL renderer cameraSurface is null, falling back to buffer mode")
                glRenderer.stop()
                encoder.release()
                fallbackToBufferMode(lifecycleOwner, viewfinderSurface, size)
                return
            }

            // 3. Encoding callback: drain encoded frames → broadcast (off GL thread)
            // Rotation is already applied in GL renderer, so always send rotation=0
            val broadcastExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
            glRenderer.onFrameEncoded = {
                val frames = encoder.drainOutput()
                if (frames.isNotEmpty()) {
                    broadcastExecutor.execute {
                        for (frame in frames) {
                            streamServer.broadcastEncodedFrame(frame, encW, encH, 0)
                        }
                    }
                }
            }

            // 4. No extra GL rotation needed — surface is rebuilt with correct dimensions
            // on each orientation change (onSurfaceTextureSizeChanged), and the
            // SurfaceTexture transform matrix handles sensor→buffer mapping automatically.

            // 5. Bind CameraX to GL renderer's surface at 16:9
            cameraController.bindCameraWithSurface(lifecycleOwner, cameraSurface, Size(encW, encH))

            cameraGlRenderer = glRenderer
            surfaceEncoder = encoder
            usingSurfaceMode = true
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
        cameraController.unbindCamera()
        cleanupSurfacePipeline()
    }

    private fun cleanupSurfacePipeline() {
        cameraGlRenderer?.stop()
        cameraGlRenderer = null
        surfaceEncoder?.release()
        surfaceEncoder = null
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
        cleanupSurfacePipeline()
        cameraController.unbindCamera()
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

    fun toggleExposurePanel() {
        _uiState.update { it.copy(showExposurePanel = !it.showExposurePanel, showFocusPanel = false) }
    }

    fun toggleFocusPanel() {
        _uiState.update { it.copy(showFocusPanel = !it.showFocusPanel, showExposurePanel = false) }
    }

    fun setZoom(ratio: Float) {
        val clamped = ratio.coerceIn(_uiState.value.minZoomRatio, _uiState.value.maxZoomRatio)
        cameraController.setZoomRatio(clamped)
        _uiState.update { it.copy(zoomRatio = clamped) }
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
        cleanupSurfacePipeline()
        cameraController.unbindCamera()
        deviceMonitor.stopMonitoring()
    }
}
