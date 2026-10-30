package com.camhub.studio.data.network

import com.camhub.studio.data.metrics.DurationPercentilesUs
import com.camhub.studio.data.metrics.RollingDurationTracker
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Camera-side, non-blocking UDP/RTP video sender.
 *
 * The encoder only publishes into bounded latest-frame slots. Packetization,
 * encryption and socket writes happen on the dedicated CameraUdpVideoSend thread.
 * Codec configuration and keyframes have their own slots so a normal frame cannot
 * overwrite the data required for decoder recovery.
 */
class UdpVideoSender(
    private val socketFactory: () -> DatagramSocket = {
        DatagramSocket(null).apply {
            reuseAddress = true
            sendBufferSize = SOCKET_SEND_BUFFER_BYTES
            bind(InetSocketAddress(0))
        }
    }
) {
    data class SendMetrics(
        val queueResidence: DurationPercentilesUs,
        val packetizeAndSend: DurationPercentilesUs,
        val keyFramePacketizeAndSend: DurationPercentilesUs,
        val socketSend: DurationPercentilesUs,
        // Lifetime counters; UDP socket acceptance does not imply delivery.
        val socketAcceptedDatagrams: Long,
        val socketSendFailures: Long,
        val latestKeyFramePacketCount: Long
    )
    companion object {
        private const val MAX_CLIENTS = 4
        private const val SOCKET_SEND_BUFFER_BYTES = 256 * 1024
    }

    private data class QueuedFrame(
        val bytes: ByteArray,
        val isKeyFrame: Boolean,
        val isCodecConfig: Boolean,
        val enqueuedAtNs: Long = System.nanoTime()
    )

    private data class ClientEndpoint(
        val address: InetSocketAddress,
        @Volatile var needsCodecConfig: Boolean = true
    )

    private data class PacketizedSendResult(
        val packetCount: Int,
        val sentSuccessfully: BooleanArray,
        val socketSendNs: Long
    )

    private val clients = ConcurrentHashMap<String, ClientEndpoint>()
    private val running = AtomicBoolean(false)
    private val wakeSender = Semaphore(0)
    private val pendingConfig = AtomicReference<QueuedFrame?>()
    private val pendingKeyFrame = AtomicReference<QueuedFrame?>()
    private val pendingLatestFrame = AtomicReference<QueuedFrame?>()
    private val cachedConfig = AtomicReference<QueuedFrame?>()
    private val transportFrameSequence = AtomicLong(0)
    private val _droppedBeforeSend = AtomicLong(0)
    private val queueResidence = RollingDurationTracker()
    private val packetizeAndSend = RollingDurationTracker()
    private val keyFramePacketizeAndSend = RollingDurationTracker()
    private val socketSend = RollingDurationTracker()
    private val socketAcceptedDatagrams = AtomicLong(0)
    private val socketSendFailures = AtomicLong(0)
    private val latestKeyFramePacketCount = AtomicLong(0)
    /** Reused by the single sender thread; packetizeEach callbacks consume it synchronously. */
    private val packetScratch = ByteArray(RtpVideoPacketCodec.MAX_DATAGRAM_BYTES)
    private val lifecycleLock = Any()
    private val ssrc = SecureRandom().nextInt().toLong() and 0xffff_ffffL

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var senderExecutor: ExecutorService? = null
    @Volatile private var frameCipher: FrameCipher? = null
    private var nextRtpSequence = SecureRandom().nextInt(0x1_0000)

    var keyFrameRequester: (() -> Unit)? = null
    val clientCount: Int get() = clients.size
    val localPort: Int get() = socket?.localPort ?: 0
    val droppedBeforeSend: Long get() = _droppedBeforeSend.get()

    fun sendMetrics(): SendMetrics = SendMetrics(
        queueResidence.snapshot(), packetizeAndSend.snapshot(), keyFramePacketizeAndSend.snapshot(),
        socketSend.snapshot(), socketAcceptedDatagrams.get(), socketSendFailures.get(),
        latestKeyFramePacketCount.get()
    )

    fun setSessionKey(key: ByteArray) {
        frameCipher = FrameCipher(key)
    }

    fun start(): Int = synchronized(lifecycleLock) {
        if (running.get()) return@synchronized localPort

        val newSocket = socketFactory()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "CameraUdpVideoSend").apply { isDaemon = true }
        }
        socket = newSocket
        senderExecutor = executor
        running.set(true)
        executor.execute(::senderLoop)
        newSocket.localPort
    }

    fun registerClient(clientId: String, host: String, port: Int): Boolean {
        require(clientId.isNotBlank()) { "Client id must not be blank" }
        require(port in 1..65_535) { "Invalid UDP port" }
        if (!clients.containsKey(clientId) && clients.size >= MAX_CLIENTS) return false

        val endpoint = ClientEndpoint(InetSocketAddress(InetAddress.getByName(host), port))
        clients[clientId] = endpoint
        cachedConfig.get()?.let { replacePending(pendingConfig, it.copy(enqueuedAtNs = System.nanoTime())) }
        signalSender()
        keyFrameRequester?.invoke()
        return true
    }

    fun unregisterClient(clientId: String) {
        clients.remove(clientId)
    }

    /**
     * Publishes a frame without waiting for network I/O.
     * Returns false when the sender is stopped or no UDP client is registered.
     */
    fun enqueueFrame(
        bytes: ByteArray,
        isKeyFrame: Boolean = false,
        isCodecConfig: Boolean = false
    ): Boolean {
        require(bytes.isNotEmpty()) { "Frame must not be empty" }
        val frame = QueuedFrame(bytes, isKeyFrame, isCodecConfig)
        if (isCodecConfig) cachedConfig.set(frame)
        if (!running.get() || clients.isEmpty()) return false

        when {
            isCodecConfig -> replacePending(pendingConfig, frame)
            isKeyFrame -> {
                // Never send a stale delta frame after a newer recovery point.
                if (pendingLatestFrame.getAndSet(null) != null) {
                    _droppedBeforeSend.incrementAndGet()
                }
                replacePending(pendingKeyFrame, frame)
            }
            else -> replacePending(pendingLatestFrame, frame)
        }
        signalSender()
        return true
    }

    fun stop() = synchronized(lifecycleLock) {
        val wasRunning = running.getAndSet(false)
        if (wasRunning) wakeSender.release()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        senderExecutor?.shutdownNow()
        senderExecutor = null
        clients.clear()
        pendingConfig.set(null)
        pendingKeyFrame.set(null)
        pendingLatestFrame.set(null)
        cachedConfig.set(null)
        frameCipher = null
        wakeSender.drainPermits()
    }

    private fun senderLoop() {
        while (running.get()) {
            try {
                // Zero CPU wakeups while idle; stop() releases the semaphore.
                wakeSender.acquire()
                var frame = takeNextFrame()
                while (frame != null && running.get()) {
                    sendFrame(frame)
                    frame = takeNextFrame()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    private fun takeNextFrame(): QueuedFrame? =
        pendingConfig.getAndSet(null)
            ?: pendingKeyFrame.getAndSet(null)
            ?: pendingLatestFrame.getAndSet(null)

    private fun sendFrame(frame: QueuedFrame) {
        val activeSocket = socket ?: return
        val activeClients = clients.entries.toList()
        if (activeClients.isEmpty()) return

        val startNs = System.nanoTime()
        queueResidence.recordNanos((startNs - frame.enqueuedAtNs).coerceAtLeast(0L))

        // Allocate RTP sequence numbers in wire order: configuration precedes
        // the media frame for clients that have not received it yet.
        var socketSendNs = 0L
        val clientsNeedingConfig = if (!frame.isCodecConfig) {
            activeClients.filter { it.value.needsCodecConfig }
        } else {
            emptyList()
        }
        if (clientsNeedingConfig.isNotEmpty()) {
            cachedConfig.get()?.let { config ->
                val configResult = packetizeAndSend(activeSocket, config, clientsNeedingConfig)
                socketSendNs += configResult.socketSendNs
                clientsNeedingConfig.forEachIndexed { index, client ->
                    if (configResult.sentSuccessfully[index]) client.value.needsCodecConfig = false
                }
            }
        }

        val mediaResult = packetizeAndSend(activeSocket, frame, activeClients)
        socketSendNs += mediaResult.socketSendNs
        if (frame.isKeyFrame) latestKeyFramePacketCount.set(mediaResult.packetCount.toLong())
        if (frame.isCodecConfig) {
            activeClients.forEachIndexed { index, client ->
                if (mediaResult.sentSuccessfully[index]) client.value.needsCodecConfig = false
            }
        }
        val durationNs = (System.nanoTime() - startNs).coerceAtLeast(0L)
        socketSend.recordNanos(socketSendNs)
        packetizeAndSend.recordNanos(durationNs)
        if (frame.isKeyFrame) keyFramePacketizeAndSend.recordNanos(durationNs)
    }

    private fun packetizeAndSend(
        activeSocket: DatagramSocket,
        frame: QueuedFrame,
        destinations: List<Map.Entry<String, ClientEndpoint>>
    ): PacketizedSendResult {
        if (destinations.isEmpty()) return PacketizedSendResult(0, BooleanArray(0), 0L)
        val wireBytes = frameCipher?.encrypt(frame.bytes) ?: frame.bytes
        val frameId = transportFrameSequence.incrementAndGet()
        val timestamp90Khz = (System.nanoTime() / 1_000_000L * 90L) and 0xffff_ffffL
        val successful = BooleanArray(destinations.size) { true }
        var socketSendNs = 0L
        val datagram = DatagramPacket(packetScratch, packetScratch.size)
        val packetCount = RtpVideoPacketCodec.packetizeEach(
            frame = wireBytes,
            transportFrameId = frameId,
            rtpTimestamp = timestamp90Khz,
            ssrc = ssrc,
            firstRtpSequence = nextRtpSequence,
            scratch = packetScratch,
            isKeyFrame = frame.isKeyFrame,
            isCodecConfig = frame.isCodecConfig
        ) { bytes, length ->
            destinations.forEachIndexed { index, entry ->
                if (!successful[index]) return@forEachIndexed
                val socketStartedNs = System.nanoTime()
                try {
                    datagram.setSocketAddress(entry.value.address)
                    datagram.setLength(length)
                    activeSocket.send(datagram)
                    socketAcceptedDatagrams.incrementAndGet()
                } catch (_: Exception) {
                    successful[index] = false
                    socketSendFailures.incrementAndGet()
                } finally {
                    socketSendNs += (System.nanoTime() - socketStartedNs).coerceAtLeast(0L)
                }
            }
        }
        nextRtpSequence = (nextRtpSequence + packetCount) and 0xffff
        return PacketizedSendResult(packetCount, successful, socketSendNs)
    }

    private fun replacePending(slot: AtomicReference<QueuedFrame?>, frame: QueuedFrame) {
        if (slot.getAndSet(frame) != null) _droppedBeforeSend.incrementAndGet()
    }

    private fun signalSender() {
        if (wakeSender.availablePermits() == 0) wakeSender.release()
    }
}
