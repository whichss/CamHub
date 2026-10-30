package com.camhub.desktop

import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class ScreenCaptureRequest(
    val display: DisplayTarget,
    val region: Rectangle,
    val framesPerSecond: Int = 30
) {
    init {
        require(framesPerSecond in 1..30)
        require(region.width > 0 && region.height > 0)
        require(display.bounds.contains(region)) { "Capture region must stay inside the selected display" }
    }
}

/** Captures one selected display region on a dedicated thread and publishes latest frames only. */
class DesktopScreenCapture(
    private val onFrame: (BufferedImage) -> Unit,
    private val onError: (String, () -> Boolean) -> Unit = { _, _ -> },
    private val captureFactory: ((ScreenCaptureRequest) -> (() -> BufferedImage))? = null
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    @Volatile private var worker: Thread? = null

    @Synchronized
    fun start(request: ScreenCaptureRequest) {
        stop()
        check(worker?.isAlive != true) { "Previous screen capture is still stopping" }
        val currentGeneration = generation.incrementAndGet()
        check(running.compareAndSet(false, true))
        worker = Thread({ captureLoop(request, currentGeneration) }, "DesktopScreenCapture").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() = stop()

    @Synchronized
    private fun stop() {
        generation.incrementAndGet()
        running.set(false)
        val currentWorker = worker
        currentWorker?.interrupt()
        currentWorker?.let { thread ->
            if (thread !== Thread.currentThread()) runCatching { thread.join(500) }
        }
        worker = currentWorker?.takeIf { it.isAlive }
    }

    private fun captureLoop(request: ScreenCaptureRequest, currentGeneration: Long) {
        try {
            val captureFrame = captureFactory?.invoke(request) ?: createRobotCapture(request)
            val intervalNanos = 1_000_000_000L / request.framesPerSecond
            while (isCurrent(currentGeneration)) {
                val startedAt = System.nanoTime()
                val frame = captureFrame()
                if (!isCurrent(currentGeneration)) break
                onFrame(frame)
                val remainingNanos = intervalNanos - (System.nanoTime() - startedAt)
                if (remainingNanos > 0L) {
                    val sleepMillis = remainingNanos / 1_000_000L
                    val sleepNanos = (remainingNanos % 1_000_000L).toInt()
                    Thread.sleep(sleepMillis, sleepNanos)
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (isCurrent(currentGeneration)) onError(error.message ?: "Screen capture failed") {
                generation.get() == currentGeneration
            }
        } finally {
            if (generation.get() == currentGeneration) running.set(false)
        }
    }

    private fun isCurrent(currentGeneration: Long): Boolean =
        running.get() && generation.get() == currentGeneration

    private fun createRobotCapture(request: ScreenCaptureRequest): () -> BufferedImage {
        val robot = Robot(requireNotNull(request.display.device) { "Display is no longer available" })
        val region = Rectangle(request.region)
        return { robot.createScreenCapture(region) }
    }
}
