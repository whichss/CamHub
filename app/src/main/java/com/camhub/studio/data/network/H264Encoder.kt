package com.camhub.studio.data.network

import android.media.MediaCodec
import android.media.MediaCodecInfo
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
    private val bitrate: Int = 4_000_000,
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
                        cachedSpsPps = spsBytes + ppsBytes
                        Log.d(TAG, "SPS/PPS cached (async): ${cachedSpsPps!!.size} bytes")

                        val frame = EncodedFrame(
                            data = cachedSpsPps!!,
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
            setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            )
            setInteger(
                MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
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
                        cachedSpsPps = spsBytes + ppsBytes
                        Log.d(TAG, "SPS/PPS cached: ${cachedSpsPps!!.size} bytes")
                        frames.add(
                            EncodedFrame(
                                data = cachedSpsPps!!,
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
