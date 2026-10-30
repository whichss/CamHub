package com.camhub.desktop

import com.camhub.studio.data.network.CamHubVideoPayloadCodec
import com.camhub.studio.data.network.FrameCipher
import com.camhub.studio.data.network.RtpVideoPacketCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded UDP/RTP publisher for the desktop screen encoder. Authentication and
 * subscription control belong to the signaling server; this class accepts only
 * destinations registered by that trusted control path.
 */
class DesktopUdpVideoPublisher(sessionKey: ByteArray) : AutoCloseable {
    data class SendStats(
        val acceptedDatagrams: Long,
        val sendFailures: Long,
        val droppedAccessUnits: Long
    )

    private data class Destination(val address: InetSocketAddress)
    private data class PendingUnit(val unit: DesktopScreenH264Encoder.EncodedAccessUnit)

    companion object {
        private const val MAX_DESTINATIONS = 4
        private const val SOCKET_SEND_BUFFER_BYTES = 256 * 1024
    }

    private val cipher = FrameCipher(sessionKey.copyOf())
    private val destinations = ConcurrentHashMap<String, Destination>()
    private val running = AtomicBoolean(false)
    private val wakeSender = Semaphore(0)
    private val pendingKeyFrame = AtomicReference<PendingUnit?>()
    private val pendingLatest = AtomicReference<PendingUnit?>()
    private val waitingForKeyFrame = AtomicBoolean(true)
    private val frameId = AtomicLong(0L)
    private val droppedAccessUnits = AtomicLong(0L)
    private val acceptedDatagrams = AtomicLong(0L)
    private val sendFailures = AtomicLong(0L)
    private val queueLock = Any()
    private val scratch = ByteArray(RtpVideoPacketCodec.MAX_DATAGRAM_BYTES)
    private val ssrc = SecureRandom().nextInt().toLong() and 0xffff_ffffL
    private val lifecycleLock = Any()

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var thread: Thread? = null
    @Volatile var onKeyFrameRequired: (() -> Unit)? = null
    internal var datagramSender: (DatagramSocket, DatagramPacket) -> Unit = { activeSocket, packet ->
        activeSocket.send(packet)
    }

    val localPort: Int get() = socket?.localPort ?: 0
    val destinationCount: Int get() = destinations.size

    fun start(): Int = synchronized(lifecycleLock) {
        if (running.get()) return@synchronized localPort
        val activeSocket = DatagramSocket(null).apply {
            reuseAddress = true
            sendBufferSize = SOCKET_SEND_BUFFER_BYTES
            bind(InetSocketAddress(0))
        }
        socket = activeSocket
        running.set(true)
        thread = Thread(::sendLoop, "DesktopUdpVideoSend").apply {
            isDaemon = true
            start()
        }
        activeSocket.localPort
    }

    /** Registers an authenticated CamHub hub's advertised UDP receive endpoint. */
    fun subscribe(clientId: String, host: String, port: Int): Boolean {
        require(clientId.isNotBlank())
        require(port in 1..65_535)
        val address = InetAddress.getByName(host)
        val destination = Destination(InetSocketAddress(address, port))
        synchronized(queueLock) {
            if (!running.get()) return false
            val previous = destinations[clientId]
            if (previous == null && destinations.size >= MAX_DESTINATIONS) return false
            destinations[clientId] = destination
            if (previous != destination) {
                if (pendingKeyFrame.getAndSet(null) != null) droppedAccessUnits.incrementAndGet()
                if (pendingLatest.getAndSet(null) != null) droppedAccessUnits.incrementAndGet()
                waitingForKeyFrame.set(true)
            }
        }
        onKeyFrameRequired?.invoke()
        return true
    }

    fun unsubscribe(clientId: String) {
        synchronized(queueLock) { destinations.remove(clientId) }
    }

    /** Constant-time handoff: encoding never waits for encryption, packetization or socket I/O. */
    fun publish(unit: DesktopScreenH264Encoder.EncodedAccessUnit): Boolean {
        if (!running.get() || destinations.isEmpty()) return false
        val pending = PendingUnit(unit)
        var requestKeyFrame = false
        val accepted = synchronized(queueLock) {
            if (!running.get() || destinations.isEmpty()) return false
            if (unit.isKeyFrame) {
                waitingForKeyFrame.set(false)
                if (pendingLatest.getAndSet(null) != null) droppedAccessUnits.incrementAndGet()
                replace(pendingKeyFrame, pending)
            } else {
                if (waitingForKeyFrame.get()) return false
                if (pendingLatest.getAndSet(pending) != null) {
                    pendingLatest.set(null)
                    waitingForKeyFrame.set(true)
                    droppedAccessUnits.addAndGet(2)
                    requestKeyFrame = true
                    return@synchronized false
                }
            }
            true
        }
        if (requestKeyFrame) onKeyFrameRequired?.invoke()
        if (accepted && wakeSender.availablePermits() == 0) wakeSender.release()
        return accepted
    }

