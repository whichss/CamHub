package com.camhub.studio.data.network

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

class H264Decoder {

    companion object {
        private const val TAG = "H264Decoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val TIMEOUT_US = 1_000L
    }

    private var decoder: MediaCodec? = null
    private var width: Int = 0
    private var height: Int = 0
    private var stride: Int = 0
    private var sliceHeight: Int = 0
    private var configured = false
    private var usingSurface = false

    // Double-buffered bitmaps to avoid UI/decode thread contention
    private var bitmapA: Bitmap? = null
    private var bitmapB: Bitmap? = null
    private var useA = true

    fun configure(width: Int, height: Int, spsPps: ByteArray): Boolean {
        release()
        this.width = width
        this.height = height

        return try {
            // Split SPS/PPS from concatenated data
            val spsSize = findSecondNalStart(spsPps)
            if (spsSize < 0) {
                Log.e(TAG, "Failed to split SPS/PPS")
                return false
            }

            val sps = ByteBuffer.wrap(spsPps, 0, spsSize)
            val pps = ByteBuffer.wrap(spsPps, spsSize, spsPps.size - spsSize)

            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setByteBuffer("csd-0", sps)
                setByteBuffer("csd-1", pps)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                )
                // Low-latency hint
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }

            decoder = MediaCodec.createDecoderByType(MIME_TYPE).apply {
                configure(format, null, null, 0)
                start()
            }

