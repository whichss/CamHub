package com.camhub.studio.ui.director

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.camhub.studio.data.DeviceMonitor
import com.camhub.studio.data.DirectorRecorder
import com.camhub.studio.data.ExternalDisplayManager
import com.camhub.studio.data.audio.AudioStreamClient
import com.camhub.studio.data.camera.CameraValueMapper
import com.camhub.studio.data.network.ConnectedPeer
import com.camhub.studio.data.network.PeerConnectionManager
import com.camhub.studio.data.network.StreamClient
import com.camhub.studio.ui.director.model.CameraNode
import com.camhub.studio.ui.director.model.ConnectionStatus
import com.camhub.studio.ui.director.model.DirectorUiState
import com.camhub.studio.ui.director.model.TransitionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DirectorViewModel @Inject constructor(
    private val connectionManager: PeerConnectionManager,
    private val streamClient: StreamClient,
    private val audioStreamClient: AudioStreamClient,
    private val deviceMonitor: DeviceMonitor,
    private val recorder: DirectorRecorder,
    private val externalDisplayManager: ExternalDisplayManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(DirectorUiState())
    val uiState: StateFlow<DirectorUiState> = _uiState.asStateFlow()

    private var lastPgmBitmap: Bitmap? = null
    private var transitionJob: Job? = null

    init {
        deviceMonitor.startMonitoring()
        externalDisplayManager.startListening()

        // Observe connected peers and build camera list from them
        viewModelScope.launch {
            connectionManager.connectedPeers.collect { peers ->
                val currentPeerNames = peers.map { it.name }.toSet()

                // Auto-cleanup: disconnect streams for peers that are no longer connected
                val activeStreams = streamClient.streams.value.keys
                for (name in activeStreams) {
                    if (name !in currentPeerNames) {
                        streamClient.disconnectStream(name)
                        audioStreamClient.disconnectAudioStream(name)
                    }
                }

                updateCamerasFromPeers(peers)
                if (peers.isNotEmpty()) {
                    connectToStreams(peers)
                }
            }
        }

        // Observe stream bitmaps and update camera nodes
        viewModelScope.launch {
            streamClient.streams.collect { streams ->
                _uiState.update { state ->
                    val updatedCameras = state.cameras.map { cam ->
                        val stream = streams[cam.name]
                        if (stream != null) {
                            cam.copy(
                                previewBitmap = stream.bitmap?.asImageBitmap() ?: cam.previewBitmap,
                                frameWidth = if (stream.frameWidth > 0) stream.frameWidth else cam.frameWidth,
                                frameHeight = if (stream.frameHeight > 0) stream.frameHeight else cam.frameHeight,
                                bitrateKbps = stream.bitrateKbps
                            )
                        } else {
                            cam
                        }
                    }
                    state.copy(cameras = updatedCameras)
                }

                // Track PGM bitmap for recording + external display
                val pgmIdx = _uiState.value.pgmCameraIndex
                val pgmCam = _uiState.value.cameras.getOrNull(pgmIdx)
                val pgmStream = pgmCam?.let { streams[it.name] }
                if (pgmStream?.bitmap != null) {
                    lastPgmBitmap = pgmStream.bitmap
                    if (recorder.recordingInfo.value.isRecording) {
                        recorder.onFrame(pgmStream.bitmap)
                    }
                    if (externalDisplayManager.isOutputEnabled.value) {
                        externalDisplayManager.updateFrame(pgmStream.bitmap)
                    }
                }
            }
        }

        // Real-time device status (battery, wifi, storage)
        viewModelScope.launch {
            deviceMonitor.status.collect { device ->
                _uiState.update {
                    it.copy(
                        batteryPercent = device.batteryPercent,
                        wifiStrength = device.wifiStrength,
                        storageUsedGb = device.storageUsedGb,
                        storageTotalGb = device.storageTotalGb
                    )
                }
            }
        }

        // Real-time bitrate from stream client
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val totalBitrate = streamClient.getTotalBitrateKbps()
                _uiState.update { it.copy(bitrateKbps = totalBitrate) }
            }
        }

        // Recording state + timecode
        viewModelScope.launch {
            recorder.recordingInfo.collect { info ->
                _uiState.update {
                    it.copy(
                        isRecording = info.isRecording,
                        isPaused = info.isPaused,
                        recordingPath = info.outputPath
                    )
                }
            }
        }

        // Timecode ticker while recording (skip updates when paused)
        viewModelScope.launch {
            while (isActive) {
                delay(33)
                val info = recorder.recordingInfo.value
                if (info.isRecording && info.startTimeMs > 0L && !info.isPaused) {
                    val elapsed = System.currentTimeMillis() - info.startTimeMs - info.pausedDurationMs
                    _uiState.update {
                        it.copy(timecode = CameraValueMapper.formatTimecode(elapsed))
                    }
                }
            }
        }

        // External display state
        viewModelScope.launch {
            externalDisplayManager.isExternalDisplayConnected.collect { connected ->
                _uiState.update { it.copy(isExternalDisplayConnected = connected) }
            }
        }

        viewModelScope.launch {
            externalDisplayManager.isOutputEnabled.collect { enabled ->
                _uiState.update { it.copy(isExternalDisplayEnabled = enabled) }
            }
        }

        // Propagate PGM camera to audio mixer for AFV logic
        viewModelScope.launch {
            _uiState.collect { state ->
                val pgmName = state.cameras.getOrNull(state.pgmCameraIndex)?.name
                audioStreamClient.setPgmCameras(if (pgmName != null) setOf(pgmName) else emptySet())
            }
        }
    }

    private fun updateCamerasFromPeers(peers: List<ConnectedPeer>) {
        _uiState.update { state ->
            val existingById = state.cameras.associateBy { it.name }

            val cameras = peers.mapIndexed { index, peer ->
                val existing = existingById[peer.name]
                existing?.copy(
                    status = ConnectionStatus.LIVE
                ) ?: CameraNode(
                    id = "CAM-${index + 1}",
                    name = peer.name,
                    label = peer.ip,
                    fps = 30,
                    tempCelsius = 0,
                    status = ConnectionStatus.LIVE
                )
            }

            val pgmIdx = if (state.pgmCameraIndex in cameras.indices) state.pgmCameraIndex
                else if (cameras.isNotEmpty()) 0 else -1
            val pvwIdx = if (state.pvwCameraIndex in cameras.indices) state.pvwCameraIndex
                else if (cameras.size > 1) 1 else -1

            val finalCameras = cameras.mapIndexed { i, cam ->
                cam.copy(isPgm = i == pgmIdx, isPvw = i == pvwIdx)
            }

            state.copy(
                cameras = finalCameras,
                pgmCameraIndex = pgmIdx,
                pvwCameraIndex = pvwIdx
            )
        }
    }

    /** Connect to video and audio streams, passing session key for AES decryption */
    private fun connectToStreams(peers: List<ConnectedPeer>) {
        val alreadyStreaming = streamClient.streams.value.keys
        val alreadyAudio = audioStreamClient.channelStates.value.keys
        for (peer in peers) {
            if (peer.streamPort > 0 && peer.name !in alreadyStreaming) {
                streamClient.connectToStream(
                    cameraName = peer.name,
                    ip = peer.ip,
                    streamPort = peer.streamPort,
                    sessionKey = peer.streamKey
                )
            }
            if (peer.audioStreamPort > 0 && peer.name !in alreadyAudio) {
                audioStreamClient.connectToAudioStream(
                    cameraName = peer.name,
                    ip = peer.ip,
                    audioPort = peer.audioStreamPort,
                    sessionKey = peer.streamKey
                )
            }
        }
    }

    fun selectPvw(cameraIndex: Int) {
        _uiState.update { state ->
            val updatedCameras = state.cameras.mapIndexed { i, cam ->
                cam.copy(isPvw = i == cameraIndex, isPgm = cam.isPgm)
            }
            state.copy(pvwCameraIndex = cameraIndex, cameras = updatedCameras)
        }
    }

    fun executeCut() {
        _uiState.update { state ->
            val oldPgm = state.pgmCameraIndex
            val oldPvw = state.pvwCameraIndex
            val updatedCameras = state.cameras.mapIndexed { i, cam ->
                cam.copy(
                    isPgm = i == oldPvw,
                    isPvw = i == oldPgm
                )
            }
            state.copy(
                pgmCameraIndex = oldPvw,
                pvwCameraIndex = oldPgm,
                cameras = updatedCameras
            )
        }
    }

    fun executeAuto() {
        val transition = _uiState.value.selectedTransition
        when (transition) {
            TransitionType.CUT -> executeCut()
            TransitionType.MIX -> executeMixTransition()
            else -> executeCut() // DIP and WIPE fall back to CUT for now
        }
    }

    private fun executeMixTransition() {
        if (_uiState.value.isTransitioning) return
        transitionJob?.cancel()
        _uiState.update { it.copy(isTransitioning = true) }

        transitionJob = viewModelScope.launch {
            val durationMs = 1000L
            val steps = 30
            val stepDelay = durationMs / steps

            for (i in 1..steps) {
                if (!isActive) break
                val progress = i.toFloat() / steps
                _uiState.update { it.copy(transitionProgress = progress, tBarPosition = progress) }
                delay(stepDelay)
            }

            // Complete: swap PGM/PVW
            performSwap()
            _uiState.update {
                it.copy(
                    transitionProgress = 0f,
                    tBarPosition = 0f,
                    isTransitioning = false
                )
            }
        }
    }

    private fun performSwap() {
        _uiState.update { state ->
            val oldPgm = state.pgmCameraIndex
            val oldPvw = state.pvwCameraIndex
            val updatedCameras = state.cameras.mapIndexed { i, cam ->
                cam.copy(
                    isPgm = i == oldPvw,
                    isPvw = i == oldPgm
                )
            }
            state.copy(
                pgmCameraIndex = oldPvw,
                pvwCameraIndex = oldPgm,
                cameras = updatedCameras
            )
        }
    }

    fun updateTBar(position: Float) {
        if (_uiState.value.isTransitioning) return
        _uiState.update { it.copy(tBarPosition = position, transitionProgress = position) }

        // Auto-swap when T-Bar reaches the end
        if (position >= 1.0f) {
            performSwap()
            _uiState.update { it.copy(tBarPosition = 0f, transitionProgress = 0f) }
        }
    }

    fun selectTransition(type: TransitionType) {
        _uiState.update { it.copy(selectedTransition = type) }
    }

    fun disconnectCamera(cameraIndex: Int) {
        val camera = _uiState.value.cameras.getOrNull(cameraIndex) ?: return
        val cameraName = camera.name
        connectionManager.disconnectPeer(cameraName)
        streamClient.disconnectStream(cameraName)
        audioStreamClient.disconnectAudioStream(cameraName)
    }

    fun showCameraControl(cameraIndex: Int) {
        _uiState.update { it.copy(showCameraControl = true, controlCameraIndex = cameraIndex) }
    }

    fun hideCameraControl() {
        _uiState.update { it.copy(showCameraControl = false, controlCameraIndex = -1) }
    }

    fun sendCameraCommand(command: String, value: Float = 0f, stringValue: String = "") {
        val cam = _uiState.value.cameras.getOrNull(_uiState.value.controlCameraIndex) ?: return
        connectionManager.sendCommand(cam.name, command, value, stringValue)
    }

    fun toggleAudioMixer() {
        _uiState.update { it.copy(showAudioMixer = !it.showAudioMixer) }
    }

    fun toggleRecording() {
        if (recorder.recordingInfo.value.isRecording) {
            recorder.stopRecording()
        } else {
            recorder.startRecording()
        }
    }

    fun pauseRecording() {
        recorder.pauseRecording()
    }

    fun resumeRecording() {
        recorder.resumeRecording()
    }

    override fun onCleared() {
        super.onCleared()
        deviceMonitor.stopMonitoring()
        externalDisplayManager.stopListening()
        externalDisplayManager.disableOutput()
        if (recorder.recordingInfo.value.isRecording) {
            recorder.stopRecording()
        }
        streamClient.disconnectAll()
        audioStreamClient.disconnectAll()
    }
}
