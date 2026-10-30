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

    /** Mutable parser output; owned by one reassembler thread and reused per datagram. */
    internal class ReusablePacketView {
        var rtpSequence: Int = 0
        var rtpTimestamp: Long = 0L
        var ssrc: Long = 0L
        var transportFrameId: Long = 0L
        var fragmentIndex: Int = 0
        var fragmentCount: Int = 0
        var totalFrameBytes: Int = 0
        var isMarker: Boolean = false
        var isKeyFrame: Boolean = false
        var isCodecConfig: Boolean = false
        var payload: ByteArray = ByteArray(0)

        fun toPacket(payload: ByteArray = this.payload) = Packet(
            rtpSequence = rtpSequence,
            rtpTimestamp = rtpTimestamp,
            ssrc = ssrc,
            transportFrameId = transportFrameId,
            fragmentIndex = fragmentIndex,
            fragmentCount = fragmentCount,
            totalFrameBytes = totalFrameBytes,
            isMarker = isMarker,
            isKeyFrame = isKeyFrame,
            isCodecConfig = isCodecConfig,
            payload = payload
        )
    }

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

    /**
     * Writes each packet into [scratch] and invokes [onPacket] synchronously.
     * The callback must consume the bytes before returning and must not retain
     * the scratch array, which is overwritten by the next fragment.
     */
    fun packetizeEach(
        frame: ByteArray,
        transportFrameId: Long,
        rtpTimestamp: Long,
        ssrc: Long,
        firstRtpSequence: Int,
        scratch: ByteArray,
        isKeyFrame: Boolean = false,
        isCodecConfig: Boolean = false,
        onPacket: (bytes: ByteArray, length: Int) -> Unit
    ): Int {
        require(frame.isNotEmpty()) { "Frame must not be empty" }
        require(transportFrameId >= 0L) { "Frame id must be non-negative" }
        require(scratch.size >= MAX_DATAGRAM_BYTES) { "Scratch buffer must hold a full datagram" }

        val fragmentCount = (frame.size + MAX_FRAGMENT_PAYLOAD_BYTES - 1) /
            MAX_FRAGMENT_PAYLOAD_BYTES
        require(fragmentCount <= 0xffff) { "Frame requires too many RTP fragments" }

        var flags = 0
        if (isKeyFrame) flags = flags or FLAG_KEY_FRAME
        if (isCodecConfig) flags = flags or FLAG_CODEC_CONFIG
        val buffer = ByteBuffer.wrap(scratch)

        for (fragmentIndex in 0 until fragmentCount) {
            val payloadOffset = fragmentIndex * MAX_FRAGMENT_PAYLOAD_BYTES
            val payloadSize = minOf(MAX_FRAGMENT_PAYLOAD_BYTES, frame.size - payloadOffset)
            val marker = fragmentIndex == fragmentCount - 1
            buffer.clear()
            buffer.put(RTP_VERSION_BITS.toByte())
            buffer.put((PAYLOAD_TYPE or if (marker) MARKER_BIT else 0).toByte())
            buffer.putShort(((firstRtpSequence + fragmentIndex) and 0xffff).toShort())
            buffer.putInt((rtpTimestamp and 0xffff_ffffL).toInt())
            buffer.putInt((ssrc and 0xffff_ffffL).toInt())
            buffer.put(MAGIC_HIGH.toByte())
            buffer.put(MAGIC_LOW.toByte())
            buffer.put(FORMAT_VERSION.toByte())
            buffer.put(flags.toByte())
            buffer.putLong(transportFrameId)
            buffer.putShort(fragmentIndex.toShort())
            buffer.putShort(fragmentCount.toShort())
            buffer.putInt(frame.size)
            buffer.put(frame, payloadOffset, payloadSize)
            onPacket(scratch, buffer.position())
        }
        return fragmentCount
    }

    /** The returned payload is owned by the caller and does not alias the datagram. */
    fun decode(datagram: ByteArray, length: Int = datagram.size): Packet? {
        val packet = decodeView(datagram, length) ?: return null
        return packet.copy(payload = packet.payload.copyOfRange(HEADER_BYTES, length))
    }

    /** Internal synchronous view; callers must not retain the reusable receive buffer. */
    internal fun decodeView(datagram: ByteArray, length: Int): Packet? {
        val view = ReusablePacketView()
        return if (decodeInto(datagram, length, view)) view.toPacket() else null
    }

    /** Parses into caller-owned reusable state without allocating per datagram. */
    internal fun decodeInto(
        datagram: ByteArray,
        length: Int,
        packet: ReusablePacketView
    ): Boolean {
        if (length !in HEADER_BYTES..minOf(datagram.size, MAX_DATAGRAM_BYTES)) return false
        val first = datagram[0].toInt() and 0xff
        val second = datagram[1].toInt() and 0xff
        if (first != RTP_VERSION_BITS || (second and 0x7f) != PAYLOAD_TYPE) return false

        val rtpSequence = readUInt16(datagram, 2)
        val rtpTimestamp = readUInt32(datagram, 4)
        val ssrc = readUInt32(datagram, 8)
        if ((datagram[12].toInt() and 0xff) != MAGIC_HIGH) return false
        if ((datagram[13].toInt() and 0xff) != MAGIC_LOW) return false
        if ((datagram[14].toInt() and 0xff) != FORMAT_VERSION) return false

        val flags = datagram[15].toInt() and 0xff
        val transportFrameId = readInt64(datagram, 16)
        val fragmentIndex = readUInt16(datagram, 24)
        val fragmentCount = readUInt16(datagram, 26)
        val totalFrameBytes = readInt32(datagram, 28)
        val payloadSize = length - HEADER_BYTES

        if (transportFrameId < 0L || fragmentCount <= 0 || fragmentIndex >= fragmentCount) return false
        if (totalFrameBytes <= 0 || payloadSize <= 0 || payloadSize > MAX_FRAGMENT_PAYLOAD_BYTES) return false
        if (totalFrameBytes < payloadSize) return false
        val expectedFragments = (totalFrameBytes.toLong() + MAX_FRAGMENT_PAYLOAD_BYTES - 1) /
            MAX_FRAGMENT_PAYLOAD_BYTES
        if (fragmentCount.toLong() != expectedFragments) return false
        val expectedPayloadSize = if (fragmentIndex == fragmentCount - 1) {
            totalFrameBytes - fragmentIndex * MAX_FRAGMENT_PAYLOAD_BYTES
        } else {
            MAX_FRAGMENT_PAYLOAD_BYTES
        }
        if (payloadSize != expectedPayloadSize) return false
        val marker = (second and MARKER_BIT) != 0
        if (marker != (fragmentIndex == fragmentCount - 1)) return false

        packet.rtpSequence = rtpSequence
        packet.rtpTimestamp = rtpTimestamp
        packet.ssrc = ssrc
        packet.transportFrameId = transportFrameId
        packet.fragmentIndex = fragmentIndex
        packet.fragmentCount = fragmentCount
        packet.totalFrameBytes = totalFrameBytes
        packet.isMarker = marker
        packet.isKeyFrame = (flags and FLAG_KEY_FRAME) != 0
        packet.isCodecConfig = (flags and FLAG_CODEC_CONFIG) != 0
        packet.payload = datagram
        return true
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or
            (data[offset + 1].toInt() and 0xff)

    private fun readUInt32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xffL) shl 24) or
            ((data[offset + 1].toLong() and 0xffL) shl 16) or
            ((data[offset + 2].toLong() and 0xffL) shl 8) or
            (data[offset + 3].toLong() and 0xffL)

    private fun readInt32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)

    private fun readInt64(data: ByteArray, offset: Int): Long =
        (readInt32(data, offset).toLong() shl 32) or
            (readInt32(data, offset + 4).toLong() and 0xffff_ffffL)
}
