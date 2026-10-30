package com.camhub.desktop

import com.camhub.studio.data.network.CamHubVideoPayload
import java.awt.image.BufferedImage
import java.io.InputStream
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.bytedeco.ffmpeg.avutil.AVFrame

/**
 * Per-camera H.264 decoder. Network reception never waits for decoding: access
 * units enter a small bounded queue and stale delta frames are discarded first.
 */
class H264FrameDecoder(
    cameraName: String,
    private val onImage: (BufferedImage, CamHubVideoPayload) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onDecodePath: (String) -> Unit = {}
) : AutoCloseable {
    private val input = LatestAccessUnitInputStream()
    private val running = AtomicBoolean(false)
    private val decoderInitialized = AtomicBoolean(false)
    private val initializationCompleted = CountDownLatch(1)
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

    internal fun awaitInitialized(timeout: Long, unit: TimeUnit): Boolean {
        return initializationCompleted.await(timeout, unit) && decoderInitialized.get()
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
        var decoder: FfmpegLowLatencyDecoder? = null
        var convertedImage: BufferedImage? = null
        val pendingMetadata = ArrayDeque<LatestAccessUnitInputStream.QueuedAccessUnit>()
        var fallbackPts = 0L
        var reportedDecodePath: String? = null
        try {
            // Wait for an IDR before allocating native decoder state. A disconnected camera
            // must not delay decoder initialization for the other camera slots.
            if (!input.awaitInitialKeyFrame() || !running.get()) return

            val activeDecoder = FfmpegLowLatencyDecoder().also { decoder = it }
            decoderInitialized.set(true)
            initializationCompleted.countDown()
            while (running.get()) {
                val accessUnit = input.takeAccessUnit() ?: break
                val payload = accessUnit.payload
                // CamHub sources repeat SPS/PPS in every IDR. The separate codec-config
                // envelope is for stream consumers that parse concatenated Annex-B bytes;
                // avcodec_send_packet expects decodable access units and may reject SPS/PPS-only
                // packets, so wait for the following IDR instead.
                if (payload.isCodecConfig) continue
                pendingMetadata.addLast(accessUnit)
                val pts = payload.frameSequence.takeIf { it > 0 } ?: ++fallbackPts
                try {
                    activeDecoder.decode(payload.h264Bytes, pts, payload.isKeyFrame) { frame: AVFrame ->
                        val metadata = pendingMetadata.removeFirstOrNull() ?: return@decode
                        val decodePath = activeDecoder.activePathLabel
                        if (decodePath != reportedDecodePath) {
                            reportedDecodePath = decodePath
                            onDecodePath(decodePath)
                        }
                        val decoderResidenceMs = (System.nanoTime() / 1_000_000L - metadata.submittedAtMs)
                            .coerceAtLeast(0L)
                        if (DesktopDecodeFrameBudget.isExpired(decoderResidenceMs)) {
                            pendingMetadata.clear()
                            input.recoverFromLateDecode()
                            activeDecoder.flush()
                            return@decode
                        }
                        convertedImage = DesktopVideoImageGuard.convertAndPublish(
                            previousImage = convertedImage,
                            convert = { activeDecoder.toBufferedImage(frame, convertedImage) },
                            onPublished = { image -> onImage(image, metadata.payload) }
                        ) ?: convertedImage
                    }
                } catch (_: FfmpegLowLatencyDecoder.SoftwareFallbackNeedsKeyFrame) {
                    pendingMetadata.clear()
                    input.recoverFromDiscontinuity()
                    activeDecoder.flush()
                }
            }
        } catch (error: Throwable) {
            if (running.get()) onError(error)
        } finally {
            runCatching { decoder?.close() }
            decoderInitialized.set(false)
            initializationCompleted.countDown()
            running.set(false)
            synchronized(this) {
                if (thread === Thread.currentThread()) thread = null
            }
        }
    }

}

internal object DesktopDecodeFrameBudget {
    // Prefer fresh video over output that has fallen more than three 30 fps frames behind.
    const val MAX_RESIDENCE_MS = 100L

    fun isExpired(decoderResidenceMs: Long): Boolean = decoderResidenceMs > MAX_RESIDENCE_MS
}

internal class LatestAccessUnitInputStream(
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000L }
) : InputStream() {
    companion object {
        // Two frames cap the application-side queue to about 67 ms at 30 fps.
        // If that budget is exceeded, recovery starts at the next independent frame.
        internal const val CAPACITY = 2
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

    data class QueuedAccessUnit(
        val payload: CamHubVideoPayload,
        val submittedAtMs: Long
    )

    private val queue = LinkedBlockingDeque<UnitChunk>(CAPACITY)
    private val outputMetadata = ConcurrentLinkedDeque<SubmittedMetadata>()
    private val closed = AtomicBoolean(false)
    private val initialKeyFrame = CountDownLatch(1)
    private val seekingKeyFrame = AtomicBoolean(false)
    private val droppedFrames = AtomicLong(0)
    private var current = ByteArray(0)
    private var currentOffset = 0

    val droppedFrameCount: Long get() = droppedFrames.get()

    fun submit(payload: CamHubVideoPayload) {
        if (closed.get() || payload.h264Bytes.isEmpty()) return
        if (payload.isKeyFrame) initialKeyFrame.countDown()
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

    fun awaitInitialKeyFrame(): Boolean {
        return try {
            initialKeyFrame.await()
            !closed.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun takeAccessUnit(): QueuedAccessUnit? {
        while (true) {
            val chunk = try {
                queue.takeFirst()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            if (chunk.closed) return null
            val payload = chunk.payload ?: continue
            return QueuedAccessUnit(payload, chunk.submittedAtMs)
        }
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
        initialKeyFrame.countDown()
        queue.clear()
        outputMetadata.clear()
        queue.offerFirst(UnitChunk(null, closed = true))
    }
}
