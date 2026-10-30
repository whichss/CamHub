package com.camhub.desktop

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter

enum class DesktopRecordingState { IDLE, STARTING, RECORDING, STOPPING, FAILED }

data class DesktopRecordingSnapshot(
    val state: DesktopRecordingState,
    val file: Path? = null,
    val framesWritten: Long = 0,
    val message: String = ""
)

/** Bounded, latest-frame PGM recorder. Encoding and file I/O stay on its own worker. */
class DesktopPgmRecorder(
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val framesPerSecond: Int = 30,
    private val bitrateBitsPerSecond: Int = 12_000_000,
    private val onState: (DesktopRecordingSnapshot) -> Unit = {}
) : AutoCloseable {
    private data class PendingFrame(val frame: DesktopVideoFrame, val transitionAlpha: Float)

    private val lock = Object()
    private val framesWritten = AtomicLong(0)
    @Volatile private var snapshot = DesktopRecordingSnapshot(DesktopRecordingState.IDLE)
    @Volatile private var worker: Thread? = null
    private var running = false
    private var pending: PendingFrame? = null

    init {
        require(width >= 16 && width % 2 == 0)
        require(height >= 16 && height % 2 == 0)
        require(framesPerSecond in 1..60)
        require(bitrateBitsPerSecond in 250_000..100_000_000)
    }

    val currentSnapshot: DesktopRecordingSnapshot get() = snapshot
    val isActive: Boolean get() = snapshot.state in setOf(
        DesktopRecordingState.STARTING,
        DesktopRecordingState.RECORDING,
        DesktopRecordingState.STOPPING
    )

    @Synchronized
    fun start(outputFile: Path) {
        check(!isActive) { "PGM recording is already active" }
        check(worker?.isAlive != true) { "Previous PGM recording is still finalizing" }
        val target = outputFile.toAbsolutePath().normalize()
        require(target.fileName.toString().endsWith(".mp4", ignoreCase = true)) {
            "PGM recording output must use the .mp4 extension"
        }
        require(target.parent != null) { "PGM recording needs a destination folder" }
        Files.createDirectories(target.parent)
        require(!Files.exists(target) || Files.size(target) == 0L) {
            "The selected recording file already exists"
        }
        framesWritten.set(0)
        synchronized(lock) {
            pending = null
            running = true
        }
        publish(DesktopRecordingSnapshot(DesktopRecordingState.STARTING, target, message = "Preparing encoder"))
        Thread({ recordLoop(target) }, "DesktopPgmRecorder").apply {
            isDaemon = true
            worker = this
            start()
        }
    }

    fun offerFrame(frame: DesktopVideoFrame, transitionAlpha: Float = 0f) {
        synchronized(lock) {
            if (!running) return
            pending = PendingFrame(frame, transitionAlpha.coerceIn(0f, 1f))
            lock.notifyAll()
        }
    }

    @Synchronized
    fun stop() {
        val previous = snapshot
        if (previous.state !in setOf(DesktopRecordingState.STARTING, DesktopRecordingState.RECORDING)) return
        publish(previous.copy(state = DesktopRecordingState.STOPPING, message = "Finalizing MP4"))
        synchronized(lock) {
            if (!running) return
            running = false
            lock.notifyAll()
        }
    }

    override fun close() {
        stop()
        worker?.let { thread ->
            if (thread !== Thread.currentThread()) runCatching { thread.join(5_000) }
        }
    }

    private fun recordLoop(outputFile: Path) {
        var recorder: FFmpegFrameRecorder? = null
        val converter = Java2DFrameConverter()
        try {
            val canvas = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
            recorder = createRecorder(outputFile)
            val shouldRecord = synchronized(this) {
                if (snapshot.state == DesktopRecordingState.STARTING) {
                    publish(DesktopRecordingSnapshot(DesktopRecordingState.RECORDING, outputFile, message = "Recording PGM"))
                    true
                } else {
                    false
                }
            }
            val startedAtNanos = System.nanoTime()

            while (shouldRecord) {
                val next = synchronized(lock) {
                    while (running && pending == null) lock.wait()
                    val value = pending
                    pending = null
                    if (!running && value == null) null else value
                } ?: break

                val graphics = canvas.createGraphics()
                try {
                    graphics.color = Color.BLACK
                    graphics.fillRect(0, 0, width, height)
                    graphics.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC
                    )
                    DesktopVideoPainter.draw(
                        graphics,
                        next.frame,
                        width,
                        height,
                        highQualitySpatialScale = true
                    )
                    if (next.transitionAlpha > 0f) {
                        graphics.color = Color(0, 0, 0, (next.transitionAlpha * 255).toInt())
                        graphics.fillRect(0, 0, width, height)
                    }
                } finally {
                    graphics.dispose()
                }

                val converted = converter.convert(canvas) ?: continue
                converted.timestamp = ((System.nanoTime() - startedAtNanos) / 1_000L).coerceAtLeast(0L)
                recorder.record(converted)
                val count = framesWritten.incrementAndGet()
                if (count % 30L == 0L) {
                    publish(DesktopRecordingSnapshot(DesktopRecordingState.RECORDING, outputFile, count, "Recording PGM"))
                }
            }

            recorder.stop()
            recorder.release()
            recorder = null
            publish(DesktopRecordingSnapshot(DesktopRecordingState.IDLE, outputFile, framesWritten.get(), "Saved recording"))
        } catch (error: Throwable) {
            synchronized(lock) { running = false; pending = null }
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            publish(DesktopRecordingSnapshot(
                DesktopRecordingState.FAILED,
                outputFile,
                framesWritten.get(),
                error.message?.take(160) ?: "PGM recording failed"
            ))
        } finally {
            converter.close()
            synchronized(lock) { pending = null; running = false; lock.notifyAll() }
            worker = null
        }
    }

    private fun createRecorder(outputFile: Path): FFmpegFrameRecorder {
        Loader.load(avcodec::class.java)
        val candidates = listOf("libx264", "libopenh264")
        var lastError: Throwable? = null
        for (codec in candidates) {
            val available = avcodec.avcodec_find_encoder_by_name(codec)
            if (available == null || available.isNull) continue
            val candidate = FFmpegFrameRecorder(outputFile.toString(), width, height).apply {
                format = "mp4"
                videoCodecName = codec
                pixelFormat = avutil.AV_PIX_FMT_YUV420P
                videoBitrate = bitrateBitsPerSecond
                frameRate = framesPerSecond.toDouble()
                gopSize = framesPerSecond * 2
                maxBFrames = 0
                setOption("movflags", "+faststart")
                if (codec == "libx264") {
                    setVideoOption("preset", "ultrafast")
                    setVideoOption("tune", "zerolatency")
                    setVideoOption("x264-params", "repeat-headers=1:keyint=${framesPerSecond * 2}:scenecut=0:rc-lookahead=0")
                } else {
                    setVideoOption("rc_mode", "1")
                    setVideoOption("allow_skip_frames", "1")
                }
            }
            try {
                candidate.start()
                return candidate
            } catch (error: Throwable) {
                lastError = error
                runCatching { candidate.release() }
            }
        }
        throw IllegalStateException("No supported H.264 encoder is available for MP4 recording", lastError)
    }

    private fun publish(value: DesktopRecordingSnapshot) {
        snapshot = value
        runCatching { onState(value) }
    }
}
