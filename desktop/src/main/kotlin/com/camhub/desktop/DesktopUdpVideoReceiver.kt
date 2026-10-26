package com.camhub.desktop

import com.camhub.studio.data.network.CamHubVideoPayload
import com.camhub.studio.data.network.CamHubVideoPayloadCodec
import com.camhub.studio.data.network.DeadlineFrameReassembler
import com.camhub.studio.data.network.FrameCipher
import com.camhub.studio.data.network.RtpVideoPacketCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

data class DesktopUdpStats(
    val packetsReceived: Long,
    val completedFrames: Long,
    val droppedFrames: Long,
    val missingPackets: Long,
    val malformedFrames: Long
) {
    val packetLossPercent: Float
        get() {
            val expected = packetsReceived + missingPackets
            return if (expected == 0L) 0f else missingPackets * 100f / expected
        }
}

class DesktopUdpVideoReceiver(
    cameraName: String,
    expectedHost: String,
    private val expectedSourcePort: Int,
    sessionKey: ByteArray,
    private val onFrame: (CamHubVideoPayload) -> Unit,
    private val onStats: (DesktopUdpStats) -> Unit,
    private val onError: (Throwable) -> Unit
) : AutoCloseable {
    private val expectedAddress = InetAddress.getByName(expectedHost)
    private val cipher = FrameCipher(sessionKey)
    private val reassembler = DeadlineFrameReassembler()
    private val running = AtomicBoolean(false)
    private val threadName = "DesktopUdpReceive-" + cameraName
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .take(30)

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var thread: Thread? = null

    val localPort: Int get() = socket?.localPort ?: 0

    fun start(): Int {
        if (!running.compareAndSet(false, true)) return localPort
        val activeSocket = DatagramSocket(null).apply {
            reuseAddress = true
            receiveBufferSize = 512 * 1_024
            soTimeout = 35
            bind(InetSocketAddress(0))
        }
        socket = activeSocket
        thread = Thread({ receiveLoop(activeSocket) }, threadName).apply {
            isDaemon = true
            start()
        }
        return activeSocket.localPort
    }

    override fun close() {
        running.set(false)
        socket?.close()
        socket = null
        thread?.interrupt()
        thread = null
        reassembler.clear()
    }

    private fun receiveLoop(activeSocket: DatagramSocket) {
        val buffer = ByteArray(RtpVideoPacketCodec.MAX_DATAGRAM_BYTES)
        var packets = 0L
        var completed = 0L
        var dropped = 0L
        var missing = 0L
        var malformed = 0L
        var lastStatsAt = elapsedMs()

        fun expireAndPublish(now: Long) {
            val expiration = reassembler.expireDetailed(now)
            dropped += expiration.frameCount
            missing += expiration.missingFragmentCount
            if (now - lastStatsAt >= 1_000L) {
                lastStatsAt = now
                onStats(DesktopUdpStats(packets, completed, dropped, missing, malformed))
            }
        }

        try {
            while (running.get()) {
                try {
                    val datagram = DatagramPacket(buffer, buffer.size)
                    activeSocket.receive(datagram)
                    val now = elapsedMs()
                    expireAndPublish(now)
                    if (datagram.address != expectedAddress) continue
                    if (expectedSourcePort != 0 && datagram.port != expectedSourcePort) continue
                    packets++

                    when (val result = reassembler.offer(buffer, now, datagram.length)) {
                        is DeadlineFrameReassembler.OfferResult.Complete -> {
                            val payload = try {
                                CamHubVideoPayloadCodec.decode(cipher.decrypt(result.frame.bytes))
                            } catch (_: Exception) {
                                null
                            }
                            if (payload == null) {
                                malformed++
                            } else {
                                completed++
                                onFrame(payload)
                            }
                        }
                        is DeadlineFrameReassembler.OfferResult.Dropped -> {
                            if (result.reason == DeadlineFrameReassembler.DropReason.MALFORMED_PACKET) {
                                malformed++
                            }
                        }
                        DeadlineFrameReassembler.OfferResult.Accepted,
                        DeadlineFrameReassembler.OfferResult.Duplicate -> Unit
                    }
                } catch (_: SocketTimeoutException) {
                    expireAndPublish(elapsedMs())
                }
            }
        } catch (error: SocketException) {
            if (running.get()) onError(error)
        } catch (error: Throwable) {
            if (running.get()) onError(error)
        } finally {
            running.set(false)
            activeSocket.close()
        }
    }

    private fun elapsedMs(): Long = System.nanoTime() / 1_000_000L
}
