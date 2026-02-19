package com.camhub.studio.ui.director.model

import androidx.compose.ui.graphics.ImageBitmap

data class DirectorUiState(
    val bitrateKbps: Int = 0,
    val latencyMs: Int = 0,
    val timecode: String = "00:00:00:00",
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val recordingPath: String = "",
    val wifiStrength: Int = 0,
    val batteryPercent: Int = 0,
    val storageUsedGb: Float = 0f,
    val storageTotalGb: Float = 0f,
    val pvwCameraIndex: Int = -1,
    val pgmCameraIndex: Int = -1,
    val tBarPosition: Float = 0f,
    val selectedTransition: TransitionType = TransitionType.CUT,
    val cameras: List<CameraNode> = emptyList(),
    val showAudioMixer: Boolean = false,
    val isExternalDisplayConnected: Boolean = false,
    val isExternalDisplayEnabled: Boolean = false,
    val transitionProgress: Float = 0f,
    val isTransitioning: Boolean = false,
    val showCameraControl: Boolean = false,
    val controlCameraIndex: Int = -1
)

data class CameraNode(
    val id: String,
    val name: String,
    val label: String = "",
    val fps: Int = 30,
    val tempCelsius: Int = 0,
    val status: ConnectionStatus = ConnectionStatus.OFFLINE,
    val isPgm: Boolean = false,
    val isPvw: Boolean = false,
    val previewBitmap: ImageBitmap? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val bitrateKbps: Int = 0
)

enum class ConnectionStatus { LIVE, STANDBY, OFFLINE }

enum class TransitionType { CUT, MIX, DIP, WIPE }
