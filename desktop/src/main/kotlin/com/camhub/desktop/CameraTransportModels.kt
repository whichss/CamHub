package com.camhub.desktop

import com.camhub.studio.data.network.CamHubControlMessage
import kotlinx.serialization.Serializable

data class CameraEndpoint(
    val host: String,
    val signalingPort: Int,
    val discoveredName: String = "Camera-$host",
    val lastSeenAtMs: Long = System.currentTimeMillis()
) {
    val id: String get() = "$host:$signalingPort"

    override fun toString(): String = "$discoveredName · $host:$signalingPort"
}

@Serializable
data class CameraAnnouncement(
    val service: String = "",
    val ip: String = "",
    val port: Int = 0,
    val deviceName: String = ""
)

internal typealias ControlMessage = CamHubControlMessage

enum class DesktopCameraStatus {
    DISCOVERED,
    CONNECTING,
    CONNECTED,
    STREAMING,
    RECONNECTING,
    ERROR,
    DISCONNECTED
}

data class CameraConnectionSnapshot(
    val name: String,
    val endpoint: CameraEndpoint,
    val status: DesktopCameraStatus,
    val detail: String = "",
    val framesReceived: Long = 0,
    val framesDropped: Long = 0,
    val packetLossPercent: Float = 0f,
    val captureToDecodeP50Ms: Long? = null,
    val captureToDecodeP95Ms: Long? = null,
    val captureToDecodeP99Ms: Long? = null,
    val captureToDecodeSampleCount: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0,
    val supportsRemotePtz: Boolean = false,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val remotePtzZoom: Float = 1f,
    val remotePtzCenterX: Float = 0.5f,
    val remotePtzCenterY: Float = 0.5f,
    val ptzAppliedRequestId: Long = 0L
)
