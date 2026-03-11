package com.camhub.studio.data.network

import android.util.Log
import androidx.camera.core.ImageProxy
import io.github.thibaultbee.srtdroid.core.models.SrtSocket
import kotlinx.coroutines.*
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamServer @Inject constructor() {

    companion object {
        private const val TAG = "StreamServer"
        private const val MAX_CLIENTS = 4
        private const val META_HEADER_V2: Byte = 2
        private const val META_HEADER_V2_SIZE = 7
        private const val FLAG_KEYFRAME: Byte = 0x01
        private const val FLAG_CODEC_CONFIG: Byte = 0x02
    }

    private var lastFrameTimeMs: Long = 0
    private val maxFps: Int = 30

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var srtServerSocket: SrtSocket? = null
    private var serverJob: Job? = null
    private val clients = CopyOnWriteArrayList<ClientConnection>()

    private var frameCipher: FrameCipher? = null
    private var srtPassphrase: String? = null
    private var usingSrt = false

    private var encoder: H264Encoder? = null
    private var useH264 = false
    private var encoderWidth = 0
    private var encoderHeight = 0

    // --- Polymorphic client connection (SRT / TCP) ---

    private sealed class ClientConnection {
        abstract var needsConfig: Boolean
        @Volatile var isSending: Boolean = false
        @Volatile var pendingKeyframe: ByteArray? = null
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

                    if (useH264) {
                        sendConfigToClient(client)
                        encoder?.requestKeyFrame()
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

                    if (useH264) {
                        sendConfigToClient(client)
                        encoder?.requestKeyFrame()
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

            val newEncoder = H264Encoder(width, height, frameRate = maxFps)
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

        val header = ByteBuffer.allocate(META_HEADER_V2_SIZE)
        header.put(META_HEADER_V2)
        header.putShort(width.toShort())
        header.putShort(height.toShort())
        header.put(rotationCode)
        header.put(flags.toByte())
        return header.array() + data
    }

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

        val nv12 = ByteArray(width * height * 3 / 2)

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

    private val broadcastPool = Executors.newCachedThreadPool()

    private fun broadcastFrame(payload: ByteArray, isKeyFrame: Boolean = false) {
        val dataToSend = if (usingSrt) payload  // SRT handles encryption via passphrase
                         else frameCipher?.encrypt(payload) ?: payload

        for (client in clients) {
            if (client.isSending) {
                // Buffer keyframes so slow clients can resync (never drop keyframes)
                if (isKeyFrame) {
                    client.pendingKeyframe = dataToSend
                }
                continue
            }
            client.isSending = true
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
                    clients.remove(client)
                    try { client.close() } catch (_: Exception) {}
                    Log.d(TAG, "Stream client disconnected")
                } finally {
                    client.isSending = false
                }
            }
        }
    }

    private fun removeDisconnected(disconnected: List<ClientConnection>) {
        for (client in disconnected) {
            clients.remove(client)
            try { client.close() } catch (_: Exception) {}
            Log.d(TAG, "Stream client disconnected")
        }
    }

    fun broadcastEncodedFrame(frame: EncodedFrame, width: Int, height: Int, rotation: Int) {
        if (clients.isEmpty()) return

        if (frame.isConfig) {
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
            val payload = buildH264Payload(frame.data, width, height, rotation, frame.isKeyFrame, false)
            broadcastFrame(payload, frame.isKeyFrame)
        }
    }

    fun getEncoder(): H264Encoder? = encoder

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        clients.forEach {
            try { it.close() } catch (_: Exception) {}
        }
        clients.clear()
        try { srtServerSocket?.close() } catch (_: Exception) {}
        srtServerSocket = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        usingSrt = false
        frameCipher = null
        srtPassphrase = null
        encoder?.release()
        encoder = null
        useH264 = false
        encoderWidth = 0
        encoderHeight = 0
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
