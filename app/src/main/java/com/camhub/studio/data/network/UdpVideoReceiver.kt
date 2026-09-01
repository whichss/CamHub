package com.camhub.studio.data.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

data class UdpReceiverStats(
    val packetsReceived: Long = 0,
    val datagramBytesReceived: Long = 0,
    val completedFrames: Long = 0,
    val deadlineDroppedFrames: Long = 0,
    val estimatedMissingPackets: Long = 0,
    val malformedPackets: Long = 0
) {
    val estimatedPacketLossPercent: Float
        get() {
            val expectedPackets = packetsReceived + estimatedMissingPackets
            return if (expectedPackets <= 0L) 0f
            else estimatedMissingPackets * 100f / expectedPackets
        }
}

/**
 * Hub-side receiver for one camera's UDP/RTP stream.
 *
 * Each camera owns one blocking receiver thread. Only complete frames that meet
 * the deadline are handed to the decoder pipeline; incomplete frames are counted
 * and discarded without retransmission.
 */
class UdpVideoReceiver(
    cameraName: String,
    expectedHost: String,
    private val expectedSourcePort: Int,
    private val frameDeadlineMs: Long = DeadlineFrameReassembler.DEFAULT_FRAME_DEADLINE_MS,
    private val statsIntervalMs: Long = 1_000L,
    private val socketFactory: () -> DatagramSocket = { DatagramSocket(null) },
    private val onFrame: (bytes: ByteArray, receivedAtWallMs: Long, receivedAtElapsedMs: Long) -> Unit,
    private val onPacketBytes: (Int) -> Unit = {},
    private val onDroppedFrames: (Int) -> Unit = {},
    private val onStats: (UdpReceiverStats) -> Unit = {},
    private val onError: (Throwable) -> Unit = {}
) {
    companion object {
        private const val SOCKET_RECEIVE_BUFFER_BYTES = 512 * 1024
        private const val ENCRYPTION_OVERHEAD_BYTES = 64
    }

    private val expectedAddress = InetAddress.getByName(expectedHost)
    private val reassembler = DeadlineFrameReassembler(
        frameDeadlineMs = frameDeadlineMs,
        maxFrameBytes = DeadlineFrameReassembler.DEFAULT_MAX_FRAME_BYTES +
            ENCRYPTION_OVERHEAD_BYTES
    )
    private val running = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val threadName = "HubUdpVideoReceive-" + cameraName
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .take(32)

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var receiverThread: Thread? = null

    val localPort: Int get() = socket?.localPort ?: 0
    val isRunning: Boolean get() = running.get()

    /** The binder is used by Android to pin this socket to Wi-Fi or Ethernet. */
    fun start(socketBinder: ((DatagramSocket) -> Unit)? = null): Int =
        synchronized(lifecycleLock) {
            if (running.get()) return@synchronized localPort

            val newSocket = socketFactory().apply {
                reuseAddress = true
                receiveBufferSize = SOCKET_RECEIVE_BUFFER_BYTES
                soTimeout = frameDeadlineMs.coerceIn(10L, 1_000L).toInt()
            }
            try {
                socketBinder?.invoke(newSocket)
                if (!newSocket.isBound) newSocket.bind(InetSocketAddress(0))
            } catch (error: Throwable) {
                try { newSocket.close() } catch (_: Exception) {}
                throw error
            }

            socket = newSocket
            running.set(true)
            receiverThread = Thread({ receiveLoop(newSocket) }, threadName).apply {
                isDaemon = true
                start()
            }
            newSocket.localPort
        }

    fun stop() = synchronized(lifecycleLock) {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        receiverThread?.interrupt()
        receiverThread = null
        reassembler.clear()
    }

    private fun receiveLoop(activeSocket: DatagramSocket) {
        val buffer = ByteArray(RtpVideoPacketCodec.MAX_DATAGRAM_BYTES)
        val datagram = DatagramPacket(buffer, buffer.size)
        var packetsReceived = 0L
        var datagramBytesReceived = 0L
        var completedFrames = 0L
        var deadlineDroppedFrames = 0L
        var estimatedMissingPackets = 0L
        var malformedPackets = 0L
        var lastStatsAtMs = elapsedRealtimeMs()

        fun publishStats(nowMs: Long) {
            if (nowMs - lastStatsAtMs < statsIntervalMs) return
            lastStatsAtMs = nowMs
            onStats(
                UdpReceiverStats(
                    packetsReceived = packetsReceived,
                    datagramBytesReceived = datagramBytesReceived,
                    completedFrames = completedFrames,
                    deadlineDroppedFrames = deadlineDroppedFrames,
                    estimatedMissingPackets = estimatedMissingPackets,
                    malformedPackets = malformedPackets
                )
            )
        }

        fun reportExpiredAndStats(nowMs: Long) {
            val expired = reassembler.expireDetailed(nowMs)
            if (expired.frameCount > 0) {
                deadlineDroppedFrames += expired.frameCount
                estimatedMissingPackets += expired.missingFragmentCount
                onDroppedFrames(expired.frameCount)
            }
            publishStats(nowMs)
        }
        try {
            while (running.get()) {
                try {
                    datagram.length = buffer.size
                    activeSocket.receive(datagram)
                    val receivedAtElapsedMs = elapsedRealtimeMs()
                    reportExpiredAndStats(receivedAtElapsedMs)
                    if (!sourceMatches(datagram)) continue

                    packetsReceived++
                    datagramBytesReceived += datagram.length
                    onPacketBytes(datagram.length)
                    when (val result = reassembler.offer(
                        datagram = buffer,
                        length = datagram.length,
                        nowMs = receivedAtElapsedMs
                    )) {
                        is DeadlineFrameReassembler.OfferResult.Complete -> {
                            completedFrames++
                            onFrame(
                                result.frame.bytes,
                                System.currentTimeMillis(),
                                receivedAtElapsedMs
                            )
                        }
                        is DeadlineFrameReassembler.OfferResult.Dropped -> {
                            if (result.reason == DeadlineFrameReassembler.DropReason.MALFORMED_PACKET) {
                                malformedPackets++
                            }
                            if (result.reason == DeadlineFrameReassembler.DropReason.FRAME_TOO_LARGE ||
                                result.reason == DeadlineFrameReassembler.DropReason.CONFLICTING_METADATA ||
                                result.reason == DeadlineFrameReassembler.DropReason.DEADLINE_EXPIRED
                            ) {
                                onDroppedFrames(1)
                            }
                        }
                        DeadlineFrameReassembler.OfferResult.Accepted,
                        DeadlineFrameReassembler.OfferResult.Duplicate -> Unit
                    }
                } catch (_: SocketTimeoutException) {
                    reportExpiredAndStats(elapsedRealtimeMs())
                }
            }
        } catch (error: SocketException) {
            if (running.get()) onError(error)
        } catch (error: Throwable) {
            if (running.get()) onError(error)
        } finally {
            running.set(false)
            try { activeSocket.close() } catch (_: Exception) {}
        }
    }

    private fun sourceMatches(datagram: DatagramPacket): Boolean =
        datagram.address == expectedAddress &&
            (expectedSourcePort == 0 || datagram.port == expectedSourcePort)

    private fun elapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000L
}
