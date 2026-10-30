package com.camhub.desktop

import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVCodecHWConfig
import org.bytedeco.ffmpeg.avutil.AVBufferRef
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer

/**
 * Low-delay H.264 decoding with a platform hardware device when FFmpeg supports it.
 * Hardware frames are transferred to host memory before the existing Java2D pipeline.
 * FFmpeg may select its software pixel format if device session creation fails.
 */
internal class FfmpegLowLatencyDecoder(
    forceSoftware: Boolean = false
) : AutoCloseable {
    private val codec = avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_H264)
        ?: error("Bundled FFmpeg has no H.264 decoder")
    private var context = avcodec.avcodec_alloc_context3(codec)
        ?: error("Could not allocate H.264 decoder context")
    private val packet = avcodec.av_packet_alloc()
        ?: error("Could not allocate H.264 packet")
    private val decodedFrame = avutil.av_frame_alloc()
        ?: error("Could not allocate H.264 decoded frame")
    private val transferredFrame = avutil.av_frame_alloc()
        ?: error("Could not allocate H.264 transfer frame")
    private var hardwareDevice: AVBufferRef? = null
    private var hardwarePixelFormat: Int? = null
    private var formatSelector: AVCodecContext.Get_format_AVCodecContext_IntPointer? = null
    private var scaleContext: SwsContext? = null
    private var scaleInput: Triple<Int, Int, Int>? = null
    private var outputPointer: BytePointer? = null
    private var closed = false

    val usingHardwareFrames: Boolean
        get() = hardwarePixelFormat != null && lastOutputUsedHardware

    val activePathLabel: String
        get() = when {
            !usingHardwareFrames -> "software decode"
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "VideoToolbox decode"
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "D3D11VA decode"
            else -> "hardware decode"
        }

    @Volatile
    private var lastOutputUsedHardware = false

    init {
        configureBaseContext(context)
        if (!forceSoftware) configureHardwareDevice()
        var opened = avcodec.avcodec_open2(context, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?)
        if (opened < 0 && hardwarePixelFormat != null) {
            createSoftwareFallbackContext()
            opened = 0
        }
        check(opened >= 0) { "Could not open H.264 decoder (FFmpeg error $opened)" }
    }

    /** Decode one H.264 access unit and synchronously process frames emitted by FFmpeg. */
    fun decode(accessUnit: ByteArray, pts: Long, isKeyFrame: Boolean, onFrame: (AVFrame) -> Unit) {
        check(!closed) { "Decoder is closed" }
        if (accessUnit.isEmpty()) return

        avcodec.av_packet_unref(packet)
        val allocated = avcodec.av_new_packet(packet, accessUnit.size)
        check(allocated >= 0) { "Could not allocate H.264 packet data (FFmpeg error $allocated)" }
        packet.data().put(accessUnit, 0, accessUnit.size)
        packet.pts(pts)
        packet.dts(pts)
        if (isKeyFrame) packet.flags(packet.flags() or avcodec.AV_PKT_FLAG_KEY)

        var transferFailure: HardwareFrameTransferException? = null
        try {
            var sent = avcodec.avcodec_send_packet(context, packet)
            if (sent == FFMPEG_EAGAIN) {
                receiveAvailableFrames(onFrame)
                sent = avcodec.avcodec_send_packet(context, packet)
            }
            check(sent >= 0) {
                "Could not submit H.264 access unit (FFmpeg error $sent)"
            }
            receiveAvailableFrames(onFrame)
        } catch (error: HardwareFrameTransferException) {
            transferFailure = error
        } finally {
            avcodec.av_packet_unref(packet)
        }
        transferFailure?.let { error ->
            if (hardwarePixelFormat == null) throw error
            createSoftwareFallbackContext()
            if (!isKeyFrame) throw SoftwareFallbackNeedsKeyFrame
            decode(accessUnit, pts, isKeyFrame, onFrame)
        }
    }

    fun flush() {
        if (!closed) avcodec.avcodec_flush_buffers(context)
    }

    private fun receiveAvailableFrames(onFrame: (AVFrame) -> Unit) {
        while (true) {
            avutil.av_frame_unref(decodedFrame)
            val received = avcodec.avcodec_receive_frame(context, decodedFrame)
            if (received == FFMPEG_EAGAIN || received == avutil.AVERROR_EOF) break
            check(received >= 0) { "Could not receive H.264 frame (FFmpeg error $received)" }

            val hardwareFormat = hardwarePixelFormat
            val source = if (hardwareFormat != null && decodedFrame.format() == hardwareFormat) {
                avutil.av_frame_unref(transferredFrame)
                val transferred = avutil.av_hwframe_transfer_data(transferredFrame, decodedFrame, 0)
                if (transferred < 0) throw HardwareFrameTransferException(transferred)
                lastOutputUsedHardware = true
                transferredFrame
            } else {
                lastOutputUsedHardware = false
                decodedFrame
            }
            onFrame(source)
        }
    }

    fun toBufferedImage(source: AVFrame, previousImage: BufferedImage?): BufferedImage {
        val width = source.width()
        val height = source.height()
        val inputFormat = source.format()
        require(width > 0 && height > 0) { "Decoder returned invalid frame size ${width}x$height" }
        val inputKey = Triple(width, height, inputFormat)
        if (scaleInput != inputKey) {
            scaleContext?.let(swscale::sws_freeContext)
            scaleContext = swscale.sws_getContext(
                width,
                height,
                inputFormat,
                width,
                height,
                avutil.AV_PIX_FMT_BGR24,
                swscale.SWS_FAST_BILINEAR,
                null,
                null,
                null as org.bytedeco.javacpp.DoublePointer?
            ) ?: error("Could not create FFmpeg pixel converter")
            scaleInput = inputKey
        }

        val image = previousImage?.takeIf { it.width == width && it.height == height }
            ?: BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
        val pixels = (image.raster.dataBuffer as DataBufferByte).data
        if (outputPointer == null || outputPointer!!.capacity() < pixels.size.toLong()) {
            outputPointer?.close()
            outputPointer = BytePointer(pixels.size.toLong()).zero()
        }
        val activeOutputPointer = checkNotNull(outputPointer)
        val destination = PointerPointer<BytePointer>(4).put(0, activeOutputPointer)
        val destinationStride = IntPointer(4).put(0, width * 3).put(1, 0).put(2, 0).put(3, 0)
        val convertedRows = try {
            swscale.sws_scale(
                scaleContext,
                source.data(),
                source.linesize(),
                0,
                height,
                destination,
                destinationStride
            )
        } finally {
            destination.close()
            destinationStride.close()
        }
        check(convertedRows == height) { "FFmpeg converted $convertedRows of $height frame rows" }
        activeOutputPointer.position(0).get(pixels)
        return image
    }

    private fun configureHardwareDevice() {
        val deviceType = when {
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true) ->
                avutil.AV_HWDEVICE_TYPE_VIDEOTOOLBOX
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) ->
                avutil.AV_HWDEVICE_TYPE_D3D11VA
            else -> return
        }
        var supportedConfig: AVCodecHWConfig? = null
        var index = 0
        while (true) {
            val config = avcodec.avcodec_get_hw_config(codec, index++) ?: break
            if (
                config.device_type() == deviceType &&
                config.methods() and avcodec.AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX != 0
            ) {
                supportedConfig = config
                break
            }
        }
        val config = supportedConfig ?: return

        val device = AVBufferRef()
        val created = avutil.av_hwdevice_ctx_create(device, deviceType, null as BytePointer?, null, 0)
        if (created < 0) {
            device.close()
            return
        }
        context.hw_device_ctx(avutil.av_buffer_ref(device))
        hardwareDevice = device
        hardwarePixelFormat = config.pix_fmt()
        val expectedHardwareFormat = config.pix_fmt()
        formatSelector = object : AVCodecContext.Get_format_AVCodecContext_IntPointer() {
            override fun call(codecContext: AVCodecContext, formats: IntPointer): Int {
                var index = 0
                while (true) {
                    val format = formats.get(index++.toLong())
                    if (format == avutil.AV_PIX_FMT_NONE) break
                    if (format == expectedHardwareFormat) return format
                }
                return formats.get(0L)
            }
        }
        context.get_format(formatSelector)
    }

    private fun createSoftwareFallbackContext() {
        avutil.av_frame_unref(decodedFrame)
        avutil.av_frame_unref(transferredFrame)
        avcodec.avcodec_free_context(context)
        hardwareDevice?.let { avutil.av_buffer_unref(it) }
        hardwareDevice = null
        hardwarePixelFormat = null
        formatSelector = null
        context = avcodec.avcodec_alloc_context3(codec)
            ?: error("Could not allocate software H.264 decoder context")
        configureBaseContext(context)
        val opened = avcodec.avcodec_open2(context, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?)
        check(opened >= 0) { "Could not open software H.264 decoder (FFmpeg error $opened)" }
        lastOutputUsedHardware = false
    }

    private fun configureBaseContext(target: AVCodecContext) {
        target.thread_count(1)
        target.flags(target.flags() or avcodec.AV_CODEC_FLAG_LOW_DELAY)
    }

    override fun close() {
        if (closed) return
        closed = true
        scaleContext?.let(swscale::sws_freeContext)
        scaleContext = null
        outputPointer?.close()
        outputPointer = null
        avcodec.av_packet_free(packet)
        avutil.av_frame_free(decodedFrame)
        avutil.av_frame_free(transferredFrame)
        avcodec.avcodec_free_context(context)
        hardwareDevice?.let(avutil::av_buffer_unref)
        hardwareDevice = null
        formatSelector = null
    }

    companion object {
        // AVERROR(EAGAIN) is platform-dependent: Darwin uses errno 35; Linux and Windows use 11.
        private val FFMPEG_EAGAIN =
            if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) -35 else -11
    }

    private class HardwareFrameTransferException(code: Int) :
        IllegalStateException("Could not transfer hardware-decoded frame (FFmpeg error $code)")

    internal data object SoftwareFallbackNeedsKeyFrame : RuntimeException(
        "Hardware frame transfer failed; waiting for the next keyframe on the software decoder"
    )
}
