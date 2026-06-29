package com.camhub.studio.data.network

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

data class EncodedFrame(
    val data: ByteArray,
    val isKeyFrame: Boolean,
    val isConfig: Boolean,
    val presentationTimeUs: Long
)

class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int = 6_000_000,
    private val frameRate: Int = 30,
    private val iFrameInterval: Int = 1
) {

    companion object {
        private const val TAG = "H264Encoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val TIMEOUT_US = 0L
    }

    private var encoder: MediaCodec? = null
    private var surfaceMode = false
    private var asyncMode = false
    private var callbackThread: HandlerThread? = null

    var cachedSpsPps: ByteArray? = null
        private set

    var inputSurface: Surface? = null
        private set

    /** Async callback for surface mode — called on codec thread when output is available */
    var onEncodedFrame: ((EncodedFrame) -> Unit)? = null

    @Suppress("DEPRECATION")
    fun start(): Boolean {
        return try {
            val format = createFormat(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            )

            encoder = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            surfaceMode = false
            asyncMode = false
            Log.d(TAG, "Encoder started (buffer mode): ${width}x${height} @ ${bitrate / 1000}kbps")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start encoder", e)
            encoder = null
            false
        }
    }

    fun startSurface(): Boolean {
        return try {
            val format = createFormat(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )

            val codec = MediaCodec.createEncoderByType(MIME_TYPE)

            // Set async callback before configure for zero-latency output
            val thread = HandlerThread("EncoderCallback").also { it.start() }
            callbackThread = thread
            val handler = Handler(thread.looper)

            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                    // Surface mode — input is fed via Surface, not buffers
                }

                override fun onOutputBufferAvailable(
                    mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo
                ) {
                    try {
                        if (info.size > 0) {
                            val outputBuffer = mc.getOutputBuffer(index)
                            if (outputBuffer != null) {
                                val data = ByteArray(info.size)
                                outputBuffer.position(info.offset)
                                outputBuffer.limit(info.offset + info.size)
                                outputBuffer.get(data)

                                val isKeyFrame =
                                    (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                val isConfig =
                                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

                                if (isConfig) {
                                    cachedSpsPps = data
                                    Log.d(TAG, "SPS/PPS from async callback: ${data.size} bytes")
                                }

                                val frame = EncodedFrame(
                                    data = data,
                                    isKeyFrame = isKeyFrame,
                                    isConfig = isConfig,
                                    presentationTimeUs = info.presentationTimeUs
                                )
                                onEncodedFrame?.invoke(frame)
                            }
                        }
                        mc.releaseOutputBuffer(index, false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Async output error", e)
                    }
                }

                override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                    val sps = format.getByteBuffer("csd-0")
                    val pps = format.getByteBuffer("csd-1")
                    if (sps != null && pps != null) {
                        val spsBytes = ByteArray(sps.remaining()).also { sps.get(it) }
                        val ppsBytes = ByteArray(pps.remaining()).also { pps.get(it) }
                        val spsPpsData = spsBytes + ppsBytes
                        cachedSpsPps = spsPpsData
                        Log.d(TAG, "SPS/PPS cached (async): ${spsPpsData.size} bytes")

                        val frame = EncodedFrame(
                            data = spsPpsData,
                            isKeyFrame = false,
                            isConfig = true,
                            presentationTimeUs = 0
                        )
                        onEncodedFrame?.invoke(frame)
                    }
                }

                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "Async encoder error", e)
                }
            }, handler)

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            encoder = codec
            surfaceMode = true
            asyncMode = true
            Log.d(TAG, "Encoder started (surface async mode): ${width}x${height} @ ${bitrate / 1000}kbps")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start surface encoder", e)
            inputSurface = null
            encoder = null
            callbackThread?.quitSafely()
            callbackThread = null
            false
        }
    }

    private fun createFormat(colorFormat: Int): MediaFormat {
        return MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )
            val profile = preferredAvcProfile()
            setInteger(MediaFormat.KEY_PROFILE, profile)
            setInteger(MediaFormat.KEY_LEVEL, preferredAvcLevel(profile))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
            // Helps late-joining viewers and packet-loss recovery by making sync frames
            // self-contained on encoders that support this vendor/public key.
            setInteger("prepend-sps-pps-to-idr-frames", 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                // Intra refresh: spread keyframe data across multiple frames
                // for smoother bitrate distribution and faster error recovery
                try {
                    setInteger("intra-refresh-period", frameRate / 2)
                } catch (_: Exception) { /* not all encoders support this */ }
            }
        }
    }

    private fun preferredAvcProfile(): Int {
        return if (isAvcProfileSupported(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)) {
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        } else {
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        }
    }

    private fun preferredAvcLevel(profile: Int): Int {
        if (profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) {
            return MediaCodecInfo.CodecProfileLevel.AVCLevel31
        }
        return if (frameRate > 30) {
            MediaCodecInfo.CodecProfileLevel.AVCLevel42
        } else {
            MediaCodecInfo.CodecProfileLevel.AVCLevel4
        }
    }

    private fun isAvcProfileSupported(profile: Int): Boolean {
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filter { it.isEncoder }
                .filter { info -> info.supportedTypes.any { it.equals(MIME_TYPE, ignoreCase = true) } }
                .flatMap { it.getCapabilitiesForType(MIME_TYPE).profileLevels.asSequence() }
                .any { it.profile == profile }
        } catch (_: Exception) {
            false
        }
    }

    fun encode(nv12: ByteArray, presentationTimeUs: Long): List<EncodedFrame> {
        val enc = encoder ?: return emptyList()
        val frames = mutableListOf<EncodedFrame>()

        try {
            // Queue input
            val inputIndex = enc.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = enc.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    val size = minOf(nv12.size, inputBuffer.capacity())
                    inputBuffer.put(nv12, 0, size)
                    enc.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
                }
            }

            // Drain output
            drainOutputTo(frames)
        } catch (e: Exception) {
            Log.e(TAG, "Encode error", e)
        }

        return frames
    }

    fun drainOutput(): List<EncodedFrame> {
        if (asyncMode) return emptyList() // async mode uses callback
        val frames = mutableListOf<EncodedFrame>()
        try {
            drainOutputTo(frames)
        } catch (e: Exception) {
            Log.e(TAG, "drainOutput error", e)
        }
        return frames
    }

    private fun drainOutputTo(frames: MutableList<EncodedFrame>) {
        val enc = encoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = enc.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = enc.outputFormat
                    val sps = newFormat.getByteBuffer("csd-0")
                    val pps = newFormat.getByteBuffer("csd-1")
                    if (sps != null && pps != null) {
                        val spsBytes = ByteArray(sps.remaining()).also { sps.get(it) }
                        val ppsBytes = ByteArray(pps.remaining()).also { pps.get(it) }
                        val spsPpsData = spsBytes + ppsBytes
                        cachedSpsPps = spsPpsData
                        Log.d(TAG, "SPS/PPS cached: ${spsPpsData.size} bytes")
                        frames.add(
                            EncodedFrame(
                                data = spsPpsData,
                                isKeyFrame = false,
                                isConfig = true,
                                presentationTimeUs = 0
                            )
                        )
                    }
                }
                outputIndex >= 0 -> {
                    val outputBuffer = enc.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer.get(data)

                        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

                        if (isConfig) {
                            cachedSpsPps = data
                            Log.d(TAG, "SPS/PPS from buffer flag: ${data.size} bytes")
                        }

                        frames.add(
                            EncodedFrame(
                                data = data,
                                isKeyFrame = isKeyFrame,
                                isConfig = isConfig,
                                presentationTimeUs = bufferInfo.presentationTimeUs
                            )
                        )
                    }
                    enc.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    }

    fun requestKeyFrame() {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            encoder?.setParameters(params)
            Log.d(TAG, "Keyframe requested")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request keyframe: ${e.message}")
        }
    }

    fun setBitrate(bitrate: Int): Boolean {
        if (bitrate <= 0) return false
        return try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate)
            }
            encoder?.setParameters(params)
            Log.d(TAG, "Encoder bitrate updated: ${bitrate / 1000}kbps")
            requestKeyFrame()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update encoder bitrate: ${e.message}")
            false
        }
    }

    fun release() {
        onEncodedFrame = null
        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        inputSurface = null
        try {
            encoder?.stop()
        } catch (_: Exception) {}
        try {
            encoder?.release()
        } catch (_: Exception) {}
        encoder = null
        callbackThread?.quitSafely()
        callbackThread = null
        cachedSpsPps = null
        surfaceMode = false
        asyncMode = false
        Log.d(TAG, "Encoder released")
    }
}
