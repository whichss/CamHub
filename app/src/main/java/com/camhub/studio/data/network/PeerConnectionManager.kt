package com.camhub.studio.data.network

import android.util.Base64
import android.util.Log
import android.net.Network
import com.camhub.studio.data.metrics.ClockOffsetTracker
import com.camhub.studio.data.metrics.ClockSyncState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLServerSocket

enum class PeerConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}

@Serializable
data class HandshakeMessage(
    val type: String,        // "hello", "hello_ack", "ping", "pong", "error", "command"
    val deviceName: String = "",
    val streamPort: Int = 0,
    val pin: String = "",
    val sessionKey: String = "",   // Base64-encoded AES-256 key (sent after PIN auth)
    // Command fields (used when type == "command")
    val command: String = "",
    val value: Float = 0f,
    val stringValue: String = "",
    val audioStreamPort: Int = 0,
    // Low-latency video negotiation. Camera advertises udpStreamPort and the
    // authenticated hub replies with udpReceivePort in an udp_subscribe command.
    val udpStreamPort: Int = 0,
    val udpReceivePort: Int = 0,
    // NTP-style clock synchronization fields used by ping/pong.
    val syncId: Long = 0L,
    val t0WallMs: Long = 0L,
    val t1WallMs: Long = 0L,
    val t2WallMs: Long = 0L,
    // Camera capability negotiation fields.
    val supportsRemotePtz: Boolean = false,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    // Correlates state-changing commands with camera acknowledgements.
    val requestId: Long = 0L
)

data class PtzAppliedEvent(
    val cameraName: String,
    val requestId: Long,
    val zoom: Float,
    val centerX: Float,
    val centerY: Float
)

data class ConnectedPeer(
    val name: String,
    val ip: String,
    val signalingPort: Int,
    val streamPort: Int,
    val audioStreamPort: Int = 0,
    val udpStreamPort: Int = 0,
    val supportsRemotePtz: Boolean = false,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val network: Network? = null,
    val transport: NetworkTransport = NetworkTransport.NONE,
    val socket: Socket? = null,
    val streamKey: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConnectedPeer) return false
        return name == other.name && ip == other.ip && signalingPort == other.signalingPort && streamPort == other.streamPort && audioStreamPort == other.audioStreamPort && udpStreamPort == other.udpStreamPort && supportsRemotePtz == other.supportsRemotePtz && minZoomRatio == other.minZoomRatio && maxZoomRatio == other.maxZoomRatio && network == other.network && transport == other.transport
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + ip.hashCode()
        result = 31 * result + signalingPort
        result = 31 * result + streamPort
        result = 31 * result + audioStreamPort
        result = 31 * result + udpStreamPort
        result = 31 * result + supportsRemotePtz.hashCode()
        result = 31 * result + minZoomRatio.hashCode()
        result = 31 * result + maxZoomRatio.hashCode()
        result = 31 * result + (network?.hashCode() ?: 0)
        result = 31 * result + transport.hashCode()
        return result
    }
}

