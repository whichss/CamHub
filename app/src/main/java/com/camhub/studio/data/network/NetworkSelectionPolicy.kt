package com.camhub.studio.data.network

enum class NetworkSelectionMode {
    AUTO,
    WIFI,
    ETHERNET
}

enum class NetworkTransport {
    NONE,
    WIFI,
    ETHERNET
}

object NetworkSelectionPolicy {
    fun orderedTransports(
        mode: NetworkSelectionMode,
        available: Set<NetworkTransport>
    ): List<NetworkTransport> = when (mode) {
        NetworkSelectionMode.AUTO -> listOf(
            NetworkTransport.ETHERNET,
            NetworkTransport.WIFI
        )
        NetworkSelectionMode.WIFI -> listOf(NetworkTransport.WIFI)
        NetworkSelectionMode.ETHERNET -> listOf(NetworkTransport.ETHERNET)
    }.filter { it in available }
}
