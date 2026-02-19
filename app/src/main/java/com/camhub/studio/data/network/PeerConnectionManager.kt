package com.camhub.studio.data.network

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val audioStreamPort: Int = 0
)

data class ConnectedPeer(
    val name: String,
    val ip: String,
    val signalingPort: Int,
    val streamPort: Int,
    val audioStreamPort: Int = 0,
    val socket: Socket? = null,
    val streamKey: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConnectedPeer) return false
        return name == other.name && ip == other.ip && signalingPort == other.signalingPort && streamPort == other.streamPort && audioStreamPort == other.audioStreamPort
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + ip.hashCode()
        result = 31 * result + signalingPort
        result = 31 * result + streamPort
        result = 31 * result + audioStreamPort
        return result
    }
}

@Singleton
class PeerConnectionManager @Inject constructor(
    private val tlsHelper: TlsHelper
) {

    companion object {
        private const val TAG = "PeerConnection"
        private const val HEARTBEAT_INTERVAL_MS = 3000L
        private const val HEARTBEAT_TIMEOUT_MS = 15000L
        private const val MAX_LINE_LENGTH = 4096
        private const val AES_KEY_SIZE = 32 // AES-256
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

    private var serverSocket: SSLServerSocket? = null
    private var serverJob: Job? = null
    private val heartbeatJobs = ConcurrentHashMap<String, Job>()
    private val peerSockets = ConcurrentHashMap<String, Socket>()
    private val peerWriters = ConcurrentHashMap<String, PrintWriter>()

    // Session key shared across all directors (generated once per server session)
    private var serverSessionKey: ByteArray? = null

    var localDeviceName: String = ""
    var localStreamPort: Int = 0
    var localAudioStreamPort: Int = 0

    /** Callback invoked on camera side when a command is received from director */
    var onCommandReceived: ((HandshakeMessage) -> Unit)? = null

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

    fun startServer(port: Int, deviceName: String, streamPort: Int, audioStreamPort: Int = 0): Int {
        localDeviceName = deviceName
        localStreamPort = streamPort
        localAudioStreamPort = audioStreamPort
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
                audioStreamPort = localAudioStreamPort
            )
            writer.println(json.encodeToString(ack))

            // Handshake done — use generous timeout for heartbeat phase
            socket.soTimeout = (HEARTBEAT_TIMEOUT_MS + 5000).toInt()
            socket.keepAlive = true

            val peerName = hello.deviceName
            peerSockets[peerName] = socket
            peerWriters[peerName] = writer

            val peer = ConnectedPeer(
                name = peerName,
                ip = clientIp,
                signalingPort = socket.port,
                streamPort = 0,
                socket = socket,
                streamKey = sessionKey
            )
            addPeer(peer)
            _connectionState.value = PeerConnectionState.CONNECTED
            accepted = true

            // Start heartbeat loop for this connection
            startHeartbeat(peerName, reader, writer)
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
            _connectionState.value = PeerConnectionState.CONNECTING
            var socket: javax.net.ssl.SSLSocket? = null
            var accepted = false
            try {
                socket = tlsHelper.createClientSocket(peer.ip, peer.port)
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
                peerSockets[peerName] = socket
                peerWriters[peerName] = writer

                val connectedPeer = ConnectedPeer(
                    name = peerName,
                    ip = peer.ip,
                    signalingPort = peer.port,
                    streamPort = ack.streamPort,
                    audioStreamPort = ack.audioStreamPort,
                    socket = socket,
                    streamKey = sessionKey
                )
                addPeer(connectedPeer)
                _connectionState.value = PeerConnectionState.CONNECTED
                accepted = true

                // Start heartbeat
                startHeartbeat(peerName, reader, writer)
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

    private fun startHeartbeat(peerName: String, reader: BufferedReader, writer: PrintWriter) {
        heartbeatJobs[peerName]?.cancel()

        heartbeatJobs[peerName] = scope.launch {
            val lastPongTime = AtomicLong(System.currentTimeMillis())

            // Ping sender
            val pingSender = launch {
                while (isActive) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    try {
                        val ping = HandshakeMessage(type = "ping")
                        writer.println(json.encodeToString(ping))
                        if (writer.checkError()) {
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
                try {
                    while (isActive) {
                        val line = try {
                            readLineLimited(reader)
                        } catch (e: java.net.SocketTimeoutException) {
                            // soTimeout fired — not fatal, timeout checker handles disconnect
                            continue
                        } ?: break
                        val msg = try {
                            json.decodeFromString<HandshakeMessage>(line)
                        } catch (e: Exception) {
                            Log.w(TAG, "Invalid heartbeat JSON from $peerName")
                            break
                        }
                        when (msg.type) {
                            "ping" -> {
                                val pong = HandshakeMessage(type = "pong")
                                writer.println(json.encodeToString(pong))
                            }
                            "pong" -> {
                                lastPongTime.set(System.currentTimeMillis())
                            }
                            "command" -> {
                                onCommandReceived?.invoke(msg)
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
            removePeer(peerName)
            peerSockets.remove(peerName)?.close()
            peerWriters.remove(peerName)

            if (_connectedPeers.value.isEmpty()) {
                _connectionState.value = PeerConnectionState.DISCONNECTED
            }
        }
    }

    private fun addPeer(peer: ConnectedPeer) {
        val current = _connectedPeers.value.toMutableList()
        current.removeAll { it.name == peer.name }
        current.add(peer)
        _connectedPeers.value = current
    }

    private fun removePeer(name: String) {
        _connectedPeers.value = _connectedPeers.value.filter { it.name != name }
    }

    fun sendCommand(peerName: String, command: String, value: Float = 0f, stringValue: String = "") {
        scope.launch {
            val writer = peerWriters[peerName] ?: return@launch
            try {
                val msg = HandshakeMessage(
                    type = "command",
                    command = command,
                    value = value,
                    stringValue = stringValue
                )
                writer.println(json.encodeToString(msg))
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

    fun stopServer() {
        serverJob?.cancel()
        serverJob = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverSessionKey = null
    }

    fun disconnectPeer(name: String) {
        heartbeatJobs.remove(name)?.cancel()
        peerWriters.remove(name)
        peerSockets.remove(name)?.let { try { it.close() } catch (_: Exception) {} }
        removePeer(name)
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
        _connectedPeers.value = emptyList()
        _connectionState.value = PeerConnectionState.DISCONNECTED
    }

    fun cleanup() {
        disconnectAll()
        stopServer()
        scope.cancel()
    }
}