            stride = width
            sliceHeight = height
            bitmapA = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmapB = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            configured = true
            usingSurface = false
            Log.d(TAG, "Decoder configured (buffer mode): ${width}x${height}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure decoder", e)
            release()
            false
        }
    }

    fun configureSurface(width: Int, height: Int, spsPps: ByteArray, surface: Surface): Boolean {
        release()
        this.width = width
        this.height = height

        return try {
            val spsSize = findSecondNalStart(spsPps)
            if (spsSize < 0) {
                Log.e(TAG, "Failed to split SPS/PPS")
                return false
            }

            val sps = ByteBuffer.wrap(spsPps, 0, spsSize)
            val pps = ByteBuffer.wrap(spsPps, spsSize, spsPps.size - spsSize)

            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setByteBuffer("csd-0", sps)
                setByteBuffer("csd-1", pps)
                // Low-latency hint
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }

            decoder = MediaCodec.createDecoderByType(MIME_TYPE).apply {
                configure(format, surface, null, 0)
                start()
            }

            configured = true
            usingSurface = true
            Log.d(TAG, "Decoder configured (surface mode): ${width}x${height}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure surface decoder", e)
            release()
            false
        }
    }

    fun decode(nalUnit: ByteArray, isKeyFrame: Boolean): Bitmap? {
        val dec = decoder ?: return null
        if (!configured) return null

        try {
            // Queue input
            val inputIndex = dec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = dec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    val size = minOf(nalUnit.size, inputBuffer.capacity())
                    inputBuffer.put(nalUnit, 0, size)
                    val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    dec.queueInputBuffer(inputIndex, 0, size, 0, flags)
                }
            }

            // Drain output
            val bufferInfo = MediaCodec.BufferInfo()
            var resultBitmap: Bitmap? = null

            while (true) {
                val outputIndex = dec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = dec.outputFormat
                        val newW = newFormat.getInteger(MediaFormat.KEY_WIDTH)
                        val newH = newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                        stride = try { newFormat.getInteger(MediaFormat.KEY_STRIDE) } catch (_: Exception) { newW }
                        sliceHeight = try { newFormat.getInteger(MediaFormat.KEY_SLICE_HEIGHT) } catch (_: Exception) { newH }
                        if (stride <= 0) stride = newW
                        if (sliceHeight <= 0) sliceHeight = newH
                        Log.d(TAG, "Output format: ${newW}x${newH}, stride=$stride, sliceHeight=$sliceHeight")
                        if (newW != width || newH != height) {
                            width = newW
                            height = newH
                            bitmapA = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmapB = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        }
                    }
                    outputIndex >= 0 -> {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = dec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null) {
                                val targetBitmap = if (useA) bitmapA else bitmapB
                                if (targetBitmap != null) {
                                    nv12ToBitmap(outputBuffer, bufferInfo.offset, targetBitmap)
                                    resultBitmap = targetBitmap
                                    useA = !useA
                                }
                            }
                        }
                        dec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            return resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Decode error", e)
            return null
        }
    }

    fun decodeSurface(nalUnit: ByteArray, isKeyFrame: Boolean) {
        val dec = decoder ?: return
        if (!configured || !usingSurface) return

        try {
            // Queue input
            val inputIndex = dec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = dec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    val size = minOf(nalUnit.size, inputBuffer.capacity())
                    inputBuffer.put(nalUnit, 0, size)
                    val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    dec.queueInputBuffer(inputIndex, 0, size, 0, flags)
                }
            }

            // Drain output — render to Surface
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val outputIndex = dec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = dec.outputFormat
                        Log.d(TAG, "Surface output format: ${newFormat.getInteger(MediaFormat.KEY_WIDTH)}x${newFormat.getInteger(MediaFormat.KEY_HEIGHT)}")
                    }
                    outputIndex >= 0 -> {
                        // true = render to Surface
                        dec.releaseOutputBuffer(outputIndex, true)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeSurface error", e)
        }
    }

    /**
     * Convert NV12 (YUV420SP) to ARGB_8888 Bitmap using BT.601 integer math.
     * Handles stride/sliceHeight alignment from MediaCodec output.
     */
    private fun nv12ToBitmap(buffer: ByteBuffer, offset: Int, bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val actualStride = if (stride > 0) stride else w
        val actualSliceH = if (sliceHeight > 0) sliceHeight else h
        val yPlaneSize = actualStride * actualSliceH
        val totalSize = yPlaneSize * 3 / 2
        val pixels = IntArray(w * h)

        buffer.position(offset)
        val yuvData = ByteArray(minOf(buffer.remaining(), totalSize))
        buffer.get(yuvData)

        for (j in 0 until h) {
            val yRowOffset = j * actualStride
            val uvRowOffset = yPlaneSize + (j shr 1) * actualStride
            for (i in 0 until w) {
                val yIdx = yRowOffset + i
                val y = if (yIdx < yuvData.size) (yuvData[yIdx].toInt() and 0xFF) - 16 else 0
                val uvIdx = uvRowOffset + (i and 0xFFFFFFFE.toInt())
                val u: Int
                val v: Int
                if (uvIdx + 1 < yuvData.size) {
                    u = (yuvData[uvIdx].toInt() and 0xFF) - 128
                    v = (yuvData[uvIdx + 1].toInt() and 0xFF) - 128
                } else {
                    u = 0
                    v = 0
                }

                val yy = 298 * y
                var r = (yy + 409 * v + 128) shr 8
                var g = (yy - 100 * u - 208 * v + 128) shr 8
                var b = (yy + 516 * u + 128) shr 8

                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)

                pixels[j * w + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    /**
     * Find the start of the second NAL unit in concatenated SPS+PPS data.
     * Looks for the 0x00 0x00 0x00 0x01 start code after the first one.
     */
    private fun findSecondNalStart(data: ByteArray): Int {
        // Skip initial start code
        var i = if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            data[2] == 0.toByte() && data[3] == 1.toByte()
        ) 4
        else if (data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            data[2] == 1.toByte()
        ) 3
        else return -1

        while (i < data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) return i
                if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) return i
            }
            i++
        }
        return -1
    }

    fun release() {
        configured = false
        usingSurface = false
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
        bitmapA?.recycle()
        bitmapB?.recycle()
        bitmapA = null
        bitmapB = null
        Log.d(TAG, "Decoder released")
    }
}