@Singleton
class PeerConnectionManager @Inject constructor(
    private val tlsHelper: TlsHelper,
    private val networkTransportManager: NetworkTransportManager,
    private val streamServer: StreamServer
) {

    companion object {
        private const val TAG = "PeerConnection"
        private const val HEARTBEAT_INTERVAL_MS = 3000L
        private const val HEARTBEAT_TIMEOUT_MS = 20000L
        private const val MAX_LINE_LENGTH = 4096
        private const val AES_KEY_SIZE = 32 // AES-256
        private const val MAX_PARSE_ERRORS = 5 // Disconnect after repeated parse failures
    }

    /** Read a line with maximum length to prevent memory exhaustion attacks */
    private fun readLineLimited(reader: BufferedReader, maxLen: Int = MAX_LINE_LENGTH): String? {
        val sb = StringBuilder()
        var ch = -1
        while (reader.read().also { ch = it } != -1) {
            if (ch == '\n'.code) break
            if (ch == '\r'.code) continue
            if (sb.length >= maxLen) throw IOException("Message too large")
            sb.append(ch.toChar())
        }
        return if (sb.isEmpty() && ch == -1) null else sb.toString()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secureRandom = SecureRandom()

    private val _connectionState = MutableStateFlow(PeerConnectionState.DISCONNECTED)
    val connectionState: StateFlow<PeerConnectionState> = _connectionState.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<ConnectedPeer>>(emptyList())
    val connectedPeers: StateFlow<List<ConnectedPeer>> = _connectedPeers.asStateFlow()

    private val _clockSyncStates = MutableStateFlow<Map<String, ClockSyncState>>(emptyMap())
    val clockSyncStates: StateFlow<Map<String, ClockSyncState>> = _clockSyncStates.asStateFlow()

    private val _ptzAppliedEvents = MutableSharedFlow<PtzAppliedEvent>(extraBufferCapacity = 16)
    val ptzAppliedEvents: SharedFlow<PtzAppliedEvent> = _ptzAppliedEvents.asSharedFlow()

    private var serverSocket: SSLServerSocket? = null
    private var serverJob: Job? = null
    @Volatile private var isServerMode = false
    private val heartbeatJobs = ConcurrentHashMap<String, Job>()
    private val peerSockets = ConcurrentHashMap<String, Socket>()
    private val peerWriters = ConcurrentHashMap<String, PrintWriter>()
    private val peerWriteLocks = ConcurrentHashMap<String, Any>()
    private val clockOffsetTrackers = ConcurrentHashMap<String, ClockOffsetTracker>()
    private val syncSequence = AtomicLong(0)
    private var observedPreferredNetworkHandle =
        networkTransportManager.state.value.preferredNetworkHandle

    // Session key shared across all directors (generated once per server session)
    private var serverSessionKey: ByteArray? = null

    var localDeviceName: String = ""
    var localStreamPort: Int = 0
    var localAudioStreamPort: Int = 0
    var localUdpStreamPort: Int = 0
    @Volatile private var localSupportsRemotePtz: Boolean = false
    @Volatile private var localMinZoomRatio: Float = 1f
    @Volatile private var localMaxZoomRatio: Float = 1f

    /** Callback invoked on camera side when a command is received from director */
    var onCommandReceived: ((HandshakeMessage) -> Unit)? = null

    init {
        scope.launch {
            networkTransportManager.state.collectLatest { transportState ->
                val nextHandle = transportState.preferredNetworkHandle
                if (nextHandle == observedPreferredNetworkHandle) return@collectLatest
                observedPreferredNetworkHandle = nextHandle
                if (nextHandle == 0L || localDeviceName.isBlank() || isServerMode) {
                    return@collectLatest
                }

                // Give Android a moment to finish installing routes, then create the
                // preferred connection before the stale connection is retired.
                delay(600)
                _connectedPeers.value.toList().forEach { peer ->
                    val preferred = networkTransportManager.preferredNetwork(peer.ip)
                        ?: return@forEach
                    if (peer.network?.networkHandle == preferred.networkHandle) return@forEach
                    connectToCamera(
                        peer = DiscoveredPeer(peer.name, peer.ip, peer.signalingPort),
                        directorName = localDeviceName
                    )
                }
            }
        }
    }

    fun updateLocalCameraCapabilities(
        supportsRemotePtz: Boolean,
        minZoomRatio: Float,
        maxZoomRatio: Float
    ) {
        val minZoom = minZoomRatio.coerceAtLeast(1f)
        val maxZoom = maxZoomRatio.coerceAtLeast(minZoom)
        if (
            localSupportsRemotePtz == supportsRemotePtz &&
            localMinZoomRatio == minZoom &&
            localMaxZoomRatio == maxZoom
        ) return
        localSupportsRemotePtz = supportsRemotePtz
        localMinZoomRatio = minZoom
        localMaxZoomRatio = maxZoom
        val message = HandshakeMessage(
            type = "capabilities",
            supportsRemotePtz = supportsRemotePtz,
            minZoomRatio = minZoom,
            maxZoomRatio = maxZoom
        )
        scope.launch {
            val encoded = json.encodeToString(message)
            peerWriters.keys.forEach { peerName ->
                sendMessage(peerName, encoded)
            }
        }
    }

    private fun installPeerTransport(peerName: String, socket: Socket, writer: PrintWriter) {
        // A camera can reconnect with the same advertised name before the old heartbeat exits.
        // Install the new transport first so stale cleanup cannot remove it.
        heartbeatJobs.remove(peerName)?.cancel()
        val oldSocket = peerSockets.put(peerName, socket)
        peerWriters[peerName] = writer
        peerWriteLocks.putIfAbsent(peerName, Any())
        if (oldSocket !== socket) {
            runCatching { oldSocket?.close() }
        }
    }

    private fun sendMessage(peerName: String, encoded: String): Boolean {
        val lock = peerWriteLocks.getOrPut(peerName) { Any() }
        synchronized(lock) {
            val writer = peerWriters[peerName] ?: return false
            writer.println(encoded)
            return !writer.checkError()
        }
    }

    private fun generateSessionKey(): ByteArray {
        val key = ByteArray(32) // AES-256
        secureRandom.nextBytes(key)
        return key
    }

    /** Generate session key early so SRT passphrase can be set before server bind */
    fun generateAndStoreSessionKey(): ByteArray {
        val key = generateSessionKey()
        serverSessionKey = key
        return key
    }

    // --- Camera side: start TLS server and wait for director connections ---

    fun startServer(
        port: Int,
        deviceName: String,
        streamPort: Int,
        audioStreamPort: Int = 0,
        udpStreamPort: Int = 0
    ): Int {
        isServerMode = true
        localDeviceName = deviceName
        localStreamPort = streamPort
        localAudioStreamPort = audioStreamPort
        localUdpStreamPort = udpStreamPort
        _connectionState.value = PeerConnectionState.DISCONNECTED

        if (serverSessionKey == null) {
            serverSessionKey = generateSessionKey()
        }
        serverSocket = tlsHelper.createServerSocket(port)
        val actualPort = serverSocket!!.localPort

        serverJob = scope.launch {
            Log.d(TAG, "TLS Server listening on port $actualPort")
            while (isActive) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    launch { handleIncomingConnection(clientSocket) }
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Server accept error", e)
                    break
                }
            }
        }

        return actualPort
    }

    private suspend fun handleIncomingConnection(socket: Socket) {
        val clientIp = socket.inetAddress.hostAddress ?: "unknown"
        var accepted = false
        try {
            socket.soTimeout = 10_000 // 10s timeout for handshake reads
            socket.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            // Read hello from Director
            val helloLine = readLineLimited(reader) ?: return
            val hello = try {
                json.decodeFromString<HandshakeMessage>(helloLine)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid JSON from $clientIp: ${e.message}")
                return
            }
            if (hello.type != "hello") return

            // Auto-accept — use shared session key
            val sessionKey = serverSessionKey ?: run {
                Log.e(TAG, "Server session key not available")
                return
            }
            val sessionKeyB64 = Base64.encodeToString(sessionKey, Base64.NO_WRAP)

            // Send hello_ack with stream port, audio port, and session key
            val ack = HandshakeMessage(
                type = "hello_ack",
                deviceName = localDeviceName,
                streamPort = localStreamPort,
                sessionKey = sessionKeyB64,
                audioStreamPort = localAudioStreamPort,
                udpStreamPort = localUdpStreamPort,
                supportsRemotePtz = localSupportsRemotePtz,
                minZoomRatio = localMinZoomRatio,
                maxZoomRatio = localMaxZoomRatio
            )
            writer.println(json.encodeToString(ack))

            // Handshake done — use generous timeout for heartbeat phase
            socket.soTimeout = (HEARTBEAT_TIMEOUT_MS + 5000).toInt()
            socket.keepAlive = true

            val peerName = hello.deviceName
            installPeerTransport(peerName, socket, writer)

            val peer = ConnectedPeer(
                name = peerName,
                ip = clientIp,
                signalingPort = socket.port,
                streamPort = 0,
                network = networkTransportManager.networkForLocalAddress(socket.localAddress),
                transport = networkTransportManager.transportOf(
                    networkTransportManager.networkForLocalAddress(socket.localAddress)
                ),
                socket = socket,
                streamKey = sessionKey
            )
            addPeer(peer)
            _connectionState.value = PeerConnectionState.CONNECTED
            accepted = true

            // Start heartbeat loop for this connection
            startHeartbeat(peerName, reader, writer, socket)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming connection", e)
        } finally {
            if (!accepted) {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    // --- Director side: connect to a discovered camera ---

    fun connectToCamera(peer: DiscoveredPeer, directorName: String) {
        scope.launch {
            localDeviceName = directorName
            _connectionState.value = PeerConnectionState.CONNECTING
            var socket: javax.net.ssl.SSLSocket? = null
            var selectedNetwork: Network? = null
            var accepted = false
            try {
                val candidates = networkTransportManager.candidateNetworks(peer.ip)
                if (candidates.isEmpty()) {
                    throw IOException("No allowed local network is available")
                }
                var lastError: Exception? = null
                for (network in candidates) {
                    try {
                        socket = tlsHelper.createClientSocket(peer.ip, peer.port, network)
                        selectedNetwork = network
                        break
                    } catch (error: Exception) {
                        lastError = error
                        Log.d(
                            TAG,
                            "${networkTransportManager.transportOf(network)} route failed for ${peer.ip}: ${error.message}"
                        )
                    }
                }
                if (socket == null) throw lastError ?: IOException("No reachable local route")
                socket.soTimeout = 10_000 // 10s timeout for handshake reads
                socket.tcpNoDelay = true

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                // Send hello
                val hello = HandshakeMessage(
                    type = "hello",
                    deviceName = directorName
                )
                writer.println(json.encodeToString(hello))

                // Read response
                val ackLine = readLineLimited(reader)
                val ack = if (ackLine != null) {
                    try {
                        json.decodeFromString<HandshakeMessage>(ackLine)
                    } catch (e: Exception) {
                        Log.w(TAG, "Invalid JSON from camera: ${e.message}")
                        null
                    }
                } else null

                if (ack == null || ack.type == "error") {
                    _connectionState.value = PeerConnectionState.ERROR
                    return@launch
                }

                if (ack.type != "hello_ack") {
                    _connectionState.value = PeerConnectionState.ERROR
                    return@launch
                }

                // Decode and validate session key
                val sessionKey = if (ack.sessionKey.isNotEmpty()) {
                    val decoded = Base64.decode(ack.sessionKey, Base64.NO_WRAP)
                    if (decoded.size != AES_KEY_SIZE) {
                        Log.w(TAG, "Invalid session key size: ${decoded.size}")
                        _connectionState.value = PeerConnectionState.ERROR
                        return@launch
                    }
                    decoded
                } else null

                // Handshake done — use generous timeout for heartbeat phase
                socket.soTimeout = (HEARTBEAT_TIMEOUT_MS + 5000).toInt()
                socket.keepAlive = true

                val peerName = ack.deviceName
                installPeerTransport(peerName, socket, writer)

                val connectedPeer = ConnectedPeer(
                    name = peerName,
                    ip = peer.ip,
                    signalingPort = peer.port,
                    streamPort = ack.streamPort,
                    audioStreamPort = ack.audioStreamPort,
                    udpStreamPort = ack.udpStreamPort,
                    supportsRemotePtz = ack.supportsRemotePtz,
                    minZoomRatio = ack.minZoomRatio,
                    maxZoomRatio = ack.maxZoomRatio,
                    network = selectedNetwork,
                    transport = networkTransportManager.transportOf(selectedNetwork),
                    socket = socket,
                    streamKey = sessionKey
                )
                addPeer(connectedPeer)
                networkTransportManager.markInUse(selectedNetwork)
                _connectionState.value = PeerConnectionState.CONNECTED
                accepted = true

                // Start heartbeat
                startHeartbeat(peerName, reader, writer, socket)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to ${peer.name}", e)
                _connectionState.value = PeerConnectionState.ERROR
            } finally {
                if (!accepted) {
                    try { socket?.close() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun startHeartbeat(
        peerName: String,
        reader: BufferedReader,
        writer: PrintWriter,
        socket: Socket
    ) {
        heartbeatJobs[peerName]?.cancel()

        heartbeatJobs[peerName] = scope.launch {
            val heartbeatJob = currentCoroutineContext()[Job]
            val lastPongTime = AtomicLong(System.currentTimeMillis())

            // Ping sender
            val pingSender = launch {
                while (isActive) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    try {
                        val ping = HandshakeMessage(
                            type = "ping",
                            syncId = syncSequence.incrementAndGet(),
                            t0WallMs = System.currentTimeMillis()
                        )
                        if (!sendMessage(peerName, json.encodeToString(ping))) {
                            throw Exception("Write error")
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Ping failed for $peerName")
                        break
                    }
                }
            }

            // Pong receiver / message reader
            val messageReader = launch {
                var parseErrors = 0
                try {
                    while (isActive) {
                        val line = try {
                            readLineLimited(reader)
                        } catch (e: java.net.SocketTimeoutException) {
                            // soTimeout fired — not fatal, timeout checker handles disconnect
                            continue
                        } catch (e: IOException) {
                            Log.d(TAG, "Read error for $peerName: ${e.message}")
                            break
                        } ?: break
                        val msg = try {
                            json.decodeFromString<HandshakeMessage>(line)
                        } catch (e: Exception) {
                            parseErrors++
                            Log.w(TAG, "Invalid heartbeat JSON from $peerName ($parseErrors/$MAX_PARSE_ERRORS)")
                            if (parseErrors >= MAX_PARSE_ERRORS) {
                                Log.w(TAG, "Too many parse errors from $peerName, disconnecting")
                                break
                            }
                            continue
                        }
                        parseErrors = 0 // Reset on successful parse
                        when (msg.type) {
                            "ping" -> {
                                val receivedAtWallMs = System.currentTimeMillis()
                                val pong = HandshakeMessage(
                                    type = "pong",
                                    syncId = msg.syncId,
                                    t0WallMs = msg.t0WallMs,
                                    t1WallMs = receivedAtWallMs,
                                    t2WallMs = System.currentTimeMillis()
                                )
                                if (!sendMessage(peerName, json.encodeToString(pong))) break
                            }
                            "pong" -> {
                                val receivedAtWallMs = System.currentTimeMillis()
                                lastPongTime.set(receivedAtWallMs)
                                if (msg.syncId > 0L && msg.t0WallMs > 0L) {
                                    val syncState = clockOffsetTrackers
                                        .getOrPut(peerName) { ClockOffsetTracker() }
                                        .record(
                                            t0 = msg.t0WallMs,
                                            t1 = msg.t1WallMs,
                                            t2 = msg.t2WallMs,
                                            t3 = receivedAtWallMs
                                        )
                                    if (syncState.isSynchronized) {
                                        updateClockSyncState(peerName, syncState)
                                    }
                                }
                            }
                            "command" -> {
                                if (isServerMode && handleTransportCommand(peerName, msg)) {
                                    // Transport commands are consumed below the UI layer.
                                } else {
                                    onCommandReceived?.invoke(msg.copy(deviceName = peerName))
                                }
                            }
                            "capabilities" -> {
                                updatePeerCapabilities(peerName, msg)
                            }
                            "ptz_applied" -> {
                                val center = msg.stringValue.split(',')
                                _ptzAppliedEvents.tryEmit(
                                    PtzAppliedEvent(
                                        cameraName = peerName,
                                        requestId = msg.requestId,
                                        zoom = msg.value,
                                        centerX = center.getOrNull(0)?.toFloatOrNull() ?: 0.5f,
                                        centerY = center.getOrNull(1)?.toFloatOrNull() ?: 0.5f
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Message reader ended for $peerName: ${e.message}")
                }
            }

            // Timeout checker
            val timeoutChecker = launch {
                while (isActive) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    if (System.currentTimeMillis() - lastPongTime.get() > HEARTBEAT_TIMEOUT_MS) {
                        Log.d(TAG, "Heartbeat timeout for $peerName")
                        break
                    }
                }
            }

            // Wait for first to finish (means disconnection), then cancel the rest
            kotlinx.coroutines.selects.select<Unit> {
                pingSender.onJoin {}
                messageReader.onJoin {}
                timeoutChecker.onJoin {}
            }
            pingSender.cancel()
            messageReader.cancel()
            timeoutChecker.cancel()

            // Peer disconnected — clean up
            runCatching { socket.close() }
            val removedCurrentTransport = peerSockets.remove(peerName, socket)
            peerWriters.remove(peerName, writer)
            heartbeatJob?.let { heartbeatJobs.remove(peerName, it) }
            if (removedCurrentTransport) {
                if (isServerMode) streamServer.unregisterUdpClient(peerName)
                peerWriteLocks.remove(peerName)
                removePeer(peerName)
                removeClockSyncState(peerName)
                if (_connectedPeers.value.isEmpty()) {
                    _connectionState.value = PeerConnectionState.DISCONNECTED
                }
            }
        }
    }

    @Synchronized
    private fun addPeer(peer: ConnectedPeer) {
        val current = _connectedPeers.value.toMutableList()
        current.removeAll { it.name == peer.name }
        current.add(peer)
        _connectedPeers.value = current
    }

    @Synchronized
    private fun removePeer(name: String) {
        _connectedPeers.value = _connectedPeers.value.filter { it.name != name }
    }

    @Synchronized
    private fun updatePeerCapabilities(peerName: String, message: HandshakeMessage) {
        _connectedPeers.value = _connectedPeers.value.map { peer ->
            if (peer.name == peerName) {
                peer.copy(
                    supportsRemotePtz = message.supportsRemotePtz,
                    minZoomRatio = message.minZoomRatio.coerceAtLeast(1f),
                    maxZoomRatio = message.maxZoomRatio.coerceAtLeast(1f)
                )
            } else {
                peer
            }
        }
    }

    @Synchronized
    private fun updateClockSyncState(peerName: String, state: ClockSyncState) {
        _clockSyncStates.value = _clockSyncStates.value.toMutableMap().apply {
            put(peerName, state)
        }
    }

    @Synchronized
    private fun removeClockSyncState(peerName: String) {
        clockOffsetTrackers.remove(peerName)
        _clockSyncStates.value = _clockSyncStates.value.toMutableMap().apply {
            remove(peerName)
        }
    }

    fun getClockSyncState(peerName: String): ClockSyncState? =
        _clockSyncStates.value[peerName]

    fun getRemoteClockOffsetMs(peerName: String): Long? =
        getClockSyncState(peerName)
            ?.takeIf { it.isSynchronized }
            ?.remoteClockOffsetMs

    fun sendCommand(
        peerName: String,
        command: String,
        value: Float = 0f,
        stringValue: String = "",
        requestId: Long = 0L
    ) {
        scope.launch {
            val writer = peerWriters[peerName] ?: return@launch
            try {
                val msg = HandshakeMessage(
                    type = "command",
                    command = command,
                    value = value,
                    stringValue = stringValue,
                    requestId = requestId
                )
                if (!sendMessage(peerName, json.encodeToString(msg))) {
                    Log.w(TAG, "Control channel unavailable for $peerName")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send command to $peerName: ${e.message}")
            }
        }
    }

    fun sendCommandToAll(command: String, value: Float = 0f, stringValue: String = "") {
        for (peerName in peerWriters.keys) {
            sendCommand(peerName, command, value, stringValue)
        }
    }

    /** Advertise the hub receiver only through the authenticated TLS channel. */
    fun sendUdpSubscribe(peerName: String, receivePort: Int) {
        if (receivePort !in 1..65_535) return
        scope.launch {
            val message = HandshakeMessage(
                type = "command",
                deviceName = localDeviceName,
                command = "udp_subscribe",
                udpReceivePort = receivePort
            )
            if (!sendMessage(peerName, json.encodeToString(message))) {
                Log.w(TAG, "Failed to subscribe UDP video from $peerName")
            }
        }
    }

    fun sendUdpUnsubscribe(peerName: String) {
        scope.launch {
            val message = HandshakeMessage(
                type = "command",
                deviceName = localDeviceName,
                command = "udp_unsubscribe"
            )
            sendMessage(peerName, json.encodeToString(message))
        }
    }

    /** Camera-side handling; the destination IP always comes from the TLS socket. */
    private fun handleTransportCommand(peerName: String, message: HandshakeMessage): Boolean {
        return when (message.command) {
            "udp_subscribe" -> {
                val peer = _connectedPeers.value.firstOrNull { it.name == peerName }
                if (peer != null && message.udpReceivePort in 1..65_535) {
                    streamServer.registerUdpClient(peerName, peer.ip, message.udpReceivePort)
                }
                true
            }
            "udp_unsubscribe" -> {
                streamServer.unregisterUdpClient(peerName)
                true
            }
            else -> false
        }
    }

    fun sendPtzAppliedToAll(
        requestId: Long,
        zoom: Float,
        centerX: Float,
        centerY: Float
    ) {
        if (requestId <= 0L) return
        scope.launch {
            val message = HandshakeMessage(
                type = "ptz_applied",
                deviceName = localDeviceName,
                value = zoom,
                stringValue = "$centerX,$centerY",
                requestId = requestId
            )
            val encoded = json.encodeToString(message)
            peerWriters.keys.forEach { peerName -> sendMessage(peerName, encoded) }
        }
    }

    fun stopServer() {
        serverJob?.cancel()
        serverJob = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverSessionKey = null
        isServerMode = false
    }

    fun disconnectPeer(name: String) {
        if (isServerMode) streamServer.unregisterUdpClient(name)
        heartbeatJobs.remove(name)?.cancel()
        peerWriters.remove(name)
        peerWriteLocks.remove(name)
        peerSockets.remove(name)?.let { try { it.close() } catch (_: Exception) {} }
        removePeer(name)
        removeClockSyncState(name)
        if (_connectedPeers.value.isEmpty()) {
            _connectionState.value = PeerConnectionState.DISCONNECTED
        }
    }

    fun disconnectAll() {
        heartbeatJobs.values.forEach { it.cancel() }
        heartbeatJobs.clear()
        peerSockets.values.forEach {
            try { it.close() } catch (_: Exception) {}
        }
        peerSockets.clear()
        peerWriters.clear()
        peerWriteLocks.clear()
        clockOffsetTrackers.clear()
        _clockSyncStates.value = emptyMap()
        _connectedPeers.value = emptyList()
        _connectionState.value = PeerConnectionState.DISCONNECTED
    }

    fun cleanup() {
        disconnectAll()
        stopServer()
        scope.cancel()
    }
}
