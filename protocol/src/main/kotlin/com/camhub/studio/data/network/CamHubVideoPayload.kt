package com.camhub.studio.data.network

/** Metadata and H.264 access-unit payload carried inside an encrypted CamHub frame. */
data class CamHubVideoPayload(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val isKeyFrame: Boolean,
    val isCodecConfig: Boolean,
    val frameSequence: Long,
    val captureAtWallMs: Long?,
    val encodedAtWallMs: Long?,
    val h264Bytes: ByteArray
)

object CamHubVideoPayloadCodec {
    private const val VERSION_2_HEADER_BYTES = 7
    private const val VERSION_3_HEADER_BYTES = 15
    private const val VERSION_4_HEADER_BYTES = 31
    private const val FLAG_KEY_FRAME = 0x01
    private const val FLAG_CODEC_CONFIG = 0x02
    private const val MAX_DIMENSION = 16_384

    fun decode(bytes: ByteArray): CamHubVideoPayload? {
        if (bytes.size < VERSION_2_HEADER_BYTES) return null

        val version = bytes[0].toInt() and 0xff
        val headerSize = when (version) {
            2 -> VERSION_2_HEADER_BYTES
            3 -> VERSION_3_HEADER_BYTES
            4 -> VERSION_4_HEADER_BYTES
            else -> return null
        }
        if (bytes.size <= headerSize) return null

        val width = readUInt16(bytes, 1)
        val height = readUInt16(bytes, 3)
        val rotation = (bytes[5].toInt() and 0xff) * 90
        val flags = bytes[6].toInt() and 0xff
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) return null
        if (rotation !in setOf(0, 90, 180, 270)) return null

        return CamHubVideoPayload(
            width = width,
            height = height,
            rotationDegrees = rotation,
            isKeyFrame = flags and FLAG_KEY_FRAME != 0,
            isCodecConfig = flags and FLAG_CODEC_CONFIG != 0,
            frameSequence = if (version >= 4) readInt64(bytes, 7) else 0L,
            captureAtWallMs = if (version >= 4) readInt64(bytes, 15) else null,
            encodedAtWallMs = when (version) {
                3 -> readInt64(bytes, 7)
                4 -> readInt64(bytes, 23)
                else -> null
            },
            h264Bytes = bytes.copyOfRange(headerSize, bytes.size)
        )
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)

    private fun readInt64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(Long.SIZE_BYTES) { index ->
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xffL)
        }
        return value
    }
}
