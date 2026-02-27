package com.camhub.studio.ui.camera.model

data class CameraUiState(
    val nodeName: String = "",
    val isRecording: Boolean = false,
    val timecode: String = "00:00:00:00",
    val format: String = "4K 30p",
    val codec: String = "H.265",
    val bitrate: String = "45 Mbps",
    val lens: LensInfo = LensInfo(),
    val isoValues: List<String> = emptyList(),
    val selectedIsoIndex: Int = 0,
    val shutterValues: List<String> = emptyList(),
    val selectedShutterIndex: Int = 0,
    val focusDistances: List<String> = emptyList(),
    val selectedFocusIndex: Int = 0,
    val isPeakingEnabled: Boolean = false,
    val audioLevels: List<Float> = emptyList(),
    val storageUsedGb: Float = 0f,
    val storageTotalGb: Float = 0f,
    val isPgm: Boolean = false,
    val isPvw: Boolean = false,
    val isRemoteOverride: Boolean = false,
    val batteryPercent: Int = 0,
    val wifiStrength: Int = 0,
    // Phase 2 additions
    val isCameraBound: Boolean = false,
    val isManualExposureSupported: Boolean = false,
    val cameraError: String? = null,
    // Phase 6 additions
    val isFrontCamera: Boolean = false,
    // Phase 7 additions
    val showExposurePanel: Boolean = false,
    val showFocusPanel: Boolean = false,
    val zoomRatio: Float = 1f,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val zoomSteps: List<String> = listOf("1.0x"),
    val selectedZoomIndex: Int = 0,
    // Tap-to-focus indicator
    val focusPointX: Float? = null,
    val focusPointY: Float? = null,
    // Portrait preview mode: true = 9:16 (tall), false = 16:9 (landscape strip)
    val isPortraitFullPreview: Boolean = true,
    // Streaming quality settings panel
    val showSettingsPanel: Boolean = false,
    val streamFps: Int = 30,
    val streamMaxResolution: Int = 1080,
    val streamBitrateMbps: Int = 4
)

data class LensInfo(
    val focalLength: String = "",
    val aperture: String = "",
    val model: String = ""
)
