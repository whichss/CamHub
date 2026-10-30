package com.camhub.desktop

import com.camhub.studio.data.network.CamHubVideoPayload
import com.camhub.studio.data.network.CamHubVideoPayloadCodec
import com.camhub.studio.data.network.DeadlineFrameReassembler
import com.camhub.studio.data.network.FrameCipher
import com.camhub.studio.data.network.IpAddressMatcher
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
    private val onError: (Throwable) -> Unit,
    private val onDroppedFrames: (Int) -> Unit = {}
) : AutoCloseable {
    private val expectedAddress = InetAddress.getByName(expectedHost)
    private val cipher = FrameCipher(sessionKey)
    private val reassembler = DeadlineFrameReassembler()
    private val running = AtomicBoolean(false)
    private var closed = false
    private var started = false
    private val threadName = "DesktopUdpReceive-" + cameraName
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .take(30)

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var thread: Thread? = null

    val localPort: Int get() = socket?.localPort ?: 0

    @Synchronized
    fun start(): Int {
        check(!closed) { "A closed video receiver cannot be restarted" }
        if (running.get()) return localPort
        check(!started) { "A stopped video receiver cannot be restarted" }
        running.set(true)
        var activeSocket: DatagramSocket? = null
        try {
            activeSocket = DatagramSocket(null)
            activeSocket.reuseAddress = true
            activeSocket.receiveBufferSize = 512 * 1_024
            activeSocket.soTimeout = 35
            activeSocket.bind(InetSocketAddress(0))
            val boundSocket = activeSocket
            socket = boundSocket
            thread = Thread({ receiveLoop(boundSocket) }, threadName).apply {
                isDaemon = true
                start()
            }
            started = true
            return boundSocket.localPort
        } catch (error: Throwable) {
            running.set(false)
            activeSocket?.close()
            socket = null
            thread = null
            throw error
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        running.set(false)
        socket?.close()
        socket = null
        thread?.interrupt()
        thread = null
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
            if (expiration.frameCount > 0) onDroppedFrames(expiration.frameCount)
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
                    if (!IpAddressMatcher.matches(expectedAddress, datagram.address)) continue
                    if (expectedSourcePort != 0 && datagram.port != expectedSourcePort) continue
                    packets++

                    val result = reassembler.offer(buffer, now, datagram.length)
                    val capacityDrops = reassembler.takeCapacityDropStats()
                    if (capacityDrops.frameCount > 0) {
                        dropped += capacityDrops.frameCount
                        missing += capacityDrops.missingFragmentCount
                        onDroppedFrames(capacityDrops.frameCount)
                    }
                    when (result) {
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
            // The receive worker owns the reassembler; clear it only after it stops using it.
            reassembler.clear()
        }
    }

    private fun elapsedMs(): Long = System.nanoTime() / 1_000_000L
}
