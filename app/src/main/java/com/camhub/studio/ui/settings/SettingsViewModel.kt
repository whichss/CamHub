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
import com.camhub.studio.data.network.PeerConnectionManager
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
    @ApplicationContext private val context: Context,
    private val connectionManager: PeerConnectionManager,
    private val streamClient: StreamClient,
    private val deviceMonitor: DeviceMonitor,
    private val recorder: DirectorRecorder,
    private val externalDisplayManager: ExternalDisplayManager
) : ViewModel() {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("camhub_settings", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            recordingStoragePath = recorder.getRecordingDirectory().absolutePath
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

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
                isKioskModeEnabled = kioskMode,
                isAutoStartEnabled = autoStart,
                screenTimeoutMinutes = screenTimeout,
                isNavigationLocked = navLock
            )
        }
    }

}
