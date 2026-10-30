package com.camhub.desktop

import java.awt.image.BufferedImage
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class DesktopCameraSourceInfo(
    val deviceName: String,
    val port: Int,
    val pairingPin: String,
    val fingerprintPrefix: String
)

/** Advertises the desktop as an authenticated CamHub camera and publishes screen video. */
class DesktopCameraSourceServer(
    private val deviceName: String = "CamHub Desktop",
    private val onStatus: (String) -> Unit = {},
    private val onFatalError: (String) -> Unit = {}
) : AutoCloseable {
    companion object {
        private const val SERVICE_TYPE = "_camhub._tcp.local."
        private const val ANNOUNCEMENT_PORT = 45_678
        private const val ANNOUNCEMENT_INTERVAL_MS = 800L
        private const val MAX_CLIENTS = 4
        private const val MAX_CONTROL_LINE_CHARS = 4_096
        private const val HEARTBEAT_TIMEOUT_MS = 22_000
        private const val PAIRING_WINDOW_MS = 60_000L
        private const val MAX_PAIRING_FAILURES = 5
    }

    private data class FailureWindow(val startedAtMs: Long, val count: Int)

    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val activeConnections = AtomicInteger(0)
    private val json = Json { ignoreUnknownKeys = true }
    private val pairingFailures = ConcurrentHashMap<String, FailureWindow>()
    private val clientExecutor = Executors.newFixedThreadPool(MAX_CLIENTS) { task ->
        Thread(task, "DesktopCameraControlClient").apply { isDaemon = true }
    }
    private val clientSockets = ConcurrentHashMap.newKeySet<SSLSocket>()
    private val identityStore = DesktopCameraIdentityStore()
    private val advertiser = DesktopCameraAdvertiser()

    @Volatile private var identity: DesktopCameraIdentity? = null
    @Volatile private var sessionKey: ByteArray? = null
    @Volatile private var sourceInfo: DesktopCameraSourceInfo? = null
    @Volatile private var serverSocket: SSLServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var pipeline: DesktopScreenVideoPipeline? = null

    val isRunning: Boolean get() = running.get()
    val connectedHubCount: Int get() = pipeline?.connectedHubCount ?: 0
    val currentSourceInfo: DesktopCameraSourceInfo? get() = sourceInfo

    /** Call from a background thread; this opens the TLS listener and advertisements. */
    @Synchronized
    fun start(): DesktopCameraSourceInfo {
        check(!closed.get()) { "Desktop camera source has been closed" }
        check(running.compareAndSet(false, true)) { "Desktop camera source is already running" }
        try {
            val localIdentity = identityStore.loadOrCreate()
            identity = localIdentity
            val key = ByteArray(32).also(SecureRandom()::nextBytes)
            sessionKey = key
            val videoPublisher = DesktopUdpVideoPublisher(key)
            val udpPort = videoPublisher.start()
            pipeline = DesktopScreenVideoPipeline(videoPublisher) { error ->
                onFatalError("Video encoder error: ${error.take(96)}")
            }
            val listener = localIdentity.sslContext.serverSocketFactory
                .createServerSocket(0, MAX_CLIENTS) as SSLServerSocket
            listener.soTimeout = 750
            serverSocket = listener
            val info = DesktopCameraSourceInfo(
                deviceName = "${deviceName.take(35)}-${localIdentity.certificateFingerprint.take(12)}",
                port = listener.localPort,
                pairingPin = localIdentity.pairingPin,
                fingerprintPrefix = localIdentity.certificateFingerprint.take(16)
                    .chunked(4).joinToString(" ")
            )
            sourceInfo = info
            advertiser.start(info.port, info.deviceName)
            acceptThread = Thread(::acceptLoop, "DesktopCameraSourceAccept").apply {
                isDaemon = true
                start()
            }
            onStatus("ADVERTISED · TLS ${info.port} · UDP $udpPort")
            return info
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    /** The capture callback only stores the newest frame; encoding and UDP run elsewhere. */
    fun offerScreenFrame(image: BufferedImage, captureAtWallMs: Long = System.currentTimeMillis()) {
        pipeline?.offerFrame(image, captureAtWallMs)
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        clientExecutor.shutdownNow()
        if (!running.getAndSet(false)) return
        runCatching { advertiser.close() }
        runCatching { serverSocket?.close() }
        serverSocket = null
        clientSockets.forEach { socket -> runCatching { socket.close() } }
        clientSockets.clear()
        acceptThread?.interrupt()
        acceptThread?.let { worker ->
            if (worker !== Thread.currentThread()) runCatching { worker.join(1_000) }
        }
        acceptThread = null
        runCatching { pipeline?.close() }
        pipeline = null
        sourceInfo = null
        identity = null
        sessionKey?.fill(0)
        sessionKey = null
        onStatus("OFFLINE")
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val socket = serverSocket?.accept() as? SSLSocket ?: break
                if (activeConnections.incrementAndGet() > MAX_CLIENTS) {
                    activeConnections.decrementAndGet()
                    runCatching { socket.close() }
                    continue
                }
                clientSockets += socket
                try {
                    clientExecutor.execute {
                        try { handleClient(socket) }
                        finally { activeConnections.decrementAndGet() }
                    }
                } catch (_: Exception) {
                    clientSockets.remove(socket)
                    activeConnections.decrementAndGet()
                    runCatching { socket.close() }
                }
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                if (running.get()) onFatalError("TLS listener stopped unexpectedly")
                break
            } catch (error: Exception) {
                if (running.get()) onStatus("TLS accept error: ${error.message ?: "unknown"}")
            }
        }
    }

    private fun handleClient(socket: SSLSocket) {
        val remoteHost = socket.inetAddress.hostAddress ?: "unknown"
        val clientId = "${remoteHost}:${socket.port}"
        try {
            socket.soTimeout = 5_000
            socket.tcpNoDelay = true
            socket.startHandshake()
            val reader = InputStreamReader(socket.inputStream, Charsets.UTF_8)
            val writer = PrintWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8), true)
            val helloLine = readLineLimited(reader) ?: return
            val hello = runCatching { json.decodeFromString<ControlMessage>(helloLine) }.getOrNull()
                ?: return
            if (hello.type != "hello") return
            if (isPairingLimited(remoteHost)) {
                send(writer, ControlMessage(type = "error", stringValue = "pairing_rate_limited"))
                return
            }
            val currentIdentity = identity ?: return
            if (!constantTimePinMatch(hello.pin, currentIdentity.pairingPin)) {
                recordPairingFailure(remoteHost)
                send(writer, ControlMessage(type = "error", stringValue = "invalid_pairing_pin"))
                return
            }
            pairingFailures.remove(remoteHost)
            val currentInfo = sourceInfo ?: return
            val key = sessionKey ?: return
            send(
                writer,
                ControlMessage(
                    type = "hello_ack",
                    deviceName = currentInfo.deviceName,
                    sessionKey = Base64.getEncoder().encodeToString(key),
                    udpStreamPort = pipelineUdpPort
                )
            )
            onStatus("CONNECTED · ${hello.deviceName.ifBlank { remoteHost }}")
            socket.soTimeout = HEARTBEAT_TIMEOUT_MS
            while (running.get() && !socket.isClosed) {
                val line = readLineLimited(reader) ?: break
                val message = runCatching { json.decodeFromString<ControlMessage>(line) }.getOrNull()
                    ?: continue
                when (message.type) {
                    "ping" -> send(
                        writer,
                        ControlMessage(
                            type = "pong",
                            syncId = message.syncId,
                            t0WallMs = message.t0WallMs,
                            t1WallMs = System.currentTimeMillis(),
                            t2WallMs = System.currentTimeMillis()
                        )
                    )
                    "command" -> when (message.command) {
                        "udp_subscribe" -> {
                            if (message.udpReceivePort in 1..65_535) {
                                if (pipeline?.subscribe(clientId, remoteHost, message.udpReceivePort) == true) {
                                    onStatus("STREAMING · ${hello.deviceName.ifBlank { remoteHost }}")
                                }
                            }
                        }
                        "udp_unsubscribe" -> {
                            pipeline?.unsubscribe(clientId)
                            onStatus("READY · ${connectedHubCount} hub(s)")
                        }
                        "request_key_frame" -> pipeline?.requestKeyFrame()
                    }
                }
            }
        } catch (error: SocketTimeoutException) {
            // Silent or half-open control channels are released by the same heartbeat deadline as Android.
        } catch (error: Exception) {
            if (running.get()) onStatus("Client disconnected: ${error.message?.take(72) ?: "network error"}")
        } finally {
            pipeline?.unsubscribe(clientId)
            clientSockets.remove(socket)
            runCatching { socket.close() }
            if (running.get()) onStatus("READY · ${connectedHubCount} hub(s)")
        }
    }

    private fun send(writer: PrintWriter, message: ControlMessage) {
        writer.println(json.encodeToString(message))
        if (writer.checkError()) throw SocketException("TLS control write failed")
    }

    private fun readLineLimited(reader: InputStreamReader): String? {
        val line = StringBuilder()
        while (true) {
            val value = reader.read()
            if (value < 0) return if (line.isEmpty()) null else line.toString()
            if (value == '\n'.code) return line.toString().removeSuffix("\r")
            if (line.length >= MAX_CONTROL_LINE_CHARS) throw SocketException("Control line too long")
            line.append(value.toChar())
        }
    }

    private fun constantTimePinMatch(candidate: String, expected: String): Boolean =
        candidate.matches(Regex("[0-9]{8}")) && MessageDigest.isEqual(
            candidate.toByteArray(Charsets.US_ASCII),
            expected.toByteArray(Charsets.US_ASCII)
        )

    private fun isPairingLimited(host: String): Boolean = pairingFailures[host]?.let {
        System.currentTimeMillis() - it.startedAtMs < PAIRING_WINDOW_MS && it.count >= MAX_PAIRING_FAILURES
    } == true

    private fun recordPairingFailure(host: String) {
        pairingFailures.compute(host) { _, current ->
            val now = System.currentTimeMillis()
            if (current == null || now - current.startedAtMs >= PAIRING_WINDOW_MS) FailureWindow(now, 1)
            else current.copy(count = current.count + 1)
        }
    }

    private val pipelineUdpPort: Int
        get() = pipeline?.udpPort ?: 0

    private inner class DesktopCameraAdvertiser : AutoCloseable {
        private val active = AtomicBoolean(false)
        private val mdns = mutableListOf<Pair<JmDNS, ServiceInfo>>()
        private val sockets = mutableListOf<Pair<DatagramSocket, Inet4Address>>()
        @Volatile private var worker: Thread? = null
        @Volatile private var port = 0
        @Volatile private var advertisedName = ""

        fun start(port: Int, deviceName: String) {
            this.port = port
            advertisedName = deviceName
            if (!active.compareAndSet(false, true)) return
            val mdnsAddresses = localDiscoveryAddresses()
            mdnsAddresses.forEach { address ->
                runCatching {
                    val dns = JmDNS.create(address, "CamHubDesktop-${address.hostAddress}")
                    val service = ServiceInfo.create(
                        SERVICE_TYPE,
                        "CamHub_$deviceName",
                        port,
                        0,
                        0,
                        false,
                        ""
                    )
                    dns.registerService(service)
                    synchronized(mdns) { mdns += dns to service }
                }.onFailure { onStatus("mDNS unavailable on ${address.hostAddress}") }
            }
            localIpv4Addresses().forEach { address ->
                runCatching {
                    val socket = DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        bind(InetSocketAddress(address, 0))
                    }
                    synchronized(sockets) { sockets += socket to address }
                }
            }
            worker = Thread(::announceLoop, "DesktopCameraUdpAnnounce").apply {
                isDaemon = true
                start()
            }
        }

        override fun close() {
            if (!active.getAndSet(false)) return
            worker?.interrupt()
            worker?.let { thread ->
                if (thread !== Thread.currentThread()) runCatching { thread.join(1_000) }
            }
            worker = null
            synchronized(sockets) {
                sockets.forEach { (socket, _) -> runCatching { socket.close() } }
                sockets.clear()
            }
            synchronized(mdns) {
                mdns.forEach { (dns, service) ->
                    runCatching { dns.unregisterService(service) }
                    runCatching { dns.close() }
                }
                mdns.clear()
            }
        }

        private fun announceLoop() {
            val announcementJson = Json { encodeDefaults = false }
            while (active.get()) {
                val activeSockets = synchronized(sockets) { sockets.toList() }
                activeSockets.forEach { (socket, address) ->
                    val message = announcementJson.encodeToString(
                        CameraAnnouncement(service = "camhub", ip = address.hostAddress, port = port, deviceName = advertisedName)
                    )
                        .toByteArray(Charsets.UTF_8)
                    val broadcasts = listOfNotNull(
                        runCatching {
                            NetworkInterface.getByInetAddress(address)?.interfaceAddresses
                                ?.firstOrNull { it.address == address }?.broadcast
                        }.getOrNull(),
                        runCatching { java.net.InetAddress.getByName("255.255.255.255") }.getOrNull()
                    ).distinct()
                    broadcasts.forEach { destination ->
                        runCatching {
                            socket.send(DatagramPacket(message, message.size, destination, ANNOUNCEMENT_PORT))
                        }
                    }
                }
                try {
                    Thread.sleep(ANNOUNCEMENT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }

        private fun localIpv4Addresses(): List<Inet4Address> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                .distinctBy { it.hostAddress }
        }.getOrDefault(emptyList())

        private fun localDiscoveryAddresses(): List<InetAddress> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filter { address ->
                    !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isMulticastAddress &&
                        (address is Inet4Address || address is Inet6Address)
                }
                .distinctBy { it.hostAddress }
                .toList()
        }.getOrDefault(emptyList())
    }
}
