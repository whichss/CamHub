package com.camhub.studio.data.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wrapper around MediaCodec for Opus decoding.
 *
 * Input:  Opus compressed frames
 * Output: PCM 16-bit mono at 48 kHz
 *
 * Opus decoding via MediaCodec requires API 29+.
 */
class OpusDecoder {

    companion object {
        private const val TAG = "OpusDecoder"
        private const val MIME_OPUS = MediaFormat.MIMETYPE_AUDIO_OPUS
        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 1
        private const val TIMEOUT_US = 10_000L

        /** Returns true if this device supports Opus decoding via MediaCodec. */
        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    /**
     * Initialise the decoder. Returns true on success.
     * Call [release] when done.
     */
    fun start(): Boolean {
        if (!isSupported()) {
            Log.w(TAG, "Opus decoding not supported (requires API 29+)")
            return false
        }
        return try {
            val format = MediaFormat.createAudioFormat(MIME_OPUS, SAMPLE_RATE, CHANNELS).apply {
                // CSD buffers required by some MediaCodec implementations for Opus.
                // CSD-0: Opus identification header (minimal)
                val csd0 = ByteBuffer.wrap(
                    byteArrayOf(
                        // OpusHead: "OpusHead" magic
                        'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
                        'H'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(),
                        // Version
                        1,
                        // Channel count
                        CHANNELS.toByte(),
                        // Pre-skip (little-endian uint16) - 3840 samples typical
                        0x00, 0x0F.toByte(),
                        // Input sample rate (little-endian uint32) - 48000
                        0x80.toByte(), 0xBB.toByte(), 0x00, 0x00,
                        // Output gain (little-endian int16)
                        0x00, 0x00,
                        // Channel mapping family
                        0x00
                    )
                )
                // CSD-1: Seek pre-roll (nanoseconds, little-endian int64) - 80ms
                val csd1 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
                    .putLong(80_000_000L)
                csd1.flip()
                // CSD-2: Seek pre-roll for codec (same as CSD-1 for most implementations)
                setByteBuffer("csd-0", csd0)
                setByteBuffer("csd-1", csd1)
                setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0L).also { it.flip() })
            }
            codec = MediaCodec.createDecoderByType(MIME_OPUS).also { c ->
                c.configure(format, null, null, 0)
                c.start()
            }
            Log.d(TAG, "Opus decoder started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Opus decoder", e)
            codec = null
            false
        }
    }

    /**
     * Decode a single Opus frame.
     *
     * @param opusData the compressed Opus frame bytes
     * @return decoded PCM 16-bit mono samples at 48 kHz, or null on failure
     */
    fun decode(opusData: ByteArray): ShortArray? {
        val c = codec ?: return null

        // --- Feed input ---
        val inIdx = c.dequeueInputBuffer(TIMEOUT_US)
        if (inIdx < 0) {
            Log.w(TAG, "No input buffer available")
            return null
        }
        val inBuf = c.getInputBuffer(inIdx) ?: return null
        inBuf.clear()
        inBuf.put(opusData)
        c.queueInputBuffer(inIdx, 0, opusData.size, 0, 0)

        // --- Drain output ---
        val outIdx = c.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
        if (outIdx < 0) return null
        val outBuf = c.getOutputBuffer(outIdx) ?: run {
            c.releaseOutputBuffer(outIdx, false)
            return null
        }
        outBuf.position(bufferInfo.offset)
        outBuf.limit(bufferInfo.offset + bufferInfo.size)
        val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(shortBuf.remaining())
        shortBuf.get(samples)
        c.releaseOutputBuffer(outIdx, false)
        return samples
    }

    /** Stop and release the underlying MediaCodec. Safe to call multiple times. */
    fun release() {
        try {
            codec?.stop()
        } catch (_: Exception) {}
        try {
            codec?.release()
        } catch (_: Exception) {}
        codec = null
        Log.d(TAG, "Opus decoder released")
    }
}
