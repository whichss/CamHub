package com.camhub.studio.data.network

import kotlinx.serialization.Serializable

/** Shared TLS control-channel JSON contract used by Android, desktop and iOS camera sources. */
@Serializable
data class CamHubControlMessage(
    val type: String,
    val deviceName: String = "",
    val streamPort: Int = 0,
    val tcpStreamPort: Int = 0,
    val pin: String = "",
    val sessionKey: String = "",
    val tcpAuthToken: String = "",
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
    val supportsAuthenticatedTcp: Boolean = false,
    val requestId: Long = 0L
)
