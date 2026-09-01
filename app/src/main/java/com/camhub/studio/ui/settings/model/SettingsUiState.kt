package com.camhub.studio.ui.settings.model

import com.camhub.studio.data.network.NetworkSelectionMode

data class SettingsUiState(
    val selectedSettingsTab: Int = 0,

    // Connection tab
    val selectedProtocol: Protocol = Protocol.WEBRTC,
    val networkSelectionMode: NetworkSelectionMode = NetworkSelectionMode.AUTO,
    val activeNetworkLabel: String = "AUTO · OFFLINE",
    val hasEthernet: Boolean = false,
    val hasWifi: Boolean = false,
    val isMdnsEnabled: Boolean = true,
    val discoveredNodes: List<DiscoveredNode> = emptyList(),
    val activeStreams: Int = 0,
    val latencyMs: Int = 0,
    val minStreamFps: Int = 0,

    // Recording tab
    val recordingStoragePath: String = "",
    val recordingFormat: RecordingFormat = RecordingFormat.MP4_H264,
    val recordingBitrateMbps: Int = 10,

    // Display tab
    val isExternalDisplayConnected: Boolean = false,
    val isExternalDisplayEnabled: Boolean = false,
    val externalDisplayResolution: DisplayResolution = DisplayResolution.MATCH_SOURCE,

    // System tab
    val batteryPercent: Int = 0,
    val wifiStrength: Int = 0,
    val storageUsedGb: Float = 0f,
    val storageTotalGb: Float = 0f,
    val appVersion: String = "1.0.0",
    val isKioskModeEnabled: Boolean = false,
    val isAutoStartEnabled: Boolean = false,
    val screenTimeoutMinutes: Int = 10,
    val isNavigationLocked: Boolean = false,

    // Streaming quality
    val streamFps: Int = 30,
    val streamMaxResolution: Int = 1080,
    val streamBitrateMbps: Int = 6,
    val isLowLatencyDecode: Boolean = true,
    val isAdaptiveBitrate: Boolean = false,
    val adaptiveBitrateStatus: String = "Off",
    val isAutomaticHubProfile: Boolean = false,

    // Bottom bar
    val droppedFrames: Int = 0
)

data class DiscoveredNode(
    val name: String,
    val ip: String,
    val status: NodeStatus
)

enum class NodeStatus { CONNECTED, IDLE, OFFLINE }
enum class Protocol { WEBRTC, NDI_HX, SRT }
enum class RecordingFormat { MP4_H264, MP4_H265 }
enum class DisplayResolution { MATCH_SOURCE, HD_1080P, UHD_4K }
