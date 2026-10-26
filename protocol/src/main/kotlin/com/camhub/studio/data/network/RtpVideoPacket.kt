package com.camhub.studio.data.network

import java.nio.ByteBuffer

/**
 * RTP packetization for one encoded CamHub video access unit.
 *
 * The first 12 bytes are a normal RTP v2 header using dynamic payload type 96.
 * A small CamHub fragmentation header follows it so a receiver can discard an
 * incomplete access unit at its playout deadline without requesting retransmission.
 * Datagrams are capped at 1,200 bytes to avoid IP fragmentation on typical Wi-Fi
 * and Ethernet paths, including networks with a smaller effective MTU.
 */
object RtpVideoPacketCodec {
    const val MAX_DATAGRAM_BYTES = 1_200
    const val RTP_HEADER_BYTES = 12
    const val FRAGMENT_HEADER_BYTES = 20
    const val HEADER_BYTES = RTP_HEADER_BYTES + FRAGMENT_HEADER_BYTES
    const val MAX_FRAGMENT_PAYLOAD_BYTES = MAX_DATAGRAM_BYTES - HEADER_BYTES

    private const val RTP_VERSION_BITS = 0x80
    private const val PAYLOAD_TYPE = 96
    private const val MARKER_BIT = 0x80
    private const val MAGIC_HIGH = 0x43 // C
    private const val MAGIC_LOW = 0x48 // H
    private const val FORMAT_VERSION = 1
    private const val FLAG_KEY_FRAME = 0x01
    private const val FLAG_CODEC_CONFIG = 0x02

    data class Packet(
        val rtpSequence: Int,
        val rtpTimestamp: Long,
        val ssrc: Long,
        val transportFrameId: Long,
        val fragmentIndex: Int,
        val fragmentCount: Int,
        val totalFrameBytes: Int,
        val isMarker: Boolean,
        val isKeyFrame: Boolean,
        val isCodecConfig: Boolean,
        val payload: ByteArray
    )

    fun packetize(
        frame: ByteArray,
        transportFrameId: Long,
        rtpTimestamp: Long,
        ssrc: Long,
        firstRtpSequence: Int,
        isKeyFrame: Boolean = false,
        isCodecConfig: Boolean = false
    ): List<ByteArray> {
        require(frame.isNotEmpty()) { "Frame must not be empty" }
        require(transportFrameId >= 0L) { "Frame id must be non-negative" }

        val fragmentCount = (frame.size + MAX_FRAGMENT_PAYLOAD_BYTES - 1) /
            MAX_FRAGMENT_PAYLOAD_BYTES
        require(fragmentCount <= 0xffff) { "Frame requires too many RTP fragments" }

        var flags = 0
        if (isKeyFrame) flags = flags or FLAG_KEY_FRAME
        if (isCodecConfig) flags = flags or FLAG_CODEC_CONFIG

        return List(fragmentCount) { fragmentIndex ->
            val payloadOffset = fragmentIndex * MAX_FRAGMENT_PAYLOAD_BYTES
            val payloadSize = minOf(MAX_FRAGMENT_PAYLOAD_BYTES, frame.size - payloadOffset)
            val marker = fragmentIndex == fragmentCount - 1
            ByteBuffer.allocate(HEADER_BYTES + payloadSize).apply {
                put(RTP_VERSION_BITS.toByte())
                put((PAYLOAD_TYPE or if (marker) MARKER_BIT else 0).toByte())
                putShort(((firstRtpSequence + fragmentIndex) and 0xffff).toShort())
                putInt((rtpTimestamp and 0xffff_ffffL).toInt())
                putInt((ssrc and 0xffff_ffffL).toInt())
                put(MAGIC_HIGH.toByte())
                put(MAGIC_LOW.toByte())
                put(FORMAT_VERSION.toByte())
                put(flags.toByte())
                putLong(transportFrameId)
                putShort(fragmentIndex.toShort())
                putShort(fragmentCount.toShort())
                putInt(frame.size)
                put(frame, payloadOffset, payloadSize)
            }.array()
        }
    }

    fun decode(datagram: ByteArray, length: Int = datagram.size): Packet? {
        if (length !in HEADER_BYTES..minOf(datagram.size, MAX_DATAGRAM_BYTES)) return null
        val buffer = ByteBuffer.wrap(datagram, 0, length)
        val first = buffer.get().toInt() and 0xff
        val second = buffer.get().toInt() and 0xff
        if (first != RTP_VERSION_BITS || (second and 0x7f) != PAYLOAD_TYPE) return null

        val rtpSequence = buffer.short.toInt() and 0xffff
        val rtpTimestamp = buffer.int.toLong() and 0xffff_ffffL
        val ssrc = buffer.int.toLong() and 0xffff_ffffL
        if ((buffer.get().toInt() and 0xff) != MAGIC_HIGH) return null
        if ((buffer.get().toInt() and 0xff) != MAGIC_LOW) return null
        if ((buffer.get().toInt() and 0xff) != FORMAT_VERSION) return null

        val flags = buffer.get().toInt() and 0xff
        val transportFrameId = buffer.long
        val fragmentIndex = buffer.short.toInt() and 0xffff
        val fragmentCount = buffer.short.toInt() and 0xffff
        val totalFrameBytes = buffer.int
        val payloadSize = length - HEADER_BYTES

        if (transportFrameId < 0L || fragmentCount <= 0 || fragmentIndex >= fragmentCount) return null
        if (totalFrameBytes <= 0 || payloadSize <= 0 || payloadSize > MAX_FRAGMENT_PAYLOAD_BYTES) return null
        if (totalFrameBytes < payloadSize) return null
        val expectedFragments = (totalFrameBytes + MAX_FRAGMENT_PAYLOAD_BYTES - 1) /
            MAX_FRAGMENT_PAYLOAD_BYTES
        if (fragmentCount != expectedFragments) return null
        val expectedPayloadSize = if (fragmentIndex == fragmentCount - 1) {
            totalFrameBytes - fragmentIndex * MAX_FRAGMENT_PAYLOAD_BYTES
        } else {
            MAX_FRAGMENT_PAYLOAD_BYTES
        }
        if (payloadSize != expectedPayloadSize) return null
        val marker = (second and MARKER_BIT) != 0
        if (marker != (fragmentIndex == fragmentCount - 1)) return null

        return Packet(
            rtpSequence = rtpSequence,
            rtpTimestamp = rtpTimestamp,
            ssrc = ssrc,
            transportFrameId = transportFrameId,
            fragmentIndex = fragmentIndex,
            fragmentCount = fragmentCount,
            totalFrameBytes = totalFrameBytes,
            isMarker = marker,
            isKeyFrame = (flags and FLAG_KEY_FRAME) != 0,
            isCodecConfig = (flags and FLAG_CODEC_CONFIG) != 0,
            payload = ByteArray(payloadSize).also { buffer.get(it) }
        )
    }
}
