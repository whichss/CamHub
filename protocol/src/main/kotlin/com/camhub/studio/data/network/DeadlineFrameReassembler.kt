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
        const val DEFAULT_MAX_PENDING_FRAMES = 8
        const val DEFAULT_MAX_FRAME_BYTES = 2 * 1024 * 1024
        private const val MAX_TERMINAL_FRAME_IDS = 64
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

    sealed interface OfferResult {
        data object Accepted : OfferResult
        data object Duplicate : OfferResult
        data class Complete(val frame: CompletedFrame) : OfferResult
        data class Dropped(val reason: DropReason) : OfferResult
    }

    private data class PendingFrame(
        val packet: RtpVideoPacketCodec.Packet,
        val firstPacketAtMs: Long,
        val fragments: Array<ByteArray?> = arrayOfNulls(packet.fragmentCount),
        var receivedFragments: Int = 0,
        var receivedBytes: Int = 0
    )

    private val pending = LinkedHashMap<Long, PendingFrame>()
    private val terminalFrameIds = LinkedHashSet<Long>()

    fun offer(
        datagram: ByteArray,
        nowMs: Long,
        length: Int = datagram.size
    ): OfferResult {
        expire(nowMs)
        val packet = RtpVideoPacketCodec.decode(datagram, length)
            ?: return OfferResult.Dropped(DropReason.MALFORMED_PACKET)
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
                pending.remove(oldestFrameId)
                rememberTerminal(oldestFrameId)
            }
            assembly = PendingFrame(packet = packet, firstPacketAtMs = nowMs)
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
        if (assembly.fragments[packet.fragmentIndex] != null) return OfferResult.Duplicate

        assembly.fragments[packet.fragmentIndex] = packet.payload
        assembly.receivedFragments++
        assembly.receivedBytes += packet.payload.size
        if (assembly.receivedFragments != packet.fragmentCount) return OfferResult.Accepted
        if (assembly.receivedBytes != packet.totalFrameBytes) {
            pending.remove(packet.transportFrameId)
            rememberTerminal(packet.transportFrameId)
            return OfferResult.Dropped(DropReason.CONFLICTING_METADATA)
        }

        val frameBytes = ByteArray(packet.totalFrameBytes)
        var offset = 0
        for (fragment in assembly.fragments) {
            val bytes = fragment ?: return OfferResult.Accepted
            bytes.copyInto(frameBytes, destinationOffset = offset)
            offset += bytes.size
        }
        pending.remove(packet.transportFrameId)
        rememberTerminal(packet.transportFrameId)
        return OfferResult.Complete(
            CompletedFrame(
                bytes = frameBytes,
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
        val expired = pending
            .filterValues { nowMs - it.firstPacketAtMs >= frameDeadlineMs }
            .toList()
        var missingFragments = 0
        expired.forEach { (frameId, assembly) ->
            missingFragments += assembly.packet.fragmentCount - assembly.receivedFragments
            pending.remove(frameId)
            rememberTerminal(frameId)
        }
        return ExpirationStats(
            frameCount = expired.size,
            missingFragmentCount = missingFragments
        )
    }

    fun clear() {
        pending.clear()
        terminalFrameIds.clear()
    }

    fun pendingFrameCount(): Int = pending.size

    private fun metadataMatches(
        first: RtpVideoPacketCodec.Packet,
        next: RtpVideoPacketCodec.Packet
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
