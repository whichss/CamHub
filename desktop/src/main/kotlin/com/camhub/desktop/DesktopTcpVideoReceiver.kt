package com.camhub.desktop

import com.camhub.studio.data.network.CamHubVideoPayloadCodec
import com.camhub.studio.data.network.FrameCipher
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Receives length-prefixed, AES-GCM protected CamHub frames on the negotiated TCP fallback port. */
class DesktopTcpVideoReceiver(
    cameraName: String,
    private val host: String,
    private val port: Int,
    sessionKey: ByteArray,
    private val authToken: ByteArray? = null,
    private val onFrame: (com.camhub.studio.data.network.CamHubVideoPayload) -> Unit,
    private val onError: (Throwable) -> Unit
) : AutoCloseable {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 2_500
        private const val MAX_ENCRYPTED_FRAME_BYTES = 2 * 1024 * 1024
    }

    private val cipher = FrameCipher(sessionKey)
    private val running = AtomicBoolean(false)
    private val threadName = "DesktopTcpReceive-" + cameraName
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .take(30)

    @Volatile private var socket: Socket? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var closed = false

    @Synchronized
    fun start() {
        check(!closed) { "A closed TCP receiver cannot be restarted" }
        if (!running.compareAndSet(false, true)) return
        val activeSocket = Socket()
        try {
            activeSocket.tcpNoDelay = true
            activeSocket.keepAlive = true
            activeSocket.receiveBufferSize = 64 * 1024
            activeSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            authToken?.let { token ->
                require(token.size == 32) { "TCP video authorization token must be 32 bytes" }
                activeSocket.getOutputStream().apply {
                    write(token)
                    flush()
                }
            }
            socket = activeSocket
            thread = Thread({ receiveLoop(activeSocket) }, threadName).apply {
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            running.set(false)
            runCatching { activeSocket.close() }
            throw error
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        thread?.interrupt()
        thread = null
    }

    private fun receiveLoop(activeSocket: Socket) {
        try {
            val input = DataInputStream(activeSocket.getInputStream())
            while (running.get()) {
                val frameSize = input.readInt()
                if (frameSize !in 1..MAX_ENCRYPTED_FRAME_BYTES) {
                    throw IOException("Invalid TCP video frame size: $frameSize")
                }
                val encrypted = ByteArray(frameSize)
                input.readFully(encrypted)
                val payload = runCatching { cipher.decrypt(encrypted) }.getOrNull()
                    ?: continue
                val decoded = CamHubVideoPayloadCodec.decode(payload) ?: continue
                onFrame(decoded)
            }
        } catch (error: Throwable) {
            if (running.get()) onError(error)
        } finally {
            running.set(false)
            runCatching { activeSocket.close() }
            if (socket === activeSocket) socket = null
        }
    }
}
