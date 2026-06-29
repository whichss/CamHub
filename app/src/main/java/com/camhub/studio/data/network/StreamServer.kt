package com.camhub.studio.data.network

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import com.camhub.studio.data.LowLatencyWifiLock
import com.camhub.studio.data.StreamingConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.thibaultbee.srtdroid.core.models.SrtSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamServer @Inject constructor(
    @ApplicationContext context: Context,
    private val streamingConfig: StreamingConfig
) {

    companion object {
        private const val TAG = "StreamServer"
        private const val MAX_CLIENTS = 4
        private const val META_HEADER_V2: Byte = 2
        private const val META_HEADER_V2_SIZE = 7
        private const val META_HEADER_V3: Byte = 3
        private const val META_HEADER_V3_SIZE = 15
        private const val FLAG_KEYFRAME: Byte = 0x01
        private const val FLAG_CODEC_CONFIG: Byte = 0x02
        private const val MAX_BLOCKED_SEND_MS = 2_000L
    }

    private var lastFrameTimeMs: Long = 0
    var maxFps: Int = 30

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var srtServerSocket: SrtSocket? = null
    private var serverJob: Job? = null
    private val clients = CopyOnWriteArrayList<ClientConnection>()
    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount.asStateFlow()

    private var frameCipher: FrameCipher? = null
    private var srtPassphrase: String? = null
    private var usingSrt = false
    private val wifiLock = LowLatencyWifiLock(context, "CamHubCameraStream")

    private var encoder: H264Encoder? = null
    private var useH264 = false
    private var encoderWidth = 0
    private var encoderHeight = 0
    @Volatile private var latestSurfaceConfig: ByteArray? = null
    @Volatile private var latestSurfaceConfigWidth = 0
    @Volatile private var latestSurfaceConfigHeight = 0
    @Volatile private var latestSurfaceConfigRotation = 0
    var keyFrameRequester: (() -> Unit)? = null

    // --- Polymorphic client connection (SRT / TCP) ---

    private sealed class ClientConnection {
        abstract var needsConfig: Boolean
        @Volatile var isSending: Boolean = false
        @Volatile var pendingKeyframe: ByteArray? = null
        @Volatile var sendStartedAtMs: Long = 0L
        abstract fun sendFrame(data: ByteArray)
        abstract fun close()

        class TcpConnection(
            val socket: Socket,
            val output: DataOutputStream,
            override var needsConfig: Boolean = true
        ) : ClientConnection() {
            init {
                socket.sendBufferSize = 64 * 1024
            }
            override fun sendFrame(data: ByteArray) {
                output.writeInt(data.size)
                output.write(data)
                output.flush()
            }
            override fun close() { socket.close() }
        }

        class SrtConnection(
            val srtSocket: SrtSocket,
            val output: DataOutputStream,
            override var needsConfig: Boolean = true
        ) : ClientConnection() {
            override fun sendFrame(data: ByteArray) {
                output.writeInt(data.size)
                output.write(data)
                output.flush()
            }
            override fun close() { srtSocket.close() }
        }
    }

    fun setSessionKey(key: ByteArray) {
        srtPassphrase = SrtTransport.sessionKeyToPassphrase(key)
        frameCipher = FrameCipher(key)
    }

    fun start(): Int {
        wifiLock.acquire()
        ensureBroadcastPool()
        if (SrtTransport.isAvailable()) {
            try {
                return startSrt()
            } catch (e: Exception) {
                Log.w(TAG, "SRT server failed, falling back to TCP", e)
                usingSrt = false
                srtServerSocket = null
            }
        }
        return startTcp()
    }

    private fun startSrt(): Int {
        val srtSocket = SrtSocket()
        SrtTransport.configureSocket(srtSocket, passphrase = srtPassphrase)
        srtSocket.bind(InetSocketAddress("0.0.0.0", 0))
        srtSocket.listen(MAX_CLIENTS)
        srtServerSocket = srtSocket
        usingSrt = true
        val port = srtSocket.localPort

        serverJob = scope.launch {
            Log.d(TAG, "SRT stream server started on port $port")
            while (isActive) {
                try {
                    val result = srtServerSocket?.accept() ?: break
                    val clientSrtSocket = result.first
                    val clientAddr = result.second

                    if (clients.size >= MAX_CLIENTS) {
                        Log.w(TAG, "Max clients ($MAX_CLIENTS) reached, rejecting SRT connection")
                        try { clientSrtSocket.close() } catch (_: Exception) {}
                        continue
                    }

                    val output = DataOutputStream(clientSrtSocket.getOutputStream())
                    val client = ClientConnection.SrtConnection(clientSrtSocket, output, needsConfig = true)
                    clients.add(client)
                    updateClientCount()

                    if (useH264) {
                        sendConfigToClient(client)
                        encoder?.requestKeyFrame()
                    } else {
                        sendLatestSurfaceConfigToClient(client)
                        keyFrameRequester?.invoke()
                    }

                    Log.d(TAG, "SRT client connected: ${clientAddr?.hostString}")
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "SRT accept error", e)
                    break
                }
            }
        }

        return port
    }

    private fun startTcp(): Int {
        serverSocket = ServerSocket(0)
        val port = serverSocket!!.localPort
        usingSrt = false

        serverJob = scope.launch {
            Log.d(TAG, "TCP stream server started on port $port")
            while (isActive) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    if (clients.size >= MAX_CLIENTS) {
                        Log.w(TAG, "Max clients ($MAX_CLIENTS) reached, rejecting connection")
                        try { clientSocket.close() } catch (_: Exception) {}
                        continue
                    }
                    clientSocket.keepAlive = true
                    clientSocket.tcpNoDelay = true
                    val output = DataOutputStream(clientSocket.getOutputStream())
                    val client = ClientConnection.TcpConnection(clientSocket, output, needsConfig = true)
                    clients.add(client)
                    updateClientCount()

                    if (useH264) {
                        sendConfigToClient(client)
                        encoder?.requestKeyFrame()
                    } else {
                        sendLatestSurfaceConfigToClient(client)
                        keyFrameRequester?.invoke()
                    }

                    Log.d(TAG, "TCP client connected: ${clientSocket.inetAddress.hostAddress}")
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "TCP accept error", e)
                    break
                }
            }
        }

        return port
    }

    fun onFrame(imageProxy: ImageProxy) {
        if (clients.isEmpty()) {
            imageProxy.close()
            return
        }

        // Frame rate limiting
        val now = System.currentTimeMillis()
        if (now - lastFrameTimeMs < 1000 / maxFps) {
            imageProxy.close()
            return
        }
        lastFrameTimeMs = now

        try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val width = imageProxy.width
            val height = imageProxy.height
            tryH264Frame(imageProxy, width, height, rotation)
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun tryH264Frame(imageProxy: ImageProxy, width: Int, height: Int, rotation: Int): Boolean {
        // Initialize encoder if needed
        if (encoder == null || encoderWidth != width || encoderHeight != height) {
            encoder?.release()
            encoder = null
            useH264 = false

            val newEncoder = H264Encoder(
                width = width,
                height = height,
                bitrate = streamingConfig.bitrateBytes,
                frameRate = maxFps
            )
            if (newEncoder.start()) {
                encoder = newEncoder
                useH264 = true
                encoderWidth = width
                encoderHeight = height
                Log.d(TAG, "H.264 encoder initialized: ${width}x${height}")
            } else {
                Log.w(TAG, "H.264 encoder failed to start")
                return false
            }
        }

        val enc = encoder ?: return false

        // Convert and encode
        val nv12 = imageProxyToNv12(imageProxy) ?: return false
        val pts = System.nanoTime() / 1000
        val frames = enc.encode(nv12, pts)

        if (frames.isEmpty()) return false

        // Broadcast encoded frames
        var sentData = false
        for (frame in frames) {
            if (frame.isConfig) {
                sendConfigToNewClients(frame.data, width, height, rotation)
            } else {
                val payload = buildH264Payload(frame.data, width, height, rotation, frame.isKeyFrame, false)
                broadcastFrame(payload, frame.isKeyFrame)
                sentData = true
            }
        }
        return sentData
    }

    private fun sendConfigToClient(client: ClientConnection) {
        val spsPps = encoder?.cachedSpsPps ?: return
        try {
            val payload = buildH264Payload(spsPps, encoderWidth, encoderHeight, 0, false, true)
            val dataToSend = if (usingSrt) payload
                             else frameCipher?.encrypt(payload) ?: payload
            client.sendFrame(dataToSend)
            client.needsConfig = false
            Log.d(TAG, "Sent SPS/PPS config to new client")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send config to client: ${e.message}")
        }
    }

    private fun sendLatestSurfaceConfigToClient(client: ClientConnection): Boolean {
        val config = latestSurfaceConfig ?: return false
        if (latestSurfaceConfigWidth <= 0 || latestSurfaceConfigHeight <= 0) return false

        return try {
            val payload = buildH264Payload(
                config,
                latestSurfaceConfigWidth,
                latestSurfaceConfigHeight,
                latestSurfaceConfigRotation,
                false,
                true
            )
            val dataToSend = if (usingSrt) payload
                             else frameCipher?.encrypt(payload) ?: payload
            client.sendFrame(dataToSend)
            client.needsConfig = false
            Log.d(TAG, "Sent cached surface SPS/PPS config to new client")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send cached surface config to client: ${e.message}")
            false
        }
    }

    private fun sendConfigToNewClients(configData: ByteArray, width: Int, height: Int, rotation: Int) {
        val payload = buildH264Payload(configData, width, height, rotation, false, true)
        val dataToSend = if (usingSrt) payload
                         else frameCipher?.encrypt(payload) ?: payload

        val dimensionsChanged = width != encoderWidth || height != encoderHeight
        val disconnected = mutableListOf<ClientConnection>()
        for (client in clients) {
            // Send config to new clients OR all clients when dimensions changed (rotation)
            if (client.needsConfig || dimensionsChanged) {
                try {
                    client.sendFrame(dataToSend)
                    client.needsConfig = false
                } catch (e: Exception) {
                    disconnected.add(client)
                }
            }
        }
        if (dimensionsChanged) {
            encoderWidth = width
            encoderHeight = height
            Log.d(TAG, "Encoder dimensions changed: ${width}x${height}, config sent to all clients")
        }
        removeDisconnected(disconnected)
    }

    private fun buildH264Payload(data: ByteArray, width: Int, height: Int, rotationDegrees: Int, isKeyFrame: Boolean, isConfig: Boolean): ByteArray {
        val rotationCode: Byte = when (rotationDegrees) {
            90 -> 1; 180 -> 2; 270 -> 3; else -> 0
        }
        var flags: Int = 0
        if (isKeyFrame) flags = flags or FLAG_KEYFRAME.toInt()
        if (isConfig) flags = flags or FLAG_CODEC_CONFIG.toInt()

        val header = ByteBuffer.allocate(META_HEADER_V3_SIZE)
        header.put(META_HEADER_V3)
        header.putShort(width.toShort())
        header.putShort(height.toShort())
        header.put(rotationCode)
        header.put(flags.toByte())
        header.putLong(System.currentTimeMillis())
        return header.array() + data
    }

    // Reusable NV12 buffer to avoid per-frame allocation
    private var nv12Buffer: ByteArray? = null

    private fun imageProxyToNv12(imageProxy: ImageProxy): ByteArray? {
        val width = imageProxy.width
        val height = imageProxy.height
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val nv12Size = width * height * 3 / 2
        val nv12 = nv12Buffer?.takeIf { it.size >= nv12Size } ?: ByteArray(nv12Size).also { nv12Buffer = it }

        // Copy Y plane row by row
        var pos = 0
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv12, pos, width)
            pos += width
        }

        // Copy UV planes interleaved as UV (NV12: UVUV...)
        // Must read per-pixel — bulk read from uBuffer gives shifted pairs on NV21 devices
        val uvHeight = height / 2
        for (row in 0 until uvHeight) {
            for (col in 0 until width / 2) {
                val uvIdx = row * uvRowStride + col * uvPixelStride
                nv12[pos++] = uBuffer.get(uvIdx)
                nv12[pos++] = vBuffer.get(uvIdx)
            }
        }

        return nv12
    }

    private var broadcastPool: ExecutorService = Executors.newFixedThreadPool(MAX_CLIENTS)

    private val clientsToRemove = CopyOnWriteArrayList<ClientConnection>()

    private fun ensureBroadcastPool() {
        if (broadcastPool.isShutdown || broadcastPool.isTerminated) {
            broadcastPool = Executors.newFixedThreadPool(MAX_CLIENTS)
        }
    }

    private fun broadcastFrame(payload: ByteArray, isKeyFrame: Boolean = false) {
        val dataToSend = if (usingSrt) payload  // SRT handles encryption via passphrase
                         else frameCipher?.encrypt(payload) ?: payload

        val now = System.currentTimeMillis()
        for (client in clients) {
            if (client.isSending) {
                if (now - client.sendStartedAtMs > MAX_BLOCKED_SEND_MS) {
                    clientsToRemove.add(client)
                    continue
                }
                // Buffer keyframes so slow clients can resync (never drop keyframes)
                if (isKeyFrame) {
                    client.pendingKeyframe = dataToSend
                }
                continue
            }
            client.isSending = true
            client.sendStartedAtMs = now
            try {
                ensureBroadcastPool()
                broadcastPool.execute {
                    try {
                        client.sendFrame(dataToSend)
                        // After sending, flush any buffered keyframe for this client
                        val pending = client.pendingKeyframe
                        if (pending != null) {
                            client.pendingKeyframe = null
                            client.sendFrame(pending)
                        }
                    } catch (e: Exception) {
                        clientsToRemove.add(client)
                    } finally {
                        client.sendStartedAtMs = 0L
                        client.isSending = false
                    }
                }
            } catch (e: RejectedExecutionException) {
                client.sendStartedAtMs = 0L
                client.isSending = false
                clientsToRemove.add(client)
            }
        }

        // Clean up disconnected clients after iteration
        if (clientsToRemove.isNotEmpty()) {
            for (client in clientsToRemove) {
                clients.remove(client)
                try { client.close() } catch (_: Exception) {}
                Log.d(TAG, "Stream client disconnected")
            }
            clientsToRemove.clear()
            updateClientCount()
        }
    }

    private fun removeDisconnected(disconnected: List<ClientConnection>) {
        for (client in disconnected) {
            clients.remove(client)
            try { client.close() } catch (_: Exception) {}
            Log.d(TAG, "Stream client disconnected")
        }
        if (disconnected.isNotEmpty()) {
            updateClientCount()
        }
    }

    private fun updateClientCount() {
        _clientCount.value = clients.size
    }

    fun broadcastEncodedFrame(frame: EncodedFrame, width: Int, height: Int, rotation: Int) {
        if (frame.isConfig) {
            latestSurfaceConfig = frame.data
            latestSurfaceConfigWidth = width
            latestSurfaceConfigHeight = height
            latestSurfaceConfigRotation = rotation
            if (clients.isEmpty()) return

            // Always send config to ALL clients — dimensions may have changed after rotation
            val payload = buildH264Payload(frame.data, width, height, rotation, false, true)
            val dataToSend = if (usingSrt) payload
                             else frameCipher?.encrypt(payload) ?: payload
            val disconnected = mutableListOf<ClientConnection>()
            for (client in clients) {
                try {
                    client.sendFrame(dataToSend)
                    client.needsConfig = false
                } catch (e: Exception) {
                    disconnected.add(client)
                }
            }
            removeDisconnected(disconnected)
            if (width != encoderWidth || height != encoderHeight) {
                encoderWidth = width
                encoderHeight = height
                Log.d(TAG, "Surface encoder dimensions updated: ${width}x${height}")
            }
        } else {
            if (clients.isEmpty()) return

            val payload = buildH264Payload(frame.data, width, height, rotation, frame.isKeyFrame, false)
            broadcastFrame(payload, frame.isKeyFrame)
        }
    }

    fun getEncoder(): H264Encoder? = encoder

    fun updateBitrate(bitrate: Int): Boolean {
        return encoder?.setBitrate(bitrate) ?: false
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        clients.forEach {
            try { it.close() } catch (_: Exception) {}
        }
        clients.clear()
        updateClientCount()
        try { srtServerSocket?.close() } catch (_: Exception) {}
        srtServerSocket = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        usingSrt = false
        frameCipher = null
        srtPassphrase = null
        keyFrameRequester = null
        latestSurfaceConfig = null
        latestSurfaceConfigWidth = 0
        latestSurfaceConfigHeight = 0
        latestSurfaceConfigRotation = 0
        wifiLock.release()
        encoder?.release()
        encoder = null
        useH264 = false
        encoderWidth = 0
        encoderHeight = 0
        nv12Buffer = null
        broadcastPool.shutdownNow()
    }

    fun cleanup() {
        stop()
        scope.cancel()
    }

    fun getPort(): Int {
        if (usingSrt) return srtServerSocket?.localPort ?: 0
        return serverSocket?.localPort ?: 0
    }
}
