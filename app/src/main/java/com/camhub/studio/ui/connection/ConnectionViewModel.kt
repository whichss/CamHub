package com.camhub.studio.ui.connection

import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.camhub.studio.data.audio.AudioCaptureService
import com.camhub.studio.data.network.DiscoveredPeer
import com.camhub.studio.data.network.HotspotManager
import com.camhub.studio.data.network.NsdDiscoveryManager
import com.camhub.studio.data.network.PeerConnectionManager
import com.camhub.studio.data.network.PeerConnectionState
import com.camhub.studio.data.network.StreamServer
import com.camhub.studio.ui.connection.model.AppRole
import com.camhub.studio.ui.connection.model.ConnectionState
import com.camhub.studio.ui.connection.model.ConnectionUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val nsdManager: NsdDiscoveryManager,
    private val connectionManager: PeerConnectionManager,
    private val streamServer: StreamServer,
    private val audioCaptureService: AudioCaptureService,
    private val hotspotManager: HotspotManager
) : ViewModel() {

    private val roleArg: String = savedStateHandle["role"] ?: "camera"
    private val role = if (roleArg == "director") AppRole.DIRECTOR else AppRole.CAMERA

    private val _uiState = MutableStateFlow(
        ConnectionUiState(
            role = role,
            deviceName = "${Build.MODEL}-${(1000..9999).random()}",
            localIp = nsdManager.getLocalIpAddress()
        )
    )
    val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

    init {
        when (role) {
            AppRole.CAMERA -> startCameraMode()
            AppRole.DIRECTOR -> startDirectorMode()
        }

        viewModelScope.launch {
            connectionManager.connectionState.collect { state ->
                _uiState.update {
                    it.copy(
                        connectionState = when (state) {
                            PeerConnectionState.DISCONNECTED -> ConnectionState.WAITING
                            PeerConnectionState.CONNECTING -> ConnectionState.CONNECTING
                            PeerConnectionState.CONNECTED -> ConnectionState.CONNECTED
                            PeerConnectionState.ERROR -> ConnectionState.ERROR
                        }
                    )
                }
            }
        }

        viewModelScope.launch {
            connectionManager.connectedPeers.collect { peers ->
                _uiState.update {
                    it.copy(
                        connectedPeerCount = peers.size,
                        connectedPeerNames = peers.map { p -> p.name },
                        isReadyToProceed = peers.isNotEmpty()
                    )
                }
            }
        }

        // Director mode: observe hotspot state
        if (role == AppRole.DIRECTOR) {
            viewModelScope.launch {
                hotspotManager.hotspotState.collect { info ->
                    _uiState.update {
                        it.copy(
                            hotspotActive = info.isActive,
                            hotspotSsid = info.ssid,
                            hotspotPassword = info.password,
                            hotspotError = info.errorMessage
                        )
                    }
                }
            }
        }
    }

    private fun startCameraMode() {
        viewModelScope.launch {
            // Start servers on IO dispatcher
            val signalingPort = withContext(Dispatchers.IO) {
                // Generate session key early (SRT needs passphrase before bind)
                val sessionKey = connectionManager.generateAndStoreSessionKey()
                streamServer.setSessionKey(sessionKey)
                audioCaptureService.setSessionKey(sessionKey)

                val videoPort = streamServer.start()
                val audioPort = audioCaptureService.start()
                connectionManager.startServer(
                    port = 0,
                    deviceName = _uiState.value.deviceName,
                    streamPort = videoPort,
                    audioStreamPort = audioPort
                )
            }

            _uiState.update { it.copy(port = signalingPort) }
            nsdManager.registerService(_uiState.value.deviceName, signalingPort)
        }
    }

    private fun startDirectorMode() {
        nsdManager.startDiscovery()

        // Observe discovered peers from NSD
        viewModelScope.launch {
            nsdManager.discoveredPeers.collect { peers ->
                _uiState.update { state ->
                    val connectedIps = connectionManager.connectedPeers.value.map { it.ip }.toSet()
                    val updatedPeers = peers.map { peer ->
                        peer.copy(isConnected = peer.ip in connectedIps)
                    }
                    state.copy(discoveredPeers = updatedPeers)
                }
            }
        }

        // Also refresh discovered peers' connected status when connections change
        viewModelScope.launch {
            connectionManager.connectedPeers.collect { connectedList ->
                val connectedIps = connectedList.map { it.ip }.toSet()
                _uiState.update { state ->
                    val updatedPeers = state.discoveredPeers.map { peer ->
                        peer.copy(isConnected = peer.ip in connectedIps)
                    }
                    state.copy(discoveredPeers = updatedPeers)
                }
            }
        }
    }

    /** Director: connect to a discovered peer directly (no PIN) */
    fun connectToPeer(peer: DiscoveredPeer) {
        connectionManager.connectToCamera(peer, _uiState.value.deviceName)
    }

    fun connectToAll() {
        val connectedNames = connectionManager.connectedPeers.value.map { it.name }.toSet()
        val unconnected = _uiState.value.discoveredPeers.filter { it.name !in connectedNames }
        for (peer in unconnected) {
            connectionManager.connectToCamera(peer, _uiState.value.deviceName)
        }
    }

    fun addManualConnection(ipPort: String) {
        val parts = ipPort.trim().split(":")
        if (parts.size == 2) {
            val ip = parts[0].trim()
            val port = parts[1].trim().toIntOrNull() ?: return
            if (port > 0 && ip.isNotEmpty()) {
                nsdManager.addManualPeer(ip, port)
            }
        }
    }

    fun disconnectPeer(name: String) {
        connectionManager.disconnectPeer(name)
    }

    /** Director: restart NSD + UDP discovery to find new cameras */
    fun rescan() {
        if (role != AppRole.DIRECTOR) return
        // Clear non-connected peers from UI immediately (connected ones stay)
        val connectedNames = connectionManager.connectedPeers.value.map { it.name }.toSet()
        _uiState.update { state ->
            state.copy(discoveredPeers = state.discoveredPeers.filter { it.name in connectedNames })
        }
        nsdManager.stopDiscovery()
        nsdManager.startDiscovery()
    }

    fun startHotspot() {
        hotspotManager.startHotspot()
    }

    fun stopHotspot() {
        hotspotManager.stopHotspot()
    }

    override fun onCleared() {
        super.onCleared()
        if (role == AppRole.DIRECTOR) {
            nsdManager.stopDiscovery()
            hotspotManager.stopHotspot()
        } else {
            nsdManager.unregisterService()
            // Don't stop audioCaptureService here — it's a singleton that must
            // survive navigation to CameraHudScreen. Cleanup happens in
            // CameraHudViewModel.onCleared() instead.
        }
    }
}
