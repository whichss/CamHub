package com.camhub.desktop

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter

/**
 * Low-delay H.264 encoder for desktop captures. Callers should invoke it from a
 * dedicated encoder thread; the screen-capture thread must only publish images.
 * Each returned byte array is one Annex-B access unit suitable for the existing
 * CamHub video payload and RTP packetizer.
 */
class DesktopScreenH264Encoder(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val framesPerSecond: Int = 30,
    private val bitrateBitsPerSecond: Int = 4_000_000
) : AutoCloseable {
    data class EncodedAccessUnit(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val isKeyFrame: Boolean,
        val captureAtWallMs: Long,
        val encodedAtWallMs: Long,
        val sequence: Long
    )

    private class FrameOutputStream : OutputStream() {
        private val bytes = ByteArrayOutputStream(128 * 1024)

        override fun write(value: Int) = bytes.write(value)
        override fun write(source: ByteArray, offset: Int, length: Int) =
            bytes.write(source, offset, length)

        fun drain(): ByteArray = bytes.toByteArray().also { bytes.reset() }
    }

    private val outputWidth = width.also { require(it >= 16 && it % 2 == 0) }
    private val outputHeight = height.also { require(it >= 16 && it % 2 == 0) }
    private val outputFrameRate = framesPerSecond.also { require(it in 1..30) }
    private val outputBitrate = bitrateBitsPerSecond.also { require(it in 250_000..20_000_000) }
    private val output = FrameOutputStream()
    private val converter = Java2DFrameConverter()
    private val sequence = AtomicLong(0L)
    private val forceNextKeyFrame = AtomicBoolean(true)
    private val canvas = BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_3BYTE_BGR)
    private val recorder = createRecorder()
    val selectedCodecName: String = recorder.videoCodecName

    @Synchronized
    fun encode(image: BufferedImage, captureAtWallMs: Long): EncodedAccessUnit? {
        val graphics = canvas.createGraphics()
        try {
            graphics.color = Color.BLACK
            graphics.fillRect(0, 0, width, height)
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            DesktopVideoPainter.draw(
                graphics = graphics,
                frame = DesktopVideoFrame(image),
                targetWidth = width,
                targetHeight = height,
                highQualitySpatialScale = false
            )
        } finally {
            graphics.dispose()
        }

        val nextSequence = sequence.incrementAndGet()
        val frame = converter.convert(canvas) ?: return null
        // Hardware encoders may drop frames whose presentation timestamp stays at zero.
        // JavaCV does not consistently assign monotonically increasing PTS values to
        // converted Java2D frames, so provide the stream timestamp explicitly.
        frame.timestamp = ((nextSequence - 1L) * 1_000_000L) / outputFrameRate
        val requestedKeyFrame = forceNextKeyFrame.getAndSet(false)
        frame.keyFrame = requestedKeyFrame || nextSequence % outputFrameRate == 0L
        recorder.record(frame)
        val bytes = output.drain()
        if (bytes.isEmpty()) {
            // Rate control may skip this frame; keep recovery pending.
            if (requestedKeyFrame) forceNextKeyFrame.set(true)
            return null
        }
        if (requestedKeyFrame && !containsIdrNal(bytes)) forceNextKeyFrame.set(true)
        return EncodedAccessUnit(
            bytes = bytes,
            width = outputWidth,
            height = outputHeight,
            isKeyFrame = containsIdrNal(bytes),
            captureAtWallMs = captureAtWallMs,
            encodedAtWallMs = System.currentTimeMillis(),
            sequence = nextSequence
        )
    }

    /** Requests an IDR at the next frame boundary, e.g. after a sender queue drop. */
    fun requestKeyFrame() {
        forceNextKeyFrame.set(true)
    }

    private fun createRecorder(): FFmpegFrameRecorder {
        // Try the platform encoder first, but retain the software encoders as a
        // compatibility path. The selected encoder still has to produce the
        // same Annex-B access units and keyframe headers used by CamHub RTP.
        val candidates = listOf("libx264", "libopenh264")
        var lastError: Throwable? = null
        Loader.load(avcodec::class.java)
        for (encoderProfile in desktopEncoderCandidates() + candidates.map(::DesktopEncoderCandidate)) {
            val codec = encoderProfile.name
            // JavaCV otherwise silently falls back to a different H.264 encoder,
            // leaving that encoder with options intended for the requested one.
            val availableEncoder = avcodec.avcodec_find_encoder_by_name(codec)
            if (availableEncoder == null || availableEncoder.isNull) continue
            // Some platform encoders initialize successfully but then buffer/drop
            // subsequent frames or omit parameter sets. Probe the actual Annex-B
            // contract before preferring one for a live RTP stream.
            if (codec in setOf("h264_videotoolbox", "h264_mf")) {
                val probeKey = "$codec:$outputWidth:$outputHeight:$outputFrameRate:$outputBitrate"
                val supportsLiveOutput = hardwareProbeCache.computeIfAbsent(probeKey) {
                    supportsLiveAccessUnitOutput(encoderProfile)
                }
                if (!supportsLiveOutput) continue
            }
            val recorderCandidate = configureRecorder(output, encoderProfile)
            try {
                recorderCandidate.start()
                // Container/header bytes emitted at start are not an access unit.
                output.drain()
                return recorderCandidate
            } catch (error: Throwable) {
                lastError = error
                runCatching { recorderCandidate.release() }
                output.drain()
            }
        }
        throw IllegalStateException("No supported low-delay H.264 encoder is available", lastError)
    }

    private data class DesktopEncoderCandidate(
        val name: String,
        val pixelFormat: Int? = null,
        val options: Map<String, String> = emptyMap()
    )

    private fun configureRecorder(
        stream: OutputStream,
        profile: DesktopEncoderCandidate
    ): FFmpegFrameRecorder = FFmpegFrameRecorder(stream, outputWidth, outputHeight).apply {
        setCloseOutputStream(false)
        format = "h264"
        videoCodecName = profile.name
        pixelFormat = profile.pixelFormat ?: avutil.AV_PIX_FMT_YUV420P
        videoBitrate = outputBitrate
        frameRate = outputFrameRate.toDouble()
        gopSize = outputFrameRate
        maxBFrames = 0
        profile.options.forEach { (name, value) -> setVideoOption(name, value) }
        if (profile.name == "libx264") {
            setVideoOption("preset", "ultrafast")
            setVideoOption("tune", "zerolatency")
            setVideoOption(
                "x264-params",
                "aud=1:repeat-headers=1:keyint=$outputFrameRate:min-keyint=$outputFrameRate:scenecut=0:rc-lookahead=0"
            )
        } else if (profile.name == "libopenh264") {
            // OpenH264 defaults to quality-based rate control, which ignores the
            // requested network bitrate. Select bitrate control explicitly and allow
            // frame skipping only when required to stay within that transport budget.
            setVideoOption("rc_mode", "1")
            setVideoOption("allow_skip_frames", "1")
        }
    }

    private fun supportsLiveAccessUnitOutput(profile: DesktopEncoderCandidate): Boolean {
        val probeOutput = FrameOutputStream()
        val probeConverter = Java2DFrameConverter()
        val probeCanvas = BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_3BYTE_BGR)
        val probeRecorder = configureRecorder(probeOutput, profile)
        return try {
            probeRecorder.start()
            probeOutput.drain()
            repeat(2) { index ->
                val graphics = probeCanvas.createGraphics()
                try {
                    graphics.color = if (index == 0) Color(22, 111, 203) else Color(203, 81, 22)
                    graphics.fillRect(0, 0, outputWidth, outputHeight)
                } finally {
                    graphics.dispose()
                }
                val frame = probeConverter.convert(probeCanvas) ?: return false
                frame.timestamp = (index * 1_000_000L) / outputFrameRate
                frame.keyFrame = index == 0
                probeRecorder.record(frame)
                val accessUnit = probeOutput.drain()
                if (accessUnit.isEmpty()) return false
                if (index == 0 && (!containsIdrNal(accessUnit) || !containsNalType(accessUnit, 7) ||
                    !containsNalType(accessUnit, 8))
                ) return false
            }
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { probeRecorder.stop() }
            runCatching { probeRecorder.release() }
            runCatching { probeConverter.close() }
            probeOutput.drain()
        }
    }

    private fun desktopEncoderCandidates(): List<DesktopEncoderCandidate> {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") -> listOf(
                DesktopEncoderCandidate(
                    name = "h264_videotoolbox",
                    pixelFormat = avutil.AV_PIX_FMT_NV12,
                    options = mapOf("realtime" to "1")
                )
            )
            os.contains("win") -> listOf(
                DesktopEncoderCandidate(
                    name = "h264_mf",
                    pixelFormat = avutil.AV_PIX_FMT_NV12,
                    options = mapOf(
                        "hw_encoding" to "1",
                        "scenario" to "live_streaming",
                        "rate_control" to "ld_vbr"
                    )
                )
            )
            else -> emptyList()
        }
    }

    @Synchronized
    override fun close() {
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
        converter.close()
    }

    private fun containsIdrNal(bytes: ByteArray): Boolean {
        return containsNalType(bytes, 5)
    }

    private fun containsNalType(bytes: ByteArray, targetType: Int): Boolean {
        var index = 0
        while (index + 4 < bytes.size) {
            val startCodeSize = when {
                bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() &&
                    bytes[index + 2] == 1.toByte() -> 3
                bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() &&
                    bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte() -> 4
                else -> {
                    index++
                    continue
                }
            }
            val nalIndex = index + startCodeSize
            if (nalIndex < bytes.size && (bytes[nalIndex].toInt() and 0x1f) == targetType) return true
            index = nalIndex
        }
        return false
    }

    private companion object {
        // Hardware capability probing encodes two frames, so do it only once for
        // each process/device stream configuration rather than on every reconnect.
        val hardwareProbeCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    }
}
