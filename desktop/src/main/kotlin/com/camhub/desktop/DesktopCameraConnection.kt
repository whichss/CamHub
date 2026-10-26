package com.camhub.desktop

import com.camhub.studio.data.network.CamHubVideoPayload
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import java.util.prefs.Preferences
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface DesktopCameraConnectionListener {
    fun onState(connection: DesktopCameraConnection, snapshot: CameraConnectionSnapshot)
    fun onImage(connection: DesktopCameraConnection, image: BufferedImage)
}

/** One camera owns one signaling, UDP receive, and H.264 decode thread group. */
class DesktopCameraConnection(
    val endpoint: CameraEndpoint,
    private val directorName: String,
    private val listener: DesktopCameraConnectionListener
) : AutoCloseable {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val HEARTBEAT_INTERVAL_MS = 3_000L
        private const val HEARTBEAT_TIMEOUT_MS = 20_000L
        private const val MAX_MESSAGE_CHARS = 4_096
        private const val FRAME_UI_UPDATE_INTERVAL_MS = 250L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val running = AtomicBoolean(false)
    private val sslContext = createTrustingSslContext()
    private val trustedIdentities = Preferences.userNodeForPackage(DesktopCameraConnection::class.java)
    private val snapshotLock = Any()
    private val lastFrameUiUpdateAtMs = AtomicLong(0L)
    private val cameraClockOffsetMs = AtomicLong(0L)
    private val bestClockRoundTripMs = AtomicLong(Long.MAX_VALUE)
    private val clockSynchronized = AtomicBoolean(false)

    @Volatile private var worker: Thread? = null
    @Volatile private var controlSocket: SSLSocket? = null
    @Volatile private var udpReceiver: DesktopUdpVideoReceiver? = null
    @Volatile private var decoder: H264FrameDecoder? = null
    @Volatile private var snapshot = CameraConnectionSnapshot(
        name = endpoint.discoveredName,
        endpoint = endpoint,
        status = DesktopCameraStatus.DISCOVERED
    )

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread(::connectionLoop, "DesktopCameraControl-${endpoint.host}").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        runCatching { send(ControlMessage(type = "command", command = "udp_unsubscribe")) }
        udpReceiver?.close()
        udpReceiver = null
        decoder?.close()
        decoder = null
        controlSocket?.close()
        controlSocket = null
        worker?.interrupt()
        worker = null
        updateSnapshot(status = DesktopCameraStatus.DISCONNECTED, detail = "Stopped")
    }

    private fun connectionLoop() {
        var retryDelayMs = 500L
        while (running.get()) {
            try {
                runSession()
                retryDelayMs = 500L
            } catch (error: Throwable) {
                if (!running.get()) break
                updateSnapshot(
                    status = DesktopCameraStatus.RECONNECTING,
                    detail = error.message?.take(100) ?: "Connection lost"
                )
                closeSession()
                try {
                    Thread.sleep(retryDelayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(10_000L)
            }
        }
        closeSession()
    }

    private fun runSession() {
        updateSnapshot(status = DesktopCameraStatus.CONNECTING, detail = "TLS ${endpoint.host}:${endpoint.signalingPort}")
        val socket = connectTls()
        controlSocket = socket
        verifyCameraIdentity(socket)
        val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
        val writer = PrintWriter(socket.outputStream.writer(Charsets.UTF_8), true)

        val pairingPin = obtainPairingPin()
        writer.println(
            json.encodeToString(
                ControlMessage(type = "hello", deviceName = directorName, pin = pairingPin)
            )
        )
        val ackLine = readLineLimited(reader) ?: throw IOException("Camera closed during handshake")
        val ack = json.decodeFromString<ControlMessage>(ackLine)
        if (ack.type == "error") {
            trustedIdentities.remove(pairingPinKey())
            throw IOException("Camera rejected the pairing PIN")
        }
        if (ack.type != "hello_ack") throw IOException("Unexpected camera response: ${ack.type}")
        trustedIdentities.put(pairingPinKey(), pairingPin)
        trustedIdentities.flush()
        val key = runCatching { Base64.getDecoder().decode(ack.sessionKey) }
            .getOrElse { throw IOException("Invalid camera session key", it) }
        if (key.size != 32) throw IOException("Invalid camera session key size")
        if (ack.udpStreamPort !in 1..65_535) throw IOException("Camera did not offer UDP video")

        val cameraName = ack.deviceName.ifBlank { endpoint.discoveredName }
        updateSnapshot(
            name = cameraName,
            status = DesktopCameraStatus.CONNECTED,
            detail = "Control connected · UDP ${ack.udpStreamPort}"
        )

        val activeDecoder = H264FrameDecoder(
            cameraName = cameraName,
            onImage = { image, metadata ->
                val captureAt = metadata.captureAtWallMs
                val latency = captureAt
                    ?.takeIf { clockSynchronized.get() }
                    ?.let { cameraCaptureAt ->
                        val captureOnDesktopClock = cameraCaptureAt - cameraClockOffsetMs.get()
                        (System.currentTimeMillis() - captureOnDesktopClock)
                            .takeIf { value -> value in 0..60_000 }
                    }
                val now = System.currentTimeMillis()
                val publishState = now - lastFrameUiUpdateAtMs.get() >= FRAME_UI_UPDATE_INTERVAL_MS
                if (publishState) lastFrameUiUpdateAtMs.set(now)
                updateSnapshot(
                    status = DesktopCameraStatus.STREAMING,
                    detail = "UDP/RTP · H.264",
                    latencyMs = latency,
                    width = metadata.width,
                    height = metadata.height,
                    rotationDegrees = metadata.rotationDegrees,
                    publish = publishState
                )
                listener.onImage(this, image)
            },
            onError = { error ->
                updateSnapshot(detail = "Decoder: ${error.message?.take(80) ?: "error"}")
            }
        )
        decoder = activeDecoder
        activeDecoder.start()

        val activeReceiver = DesktopUdpVideoReceiver(
            cameraName = cameraName,
            expectedHost = endpoint.host,
            expectedSourcePort = ack.udpStreamPort,
            sessionKey = key,
            onFrame = { frame: CamHubVideoPayload ->
                activeDecoder.submit(frame)
                synchronized(snapshotLock) {
                    val nextCount = snapshot.framesReceived + 1
                    snapshot = snapshot.copy(
                        framesReceived = nextCount,
                        width = frame.width,
                        height = frame.height,
                        rotationDegrees = frame.rotationDegrees
                    )
                    if (nextCount == 1L) listener.onState(this, snapshot)
                }
            },
            onStats = { stats ->
                updateSnapshot(
                    framesReceived = stats.completedFrames,
                    framesDropped = stats.droppedFrames,
                    packetLossPercent = stats.packetLossPercent
                )
            },
            onError = { error ->
                if (running.get()) updateSnapshot(detail = "UDP: ${error.message?.take(80) ?: "error"}")
            }
        )
        udpReceiver = activeReceiver
        val receivePort = activeReceiver.start()
        writer.println(
            json.encodeToString(
                ControlMessage(
                    type = "command",
                    deviceName = directorName,
                    command = "udp_subscribe",
                    udpReceivePort = receivePort
                )
            )
        )

        socket.soTimeout = 750
        var lastMessageAt = System.currentTimeMillis()
        var lastPingAt = 0L
        var syncSequence = 0L
        while (running.get()) {
            val now = System.currentTimeMillis()
            if (now - lastPingAt >= HEARTBEAT_INTERVAL_MS) {
                lastPingAt = now
                syncSequence++
                writer.println(
                    json.encodeToString(
                        ControlMessage(
                            type = "ping",
                            syncId = syncSequence,
                            t0WallMs = now
                        )
                    )
                )
                if (writer.checkError()) throw IOException("Control write failed")
            }

            val line = try {
                readLineLimited(reader)
            } catch (_: SocketTimeoutException) {
                null
            }
            if (line != null) {
                lastMessageAt = System.currentTimeMillis()
                val message = json.decodeFromString<ControlMessage>(line)
                if (message.type == "ping") {
                    val receivedAt = System.currentTimeMillis()
                    writer.println(
                        json.encodeToString(
                            ControlMessage(
                                type = "pong",
                                syncId = message.syncId,
                                t0WallMs = message.t0WallMs,
                                t1WallMs = receivedAt,
                                t2WallMs = System.currentTimeMillis()
                            )
                        )
                    )
                } else if (
                    message.type == "pong" &&
                    message.t0WallMs > 0L &&
                    message.t1WallMs > 0L &&
                    message.t2WallMs > 0L
                ) {
                    updateClockOffset(message, System.currentTimeMillis())
                }
            }
            if (System.currentTimeMillis() - lastMessageAt > HEARTBEAT_TIMEOUT_MS) {
                throw IOException("Camera heartbeat timed out")
            }
        }
    }

    private fun connectTls(): SSLSocket {
        val transport = Socket().apply {
            tcpNoDelay = true
            keepAlive = true
            connect(InetSocketAddress(endpoint.host, endpoint.signalingPort), CONNECT_TIMEOUT_MS)
        }
        return try {
            (sslContext.socketFactory.createSocket(
                transport,
                endpoint.host,
                endpoint.signalingPort,
                true
            ) as SSLSocket).apply {
                soTimeout = HANDSHAKE_TIMEOUT_MS
                startHandshake()
            }
        } catch (error: Throwable) {
            runCatching { transport.close() }
            throw error
        }
    }

    private fun verifyCameraIdentity(socket: SSLSocket) {
        val certificate = socket.session.peerCertificates.firstOrNull() as? X509Certificate
            ?: throw IOException("Camera did not provide an X.509 certificate")
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02X".format(it) }
        val key = "camera_${endpoint.host}_${endpoint.signalingPort}"
        if (trustedIdentities.get(key, null) == fingerprint) return

        val message = buildString {
            appendLine("Compare this SHA-256 prefix with VERIFY shown on the camera device.")
            appendLine()
            appendLine(fingerprint.take(16).chunked(4).joinToString(" "))
            appendLine()
            append("Trust only when both codes match. A changed code can indicate a replaced device or an intercepted connection.")
        }
        val approved = booleanArrayOf(false)
        val prompt = Runnable {
            approved[0] = JOptionPane.showConfirmDialog(
                null,
                message,
                "Verify CamHub camera identity",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            ) == JOptionPane.YES_OPTION
        }
        if (SwingUtilities.isEventDispatchThread()) prompt.run()
        else SwingUtilities.invokeAndWait(prompt)
        if (!approved[0]) throw IOException("Camera identity was not confirmed")
        trustedIdentities.put(key, fingerprint)
        trustedIdentities.flush()
    }

    private fun obtainPairingPin(): String {
        trustedIdentities.get(pairingPinKey(), null)
            ?.takeIf { it.matches(Regex("[0-9]{8}")) }
            ?.let { return it }
        val entered = arrayOfNulls<String>(1)
        val prompt = Runnable {
            entered[0] = JOptionPane.showInputDialog(
                null,
                "Enter the 8-digit PAIR PIN shown on ${endpoint.discoveredName}.",
                "Pair CamHub camera",
                JOptionPane.QUESTION_MESSAGE
            )
        }
        if (SwingUtilities.isEventDispatchThread()) prompt.run()
        else SwingUtilities.invokeAndWait(prompt)
        return entered[0]?.trim()?.takeIf { it.matches(Regex("[0-9]{8}")) }
            ?: throw IOException("A valid 8-digit pairing PIN is required")
    }

    private fun pairingPinKey(): String = "pairing_pin_${endpoint.host}_${endpoint.signalingPort}"

    private fun send(message: ControlMessage) {
        val socket = controlSocket ?: return
        PrintWriter(socket.outputStream.writer(Charsets.UTF_8), true)
            .println(json.encodeToString(message))
    }

    private fun closeSession() {
        udpReceiver?.close()
        udpReceiver = null
        decoder?.close()
        decoder = null
        runCatching { controlSocket?.close() }
        controlSocket = null
    }

    private fun updateSnapshot(
        name: String? = null,
        status: DesktopCameraStatus? = null,
        detail: String? = null,
        framesReceived: Long? = null,
        framesDropped: Long? = null,
        packetLossPercent: Float? = null,
        latencyMs: Long? = snapshot.latencyMs,
        width: Int? = null,
        height: Int? = null,
        rotationDegrees: Int? = null,
        publish: Boolean = true
    ) {
        val updated = synchronized(snapshotLock) {
            snapshot.copy(
                name = name ?: snapshot.name,
                status = status ?: snapshot.status,
                detail = detail ?: snapshot.detail,
                framesReceived = framesReceived ?: snapshot.framesReceived,
                framesDropped = framesDropped ?: snapshot.framesDropped,
                packetLossPercent = packetLossPercent ?: snapshot.packetLossPercent,
                latencyMs = latencyMs,
                width = width ?: snapshot.width,
                height = height ?: snapshot.height,
                rotationDegrees = rotationDegrees ?: snapshot.rotationDegrees
            ).also { snapshot = it }
        }
        if (publish) listener.onState(this, updated)
    }

    private fun readLineLimited(reader: BufferedReader): String? {
        val text = StringBuilder()
        while (true) {
            val next = reader.read()
            if (next < 0) return if (text.isEmpty()) null else text.toString()
            if (next == '\n'.code) return text.toString()
            if (next == '\r'.code) continue
            if (text.length >= MAX_MESSAGE_CHARS) throw IOException("Control message too large")
            text.append(next.toChar())
        }
    }

    private fun updateClockOffset(message: ControlMessage, receivedAtWallMs: Long) {
        val roundTripMs = (receivedAtWallMs - message.t0WallMs) -
            (message.t2WallMs - message.t1WallMs)
        if (roundTripMs !in 0..2_000L) return
        val offsetMs = ((message.t1WallMs - message.t0WallMs) +
            (message.t2WallMs - receivedAtWallMs)) / 2L
        while (true) {
            val previousBest = bestClockRoundTripMs.get()
            if (roundTripMs >= previousBest && clockSynchronized.get()) return
            if (bestClockRoundTripMs.compareAndSet(previousBest, roundTripMs)) {
                cameraClockOffsetMs.set(offsetMs)
                clockSynchronized.set(true)
                return
            }
        }
    }

    private fun createTrustingSslContext(): SSLContext {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
    }
}
