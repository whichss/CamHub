package com.camhub.desktop

import com.camhub.studio.data.network.CamHubVideoPayload
import com.camhub.studio.data.network.decodeTcpStreamAuthToken
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
import java.util.concurrent.atomic.AtomicReference
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
    private val listener: DesktopCameraConnectionListener,
    private val trustedIdentities: Preferences = Preferences.userNodeForPackage(DesktopCameraConnection::class.java)
) : AutoCloseable {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val HEARTBEAT_INTERVAL_MS = 3_000L
        private const val HEARTBEAT_TIMEOUT_MS = 20_000L
        private const val KEY_FRAME_REQUEST_COOLDOWN_MS = 250L
        private const val TCP_FALLBACK_AFTER_NO_VIDEO_MS = 1_750L
        private const val MAX_MESSAGE_CHARS = 4_096
        private const val FRAME_UI_UPDATE_INTERVAL_MS = 250L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val running = AtomicBoolean(false)
    private val sslContext = createTrustingSslContext()
    private val snapshotLock = Any()
    private val resourceLock = Any()
    private val controlWriteLock = Any()
    private val frameSessionLock = Any()
    private var frameSessionGeneration = 0L
    private val lastFrameUiUpdateAtMs = AtomicLong(0L)
    private val captureToDecodeLatency = DesktopCaptureLatencyWindow()
    private val cameraClockOffsetMs = AtomicLong(0L)
    private val bestClockRoundTripMs = AtomicLong(Long.MAX_VALUE)
    private val clockSynchronized = AtomicBoolean(false)
    private val ptzRequestSequence = AtomicLong((SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(1L))
    private val firstPtzRequestId = ptzRequestSequence.get()
    private val lastSentPtzRequestId = AtomicLong(0L)

    @Volatile private var worker: Thread? = null
    @Volatile private var controlSocket: SSLSocket? = null
    @Volatile private var udpReceiver: DesktopUdpVideoReceiver? = null
    @Volatile private var tcpReceiver: DesktopTcpVideoReceiver? = null
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

    fun requestPtz(zoom: Float, centerX: Float, centerY: Float): Boolean {
        val current = snapshot
        if (!current.supportsRemotePtz || !zoom.isFinite() || !centerX.isFinite() || !centerY.isFinite()) {
            return false
        }
        val targetZoom = zoom.coerceIn(current.minZoomRatio, current.maxZoomRatio)
        val halfVisible = 0.5f / targetZoom.coerceAtLeast(1f)
        val targetX = centerX.coerceIn(halfVisible, 1f - halfVisible)
        val targetY = centerY.coerceIn(halfVisible, 1f - halfVisible)
        val requestId = ptzRequestSequence.updateAndGet { previous ->
            if (previous == Long.MAX_VALUE) firstPtzRequestId else previous + 1
        }
        lastSentPtzRequestId.set(requestId)
        return send(
            ControlMessage(
                type = "command",
                command = "set_ptz",
                value = targetZoom,
                stringValue = "$targetX,$targetY",
                requestId = requestId
            )
        )
    }

    override fun close() {
        synchronized(resourceLock) {
            if (!running.getAndSet(false)) return
        }
        runCatching { send(ControlMessage(type = "command", command = "udp_unsubscribe")) }
        closeSession()
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
                closeSession()
                if (error is NonRetryableCameraConnectionException) {
                    val reportFailure = synchronized(resourceLock) {
                        if (!running.get()) false else {
                            running.set(false)
                            true
                        }
                    }
                    if (reportFailure) {
                        updateSnapshot(
                            status = DesktopCameraStatus.ERROR,
                            detail = error.message?.take(100) ?: "Camera authentication failed"
                        )
                    }
                    break
                }
                updateSnapshot(
                    status = DesktopCameraStatus.RECONNECTING,
                    detail = error.message?.take(100) ?: "Connection lost"
                )
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
        // A fresh TLS/UDP session must not reuse the previous camera clock estimate or
        // display counters from an earlier connection to the same endpoint.
        clockSynchronized.set(false)
        cameraClockOffsetMs.set(0L)
        bestClockRoundTripMs.set(Long.MAX_VALUE)
        lastFrameUiUpdateAtMs.set(0L)
        captureToDecodeLatency.clear()
        synchronized(snapshotLock) {
            snapshot = snapshot.copy(
                framesReceived = 0,
                framesDropped = 0,
                packetLossPercent = 0f,
                captureToDecodeP50Ms = null,
                captureToDecodeP95Ms = null,
                captureToDecodeP99Ms = null,
                captureToDecodeSampleCount = 0,
                width = 0,
                height = 0,
                rotationDegrees = 0,
                supportsRemotePtz = false,
                minZoomRatio = 1f,
                maxZoomRatio = 1f,
                remotePtzZoom = 1f,
                remotePtzCenterX = 0.5f,
                remotePtzCenterY = 0.5f,
                ptzAppliedRequestId = 0L
            )
        }
        updateSnapshot(status = DesktopCameraStatus.CONNECTING, detail = "TLS ${endpoint.host}:${endpoint.signalingPort}")
        val socket = connectTls()
        synchronized(resourceLock) {
            if (!running.get()) {
                socket.close()
                throw IOException("Camera connection stopped")
            }
            controlSocket = socket
        }
        ensureRunning()
        verifyCameraIdentity(socket)
        ensureRunning()
        val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
        val writer = PrintWriter(socket.outputStream.writer(Charsets.UTF_8), true)

        val pairingPin = obtainPairingPin()
        ensureRunning()
        writeControl(
            writer,
            ControlMessage(type = "hello", deviceName = directorName, pin = pairingPin)
        )
        val ackLine = readLineLimited(reader) ?: throw IOException("Camera closed during handshake")
        val ack = json.decodeFromString<ControlMessage>(ackLine)
        if (ack.type == "error") {
            trustedIdentities.remove(pairingPinKey())
            throw NonRetryableCameraConnectionException("Camera rejected the pairing PIN")
        }
        if (ack.type != "hello_ack") throw IOException("Unexpected camera response: ${ack.type}")
        trustedIdentities.put(pairingPinKey(), pairingPin)
        trustedIdentities.flush()
        val key = runCatching { Base64.getDecoder().decode(ack.sessionKey) }
            .getOrElse { throw IOException("Invalid camera session key", it) }
        if (key.size != 32) throw IOException("Invalid camera session key size")
        val tcpAuthToken = try {
            decodeTcpStreamAuthToken(
                encodedToken = ack.tcpAuthToken,
                supportsAuthenticatedTcp = ack.supportsAuthenticatedTcp,
                tcpPort = ack.tcpStreamPort,
                sessionKey = key
            )
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid authenticated TCP configuration", error)
        }
        val udpAvailable = ack.udpStreamPort in 1..65_535
        val tcpAvailable = ack.supportsAuthenticatedTcp && ack.tcpStreamPort in 1..65_535
        if (!udpAvailable && !tcpAvailable) {
            throw IOException("Camera offers neither UDP video nor authenticated TCP fallback")
        }

        val cameraName = ack.deviceName.ifBlank { endpoint.discoveredName }
        ensureRunning()
        val capabilities = normalizePtzCapabilities(ack)
        updateSnapshot(
            name = cameraName,
            status = DesktopCameraStatus.CONNECTED,
            detail = if (udpAvailable) "Control connected · UDP ${ack.udpStreamPort}"
                     else "Control connected · authenticated TCP ${ack.tcpStreamPort}",
            supportsRemotePtz = capabilities.supported,
            minZoomRatio = capabilities.minimum,
            maxZoomRatio = capabilities.maximum
        )

        val videoWatchdog = VideoStallWatchdog()
        val lastVideoInputAtMs = AtomicLong(System.currentTimeMillis())
        val tcpFallbackStarted = AtomicBoolean(false)
        val usingTcpVideo = AtomicBoolean(!udpAvailable)
        val decoderPath = AtomicReference("detecting decoder")
        fun videoDetail(): String =
            "${if (usingTcpVideo.get()) "Authenticated TCP" else "UDP/RTP"} · H.264 · ${decoderPath.get()}"
        val frameSession = synchronized(frameSessionLock) { ++frameSessionGeneration }
        fun ifCurrentSession(action: () -> Unit) = synchronized(frameSessionLock) {
            if (frameSession == frameSessionGeneration && running.get()) action()
        }
        val activeDecoder = H264FrameDecoder(
            cameraName = cameraName,
            onImage = { image, metadata ->
                ifCurrentSession {
                videoWatchdog.onDecodedFrame()
                val captureAt = metadata.captureAtWallMs
                val latency = captureAt
                    ?.takeIf { clockSynchronized.get() }
                    ?.let { cameraCaptureAt ->
                        val captureOnDesktopClock = cameraCaptureAt - cameraClockOffsetMs.get()
                        (System.currentTimeMillis() - captureOnDesktopClock)
                            .takeIf { value -> value in 0..60_000 }
                    }
                latency?.let(captureToDecodeLatency::record)
                val now = System.currentTimeMillis()
                val publishState = now - lastFrameUiUpdateAtMs.get() >= FRAME_UI_UPDATE_INTERVAL_MS
                if (publishState) lastFrameUiUpdateAtMs.set(now)
                val latencyPercentiles = if (publishState) captureToDecodeLatency.snapshot() else null
                updateSnapshot(
                    status = DesktopCameraStatus.STREAMING,
                    detail = videoDetail(),
                    captureToDecodeP50Ms = latencyPercentiles?.p50Ms,
                    captureToDecodeP95Ms = latencyPercentiles?.p95Ms,
                    captureToDecodeP99Ms = latencyPercentiles?.p99Ms,
                    captureToDecodeSampleCount = latencyPercentiles?.sampleCount,
                    width = metadata.width,
                    height = metadata.height,
                    rotationDegrees = metadata.rotationDegrees,
                    publish = publishState
                )
                listener.onImage(this, image)
                }
            },
            onError = { error ->
                ifCurrentSession { updateSnapshot(detail = "Decoder: ${error.message?.take(80) ?: "error"}") }
            },
            onDecodePath = { path ->
                decoderPath.set(path)
                ifCurrentSession { updateSnapshot(detail = videoDetail()) }
            }
        )
        synchronized(resourceLock) {
            ensureRunning()
            decoder = activeDecoder
            activeDecoder.start()
        }
        val droppedFrameSignals = AtomicLong(0L)

        fun deliverFrame(frame: CamHubVideoPayload) {
            lastVideoInputAtMs.set(System.currentTimeMillis())
            ifCurrentSession {
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
            }
        }

        var activeUdpReceiver: DesktopUdpVideoReceiver? = null
        if (udpAvailable) {
            val receiver = DesktopUdpVideoReceiver(
                cameraName = cameraName,
                expectedHost = endpoint.host,
                expectedSourcePort = ack.udpStreamPort,
                sessionKey = key,
                onFrame = ::deliverFrame,
                onStats = { stats ->
                    ifCurrentSession {
                        updateSnapshot(
                            framesReceived = stats.completedFrames,
                            framesDropped = stats.droppedFrames,
                            packetLossPercent = stats.packetLossPercent
                        )
                    }
                },
                onError = { error ->
                    ifCurrentSession { updateSnapshot(detail = "UDP: ${error.message?.take(80) ?: "error"}") }
                },
                onDroppedFrames = { count ->
                    ifCurrentSession {
                        if (count > 0) {
                            activeDecoder.recoverFromDiscontinuity()
                            droppedFrameSignals.addAndGet(count.toLong())
                        }
                    }
                }
            )
            activeUdpReceiver = receiver
            val receivePort = synchronized(resourceLock) {
                ensureRunning()
                udpReceiver = receiver
                receiver.start()
            }
            writeControl(
                writer,
                ControlMessage(
                    type = "command",
                    deviceName = directorName,
                    command = "udp_subscribe",
                    udpReceivePort = receivePort
                )
            )
        }

        fun startTcpFallback() {
            if (!tcpAvailable || !tcpFallbackStarted.compareAndSet(false, true)) return
            activeUdpReceiver?.close()
            synchronized(resourceLock) {
                if (udpReceiver === activeUdpReceiver) udpReceiver = null
            }
            if (udpAvailable) {
                writeControl(writer, ControlMessage(type = "command", command = "udp_unsubscribe"))
            }
            val receiver = DesktopTcpVideoReceiver(
                cameraName = cameraName,
                host = endpoint.host,
                port = ack.tcpStreamPort,
                sessionKey = key,
                authToken = tcpAuthToken,
                onFrame = ::deliverFrame,
                onError = { error ->
                    ifCurrentSession {
                        updateSnapshot(detail = "TCP fallback: ${error.message?.take(80) ?: "disconnected"}")
                    }
                }
            )
            ensureRunning()
            receiver.start()
            synchronized(resourceLock) {
                if (!running.get()) {
                    receiver.close()
                    throw IOException("Camera connection stopped")
                }
                tcpReceiver = receiver
            }
            usingTcpVideo.set(true)
            lastVideoInputAtMs.set(System.currentTimeMillis())
            videoWatchdog.onDecodedFrame()
            updateSnapshot(detail = "UDP unavailable · authenticated TCP fallback")
        }

        if (!udpAvailable) startTcpFallback()

        socket.soTimeout = 750
        var lastMessageAt = System.currentTimeMillis()
        var lastPingAt = 0L
        var lastKeyFrameRequestAt = 0L
        var handledDroppedFrames = 0L
        var syncSequence = 0L
        while (running.get()) {
            val now = System.currentTimeMillis()
            if (!tcpFallbackStarted.get() && now - lastVideoInputAtMs.get() >= TCP_FALLBACK_AFTER_NO_VIDEO_MS) {
                startTcpFallback()
            }
            if (videoWatchdog.isStalled()) {
                throw IOException("Video stalled; reconnecting")
            }
            val observedDroppedFrames = droppedFrameSignals.get()
            if (observedDroppedFrames > handledDroppedFrames &&
                now - lastKeyFrameRequestAt >= KEY_FRAME_REQUEST_COOLDOWN_MS
            ) {
                handledDroppedFrames = observedDroppedFrames
                lastKeyFrameRequestAt = now
                writeControl(writer, ControlMessage(type = "command", command = "request_key_frame"))
            }
            if (now - lastPingAt >= HEARTBEAT_INTERVAL_MS) {
                lastPingAt = now
                syncSequence++
                writeControl(
                    writer,
                    ControlMessage(
                        type = "ping",
                        syncId = syncSequence,
                        t0WallMs = now
                    )
                )
            }

            val line = try {
                readLineLimited(reader) ?: throw IOException("Camera closed the control connection")
            } catch (_: SocketTimeoutException) {
                null
            }
            if (line != null) {
                lastMessageAt = System.currentTimeMillis()
                val message = json.decodeFromString<ControlMessage>(line)
                if (message.type == "ping") {
                    val receivedAt = System.currentTimeMillis()
                    writeControl(
                        writer,
                        ControlMessage(
                            type = "pong",
                            syncId = message.syncId,
                            t0WallMs = message.t0WallMs,
                            t1WallMs = receivedAt,
                            t2WallMs = System.currentTimeMillis()
                        )
                    )
                } else if (message.type == "capabilities") {
                    val capabilities = normalizePtzCapabilities(message)
                    updateSnapshot(
                        supportsRemotePtz = capabilities.supported,
                        minZoomRatio = capabilities.minimum,
                        maxZoomRatio = capabilities.maximum
                    )
                } else if (message.type == "ptz_applied") {
                    val center = message.stringValue.split(',')
                    val centerX = center.getOrNull(0)?.toFloatOrNull()
                    val centerY = center.getOrNull(1)?.toFloatOrNull()
                    val latestSent = lastSentPtzRequestId.get()
                    if (message.requestId in firstPtzRequestId..latestSent &&
                        message.requestId > snapshot.ptzAppliedRequestId &&
                        message.value.isFinite() && message.value >= 1f &&
                        centerX?.isFinite() == true && centerY?.isFinite() == true
                    ) {
                        val appliedZoom = message.value.coerceIn(
                            snapshot.minZoomRatio,
                            snapshot.maxZoomRatio.coerceAtLeast(snapshot.minZoomRatio)
                        )
                        val halfVisible = 0.5f / appliedZoom.coerceAtLeast(1f)
                        updateSnapshot(
                            remotePtzZoom = appliedZoom,
                            remotePtzCenterX = centerX!!.coerceIn(halfVisible, 1f - halfVisible),
                            remotePtzCenterY = centerY!!.coerceIn(halfVisible, 1f - halfVisible),
                            ptzAppliedRequestId = message.requestId
                        )
                    }
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
        if (!approved[0]) throw NonRetryableCameraConnectionException("Camera identity was not confirmed")
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
            ?: throw NonRetryableCameraConnectionException("A valid 8-digit pairing PIN is required")
    }

    private fun pairingPinKey(): String = "pairing_pin_${endpoint.host}_${endpoint.signalingPort}"

    private fun normalizePtzCapabilities(message: ControlMessage): PtzCapabilities {
        val minimum = message.minZoomRatio
        val maximum = message.maxZoomRatio
        if (!message.supportsRemotePtz || !minimum.isFinite() || !maximum.isFinite() || maximum <= minimum) {
            return PtzCapabilities.UNSUPPORTED
        }
        val safeMinimum = minimum.coerceIn(1f, 32f)
        val safeMaximum = maximum.coerceIn(safeMinimum, 32f)
        return if (safeMaximum > safeMinimum) {
            PtzCapabilities(true, safeMinimum, safeMaximum)
        } else {
            PtzCapabilities.UNSUPPORTED
        }
    }

    private fun ensureRunning() {
        if (!running.get()) throw IOException("Camera connection stopped")
    }

    private fun send(message: ControlMessage): Boolean {
        val socket = controlSocket ?: return false
        return synchronized(controlWriteLock) {
            val writer = PrintWriter(socket.outputStream.writer(Charsets.UTF_8), true)
            writer.println(json.encodeToString(message))
            !writer.checkError()
        }
    }

    private fun writeControl(writer: PrintWriter, message: ControlMessage) {
        synchronized(controlWriteLock) {
            writer.println(json.encodeToString(message))
            if (writer.checkError()) throw IOException("Control write failed")
        }
    }

    private fun closeSession() {
        synchronized(frameSessionLock) { frameSessionGeneration++ }
        val resources = synchronized(resourceLock) {
            listOf<AutoCloseable?>(udpReceiver, tcpReceiver, decoder, controlSocket).also {
                udpReceiver = null
                tcpReceiver = null
                decoder = null
                controlSocket = null
            }
        }
        runCatching { resources[0]?.close() }
        runCatching { resources[1]?.close() }
        runCatching { resources[2]?.close() }
        runCatching { synchronized(controlWriteLock) { resources[3]?.close() } }
    }

    private fun updateSnapshot(
        name: String? = null,
        status: DesktopCameraStatus? = null,
        detail: String? = null,
        framesReceived: Long? = null,
        framesDropped: Long? = null,
        packetLossPercent: Float? = null,
        captureToDecodeP50Ms: Long? = snapshot.captureToDecodeP50Ms,
        captureToDecodeP95Ms: Long? = snapshot.captureToDecodeP95Ms,
        captureToDecodeP99Ms: Long? = snapshot.captureToDecodeP99Ms,
        captureToDecodeSampleCount: Int? = snapshot.captureToDecodeSampleCount,
        width: Int? = null,
        height: Int? = null,
        rotationDegrees: Int? = null,
        supportsRemotePtz: Boolean? = null,
        minZoomRatio: Float? = null,
        maxZoomRatio: Float? = null,
        remotePtzZoom: Float? = null,
        remotePtzCenterX: Float? = null,
        remotePtzCenterY: Float? = null,
        ptzAppliedRequestId: Long? = null,
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
                captureToDecodeP50Ms = captureToDecodeP50Ms,
                captureToDecodeP95Ms = captureToDecodeP95Ms,
                captureToDecodeP99Ms = captureToDecodeP99Ms,
                captureToDecodeSampleCount = captureToDecodeSampleCount ?: snapshot.captureToDecodeSampleCount,
                width = width ?: snapshot.width,
                height = height ?: snapshot.height,
                rotationDegrees = rotationDegrees ?: snapshot.rotationDegrees,
                supportsRemotePtz = supportsRemotePtz ?: snapshot.supportsRemotePtz,
                minZoomRatio = minZoomRatio ?: snapshot.minZoomRatio,
                maxZoomRatio = maxZoomRatio ?: snapshot.maxZoomRatio,
                remotePtzZoom = remotePtzZoom ?: snapshot.remotePtzZoom,
                remotePtzCenterX = remotePtzCenterX ?: snapshot.remotePtzCenterX,
                remotePtzCenterY = remotePtzCenterY ?: snapshot.remotePtzCenterY,
                ptzAppliedRequestId = ptzAppliedRequestId ?: snapshot.ptzAppliedRequestId
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

private data class PtzCapabilities(val supported: Boolean, val minimum: Float, val maximum: Float) {
    companion object {
        val UNSUPPORTED = PtzCapabilities(false, 1f, 1f)
    }
}

private class NonRetryableCameraConnectionException(message: String) : IOException(message)
