package com.camhub.studio.ui.director.model

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import com.camhub.studio.data.metrics.PipelineLatencyBreakdown
import com.camhub.studio.data.metrics.FrameSinkLatencyBreakdown
import com.camhub.studio.data.capability.HubCapabilityReport
import com.camhub.studio.data.capability.HubRuntimeRecommendation
import com.camhub.studio.data.network.DiscoveredPeer
import com.camhub.studio.data.network.VideoTransport
import com.camhub.studio.data.ptz.HybridPtzState

data class DirectorUiState(
    val bitrateKbps: Int = 0,
    val latencyMs: Int = 0,
    val latencyP50Ms: Int = 0,
    val latencyP95Ms: Int = 0,
    val latencyP99Ms: Int = 0,
    val timecode: String = "00:00:00:00",
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val recordingPath: String = "",
    val isRecordingSpatialUpscaling: Boolean = false,
    val recordingOutputHeight: Int = 0,
    val wifiStrength: Int = 0,
    val networkTransportLabel: String = "AUTO · OFFLINE",
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
    val isLivePtzUnlocked: Boolean = false,
    val showCameraControl: Boolean = false,
    val controlCameraIndex: Int = -1,
    val audioMasterLevel: Float = 0f,
    val autoRecordCameras: Boolean = false,
    val showDeviceManager: Boolean = false,
    val discoveredPeers: List<DiscoveredPeer> = emptyList(),
    val hubCapabilityReport: HubCapabilityReport? = null,
    val runtimeRecommendation: HubRuntimeRecommendation? = null,
    val thermalStatus: Int = -1,
    val isAutomaticHubProfile: Boolean = false,
    val effectiveStreamHeight: Int = 0,
    val effectiveStreamFps: Int = 0,
    val isPgmSpatialUpscalingEnabled: Boolean = false,
    val pgmOutputHeight: Int = 0
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
    val previewSourceBitmap: Bitmap? = null,
    val previewBitmap: ImageBitmap? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val frameSequence: Long = 0L,
    val bitrateKbps: Int = 0,
    val latencyMs: Int = 0,
    val latencyP50Ms: Int = 0,
    val latencyP95Ms: Int = 0,
    val latencyP99Ms: Int = 0,
    val latencySampleCount: Int = 0,
    val isClockSynchronized: Boolean = false,
    val pipelineLatency: PipelineLatencyBreakdown? = null,
    val externalDisplayLatency: FrameSinkLatencyBreakdown? = null,
    val externalLatencyP50Ms: Int = 0,
    val externalLatencyP95Ms: Int = 0,
    val externalLatencyP99Ms: Int = 0,
    val externalLatencySampleCount: Int = 0,
    val droppedFrames: Int = 0,
    /** Source frames arriving from the camera, independent of multiview render throttling. */
    val ingressFps: Int = 0,
    val videoTransport: VideoTransport = VideoTransport.NONE,
    val udpPacketsReceived: Long = 0,
    val udpCompletedFrames: Long = 0,
    val udpDeadlineDroppedFrames: Long = 0,
    val udpEstimatedMissingPackets: Long = 0,
    val udpPacketLossPercent: Float = 0f,
    val transportFallbackReason: String = "",
    val adaptiveBitrateTargetMbps: Int = 0,
    val adaptiveBitrateReason: String = "",
    val audioLevel: Float = 0f,
    val audioStatus: String = "Disconnected",
    val isRecording: Boolean = false,
    val supportsRemotePtz: Boolean = false,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val ptzState: HybridPtzState = HybridPtzState()
)

enum class ConnectionStatus { LIVE, STANDBY, OFFLINE }

enum class TransitionType { CUT, MIX, DIP, WIPE }
