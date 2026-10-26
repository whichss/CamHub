package com.camhub.desktop

import com.camhub.studio.data.network.CamHubVideoPayload
import java.awt.Graphics2D
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
    private val threadName = "DesktopH264Decode-" + cameraName
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .take(30)

    @Volatile private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread(::decodeLoop, threadName).apply {
            isDaemon = true
            start()
        }
    }

    fun submit(payload: CamHubVideoPayload) {
        if (running.get()) input.submit(payload)
    }

    override fun close() {
        running.set(false)
        input.close()
        thread?.interrupt()
        thread = null
    }

    private fun decodeLoop() {
        val converter = Java2DFrameConverter()
        val grabber = FFmpegFrameGrabber(input, 0).apply {
            format = "h264"
            setOption("fflags", "nobuffer")
            setOption("avioflags", "direct")
            setOption("probesize", "2048")
            setOption("analyzeduration", "0")
            setOption("fpsprobesize", "0")
            setVideoOption("flags", "low_delay")
            setVideoOption("threads", "1")
        }
        try {
            grabber.start()
            while (running.get()) {
                val frame = grabber.grabImage() ?: continue
                val converted = converter.convert(frame) ?: continue
                val metadata = input.takeOutputMetadata() ?: continue
                if (metadata.decoderResidenceMs > LatestAccessUnitInputStream.MAX_FRAME_AGE_MS) {
                    input.recoverFromLateDecode()
                    continue
                }
                onImage(deepCopy(converted), metadata.payload)
            }
        } catch (error: Throwable) {
            if (running.get()) onError(error)
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
            converter.close()
            running.set(false)
        }
    }

    private fun deepCopy(source: BufferedImage): BufferedImage {
        val copy = BufferedImage(source.width, source.height, BufferedImage.TYPE_3BYTE_BGR)
        copy.createGraphics().use { it.drawImage(source, 0, 0, null) }
        return copy
    }

    private inline fun <T : Graphics2D, R> T.use(block: (T) -> R): R = try {
        block(this)
    } finally {
        dispose()
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
