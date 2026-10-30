package com.camhub.studio.data.network

import java.util.LinkedHashSet

/**
 * Reassembles out-of-order RTP fragments while keeping latency bounded.
 *
 * Incomplete frames are forgotten after [frameDeadlineMs]. Late fragments are
 * intentionally ignored rather than retransmitted. One instance is intended for
 * one camera stream and can therefore remain lock-free on its receiver coroutine.
 */
class DeadlineFrameReassembler(
    private val frameDeadlineMs: Long = DEFAULT_FRAME_DEADLINE_MS,
    private val maxPendingFrames: Int = DEFAULT_MAX_PENDING_FRAMES,
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES
) {
    companion object {
        const val DEFAULT_FRAME_DEADLINE_MS = 35L
        // At 30 fps with a 35 ms deadline, four slots cover normal frame overlap
        // plus jitter while halving the worst-case assembly memory per camera.
        const val DEFAULT_MAX_PENDING_FRAMES = 4
        const val DEFAULT_MAX_FRAME_BYTES = 2 * 1024 * 1024
        private const val MAX_TERMINAL_FRAME_IDS = 64
        private val EMPTY_PAYLOAD = ByteArray(0)
    }

    init {
        require(frameDeadlineMs > 0L)
        require(maxPendingFrames > 0)
        require(maxFrameBytes > 0)
    }

    enum class DropReason {
        MALFORMED_PACKET,
        FRAME_TOO_LARGE,
        DEADLINE_EXPIRED,
        ALREADY_FINISHED,
        CONFLICTING_METADATA,
        PENDING_LIMIT
    }

    data class CompletedFrame(
        val bytes: ByteArray,
        val transportFrameId: Long,
        val rtpTimestamp: Long,
        val ssrc: Long,
        val isKeyFrame: Boolean,
        val isCodecConfig: Boolean,
        val firstPacketAtMs: Long,
        val completedAtMs: Long
    )

    data class ExpirationStats(
        val frameCount: Int,
        val missingFragmentCount: Int
    )

    data class CapacityDropStats(
        val frameCount: Int,
        val missingFragmentCount: Int
    )

    sealed interface OfferResult {
        data object Accepted : OfferResult
        data object Duplicate : OfferResult
        data class Complete(val frame: CompletedFrame) : OfferResult
        data class Dropped(val reason: DropReason) : OfferResult
    }

    private data class PendingFrame(
        val packet: RtpVideoPacketCodec.Packet,
        val firstPacketAtMs: Long,
        val bytes: ByteArray = ByteArray(packet.totalFrameBytes),
        val received: BooleanArray = BooleanArray(packet.fragmentCount),
        var receivedFragments: Int = 0,
        var receivedBytes: Int = 0
    )

    private val pending = LinkedHashMap<Long, PendingFrame>()
    private val terminalFrameIds = LinkedHashSet<Long>()
    private val packetScratch = RtpVideoPacketCodec.ReusablePacketView()
    private var capacityDroppedFrames = 0
    private var capacityDroppedFragments = 0

    fun offer(
        datagram: ByteArray,
        nowMs: Long,
        length: Int = datagram.size
    ): OfferResult {
        expire(nowMs)
        if (!RtpVideoPacketCodec.decodeInto(datagram, length, packetScratch)) {
            return OfferResult.Dropped(DropReason.MALFORMED_PACKET)
        }
        val packet = packetScratch
        if (packet.totalFrameBytes > maxFrameBytes) {
            rememberTerminal(packet.transportFrameId)
            return OfferResult.Dropped(DropReason.FRAME_TOO_LARGE)
        }
        if (packet.transportFrameId in terminalFrameIds) {
            return OfferResult.Dropped(DropReason.ALREADY_FINISHED)
        }

        var assembly = pending[packet.transportFrameId]
        if (assembly == null) {
            if (pending.size >= maxPendingFrames) {
                val oldestFrameId = pending.entries.first().key
                pending.remove(oldestFrameId)?.let { evicted ->
                    capacityDroppedFrames++
                    capacityDroppedFragments += evicted.packet.fragmentCount - evicted.receivedFragments
                }
                rememberTerminal(oldestFrameId)
            }
            // Retain metadata only, never the receiver's reusable datagram buffer.
            assembly = PendingFrame(packet = packet.toPacket(EMPTY_PAYLOAD), firstPacketAtMs = nowMs)
            pending[packet.transportFrameId] = assembly
        } else if (!metadataMatches(assembly.packet, packet)) {
            pending.remove(packet.transportFrameId)
            rememberTerminal(packet.transportFrameId)
            return OfferResult.Dropped(DropReason.CONFLICTING_METADATA)
        }

        if (nowMs - assembly.firstPacketAtMs >= frameDeadlineMs) {
            pending.remove(packet.transportFrameId)
            rememberTerminal(packet.transportFrameId)
            return OfferResult.Dropped(DropReason.DEADLINE_EXPIRED)
        }
        if (assembly.received[packet.fragmentIndex]) return OfferResult.Duplicate

        val payloadSize = length - RtpVideoPacketCodec.HEADER_BYTES
        val offset = packet.fragmentIndex * RtpVideoPacketCodec.MAX_FRAGMENT_PAYLOAD_BYTES
        datagram.copyInto(assembly.bytes, offset, RtpVideoPacketCodec.HEADER_BYTES, length)
        assembly.received[packet.fragmentIndex] = true
        assembly.receivedFragments++
        assembly.receivedBytes += payloadSize
        if (assembly.receivedFragments != packet.fragmentCount) return OfferResult.Accepted
        if (assembly.receivedBytes != packet.totalFrameBytes) {
            pending.remove(packet.transportFrameId)
            rememberTerminal(packet.transportFrameId)
            return OfferResult.Dropped(DropReason.CONFLICTING_METADATA)
        }

        pending.remove(packet.transportFrameId)
        rememberTerminal(packet.transportFrameId)
        return OfferResult.Complete(
            CompletedFrame(
                bytes = assembly.bytes,
                transportFrameId = packet.transportFrameId,
                rtpTimestamp = packet.rtpTimestamp,
                ssrc = packet.ssrc,
                isKeyFrame = packet.isKeyFrame,
                isCodecConfig = packet.isCodecConfig,
                firstPacketAtMs = assembly.firstPacketAtMs,
                completedAtMs = nowMs
            )
        )
    }

    /** Returns the number of incomplete frames discarded at this deadline check. */
    fun expire(nowMs: Long): Int = expireDetailed(nowMs).frameCount

    fun expireDetailed(nowMs: Long): ExpirationStats {
        var expiredFrames = 0
        var missingFragments = 0
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val (frameId, assembly) = iterator.next()
            if (nowMs - assembly.firstPacketAtMs >= frameDeadlineMs) {
                missingFragments += assembly.packet.fragmentCount - assembly.receivedFragments
                expiredFrames++
                iterator.remove()
                rememberTerminal(frameId)
            }
        }
        return ExpirationStats(
            frameCount = expiredFrames,
            missingFragmentCount = missingFragments
        )
    }

    fun clear() {
        pending.clear()
        terminalFrameIds.clear()
        capacityDroppedFrames = 0
        capacityDroppedFragments = 0
    }

    fun pendingFrameCount(): Int = pending.size

    /** Returns and clears partial frames evicted to enforce [maxPendingFrames]. */
    fun takeCapacityDropStats(): CapacityDropStats {
        val result = CapacityDropStats(capacityDroppedFrames, capacityDroppedFragments)
        capacityDroppedFrames = 0
        capacityDroppedFragments = 0
        return result
    }

    private fun metadataMatches(
        first: RtpVideoPacketCodec.Packet,
        next: RtpVideoPacketCodec.ReusablePacketView
    ): Boolean = first.transportFrameId == next.transportFrameId &&
        first.rtpTimestamp == next.rtpTimestamp &&
        first.ssrc == next.ssrc &&
        first.fragmentCount == next.fragmentCount &&
        first.totalFrameBytes == next.totalFrameBytes &&
        first.isKeyFrame == next.isKeyFrame &&
        first.isCodecConfig == next.isCodecConfig

    private fun rememberTerminal(frameId: Long) {
        terminalFrameIds.add(frameId)
        while (terminalFrameIds.size > MAX_TERMINAL_FRAME_IDS) {
            val oldest = terminalFrameIds.first()
            terminalFrameIds.remove(oldest)
        }
    }
}
