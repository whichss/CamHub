package com.camhub.studio.data.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wrapper around MediaCodec for Opus encoding.
 *
 * Input:  PCM 16-bit mono samples at 48 kHz
 * Output: Opus compressed frames
 *
 * Frame size: 20 ms = 960 samples at 48 kHz
 * Bitrate:    64 kbps
 *
 * Opus encoding via MediaCodec requires API 29+.
 */
class OpusEncoder {

    companion object {
        private const val TAG = "OpusEncoder"
        private const val MIME_OPUS = MediaFormat.MIMETYPE_AUDIO_OPUS
        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 1
        private const val BITRATE = 64_000
        const val FRAME_SIZE = 960  // 20ms at 48kHz
        private const val TIMEOUT_US = 10_000L

        /** Returns true if this device supports Opus encoding via MediaCodec. */
        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        /**
         * Resample PCM 16-bit mono from 44 100 Hz to 48 000 Hz using linear interpolation.
         *
         * @param input   source samples at 44 100 Hz
         * @param inCount number of valid samples in [input]
         * @return resampled [ShortArray] at 48 000 Hz
         */
        fun resample44100to48000(input: ShortArray, inCount: Int): ShortArray {
            val ratio = 44100.0 / 48000.0
            val outCount = (inCount / ratio).toInt()
            val output = ShortArray(outCount)
            for (i in 0 until outCount) {
                val srcPos = i * ratio
                val srcIdx = srcPos.toInt()
                val frac = (srcPos - srcIdx).toFloat()
                val s0 = input[srcIdx.coerceAtMost(inCount - 1)].toInt()
                val s1 = input[(srcIdx + 1).coerceAtMost(inCount - 1)].toInt()
                output[i] = (s0 + frac * (s1 - s0)).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
            return output
        }
    }

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    /**
     * Initialise the encoder. Returns true on success.
     * Call [release] when done.
     */
    fun start(): Boolean {
        if (!isSupported()) {
            Log.w(TAG, "Opus encoding not supported (requires API 29+)")
            return false
        }
        return try {
            val format = MediaFormat.createAudioFormat(MIME_OPUS, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            }
            codec = MediaCodec.createEncoderByType(MIME_OPUS).also { c ->
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                c.start()
            }
            Log.d(TAG, "Opus encoder started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Opus encoder", e)
            codec = null
            false
        }
    }

    /**
     * Encode a buffer of PCM 16-bit mono samples (48 kHz).
     * [samples] must contain exactly [FRAME_SIZE] entries.
     *
     * @return the Opus-compressed frame, or null on failure.
     */
    fun encode(samples: ShortArray): ByteArray? {
        val c = codec ?: return null

        // --- Feed input ---
        val inIdx = c.dequeueInputBuffer(TIMEOUT_US)
        if (inIdx < 0) {
            Log.w(TAG, "No input buffer available")
            return null
        }
        val inBuf = c.getInputBuffer(inIdx) ?: return null
        inBuf.clear()
        val pcmBytes = ByteBuffer.allocate(samples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { asShortBuffer().put(samples) }
            .array()
        inBuf.put(pcmBytes)
        c.queueInputBuffer(inIdx, 0, pcmBytes.size, 0, 0)

        // --- Drain output ---
        val outIdx = c.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
        if (outIdx < 0) return null
        val outBuf = c.getOutputBuffer(outIdx) ?: run {
            c.releaseOutputBuffer(outIdx, false)
            return null
        }
        val encoded = ByteArray(bufferInfo.size)
        outBuf.position(bufferInfo.offset)
        outBuf.limit(bufferInfo.offset + bufferInfo.size)
        outBuf.get(encoded)
        c.releaseOutputBuffer(outIdx, false)
        return encoded
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
        Log.d(TAG, "Opus encoder released")
    }
}