    fun stats() = SendStats(
        acceptedDatagrams = acceptedDatagrams.get(),
        sendFailures = sendFailures.get(),
        droppedAccessUnits = droppedAccessUnits.get()
    )

    override fun close() = synchronized(lifecycleLock) {
        if (!running.getAndSet(false)) return@synchronized
        wakeSender.release()
        socket?.close()
        socket = null
        thread?.interrupt()
        thread?.let { worker ->
            if (worker !== Thread.currentThread()) runCatching { worker.join(500) }
        }
        thread = null
        synchronized(queueLock) {
            destinations.clear()
            pendingKeyFrame.set(null)
            pendingLatest.set(null)
            waitingForKeyFrame.set(true)
        }
        wakeSender.drainPermits()
    }

    private fun sendLoop() {
        try {
            while (running.get()) {
                wakeSender.acquire()
                var next = takeNext()
                while (next != null && running.get()) {
                    try {
                        send(next.unit)
                    } catch (_: Exception) {
                        sendFailures.incrementAndGet()
                        recoverFromSendFailure()
                    }
                    next = takeNext()
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            running.set(false)
        }
    }

    private fun send(unit: DesktopScreenH264Encoder.EncodedAccessUnit) {
        val activeSocket = socket ?: return
        val activeDestinations = destinations.values.toList()
        if (activeDestinations.isEmpty()) return
        val framePayload = CamHubVideoPayloadCodec.encode(
            width = unit.width,
            height = unit.height,
            rotationDegrees = 0,
            isKeyFrame = unit.isKeyFrame,
            isCodecConfig = false,
            frameSequence = unit.sequence,
            captureAtWallMs = unit.captureAtWallMs,
            encodedAtWallMs = unit.encodedAtWallMs,
            h264Bytes = unit.bytes
        )
        val encrypted = cipher.encrypt(framePayload)
        val transportId = frameId.incrementAndGet()
        val timestamp90Khz = (System.nanoTime() / 1_000_000L * 90L) and 0xffff_ffffL
        val datagram = DatagramPacket(scratch, scratch.size)
        var frameSendFailed = false
        val packetCount = RtpVideoPacketCodec.packetizeEach(
            frame = encrypted,
            transportFrameId = transportId,
            rtpTimestamp = timestamp90Khz,
            ssrc = ssrc,
            firstRtpSequence = nextRtpSequence,
            scratch = scratch,
            isKeyFrame = unit.isKeyFrame
        ) { bytes, length ->
            activeDestinations.forEach { destination ->
                try {
                    datagram.socketAddress = destination.address
                    datagram.length = length
                    datagramSender(activeSocket, datagram)
                    acceptedDatagrams.incrementAndGet()
                } catch (_: Exception) {
                    sendFailures.incrementAndGet()
                    frameSendFailed = true
                }
            }
        }
        nextRtpSequence = (nextRtpSequence + packetCount) and 0xffff
        if (frameSendFailed) recoverFromSendFailure()
    }

    private fun recoverFromSendFailure() {
        val requestKeyFrame = synchronized(queueLock) {
            if (pendingKeyFrame.get() != null) {
                false
            } else {
                waitingForKeyFrame.set(true)
                if (pendingLatest.getAndSet(null) != null) droppedAccessUnits.incrementAndGet()
                true
            }
        }
        if (requestKeyFrame) onKeyFrameRequired?.invoke()
    }

    private fun takeNext(): PendingUnit? = synchronized(queueLock) {
        pendingKeyFrame.getAndSet(null) ?: pendingLatest.getAndSet(null)
    }

    private fun replace(slot: AtomicReference<PendingUnit?>, value: PendingUnit) {
        if (slot.getAndSet(value) != null) droppedAccessUnits.incrementAndGet()
    }

    private var nextRtpSequence = SecureRandom().nextInt(0x1_0000)
}
