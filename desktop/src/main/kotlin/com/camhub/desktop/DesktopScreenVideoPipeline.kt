package com.camhub.desktop

import java.awt.image.BufferedImage
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Latest-frame-only capture → encoder → UDP pipeline; screen capture never waits on it. */
internal class DesktopScreenVideoPipeline(
    private val publisher: DesktopUdpVideoPublisher,
    private val onError: (String) -> Unit = {}
) : AutoCloseable {
    private data class CapturedFrame(val image: BufferedImage, val captureAtWallMs: Long)

    private val running = AtomicBoolean(true)
    private val latest = AtomicReference<CapturedFrame?>()
    private val wakeEncoder = Semaphore(0)
    private val droppedFrames = AtomicLong(0L)
    @Volatile private var encoder: DesktopScreenH264Encoder? = null
    @Volatile private var worker: Thread? = null
    private val closed = AtomicBoolean(false)

    val udpPort: Int get() = publisher.localPort
    val connectedHubCount: Int get() = publisher.destinationCount

    init {
        publisher.onKeyFrameRequired = { encoder?.requestKeyFrame() }
        worker = Thread(::encodeLoop, "DesktopScreenEncode").apply {
            isDaemon = true
            start()
        }
    }

    fun offerFrame(image: BufferedImage, captureAtWallMs: Long = System.currentTimeMillis()) {
        if (!running.get() || publisher.destinationCount == 0) return
        if (latest.getAndSet(CapturedFrame(image, captureAtWallMs)) != null) {
            droppedFrames.incrementAndGet()
        }
        if (wakeEncoder.availablePermits() == 0) wakeEncoder.release()
    }

    fun droppedFrameCount(): Long = droppedFrames.get()

    fun subscribe(clientId: String, host: String, port: Int): Boolean =
        publisher.subscribe(clientId, host, port)

    fun unsubscribe(clientId: String) {
        publisher.unsubscribe(clientId)
        if (publisher.destinationCount == 0) latest.set(null)
    }

    fun requestKeyFrame() {
        encoder?.requestKeyFrame()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        latest.set(null)
        wakeEncoder.release()
        worker?.interrupt()
        worker?.let { thread ->
            if (thread !== Thread.currentThread()) runCatching { thread.join(1_000) }
        }
        worker = null
        publisher.close()
    }

    private fun encodeLoop() {
        var activeEncoder: DesktopScreenH264Encoder? = null
        try {
            while (running.get()) {
                wakeEncoder.acquire()
                var captured = latest.getAndSet(null)
                while (captured != null && running.get()) {
                    if (publisher.destinationCount > 0) {
                        val currentEncoder = activeEncoder ?: DesktopScreenH264Encoder().also {
                            activeEncoder = it
                            encoder = it
                        }
                        val encoded = currentEncoder.encode(captured.image, captured.captureAtWallMs)
                        if (encoded != null) publisher.publish(encoded)
                    }
                    captured = latest.getAndSet(null)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            if (running.get()) onError(error.message ?: "Desktop video encoder failed")
            running.set(false)
        } finally {
            encoder = null
            activeEncoder?.close()
            activeEncoder = null
        }
    }
}
