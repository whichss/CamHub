package com.camhub.studio.ui.connection.model

import com.camhub.studio.data.network.DiscoveredPeer

data class ConnectionUiState(
    val role: AppRole = AppRole.CAMERA,
    val deviceName: String = "",
    val localIp: String = "0.0.0.0",
    val port: Int = 0,
    val connectionState: ConnectionState = ConnectionState.WAITING,
    val discoveredPeers: List<DiscoveredPeer> = emptyList(),
    val connectedPeerCount: Int = 0,
    val connectedPeerNames: List<String> = emptyList(),
    val errorMessage: String? = null,
    // Hotspot
    val hotspotActive: Boolean = false,
    val hotspotSsid: String = "",
    val hotspotPassword: String = "",
    val hotspotError: String? = null,
    val isReadyToProceed: Boolean = false
)

enum class AppRole { DIRECTOR, CAMERA }

enum class ConnectionState { WAITING, CONNECTING, CONNECTED, ERROR }
