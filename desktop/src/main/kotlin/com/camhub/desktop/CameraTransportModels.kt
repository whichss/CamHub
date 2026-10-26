package com.camhub.desktop

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
    val port: Int = 0
)

@Serializable
internal data class ControlMessage(
    val type: String,
    val deviceName: String = "",
    val streamPort: Int = 0,
    val pin: String = "",
    val sessionKey: String = "",
    val command: String = "",
    val value: Float = 0f,
    val stringValue: String = "",
    val audioStreamPort: Int = 0,
    val udpStreamPort: Int = 0,
    val udpReceivePort: Int = 0,
    val syncId: Long = 0L,
    val t0WallMs: Long = 0L,
    val t1WallMs: Long = 0L,
    val t2WallMs: Long = 0L,
    val supportsRemotePtz: Boolean = false,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val requestId: Long = 0L
)

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
    val latencyMs: Long? = null,
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0
)
