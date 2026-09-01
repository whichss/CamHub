package com.camhub.studio.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class NetworkTransportState(
    val selectionMode: NetworkSelectionMode = NetworkSelectionMode.AUTO,
    val availableTransports: Set<NetworkTransport> = emptySet(),
    val preferredTransport: NetworkTransport = NetworkTransport.NONE,
    val activeTransport: NetworkTransport = NetworkTransport.NONE,
    val localIpAddress: String = "0.0.0.0",
    val preferredNetworkHandle: Long = 0L
) {
    val displayLabel: String
        get() = when (activeTransport) {
            NetworkTransport.ETHERNET -> "AUTO · LAN"
            NetworkTransport.WIFI -> "AUTO · WI-FI"
            NetworkTransport.NONE -> "AUTO · OFFLINE"
        }.let { automaticLabel ->
            when (selectionMode) {
                NetworkSelectionMode.AUTO -> automaticLabel
                NetworkSelectionMode.WIFI -> "WI-FI FIXED"
                NetworkSelectionMode.ETHERNET -> "LAN FIXED"
            }
        }
}

/**
 * Tracks local data transports and provides destination-aware socket binding.
 * Cellular is deliberately excluded from CamHub's local video path.
 */
@Singleton
class NetworkTransportManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "NetworkTransport"
        private const val PREFS_NAME = "camhub_network_transport"
        private const val PREF_MODE = "selection_mode"
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val knownNetworks = ConcurrentHashMap<Network, NetworkTransport>()

    @Volatile
    private var inUseNetwork: Network? = null

    private val _state = MutableStateFlow(
        NetworkTransportState(selectionMode = loadMode())
    )
    val state: StateFlow<NetworkTransportState> = _state.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNetwork(network)
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            refreshNetwork(network, capabilities)
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            refreshState()

        override fun onLost(network: Network) {
            knownNetworks.remove(network)
            if (inUseNetwork == network) inUseNetwork = null
            refreshState()
        }
    }

    init {
        connectivityManager.allNetworks.forEach(::refreshNetwork)
        val request = NetworkRequest.Builder().build()
        runCatching { connectivityManager.registerNetworkCallback(request, networkCallback) }
            .onFailure { Log.w(TAG, "Network callback registration failed", it) }
        refreshState()
    }

    fun setSelectionMode(mode: NetworkSelectionMode) {
        if (_state.value.selectionMode == mode) return
        prefs.edit().putString(PREF_MODE, mode.name).apply()
        _state.value = _state.value.copy(selectionMode = mode)
        inUseNetwork = null
        refreshState()
    }

    fun candidateNetworks(destinationIp: String? = null): List<Network> {
        val address = destinationIp
            ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
        val available = knownNetworks.entries
            .filter { (_, transport) -> transport != NetworkTransport.NONE }
        val orderedTransports = NetworkSelectionPolicy.orderedTransports(
            mode = _state.value.selectionMode,
            available = available.mapTo(mutableSetOf()) { it.value }
        )

        return available
            .sortedWith(
                compareBy<Map.Entry<Network, NetworkTransport>> {
                    orderedTransports.indexOf(it.value).takeIf { index -> index >= 0 }
                        ?: Int.MAX_VALUE
                }.thenByDescending { entry -> routeMatches(entry.key, address) }
            )
            .filter { it.value in orderedTransports }
            .let { entries ->
                if (address == null) entries
                else entries.sortedByDescending { routeMatches(it.key, address) }
            }
            .map { it.key }
    }

    fun preferredNetwork(destinationIp: String? = null): Network? =
        candidateNetworks(destinationIp).firstOrNull()

    fun transportOf(network: Network?): NetworkTransport =
        network?.let { knownNetworks[it] } ?: NetworkTransport.NONE

    fun localIpv4Address(network: Network?): Inet4Address? {
        if (network == null) return null
        return connectivityManager.getLinkProperties(network)
            ?.linkAddresses
            ?.asSequence()
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
    }

    fun localIpAddress(): String =
        localIpv4Address(inUseNetwork ?: preferredNetwork())?.hostAddress ?: "0.0.0.0"

    fun networkForLocalAddress(address: InetAddress?): Network? {
        if (address == null) return null
        return knownNetworks.keys.firstOrNull { network ->
            connectivityManager.getLinkProperties(network)
                ?.linkAddresses
                ?.any { it.address == address }
                ?: false
        }
    }

    fun createBoundSocket(network: Network?): Socket =
        network?.socketFactory?.createSocket() ?: Socket()

    fun markInUse(network: Network?) {
        if (network == null || knownNetworks[network] == null) return
        inUseNetwork = network
        refreshState()
    }

    fun networkSnapshot(): List<Network> = candidateNetworks()

    private fun loadMode(): NetworkSelectionMode = runCatching {
        NetworkSelectionMode.valueOf(
            prefs.getString(PREF_MODE, NetworkSelectionMode.AUTO.name)
                ?: NetworkSelectionMode.AUTO.name
        )
    }.getOrDefault(NetworkSelectionMode.AUTO)

    private fun refreshNetwork(network: Network) {
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        if (capabilities == null) {
            knownNetworks.remove(network)
        } else {
            refreshNetwork(network, capabilities)
        }
    }

    private fun refreshNetwork(network: Network, capabilities: NetworkCapabilities) {
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                NetworkTransport.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                NetworkTransport.WIFI
            else -> NetworkTransport.NONE
        }
        if (transport == NetworkTransport.NONE) knownNetworks.remove(network)
        else knownNetworks[network] = transport
        refreshState()
    }

    @Synchronized
    private fun refreshState() {
        val mode = _state.value.selectionMode
        val available = knownNetworks.values.toSet()
        val preferred = NetworkSelectionPolicy.orderedTransports(mode, available).firstOrNull()
            ?: NetworkTransport.NONE
        val preferredNetwork = candidateNetworks().firstOrNull()
        val currentInUse = inUseNetwork?.takeIf {
            knownNetworks[it] != null && transportOf(it) in
                NetworkSelectionPolicy.orderedTransports(mode, available)
        }
        val activeNetwork = currentInUse ?: preferredNetwork
        _state.value = NetworkTransportState(
            selectionMode = mode,
            availableTransports = available,
            preferredTransport = preferred,
            activeTransport = transportOf(activeNetwork),
            localIpAddress = localIpv4Address(activeNetwork)?.hostAddress ?: "0.0.0.0",
            preferredNetworkHandle = preferredNetwork?.networkHandle ?: 0L
        )
    }

    private fun routeMatches(network: Network, address: InetAddress?): Boolean {
        if (address == null) return true
        return connectivityManager.getLinkProperties(network)
            ?.routes
            ?.any { it.matches(address) }
            ?: false
    }
}
