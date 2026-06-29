package com.camhub.studio.ui.settings

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.camhub.studio.data.DeviceMonitor
import com.camhub.studio.data.DirectorRecorder
import com.camhub.studio.data.ExternalDisplayManager
import com.camhub.studio.data.StreamingConfig
import com.camhub.studio.data.network.PeerConnectionManager
import com.camhub.studio.data.network.CameraStreamState
import com.camhub.studio.data.network.StreamClient
import com.camhub.studio.ui.settings.model.DiscoveredNode
import com.camhub.studio.ui.settings.model.DisplayResolution
import com.camhub.studio.ui.settings.model.NodeStatus
import com.camhub.studio.ui.settings.model.Protocol
import com.camhub.studio.ui.settings.model.RecordingFormat
import com.camhub.studio.ui.settings.model.SettingsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val connectionManager: PeerConnectionManager,
    private val streamClient: StreamClient,
    private val deviceMonitor: DeviceMonitor,
    private val recorder: DirectorRecorder,
    private val externalDisplayManager: ExternalDisplayManager,
    private val streamingConfig: StreamingConfig
) : ViewModel() {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("camhub_settings", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            recordingStoragePath = recorder.getRecordingDirectory().absolutePath
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var adaptiveBitrateCeilingMbps = streamingConfig.bitrateMbps
    private var lastAdaptiveChangeMs = 0L
    private var lastObservedDroppedFrames = 0
    private var stableSinceMs = 0L
    private var adaptiveBitrateStatusText =
        if (streamingConfig.adaptiveBitrate) adaptiveMonitoringStatus() else "Off"

    init {
        loadSavedSettings()

        // Observe connected peers for discovery panel
        viewModelScope.launch {
            connectionManager.connectedPeers.collect { peers ->
                val nodes = peers.map { peer ->
                    DiscoveredNode(
                        name = peer.name,
                        ip = peer.ip,
                        status = NodeStatus.CONNECTED
                    )
                }
                _uiState.update {
                    it.copy(
                        discoveredNodes = nodes,
                        activeStreams = peers.size
                    )
                }
            }
        }

        // Observe device status
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

        // Observe external display
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

        // Observe stream diagnostics for the bottom status bar.
        viewModelScope.launch {
            streamClient.streams.collect { streams ->
                val liveStreams = streams.values.filter { it.isConnected }
                val maxLatencyMs = liveStreams.maxOfOrNull { it.latencyMs } ?: 0
                val droppedFrames = liveStreams.sumOf { it.droppedFrames }
                    .coerceAtMost(Int.MAX_VALUE)
                val minStreamFps = liveStreams
                    .map { it.actualFps }
                    .filter { it > 0 }
                    .minOrNull() ?: 0
                _uiState.update {
                    it.copy(
                        activeStreams = liveStreams.size,
                        latencyMs = maxLatencyMs,
                        minStreamFps = minStreamFps,
                        droppedFrames = droppedFrames
                    )
                }
                maybeAdaptBitrate(liveStreams, maxLatencyMs, droppedFrames, minStreamFps)
            }
        }
    }

    fun selectProtocol(protocol: Protocol) {
        _uiState.update { it.copy(selectedProtocol = protocol) }
    }

    fun toggleMdns() {
        _uiState.update { it.copy(isMdnsEnabled = !it.isMdnsEnabled) }
    }

    fun selectTab(index: Int) {
        _uiState.update { it.copy(selectedSettingsTab = index) }
    }

    // Recording
    fun selectRecordingFormat(format: RecordingFormat) {
        _uiState.update { it.copy(recordingFormat = format) }
        recorder.useHevc = (format == RecordingFormat.MP4_H265)
        prefs.edit().putString("recording_format", format.name).apply()
    }

    fun updateRecordingBitrate(mbps: Int) {
        _uiState.update { it.copy(recordingBitrateMbps = mbps) }
        recorder.bitrateMbps = mbps
        prefs.edit().putInt("recording_bitrate", mbps).apply()
    }

    fun updateStoragePath(displayPath: String) {
        _uiState.update { it.copy(recordingStoragePath = displayPath) }
        prefs.edit().putString("recording_storage_path", displayPath).apply()
    }

    // Display
    fun toggleExternalDisplay(activity: Activity) {
        if (externalDisplayManager.isOutputEnabled.value) {
            externalDisplayManager.disableOutput()
        } else {
            externalDisplayManager.enableOutput(activity)
        }
    }

    fun selectDisplayResolution(resolution: DisplayResolution) {
        _uiState.update { it.copy(externalDisplayResolution = resolution) }
    }

    // System / Kiosk
    fun toggleKioskMode() {
        val newValue = !_uiState.value.isKioskModeEnabled
        _uiState.update { it.copy(isKioskModeEnabled = newValue) }
        prefs.edit().putBoolean("kiosk_mode", newValue).apply()
    }

    fun applyKioskMode(activity: Activity) {
        val isKiosk = _uiState.value.isKioskModeEnabled
        if (isKiosk) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            controller.hide(WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    fun toggleAutoStart() {
        val newValue = !_uiState.value.isAutoStartEnabled
        _uiState.update { it.copy(isAutoStartEnabled = newValue) }
        prefs.edit().putBoolean("auto_start", newValue).apply()
    }

    fun updateScreenTimeout(minutes: Int) {
        _uiState.update { it.copy(screenTimeoutMinutes = minutes) }
        prefs.edit().putInt("screen_timeout", minutes).apply()
    }

    // Streaming quality
    fun updateStreamFps(fps: Int) {
        val clamped = fps.coerceIn(MIN_STREAM_FPS, MAX_STREAM_FPS)
        streamingConfig.fps = clamped
        connectionManager.sendCommandToAll("set_stream_fps", value = clamped.toFloat())
        _uiState.update { it.copy(streamFps = clamped) }
    }

    fun updateStreamResolution(resolution: Int) {
        val clamped = resolution.coerceIn(MIN_STREAM_RESOLUTION, MAX_STREAM_RESOLUTION)
        streamingConfig.maxResolution = clamped
        connectionManager.sendCommandToAll("set_stream_resolution", value = clamped.toFloat())
        _uiState.update { it.copy(streamMaxResolution = clamped) }
    }

    fun updateStreamBitrate(mbps: Int) {
        adaptiveBitrateCeilingMbps = mbps.coerceIn(MIN_STREAM_BITRATE_MBPS, MAX_STREAM_BITRATE_MBPS)
        applyStreamBitrate(adaptiveBitrateCeilingMbps)
        if (_uiState.value.isAdaptiveBitrate) {
            updateAdaptiveBitrateStatus("Ceiling set to ${adaptiveBitrateCeilingMbps} Mbps")
        }
    }

    fun toggleLowLatencyDecode() {
        val newValue = !_uiState.value.isLowLatencyDecode
        streamingConfig.lowLatencyDecode = newValue
        _uiState.update { it.copy(isLowLatencyDecode = newValue) }
    }

    fun toggleAdaptiveBitrate() {
        val newValue = !_uiState.value.isAdaptiveBitrate
        streamingConfig.adaptiveBitrate = newValue
        if (newValue) {
            adaptiveBitrateCeilingMbps = _uiState.value.streamBitrateMbps
                .coerceIn(MIN_STREAM_BITRATE_MBPS, MAX_STREAM_BITRATE_MBPS)
            lastObservedDroppedFrames = _uiState.value.droppedFrames
            stableSinceMs = 0L
            lastAdaptiveChangeMs = 0L
        }
        val status = if (newValue) adaptiveMonitoringStatus() else "Off"
        adaptiveBitrateStatusText = status
        _uiState.update {
            it.copy(
                isAdaptiveBitrate = newValue,
                adaptiveBitrateStatus = status
            )
        }
    }

    fun toggleNavigationLock() {
        val newValue = !_uiState.value.isNavigationLocked
        _uiState.update { it.copy(isNavigationLocked = newValue) }
        prefs.edit().putBoolean("nav_lock", newValue).apply()
    }

    fun applyNavigationLock(activity: Activity) {
        val isLocked = _uiState.value.isNavigationLocked
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        if (isLocked) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun loadSavedSettings() {
        val format = prefs.getString("recording_format", null)?.let {
            try { RecordingFormat.valueOf(it) } catch (_: Exception) { null }
        }
        val bitrate = prefs.getInt("recording_bitrate", 10)
        val storagePath = prefs.getString("recording_storage_path", null)
        val kioskMode = prefs.getBoolean("kiosk_mode", false)
        val autoStart = prefs.getBoolean("auto_start", false)
        val screenTimeout = prefs.getInt("screen_timeout", 10)
        val navLock = prefs.getBoolean("nav_lock", false)

        recorder.bitrateMbps = bitrate
        if (format != null) {
            recorder.useHevc = (format == RecordingFormat.MP4_H265)
        }

        _uiState.update {
            it.copy(
                recordingFormat = format ?: it.recordingFormat,
                recordingBitrateMbps = bitrate,
                recordingStoragePath = storagePath ?: it.recordingStoragePath,
                isKioskModeEnabled = kioskMode,
                isAutoStartEnabled = autoStart,
                screenTimeoutMinutes = screenTimeout,
                isNavigationLocked = navLock,
                streamFps = streamingConfig.fps,
                streamMaxResolution = streamingConfig.maxResolution,
                streamBitrateMbps = streamingConfig.bitrateMbps,
                isLowLatencyDecode = streamingConfig.lowLatencyDecode,
                isAdaptiveBitrate = streamingConfig.adaptiveBitrate,
                adaptiveBitrateStatus = adaptiveBitrateStatusText
            )
        }
        adaptiveBitrateCeilingMbps = streamingConfig.bitrateMbps
        adaptiveBitrateStatusText =
            if (streamingConfig.adaptiveBitrate) adaptiveMonitoringStatus() else "Off"
        updateAdaptiveBitrateStatus(adaptiveBitrateStatusText)
    }

    private fun maybeAdaptBitrate(
        liveStreams: List<CameraStreamState>,
        maxLatencyMs: Int,
        droppedFrames: Int,
        minStreamFps: Int
    ) {
        if (!_uiState.value.isAdaptiveBitrate) {
            updateAdaptiveBitrateStatus("Off")
            return
        }

        if (liveStreams.isEmpty()) {
            stableSinceMs = 0L
            lastObservedDroppedFrames = droppedFrames
            updateAdaptiveBitrateStatus("Standby: no active stream")
            return
        }

        val now = System.currentTimeMillis()
        val droppedDelta = (droppedFrames - lastObservedDroppedFrames).coerceAtLeast(0)
        lastObservedDroppedFrames = droppedFrames

        if (now - lastAdaptiveChangeMs < ADAPTIVE_CHANGE_COOLDOWN_MS) return

        val currentBitrate = _uiState.value.streamBitrateMbps
        val targetFps = streamingConfig.fps.coerceAtLeast(1)
        val fpsPressure = minStreamFps > 0 && minStreamFps < (targetFps * ADAPTIVE_FPS_DOWN_RATIO)
        val severeCongestion = maxLatencyMs >= ADAPTIVE_SEVERE_LATENCY_DOWN_MS ||
            droppedDelta >= ADAPTIVE_SEVERE_DROPS_DOWN_THRESHOLD
        val congested = maxLatencyMs >= ADAPTIVE_LATENCY_DOWN_MS ||
            droppedDelta >= ADAPTIVE_DROPS_DOWN_THRESHOLD ||
            fpsPressure

        if (congested) {
            stableSinceMs = 0L
            val reason = adaptiveCongestionReason(maxLatencyMs, droppedDelta, minStreamFps, targetFps)
            if (currentBitrate > MIN_STREAM_BITRATE_MBPS) {
                val stepDown = if (severeCongestion) 2 else 1
                val nextBitrate = (currentBitrate - stepDown).coerceAtLeast(MIN_STREAM_BITRATE_MBPS)
                applyStreamBitrate(nextBitrate)
                updateAdaptiveBitrateStatus("Lowered to ${nextBitrate} Mbps: $reason")
                lastAdaptiveChangeMs = now
            } else {
                updateAdaptiveBitrateStatus("At minimum 1 Mbps: $reason")
            }
            return
        }

        val fpsHealthy = minStreamFps == 0 || minStreamFps >= (targetFps * ADAPTIVE_FPS_UP_RATIO)
        val healthy = maxLatencyMs in 1 until ADAPTIVE_LATENCY_UP_MS &&
            droppedDelta == 0 &&
            fpsHealthy
        if (!healthy) {
            stableSinceMs = 0L
            updateAdaptiveBitrateStatus(
                "Monitoring: ${maxLatencyMs}ms, ${fpsStatus(minStreamFps, targetFps)}, drops +$droppedDelta"
            )
            return
        }

        if (stableSinceMs == 0L) {
            stableSinceMs = now
            updateAdaptiveBitrateStatus("Stable at ${currentBitrate} Mbps")
            return
        }

        if (now - stableSinceMs >= ADAPTIVE_STABLE_RECOVERY_MS &&
            currentBitrate < adaptiveBitrateCeilingMbps
        ) {
            val nextBitrate = currentBitrate + 1
            applyStreamBitrate(nextBitrate)
            updateAdaptiveBitrateStatus("Recovered to ${nextBitrate} Mbps")
            stableSinceMs = now
            lastAdaptiveChangeMs = now
        } else {
            updateAdaptiveBitrateStatus("Stable at ${currentBitrate} Mbps")
        }
    }

    private fun applyStreamBitrate(mbps: Int) {
        val clamped = mbps.coerceIn(MIN_STREAM_BITRATE_MBPS, MAX_STREAM_BITRATE_MBPS)
        streamingConfig.bitrateMbps = clamped
        connectionManager.sendCommandToAll("set_stream_bitrate", value = clamped.toFloat())
        _uiState.update { it.copy(streamBitrateMbps = clamped) }
    }

    private fun adaptiveMonitoringStatus(): String =
        "Monitoring up to ${adaptiveBitrateCeilingMbps} Mbps"

    private fun adaptiveCongestionReason(
        maxLatencyMs: Int,
        droppedDelta: Int,
        minStreamFps: Int,
        targetFps: Int
    ): String =
        when {
            maxLatencyMs >= ADAPTIVE_LATENCY_DOWN_MS &&
                droppedDelta >= ADAPTIVE_DROPS_DOWN_THRESHOLD ->
                "${maxLatencyMs}ms latency, drops +$droppedDelta"
            maxLatencyMs >= ADAPTIVE_LATENCY_DOWN_MS ->
                "${maxLatencyMs}ms latency"
            minStreamFps > 0 && minStreamFps < (targetFps * ADAPTIVE_FPS_DOWN_RATIO) ->
                fpsStatus(minStreamFps, targetFps)
            else ->
                "drops +$droppedDelta"
        }

    private fun fpsStatus(minStreamFps: Int, targetFps: Int): String =
        if (minStreamFps > 0) "${minStreamFps}/${targetFps}fps" else "--/${targetFps}fps"

    private fun updateAdaptiveBitrateStatus(status: String) {
        if (adaptiveBitrateStatusText == status) return
        adaptiveBitrateStatusText = status
        _uiState.update { it.copy(adaptiveBitrateStatus = status) }
    }

    companion object {
        private const val MIN_STREAM_BITRATE_MBPS = 1
        private const val MAX_STREAM_BITRATE_MBPS = 20
        private const val MIN_STREAM_FPS = 1
        private const val MAX_STREAM_FPS = 60
        private const val MIN_STREAM_RESOLUTION = 360
        private const val MAX_STREAM_RESOLUTION = 2160
        private const val ADAPTIVE_LATENCY_DOWN_MS = 180
        private const val ADAPTIVE_SEVERE_LATENCY_DOWN_MS = 320
        private const val ADAPTIVE_LATENCY_UP_MS = 80
        private const val ADAPTIVE_DROPS_DOWN_THRESHOLD = 5
        private const val ADAPTIVE_SEVERE_DROPS_DOWN_THRESHOLD = 15
        private const val ADAPTIVE_FPS_DOWN_RATIO = 0.65f
        private const val ADAPTIVE_FPS_UP_RATIO = 0.85f
        private const val ADAPTIVE_CHANGE_COOLDOWN_MS = 5_000L
        private const val ADAPTIVE_STABLE_RECOVERY_MS = 15_000L
    }

}
