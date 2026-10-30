package com.camhub.desktop

import com.camhub.studio.data.network.CamHubVideoPayload
import java.awt.image.BufferedImage
import java.io.InputStream
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter

/**
 * Per-camera H.264 decoder. Network reception never waits for decoding: access
 * units enter a small bounded queue and stale delta frames are discarded first.
 */
class H264FrameDecoder(
    cameraName: String,
    private val onImage: (BufferedImage, CamHubVideoPayload) -> Unit,
    private val onError: (Throwable) -> Unit
) : AutoCloseable {
    private val input = LatestAccessUnitInputStream()
    private val running = AtomicBoolean(false)
    private val decoderInitialized = AtomicBoolean(false)
    private val threadName = "DesktopH264Decode-" + cameraName
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .take(30)

    @Volatile private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (thread?.isAlive == true) return
        if (!running.compareAndSet(false, true)) return
        thread = Thread(::decodeLoop, threadName).apply {
            isDaemon = true
            start()
        }
    }

    fun submit(payload: CamHubVideoPayload) {
        if (running.get()) input.submit(payload)
    }

    fun recoverFromDiscontinuity() {
        if (running.get()) input.recoverFromDiscontinuity()
    }

    @Synchronized
    override fun close() {
        running.set(false)
        input.close()
        // Closing the input wakes JavaCV when it is waiting for access units. Do not interrupt
        // JavaCPP while it is loading native libraries: an interrupted file-lock wait poisons
        // FFmpeg class initialization for the rest of this process.
        if (decoderInitialized.get()) thread?.interrupt()
    }

    private fun decodeLoop() {
        var converter: Java2DFrameConverter? = null
        var grabber: FFmpegFrameGrabber? = null
        var convertedImage: BufferedImage? = null
        try {
            val activeConverter = Java2DFrameConverter().also { converter = it }
            val activeGrabber = FFmpegFrameGrabber(input, 0).apply {
                format = "h264"
                setOption("fflags", "nobuffer")
                setOption("avioflags", "direct")
                setOption("probesize", "2048")
                setOption("analyzeduration", "0")
                setOption("fpsprobesize", "0")
                setVideoOption("flags", "low_delay")
                setVideoOption("threads", "1")
            }.also { grabber = it }
            // This is a live Annex-B stream that stays open. SPS/PPS arrives as
            // a separate config access unit before video, so probing a finite
            // sample here only consumes frames from the bounded startup queue.
            activeGrabber.start(false)
            decoderInitialized.set(true)
            while (running.get()) {
                val frame = activeGrabber.grabImage() ?: continue
                val metadata = input.takeOutputMetadata() ?: continue
                if (metadata.decoderResidenceMs > LatestAccessUnitInputStream.MAX_FRAME_AGE_MS) {
                    input.recoverFromLateDecode()
                    continue
                }
                convertedImage = DesktopVideoImageGuard.convertAndPublish(
                    previousImage = convertedImage,
                    convert = { activeConverter.convert(frame) },
                    onPublished = { image -> onImage(image, metadata.payload) }
                ) ?: convertedImage
            }
        } catch (error: Throwable) {
            if (running.get()) onError(error)
        } finally {
            runCatching { grabber?.stop() }
            runCatching { grabber?.release() }
            converter?.close()
            decoderInitialized.set(false)
            running.set(false)
            synchronized(this) {
                if (thread === Thread.currentThread()) thread = null
            }
        }
    }

}

internal class LatestAccessUnitInputStream(
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000L }
) : InputStream() {
    companion object {
        // Two frames cap the application-side queue to about 67 ms at 30 fps.
        // If that budget is exceeded, recovery starts at the next independent frame.
        internal const val CAPACITY = 2
        internal const val MAX_FRAME_AGE_MS = 180L
    }

    private data class UnitChunk(
        val payload: CamHubVideoPayload?,
        val submittedAtMs: Long = 0L,
        val closed: Boolean = false
    )

    internal data class OutputMetadata(
        val payload: CamHubVideoPayload,
        val decoderResidenceMs: Long
    )

    private data class SubmittedMetadata(
        val payload: CamHubVideoPayload,
        val submittedAtMs: Long
    )

    private val queue = LinkedBlockingDeque<UnitChunk>(CAPACITY)
    private val outputMetadata = ConcurrentLinkedDeque<SubmittedMetadata>()
    private val closed = AtomicBoolean(false)
    private val seekingKeyFrame = AtomicBoolean(false)
    private val droppedFrames = AtomicLong(0)
    private var current = ByteArray(0)
    private var currentOffset = 0

    val droppedFrameCount: Long get() = droppedFrames.get()

    fun submit(payload: CamHubVideoPayload) {
        if (closed.get() || payload.h264Bytes.isEmpty()) return
        val chunk = UnitChunk(payload, submittedAtMs = elapsedMs())
        if (payload.isCodecConfig) {
            queue.removeIf { it.payload?.isCodecConfig == true }
            if (!queue.offerFirst(chunk)) {
                queue.pollLast()
                queue.offerFirst(chunk)
            }
            return
        }
        if (seekingKeyFrame.get() && !payload.isKeyFrame) {
            droppedFrames.incrementAndGet()
            return
        }
        if (payload.isKeyFrame) {
            seekingKeyFrame.set(false)
            queue.removeIf {
                val queued = it.payload
                queued != null && !queued.isCodecConfig && !queued.isKeyFrame
            }
        }
        if (!queue.offerLast(chunk)) {
            if (payload.isKeyFrame) {
                queue.removeIf { it.payload?.isCodecConfig != true }
                if (!queue.offerLast(chunk)) {
                    queue.pollLast()
                    queue.offerLast(chunk)
                }
            } else {
                enterKeyFrameRecovery()
                droppedFrames.incrementAndGet()
            }
        }
    }

    fun takeOutputMetadata(): OutputMetadata? {
        val metadata = outputMetadata.pollFirst() ?: return null
        return OutputMetadata(
            payload = metadata.payload,
            decoderResidenceMs = (elapsedMs() - metadata.submittedAtMs).coerceAtLeast(0L)
        )
    }

    fun recoverFromLateDecode() {
        recoverFromDiscontinuity()
    }

    fun recoverFromDiscontinuity() {
        droppedFrames.incrementAndGet()
        enterKeyFrameRecovery()
    }

    private fun enterKeyFrameRecovery() {
        seekingKeyFrame.set(true)
        queue.removeIf { it.payload?.isCodecConfig != true }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (currentOffset >= current.size) {
            val chunk = try {
                queue.takeFirst()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
            if (chunk.closed) return -1
            val payload = chunk.payload ?: continue
            if (!payload.isCodecConfig) {
                outputMetadata.offerLast(SubmittedMetadata(payload, chunk.submittedAtMs))
            }
            current = payload.h264Bytes
            currentOffset = 0
        }
        val copied = minOf(length, current.size - currentOffset)
        current.copyInto(target, offset, currentOffset, currentOffset + copied)
        currentOffset += copied
        return copied
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        queue.clear()
        outputMetadata.clear()
        queue.offerFirst(UnitChunk(null, closed = true))
    }
}
