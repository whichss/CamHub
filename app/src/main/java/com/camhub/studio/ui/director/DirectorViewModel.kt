package com.camhub.studio.ui.director

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.camhub.studio.data.DeviceMonitor
import com.camhub.studio.data.DirectorRecorder
import com.camhub.studio.data.ExternalDisplayManager
import com.camhub.studio.data.StreamingConfig
import com.camhub.studio.data.audio.AudioStreamClient
import com.camhub.studio.data.camera.CameraValueMapper
import com.camhub.studio.data.capability.DeviceCapabilityProbe
import com.camhub.studio.data.capability.HubPerformanceGovernor
import com.camhub.studio.data.capability.HubPerformanceSample
import com.camhub.studio.data.capability.HubProfileApplicationPolicy
import com.camhub.studio.data.capability.HubRuntimeRecommendation
import com.camhub.studio.data.capability.HubStreamLimits
import com.camhub.studio.data.capability.PgmRecordingPolicy
import com.camhub.studio.data.ptz.HybridPtzState
import com.camhub.studio.data.ptz.HybridPtzController
import com.camhub.studio.data.ptz.PtzMode
import com.camhub.studio.data.network.ConnectedPeer
import com.camhub.studio.data.network.AdaptiveBitrateController
import com.camhub.studio.data.network.AdaptiveBitrateSample
import com.camhub.studio.data.network.DiscoveredPeer
import com.camhub.studio.data.network.NsdDiscoveryManager
import com.camhub.studio.data.network.NetworkTransportManager
import com.camhub.studio.data.network.PeerConnectionManager
import com.camhub.studio.data.network.StreamClient
import com.camhub.studio.data.network.VideoTransport
import com.camhub.studio.ui.director.model.CameraNode
import com.camhub.studio.ui.director.model.ConnectionStatus
import com.camhub.studio.ui.director.model.DirectorUiState
import com.camhub.studio.ui.director.model.TransitionType
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class DirectorViewModel @Inject constructor(
    private val connectionManager: PeerConnectionManager,
    private val streamClient: StreamClient,
    private val audioStreamClient: AudioStreamClient,
    private val deviceMonitor: DeviceMonitor,
    private val recorder: DirectorRecorder,
    private val externalDisplayManager: ExternalDisplayManager,
    private val nsdManager: NsdDiscoveryManager,
    private val networkTransportManager: NetworkTransportManager,
    private val deviceCapabilityProbe: DeviceCapabilityProbe,
    private val streamingConfig: StreamingConfig
) : ViewModel() {

    private data class PendingRemotePtz(
        val requestId: Long,
        val requestedState: HybridPtzState
    )

    private data class AdaptiveCounterSnapshot(
        val udpPacketsReceived: Long,
        val udpMissingPackets: Long,
        val droppedFrames: Int
    )

    private val directorName = "${Build.MODEL}-Director"

    private val _uiState = MutableStateFlow(DirectorUiState())
    val uiState: StateFlow<DirectorUiState> = _uiState.asStateFlow()

    private var lastPgmBitmap: Bitmap? = null
    private var transitionJob: Job? = null
    private val imageBitmapCache = IdentityHashMap<Bitmap, ImageBitmap>()
    private var performanceGovernor: HubPerformanceGovernor? = null
    private var lastGovernorDroppedFrames = 0
    private var lastAppliedStreamLimits: HubStreamLimits? = null
    private var wasAutomaticHubProfileEnabled = false
    private var wasAdaptiveBitrateEnabled = false
    private val adaptiveBitrateControllers = mutableMapOf<String, AdaptiveBitrateController>()
    private val adaptiveCounterSnapshots = mutableMapOf<String, AdaptiveCounterSnapshot>()
    private var lastAutomaticPeerNames: Set<String> = emptySet()
    private val ptzCommandJobs = mutableMapOf<String, Job>()
    private val pendingRemotePtz = mutableMapOf<String, PendingRemotePtz>()
    private val ptzRequestSequence = AtomicLong(0L)
    // These collections are read by StateFlow collectors started in init.
    // Keep them initialized before init runs; declaring them farther down the
    // class leaves their JVM fields null when StateFlow emits immediately.
    private val connectedStreamNames = mutableSetOf<String>()
    private val connectedAudioNames = mutableSetOf<String>()
    private val connectedStreamNetworkHandles = mutableMapOf<String, Long>()
    private val connectedAudioNetworkHandles = mutableMapOf<String, Long>()
    private val desiredCameraIps = mutableSetOf<String>()
    private val reconnectJobs = mutableMapOf<String, Job>()
    private val offlineMarkJobs = mutableMapOf<String, Job>()

    fun onFrameDrawn(cameraName: String, frameSequence: Long) {
        streamClient.markFrameDrawn(
            cameraName = cameraName,
            frameSequence = frameSequence,
            drawnAtElapsedMs = SystemClock.elapsedRealtime()
        )
    }

    init {
        deviceMonitor.startMonitoring()
        externalDisplayManager.startListening()

        viewModelScope.launch {
            networkTransportManager.state.collect { transportState ->
                _uiState.update {
                    it.copy(networkTransportLabel = transportState.displayLabel)
                }
            }
        }

        viewModelScope.launch {
            connectionManager.ptzAppliedEvents.collect { event ->
                val pending = pendingRemotePtz[event.cameraName] ?: return@collect
                if (pending.requestId != event.requestId) return@collect

                val camera = _uiState.value.cameras.firstOrNull {
                    it.name == event.cameraName
                } ?: return@collect
                ptzCommandJobs.remove(event.cameraName)?.cancel()
                ptzCommandJobs[event.cameraName] = launch {
                    val frameArrivalDelayMs = (
                        camera.latencyP95Ms + REMOTE_PTZ_FRAME_MARGIN_MS
                    ).coerceIn(
                        MIN_REMOTE_PTZ_FRAME_DELAY_MS,
                        MAX_REMOTE_PTZ_FRAME_DELAY_MS
                    ).toLong()
                    delay(frameArrivalDelayMs)

                    if (pendingRemotePtz[event.cameraName] != pending) return@launch
                    _uiState.update { state ->
                        state.copy(
                            cameras = state.cameras.map { current ->
                                if (
                                    current.name == event.cameraName &&
                                    current.ptzState == pending.requestedState &&
                                    current.ptzState.ptzMode == PtzMode.REMOTE_PENDING
                                ) {
                                    current.copy(
                                        ptzState = HybridPtzController.markRemoteApplied(
                                            state = current.ptzState,
                                            appliedZoom = event.zoom,
                                            appliedCenterX = event.centerX,
                                            appliedCenterY = event.centerY
                                        )
                                    )
                                } else {
                                    current
                                }
                            }
                        )
                    }
                    pendingRemotePtz.remove(event.cameraName)
                    ptzCommandJobs.remove(event.cameraName)
                }
            }
        }

        viewModelScope.launch {
            runCatching { withContext(Dispatchers.Default) { deviceCapabilityProbe.probe() } }
                .onSuccess { report ->
                    performanceGovernor = HubPerformanceGovernor(report.profile)
                    _uiState.update { it.copy(hubCapabilityReport = report) }
                }
        }

        // Observe connected peers and build camera list from them
        viewModelScope.launch {
            connectionManager.connectedPeers.collect { peers ->
                val currentPeerNames = peers.map { it.name }.toSet()
                val currentPeerIps = peers.map { it.ip }.toSet()
                peers.forEach { peer ->
                    desiredCameraIps.add(peer.ip)
                    reconnectJobs.remove(peer.ip)?.cancel()
                    offlineMarkJobs.remove(peer.ip)?.cancel()
                }
                if (currentPeerNames != lastAutomaticPeerNames) {
                    lastAppliedStreamLimits = null
                    lastAutomaticPeerNames = currentPeerNames
                }

                // Auto-cleanup: disconnect streams for peers that are no longer connected
                val activeStreams = connectedStreamNames.toSet()
                for (name in activeStreams) {
                    if (name !in currentPeerNames) {
                        connectedStreamNames.remove(name)
                        connectedAudioNames.remove(name)
                        connectedStreamNetworkHandles.remove(name)
                        connectedAudioNetworkHandles.remove(name)
                        adaptiveBitrateControllers.remove(name)
                        adaptiveCounterSnapshots.remove(name)
                        ptzCommandJobs.remove(name)?.cancel()
                        pendingRemotePtz.remove(name)
                        streamClient.disconnectStream(name)
                        audioStreamClient.disconnectAudioStream(name)
                    }
                }

                updateCamerasFromPeers(peers)
                _uiState.value.cameras
                    .filter { camera -> camera.label !in currentPeerIps }
                    .forEach { camera -> scheduleOfflineMark(camera.name, camera.label) }
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
                                status = if (stream.isConnected) {
                                    ConnectionStatus.LIVE
                                } else {
                                    ConnectionStatus.STANDBY
                                },
                                previewBitmap = stream.bitmap?.asCachedImageBitmap() ?: cam.previewBitmap,
                                previewSourceBitmap = stream.bitmap ?: cam.previewSourceBitmap,
                                frameWidth = if (stream.frameWidth > 0) stream.frameWidth else cam.frameWidth,
                                frameHeight = if (stream.frameHeight > 0) stream.frameHeight else cam.frameHeight,
                                frameSequence = stream.pipelineLatency?.frameSequence
                                    ?: stream.frameSequence,
                                bitrateKbps = stream.bitrateKbps,
                                latencyMs = stream.latencyMs,
                                latencyP50Ms = stream.latencyP50Ms,
                                latencyP95Ms = stream.latencyP95Ms,
                                latencyP99Ms = stream.latencyP99Ms,
                                latencySampleCount = stream.latencySampleCount,
                                isClockSynchronized = stream.isClockSynchronized,
                                pipelineLatency = stream.pipelineLatency,
                                externalDisplayLatency = stream.externalDisplayLatency,
                                externalLatencyP50Ms = stream.externalLatencyP50Ms,
                                externalLatencyP95Ms = stream.externalLatencyP95Ms,
                                externalLatencyP99Ms = stream.externalLatencyP99Ms,
                                externalLatencySampleCount = stream.externalLatencySampleCount,
                                droppedFrames = stream.droppedFrames,
                                ingressFps = stream.ingressFps,
                                videoTransport = stream.videoTransport,
                                udpPacketsReceived = stream.udpPacketsReceived,
                                udpCompletedFrames = stream.udpCompletedFrames,
                                udpDeadlineDroppedFrames = stream.udpDeadlineDroppedFrames,
                                udpEstimatedMissingPackets = stream.udpEstimatedMissingPackets,
                                udpPacketLossPercent = stream.udpPacketLossPercent,
                                transportFallbackReason = stream.transportFallbackReason,
                                fps = if (stream.actualFps > 0) stream.actualFps else cam.fps,
                                audioStatus = if (stream.isConnected) cam.audioStatus else "Reconnecting"
                            )
                        } else {
                            cam
                        }
                    }
                    val liveCameras = updatedCameras.filter {
                        it.status == ConnectionStatus.LIVE && it.latencySampleCount > 0
                    }
                    state.copy(
                        cameras = updatedCameras,
                        latencyMs = liveCameras.maxOfOrNull { it.latencyMs } ?: 0,
                        latencyP50Ms = liveCameras.maxOfOrNull { it.latencyP50Ms } ?: 0,
                        latencyP95Ms = liveCameras.maxOfOrNull { it.latencyP95Ms } ?: 0,
                        latencyP99Ms = liveCameras.maxOfOrNull { it.latencyP99Ms } ?: 0
                    )
                }

                // Track PGM bitmap for recording + external display
                val pgmIdx = _uiState.value.pgmCameraIndex
                val pgmOutput = _uiState.value.cameras.getOrNull(pgmIdx)?.let { camera ->
                    streams[camera.name]?.let { stream ->
                        stream.bitmap?.let { bitmap -> Triple(camera, stream, bitmap) }
                    }
                }
                if (pgmOutput != null) {
                    val (pgmCam, pgmStream, pgmBitmap) = pgmOutput
                    lastPgmBitmap = pgmBitmap
                    val ptzTransform = HybridPtzController.hubTransform(pgmCam.ptzState)
                    if (recorder.recordingInfo.value.isRecording) {
                        recorder.onFrame(pgmBitmap, ptzTransform)
                    }
                    if (externalDisplayManager.isOutputEnabled.value) {
                        val frameSequence = pgmStream.pipelineLatency?.frameSequence
                            ?: pgmStream.frameSequence
                        externalDisplayManager.updateFrame(
                            bitmap = pgmBitmap,
                            cameraName = pgmCam.name,
                            frameSequence = frameSequence,
                            enableSpatialUpscaling = _uiState.value.isPgmSpatialUpscalingEnabled,
                            spatialUpscaleOutputHeight = _uiState.value.pgmOutputHeight,
                            ptzTransform = ptzTransform,
                            onFrameDrawn = streamClient::markExternalFrameDrawn
                        )
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
                        storageTotalGb = device.storageTotalGb,
                        thermalStatus = device.thermalStatus
                    )
                }
                updateRuntimeRecommendation(device.thermalStatus)
            }
        }

        // Real-time bitrate from stream client
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val totalBitrate = streamClient.getTotalBitrateKbps()
                _uiState.update { it.copy(bitrateKbps = totalBitrate) }
                updateAdaptiveBitrates()
            }
        }

        // Recording state + timecode
        viewModelScope.launch {
            recorder.recordingInfo.collect { info ->
                _uiState.update {
                    it.copy(
                        isRecording = info.isRecording,
                        isPaused = info.isPaused,
                        recordingPath = info.outputPath,
                        isRecordingSpatialUpscaling = info.isSpatialUpscaling,
                        recordingOutputHeight = info.outputHeight
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

        // Audio master level for status bar
        viewModelScope.launch {
            audioStreamClient.masterLevel.collect { level ->
                _uiState.update { it.copy(audioMasterLevel = level) }
            }
        }

        // Per-camera audio diagnostics for quick troubleshooting on camera cards
        viewModelScope.launch {
            audioStreamClient.channelStates.collect { channelStates ->
                _uiState.update { state ->
                    state.copy(
                        cameras = state.cameras.map { cam ->
                            val audio = channelStates[cam.name]
                            if (audio != null) {
                                cam.copy(
                                    audioLevel = audio.level,
                                    audioStatus = audio.statusText
                                )
                            } else {
                                cam.copy(
                                    audioLevel = 0f,
                                    audioStatus = when (cam.status) {
                                        ConnectionStatus.LIVE -> "No Audio"
                                        ConnectionStatus.STANDBY -> "Reconnecting"
                                        ConnectionStatus.OFFLINE -> "Disconnected"
                                    }
                                )
                            }
                        }
                    )
                }
            }
        }

        // Propagate PGM camera to audio mixer for AFV logic
        viewModelScope.launch {
            _uiState.collect { state ->
                val pgmName = state.cameras.getOrNull(state.pgmCameraIndex)?.name
                audioStreamClient.setPgmCameras(if (pgmName != null) setOf(pgmName) else emptySet())
            }
        }

        // Start NSD discovery for device manager
        nsdManager.startDiscovery()

        // Observe discovered peers (merge with connected state)
        viewModelScope.launch {
            nsdManager.discoveredPeers.collect { peers ->
                val connectedIps = connectionManager.connectedPeers.value.map { it.ip }.toSet()
                val updatedPeers = peers.map { peer ->
                    peer.copy(isConnected = peer.ip in connectedIps)
                }
                _uiState.update { it.copy(discoveredPeers = updatedPeers) }
                peers
                    .filter { peer ->
                        peer.ip in desiredCameraIps && peer.ip !in connectedIps
                    }
                    .forEach(::scheduleReconnect)
            }
        }

        // Refresh discovered peers' connected status when connections change
        viewModelScope.launch {
            connectionManager.connectedPeers.collect { connectedList ->
                val connectedIps = connectedList.map { it.ip }.toSet()
                _uiState.update { state ->
                    val updatedPeers = state.discoveredPeers.map { peer ->
                        peer.copy(isConnected = peer.ip in connectedIps)
                    }
                    state.copy(discoveredPeers = updatedPeers)
                }
            }
        }
    }

    private fun updateCamerasFromPeers(peers: List<ConnectedPeer>) {
        _uiState.update { state ->
            val unmatchedPeers = peers.toMutableList()
            val retainedCameras = state.cameras.map { existing ->
                val peer = unmatchedPeers.firstOrNull {
                    it.name == existing.name || it.ip == existing.label
                }
                if (peer != null) {
                    unmatchedPeers.remove(peer)
                    existing.copy(
                        name = peer.name,
                        label = peer.ip,
                        status = if (
                            streamClient.streams.value[peer.name]?.isConnected == true
                        ) {
                            ConnectionStatus.LIVE
                        } else {
                            ConnectionStatus.STANDBY
                        },
                        supportsRemotePtz = peer.supportsRemotePtz,
                        minZoomRatio = peer.minZoomRatio,
                        maxZoomRatio = peer.maxZoomRatio,
                        audioStatus = "Connecting",
                        ptzState = if (
                            peer.supportsRemotePtz && existing.ptzState.zoom == 1f
                        ) {
                            existing.ptzState.copy(ptzMode = PtzMode.REMOTE)
                        } else {
                            existing.ptzState
                        }
                    )
                } else {
                    existing.copy(
                        status = ConnectionStatus.STANDBY,
                        bitrateKbps = 0,
                        fps = 0,
                        ingressFps = 0,
                        audioLevel = 0f,
                        audioStatus = "Reconnecting"
                    )
                }
            }
            val newCameras = unmatchedPeers
                .take((MAX_CAMERA_COUNT - retainedCameras.size).coerceAtLeast(0))
                .mapIndexed { index, peer ->
                    CameraNode(
                        id = "CAM-${retainedCameras.size + index + 1}",
                        name = peer.name,
                        label = peer.ip,
                        fps = 0,
                        tempCelsius = 0,
                        status = ConnectionStatus.STANDBY,
                        audioStatus = "Connecting",
                        supportsRemotePtz = peer.supportsRemotePtz,
                        minZoomRatio = peer.minZoomRatio,
                        maxZoomRatio = peer.maxZoomRatio,
                        ptzState = HybridPtzState(
                            ptzMode = if (peer.supportsRemotePtz) PtzMode.REMOTE else PtzMode.HUB
                        )
                    )
                }
            val cameras = (retainedCameras + newCameras).take(MAX_CAMERA_COUNT)

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
        refreshStreamRenderPriorities()
    }

    private fun refreshStreamRenderPriorities() {
        val state = _uiState.value
        state.cameras.forEachIndexed { index, camera ->
            val fps = when (index) {
                state.pgmCameraIndex -> 30
                state.pvwCameraIndex -> 20
                else -> 10
            }
            streamClient.setRenderFpsLimit(camera.name, fps)
        }
    }

    private fun scheduleReconnect(peer: DiscoveredPeer) {
        if (peer.ip !in desiredCameraIps) return
        if (connectionManager.connectedPeers.value.any { it.ip == peer.ip }) return
        if (reconnectJobs[peer.ip]?.isActive == true) return

        val job = viewModelScope.launch {
            var attempt = 0
            while (
                isActive &&
                peer.ip in desiredCameraIps &&
                connectionManager.connectedPeers.value.none { it.ip == peer.ip }
            ) {
                if (attempt > 0) {
                    val backoffMs = (RECONNECT_BASE_DELAY_MS * (1L shl minOf(attempt - 1, 4)))
                        .coerceAtMost(RECONNECT_MAX_DELAY_MS)
                    delay(backoffMs)
                }

                val latestPeer = _uiState.value.discoveredPeers
                    .firstOrNull { it.ip == peer.ip }
                if (latestPeer == null) {
                    delay(RECONNECT_DISCOVERY_POLL_MS)
                    attempt = (attempt + 1).coerceAtMost(MAX_RECONNECT_BACKOFF_STEP)
                    continue
                }

                _uiState.update { state ->
                    state.copy(
                        cameras = state.cameras.map { camera ->
                            if (camera.label == peer.ip) {
                                camera.copy(
                                    status = ConnectionStatus.STANDBY,
                                    audioStatus = "Reconnecting"
                                )
                            } else {
                                camera
                            }
                        }
                    )
                }
                connectionManager.connectToCamera(latestPeer, directorName)
                delay(RECONNECT_RESULT_WAIT_MS)
                attempt = (attempt + 1).coerceAtMost(MAX_RECONNECT_BACKOFF_STEP)
            }
        }
        reconnectJobs[peer.ip] = job
        job.invokeOnCompletion {
            if (reconnectJobs[peer.ip] === job) reconnectJobs.remove(peer.ip)
        }
    }

    private fun scheduleOfflineMark(cameraName: String, cameraIp: String) {
        if (cameraIp.isBlank() || offlineMarkJobs[cameraIp]?.isActive == true) return
        offlineMarkJobs[cameraIp] = viewModelScope.launch {
            delay(OFFLINE_GRACE_MS)
            if (connectionManager.connectedPeers.value.none { it.ip == cameraIp }) {
                _uiState.update { state ->
                    state.copy(
                        cameras = state.cameras.map { camera ->
                            if (camera.name == cameraName || camera.label == cameraIp) {
                                camera.copy(
                                    status = ConnectionStatus.OFFLINE,
                                    bitrateKbps = 0,
                                    fps = 0,
                                    ingressFps = 0,
                                    audioLevel = 0f,
                                    audioStatus = "Disconnected"
                                )
                            } else {
                                camera
                            }
                        }
                    )
                }
            }
            offlineMarkJobs.remove(cameraIp)
        }
    }

    private fun updateRuntimeRecommendation(thermalStatus: Int) {
        val governor = performanceGovernor ?: return
        val state = _uiState.value
        val liveCameras = state.cameras.filter {
            it.status == ConnectionStatus.LIVE && it.latencySampleCount > 0
        }
        val totalDroppedFrames = liveCameras.sumOf { it.droppedFrames }
        val droppedFrameDelta = (totalDroppedFrames - lastGovernorDroppedFrames).coerceAtLeast(0)
        lastGovernorDroppedFrames = totalDroppedFrames

        val recommendation = governor.evaluate(
            HubPerformanceSample(
                timestampMs = SystemClock.elapsedRealtime(),
                maxP95LatencyMs = liveCameras.maxOfOrNull { it.latencyP95Ms } ?: 0,
                droppedFrameDelta = droppedFrameDelta,
                // Multiview rendering is intentionally limited to 30/20/10fps.
                // Runtime pressure must use source ingress or every background camera
                // would look overloaded even on a healthy hub.
                minActualFps = liveCameras
                    .map { it.ingressFps }
                    .filter { it > 0 }
                    .minOrNull() ?: 0,
                targetFps = DEFAULT_TARGET_FPS,
                thermalStatus = thermalStatus,
                connectedCameraCount = liveCameras.size
            )
        )
        _uiState.update { it.copy(runtimeRecommendation = recommendation) }
        applyRuntimeRecommendation(recommendation)
    }

    private fun updateAdaptiveBitrates() {
        val enabled = streamingConfig.adaptiveBitrate
        val ceilingMbps = streamingConfig.bitrateMbps.coerceIn(1, 20)
        if (!enabled) {
            if (wasAdaptiveBitrateEnabled) {
                connectionManager.connectedPeers.value.forEach { peer ->
                    connectionManager.sendCommand(
                        peer.name,
                        "set_stream_bitrate",
                        value = ceilingMbps.toFloat()
                    )
                }
                _uiState.update { state ->
                    state.copy(
                        cameras = state.cameras.map {
                            it.copy(
                                adaptiveBitrateTargetMbps = 0,
                                adaptiveBitrateReason = ""
                            )
                        }
                    )
                }
            }
            wasAdaptiveBitrateEnabled = false
            adaptiveBitrateControllers.clear()
            adaptiveCounterSnapshots.clear()
            return
        }

        wasAdaptiveBitrateEnabled = true
        val nowMs = SystemClock.elapsedRealtime()
        val streams = streamClient.streams.value.filterValues { it.isConnected }
        val activeNames = streams.keys
        adaptiveBitrateControllers.keys.retainAll(activeNames)
        adaptiveCounterSnapshots.keys.retainAll(activeNames)
        val targetByCamera = mutableMapOf<String, Pair<Int, String>>()

        for ((cameraName, stream) in streams) {
            val counters = AdaptiveCounterSnapshot(
                udpPacketsReceived = stream.udpPacketsReceived,
                udpMissingPackets = stream.udpEstimatedMissingPackets,
                droppedFrames = stream.droppedFrames
            )
            val previousCounters = adaptiveCounterSnapshots.put(cameraName, counters)
            val packetDelta = previousCounters?.let {
                (counters.udpPacketsReceived - it.udpPacketsReceived).coerceAtLeast(0L)
            } ?: 0L
            val missingDelta = previousCounters?.let {
                (counters.udpMissingPackets - it.udpMissingPackets).coerceAtLeast(0L)
            } ?: 0L
            val droppedDelta = previousCounters?.let {
                (counters.droppedFrames - it.droppedFrames).coerceAtLeast(0)
            } ?: 0
            val recentLoss = if (
                stream.videoTransport == VideoTransport.UDP_RTP &&
                packetDelta + missingDelta > 0L
            ) {
                missingDelta * 100f / (packetDelta + missingDelta)
            } else null

            val controller = adaptiveBitrateControllers.getOrPut(cameraName) {
                AdaptiveBitrateController(ceilingMbps = ceilingMbps)
            }
            val ceilingDecision = controller.updateCeiling(ceilingMbps, nowMs)
            val decision = ceilingDecision ?: controller.evaluate(
                AdaptiveBitrateSample(
                    nowMs = nowMs,
                    latencyP95Ms = stream.latencyP95Ms,
                    latencyP99Ms = stream.latencyP99Ms,
                    hasLatencySample = stream.isClockSynchronized &&
                        stream.latencySampleCount > 0,
                    recentPacketLossPercent = recentLoss,
                    droppedFrameDelta = droppedDelta,
                    actualFps = stream.ingressFps,
                    targetFps = (_uiState.value.effectiveStreamFps.takeIf { it > 0 }
                        ?: streamingConfig.fps).coerceAtLeast(1)
                )
            )
            if (decision != null) {
                connectionManager.sendCommand(
                    cameraName,
                    "set_stream_bitrate",
                    value = decision.targetMbps.toFloat()
                )
            }
            val priorReason = _uiState.value.cameras
                .firstOrNull { it.name == cameraName }
                ?.adaptiveBitrateReason
                .orEmpty()
            targetByCamera[cameraName] = controller.currentTargetMbps to
                (decision?.reason ?: priorReason.ifBlank { "Monitoring" })
        }

        _uiState.update { state ->
            state.copy(
                cameras = state.cameras.map { camera ->
                    val adaptive = targetByCamera[camera.name]
                    if (adaptive != null) {
                        camera.copy(
                            adaptiveBitrateTargetMbps = adaptive.first,
                            adaptiveBitrateReason = adaptive.second
                        )
                    } else {
                        camera.copy(
                            adaptiveBitrateTargetMbps = 0,
                            adaptiveBitrateReason = ""
                        )
                    }
                }
            )
        }
    }

    private fun applyRuntimeRecommendation(
        recommendation: HubRuntimeRecommendation
    ) {
        val automatic = streamingConfig.automaticHubProfile
        if (!automatic) {
            if (wasAutomaticHubProfileEnabled) {
                connectionManager.sendCommandToAll(
                    "set_stream_fps",
                    value = streamingConfig.fps.toFloat()
                )
                connectionManager.sendCommandToAll(
                    "set_stream_resolution",
                    value = streamingConfig.maxResolution.toFloat()
                )
            }
            wasAutomaticHubProfileEnabled = false
            lastAppliedStreamLimits = null
            _uiState.update {
                it.copy(
                    isAutomaticHubProfile = false,
                    effectiveStreamHeight = streamingConfig.maxResolution,
                    effectiveStreamFps = streamingConfig.fps,
                    isPgmSpatialUpscalingEnabled = false,
                    pgmOutputHeight = 0
                )
            }
            return
        }

        val limits = HubProfileApplicationPolicy.limitsFor(
            profile = recommendation.recommendedProfile,
            configuredMaxResolution = streamingConfig.maxResolution,
            configuredFps = streamingConfig.fps
        )
        if (limits != lastAppliedStreamLimits) {
            connectionManager.sendCommandToAll("set_stream_fps", value = limits.streamFps.toFloat())
            connectionManager.sendCommandToAll(
                "set_stream_resolution",
                value = limits.streamHeight.toFloat()
            )
            lastAppliedStreamLimits = limits
        }
        wasAutomaticHubProfileEnabled = true
        _uiState.update {
            it.copy(
                isAutomaticHubProfile = true,
                effectiveStreamHeight = limits.streamHeight,
                effectiveStreamFps = limits.streamFps,
                isPgmSpatialUpscalingEnabled = limits.enableSpatialUpscaling &&
                    limits.streamHeight < recommendation.recommendedProfile.pgmHeight,
                pgmOutputHeight = recommendation.recommendedProfile.pgmHeight
            )
        }
    }

    /** Connect to video and audio streams, passing session key for AES decryption */
    private fun connectToStreams(peers: List<ConnectedPeer>) {
        for (peer in peers) {
            val networkHandle = peer.network?.networkHandle ?: 0L
            val canUseUdp = peer.udpStreamPort in 1..65_535 && peer.streamKey != null
            if (
                (canUseUdp || peer.streamPort > 0) &&
                (peer.name !in connectedStreamNames ||
                    connectedStreamNetworkHandles[peer.name] != networkHandle)
            ) {
                connectedStreamNames.add(peer.name)
                connectedStreamNetworkHandles[peer.name] = networkHandle
                if (canUseUdp) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val receivePort = streamClient.connectToUdpStream(
                                cameraName = peer.name,
                                cameraIp = peer.ip,
                                cameraUdpPort = peer.udpStreamPort,
                                sessionKey = peer.streamKey,
                                network = peer.network,
                                onFallbackRequired = { reason ->
                                    viewModelScope.launch {
                                        fallbackToReliableVideo(peer, networkHandle, reason)
                                    }
                                }
                            )
                            connectionManager.sendUdpSubscribe(peer.name, receivePort)
                        } catch (error: Throwable) {
                            Log.w(TAG, "UDP setup failed for ${peer.name}", error)
                            viewModelScope.launch {
                                fallbackToReliableVideo(peer, networkHandle, "setup_failed")
                            }
                        }
                    }
                } else {
                    connectReliableVideo(peer)
                }
            }
            if (
                peer.audioStreamPort > 0 &&
                (peer.name !in connectedAudioNames ||
                    connectedAudioNetworkHandles[peer.name] != networkHandle)
            ) {
                connectedAudioNames.add(peer.name)
                connectedAudioNetworkHandles[peer.name] = networkHandle
                audioStreamClient.connectToAudioStream(
                    cameraName = peer.name,
                    ip = peer.ip,
                    audioPort = peer.audioStreamPort,
                    sessionKey = peer.streamKey,
                    network = peer.network
                )
            }
        }
    }

    private fun fallbackToReliableVideo(
        attemptedPeer: ConnectedPeer,
        expectedNetworkHandle: Long,
        reason: String
    ) {
        val currentPeer = connectionManager.connectedPeers.value.firstOrNull {
            it.name == attemptedPeer.name &&
                (it.network?.networkHandle ?: 0L) == expectedNetworkHandle
        } ?: return
        if (currentPeer.streamPort <= 0) return

        Log.w(TAG, "UDP unavailable for ${currentPeer.name} ($reason); using SRT/TCP")
        connectionManager.sendUdpUnsubscribe(currentPeer.name)
        connectReliableVideo(currentPeer, fallbackReason = reason)
    }

    private fun connectReliableVideo(peer: ConnectedPeer, fallbackReason: String = "") {
        if (peer.streamPort <= 0) return
        streamClient.connectToStream(
            cameraName = peer.name,
            ip = peer.ip,
            streamPort = peer.streamPort,
            sessionKey = peer.streamKey,
            network = peer.network,
            fallbackReason = fallbackReason
        )
    }

    fun selectPvw(cameraIndex: Int) {
        _uiState.update { state ->
            if (state.cameras.getOrNull(cameraIndex)?.status != ConnectionStatus.LIVE) {
                return@update state
            }
            val updatedCameras = state.cameras.mapIndexed { i, cam ->
                cam.copy(isPvw = i == cameraIndex, isPgm = cam.isPgm)
            }
            state.copy(pvwCameraIndex = cameraIndex, cameras = updatedCameras)
        }
        refreshStreamRenderPriorities()
    }

    fun executeCut() {
        _uiState.update { state ->
            val oldPgm = state.pgmCameraIndex
            val oldPvw = state.pvwCameraIndex
            if (state.cameras.getOrNull(oldPvw)?.status != ConnectionStatus.LIVE) {
                return@update state
            }
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
        refreshStreamRenderPriorities()
    }

    fun executeAuto() {
        val transition = _uiState.value.selectedTransition
        when (transition) {
            TransitionType.CUT -> executeCut()
            else -> executeTransition()
        }
    }

    private fun executeTransition() {
        if (_uiState.value.isTransitioning) return
        if (_uiState.value.cameras.getOrNull(_uiState.value.pvwCameraIndex)?.status != ConnectionStatus.LIVE) return
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
            if (state.cameras.getOrNull(oldPvw)?.status != ConnectionStatus.LIVE) {
                return@update state
            }
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
        refreshStreamRenderPriorities()
    }

    fun updateTBar(position: Float) {
        if (_uiState.value.isTransitioning) return
        if (_uiState.value.cameras.getOrNull(_uiState.value.pvwCameraIndex)?.status != ConnectionStatus.LIVE) return
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
        desiredCameraIps.remove(camera.label)
        reconnectJobs.remove(camera.label)?.cancel()
        offlineMarkJobs.remove(camera.label)?.cancel()
        connectedStreamNames.remove(cameraName)
        connectedAudioNames.remove(cameraName)
        connectedStreamNetworkHandles.remove(cameraName)
        connectedAudioNetworkHandles.remove(cameraName)
        ptzCommandJobs.remove(cameraName)?.cancel()
        pendingRemotePtz.remove(cameraName)
        connectionManager.disconnectPeer(cameraName)
        streamClient.disconnectStream(cameraName)
        audioStreamClient.disconnectAudioStream(cameraName)
        removeCameraSlot(cameraName)
    }

    fun showCameraControl(cameraIndex: Int) {
        _uiState.update { it.copy(showCameraControl = true, controlCameraIndex = cameraIndex) }
    }

    fun hideCameraControl() {
        _uiState.update { it.copy(showCameraControl = false, controlCameraIndex = -1) }
    }

    fun toggleLivePtzLock() {
        _uiState.update { it.copy(isLivePtzUnlocked = !it.isLivePtzUnlocked) }
    }

    fun applyPtzGesture(
        cameraName: String,
        zoomFactor: Float,
        panXNormalized: Float,
        panYNormalized: Float
    ) {
        updateCameraPtz(cameraName) { camera ->
            HybridPtzController.applyGesture(
                state = camera.ptzState,
                zoomFactor = zoomFactor,
                panX = panXNormalized,
                panY = panYNormalized,
                maxZoom = camera.effectivePtzMaxZoom(),
                supportsRemotePtz = camera.supportsRemotePtz
            )
        }
    }

    fun doubleTapPtz(cameraName: String, tapX: Float, tapY: Float) {
        updateCameraPtz(cameraName) { camera ->
            HybridPtzController.doubleTap(
                state = camera.ptzState,
                tapX = tapX,
                tapY = tapY,
                maxZoom = camera.effectivePtzMaxZoom(),
                supportsRemotePtz = camera.supportsRemotePtz
            )
        }
    }

    fun setPtzZoom(cameraName: String, zoom: Float) {
        updateCameraPtz(cameraName) { camera ->
            val currentZoom = camera.ptzState.zoom.coerceAtLeast(1f)
            HybridPtzController.applyGesture(
                state = camera.ptzState,
                zoomFactor = zoom / currentZoom,
                panX = 0f,
                panY = 0f,
                maxZoom = camera.effectivePtzMaxZoom(),
                supportsRemotePtz = camera.supportsRemotePtz
            )
        }
    }

    private fun updateCameraPtz(
        cameraName: String,
        updatePtz: (CameraNode) -> HybridPtzState
    ) {
        var updatedCamera: CameraNode? = null
        _uiState.update { state ->
            val camera = state.cameras.firstOrNull { it.name == cameraName } ?: return@update state
            if (camera.isPgm && !state.isLivePtzUnlocked) return@update state
            val newCamera = camera.copy(ptzState = updatePtz(camera))
            updatedCamera = newCamera
            state.copy(
                cameras = state.cameras.map { if (it.name == cameraName) newCamera else it }
            )
        }
        updatedCamera?.let(::scheduleRemotePtz)
    }

    private fun scheduleRemotePtz(camera: CameraNode) {
        if (!camera.supportsRemotePtz) return
        ptzCommandJobs.remove(camera.name)?.cancel()
        pendingRemotePtz.remove(camera.name)
        ptzCommandJobs[camera.name] = viewModelScope.launch {
            delay(REMOTE_PTZ_DEBOUNCE_MS)
            val requested = _uiState.value.cameras
                .firstOrNull { it.name == camera.name }
                ?.ptzState ?: return@launch
            val requestId = ptzRequestSequence.incrementAndGet()
            pendingRemotePtz[camera.name] = PendingRemotePtz(requestId, requested)
            connectionManager.sendCommand(
                peerName = camera.name,
                command = "set_ptz",
                value = requested.zoom,
                stringValue = "${requested.centerX},${requested.centerY}",
                requestId = requestId
            )
        }
    }

    private fun CameraNode.effectivePtzMaxZoom(): Float =
        if (supportsRemotePtz) maxZoomRatio.coerceAtLeast(1f) else HUB_PTZ_MAX_ZOOM

    fun sendCameraCommand(command: String, value: Float = 0f, stringValue: String = "") {
        val idx = _uiState.value.controlCameraIndex
        val cam = _uiState.value.cameras.getOrNull(idx) ?: return
        connectionManager.sendCommand(cam.name, command, value, stringValue)

        // Optimistic UI update for recording state
        if (command == "start_recording" || command == "stop_recording") {
            val isRec = command == "start_recording"
            _uiState.update { state ->
                val updatedCameras = state.cameras.toMutableList()
                updatedCameras[idx] = updatedCameras[idx].copy(isRecording = isRec)
                state.copy(cameras = updatedCameras)
            }
        }
    }

    fun toggleAudioMixer() {
        _uiState.update { it.copy(showAudioMixer = !it.showAudioMixer) }
    }

    fun toggleAutoRecordCameras() {
        _uiState.update { it.copy(autoRecordCameras = !it.autoRecordCameras) }
    }

    fun toggleRecording() {
        val wasRecording = recorder.recordingInfo.value.isRecording
        if (wasRecording) {
            recorder.stopRecording()
            // Auto-stop camera recording if enabled
            if (_uiState.value.autoRecordCameras) {
                connectionManager.sendCommandToAll("stop_recording")
                _uiState.update { state ->
                    state.copy(cameras = state.cameras.map { it.copy(isRecording = false) })
                }
            }
        } else {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

            // Use actual PGM frame dimensions if available
            val pgmCam = _uiState.value.cameras.getOrNull(_uiState.value.pgmCameraIndex)
            val sourceWidth = if (pgmCam != null && pgmCam.frameWidth > 0) pgmCam.frameWidth else 1920
            val sourceHeight = if (pgmCam != null && pgmCam.frameHeight > 0) pgmCam.frameHeight else 1080
            val recordingPlan = PgmRecordingPolicy.plan(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                spatialUpscalingEnabled = _uiState.value.isPgmSpatialUpscalingEnabled,
                targetHeight = _uiState.value.pgmOutputHeight
            )
            recorder.startRecording(
                width = recordingPlan.width,
                height = recordingPlan.height,
                sessionTimestamp = timestamp,
                useSpatialUpscaling = recordingPlan.useSpatialUpscaling
            )

            // Auto-start camera recording with per-camera numbered filenames
            if (_uiState.value.autoRecordCameras) {
                val cameras = _uiState.value.cameras
                cameras.forEachIndexed { index, cam ->
                    val filePrefix = "CamHub_$timestamp-CAM${index + 1}"
                    connectionManager.sendCommand(cam.name, "start_recording", stringValue = filePrefix)
                }
                _uiState.update { state ->
                    state.copy(cameras = state.cameras.map { it.copy(isRecording = true) })
                }
            }
        }
    }

    fun pauseRecording() {
        recorder.pauseRecording()
    }

    fun resumeRecording() {
        recorder.resumeRecording()
    }

    fun toggleDeviceManager() {
        _uiState.update { it.copy(showDeviceManager = !it.showDeviceManager) }
    }

    fun connectToPeer(peer: DiscoveredPeer) {
        desiredCameraIps.add(peer.ip)
        connectionManager.connectToCamera(peer, directorName)
    }

    fun disconnectPeer(name: String) {
        val discoveredIp = _uiState.value.discoveredPeers
            .firstOrNull { it.name == name }
            ?.ip
        val connectedPeer = connectionManager.connectedPeers.value.firstOrNull {
            it.name == name || it.ip == discoveredIp
        }
        val cameraName = connectedPeer?.name ?: name
        val cameraIp = connectedPeer?.ip
            ?: discoveredIp
            ?: _uiState.value.cameras.firstOrNull { it.name == cameraName }?.label
            .orEmpty()

        desiredCameraIps.remove(cameraIp)
        reconnectJobs.remove(cameraIp)?.cancel()
        offlineMarkJobs.remove(cameraIp)?.cancel()
        connectedStreamNames.remove(cameraName)
        connectedAudioNames.remove(cameraName)
        connectedStreamNetworkHandles.remove(cameraName)
        connectedAudioNetworkHandles.remove(cameraName)
        ptzCommandJobs.remove(cameraName)?.cancel()
        pendingRemotePtz.remove(cameraName)
        connectionManager.disconnectPeer(cameraName)
        streamClient.disconnectStream(cameraName)
        audioStreamClient.disconnectAudioStream(cameraName)
        removeCameraSlot(cameraName)
    }

    fun connectToAllPeers() {
        val connectedIps = connectionManager.connectedPeers.value.map { it.ip }.toSet()
        val unconnected = _uiState.value.discoveredPeers.filter { it.ip !in connectedIps }
        for (peer in unconnected) {
            connectToPeer(peer)
        }
    }

    private fun removeCameraSlot(cameraName: String) {
        _uiState.update { state ->
            val oldPgmName = state.cameras.getOrNull(state.pgmCameraIndex)?.name
            val oldPvwName = state.cameras.getOrNull(state.pvwCameraIndex)?.name
            val remaining = state.cameras.filterNot { it.name == cameraName }

            val pgmIndex = remaining.indexOfFirst { it.name == oldPgmName }
                .takeIf { it >= 0 }
                ?: remaining.indexOfFirst { it.status == ConnectionStatus.LIVE }
            val pvwIndex = remaining.indexOfFirst { it.name == oldPvwName }
                .takeIf { it >= 0 && it != pgmIndex }
                ?: remaining.indexOfFirst {
                    it.status == ConnectionStatus.LIVE && remaining.indexOf(it) != pgmIndex
                }
            val updated = remaining.mapIndexed { index, camera ->
                camera.copy(isPgm = index == pgmIndex, isPvw = index == pvwIndex)
            }
            state.copy(
                cameras = updated,
                pgmCameraIndex = pgmIndex,
                pvwCameraIndex = pvwIndex
            )
        }
    }

    fun addManualConnection(ipPort: String) {
        val parts = ipPort.trim().split(":")
        if (parts.size == 2) {
            val ip = parts[0].trim()
            val port = parts[1].trim().toIntOrNull() ?: return
            if (port > 0 && ip.isNotEmpty()) {
                nsdManager.addManualPeer(ip, port)
            }
        }
    }

    fun rescanDevices() {
        val connectedNames = connectionManager.connectedPeers.value.map { it.name }.toSet()
        _uiState.update { state ->
            state.copy(discoveredPeers = state.discoveredPeers.filter { it.name in connectedNames })
        }
        nsdManager.stopDiscovery()
        nsdManager.startDiscovery()
    }

    override fun onCleared() {
        super.onCleared()
        transitionJob?.cancel()
        ptzCommandJobs.values.forEach { it.cancel() }
        ptzCommandJobs.clear()
        pendingRemotePtz.clear()
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        offlineMarkJobs.values.forEach { it.cancel() }
        offlineMarkJobs.clear()
        desiredCameraIps.clear()
        try { nsdManager.stopDiscovery() } catch (_: Exception) {}
        try { deviceMonitor.stopMonitoring() } catch (_: Exception) {}
        try { externalDisplayManager.stopListening() } catch (_: Exception) {}
        try { externalDisplayManager.disableOutput() } catch (_: Exception) {}
        if (recorder.recordingInfo.value.isRecording) {
            try { recorder.stopRecording() } catch (_: Exception) {}
        }
        connectedStreamNames.clear()
        connectedAudioNames.clear()
        connectedStreamNetworkHandles.clear()
        connectedAudioNetworkHandles.clear()
        adaptiveBitrateControllers.clear()
        adaptiveCounterSnapshots.clear()
        streamClient.disconnectAll()
        audioStreamClient.disconnectAll()
        imageBitmapCache.clear()
    }

    private fun Bitmap.asCachedImageBitmap(): ImageBitmap {
        if (imageBitmapCache.size > MAX_IMAGE_BITMAP_CACHE_SIZE) {
            imageBitmapCache.clear()
        }
        return imageBitmapCache[this] ?: asImageBitmap().also { imageBitmapCache[this] = it }
    }

    companion object {
        private const val TAG = "DirectorViewModel"
        private const val MAX_IMAGE_BITMAP_CACHE_SIZE = 64
        private const val DEFAULT_TARGET_FPS = 30
        private const val HUB_PTZ_MAX_ZOOM = 4f
        private const val REMOTE_PTZ_DEBOUNCE_MS = 50L
        private const val REMOTE_PTZ_FRAME_MARGIN_MS = 34
        private const val MIN_REMOTE_PTZ_FRAME_DELAY_MS = 50
        private const val MAX_REMOTE_PTZ_FRAME_DELAY_MS = 500
        private const val MAX_CAMERA_COUNT = 4
        private const val RECONNECT_BASE_DELAY_MS = 500L
        private const val RECONNECT_MAX_DELAY_MS = 10_000L
        private const val RECONNECT_RESULT_WAIT_MS = 3_000L
        private const val RECONNECT_DISCOVERY_POLL_MS = 1_000L
        private const val MAX_RECONNECT_BACKOFF_STEP = 5
        private const val OFFLINE_GRACE_MS = 15_000L
    }
}
