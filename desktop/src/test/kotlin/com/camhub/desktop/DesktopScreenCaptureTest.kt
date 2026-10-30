package com.camhub.desktop

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopScreenCaptureTest {
    @Test
    fun `delayed error is invalid after capture restarts`() {
        val errorReceived = CountDownLatch(1)
        val validity = AtomicReference<(() -> Boolean)?>()
        val calls = AtomicInteger()
        val capture = DesktopScreenCapture(
            onFrame = {},
            onError = { _, isCurrent -> validity.set(isCurrent); errorReceived.countDown() },
            captureFactory = {
                if (calls.incrementAndGet() == 1) {
                    { throw IllegalStateException("capture failed") }
                } else {
                    { BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB) }
                }
            }
        )
        val display = DisplayTarget("test", "Test", Rectangle(0, 0, 2, 2), 60, true)
        val request = ScreenCaptureRequest(display, Rectangle(0, 0, 2, 2))
        try {
            capture.start(request)
            assertTrue(errorReceived.await(2, TimeUnit.SECONDS))
            assertTrue(validity.get()!!.invoke())
            capture.start(request)
            assertFalse(validity.get()!!.invoke())
        } finally {
            capture.close()
        }
    }

    @Test
    fun `stale capture cannot publish after stop and restart waits for it`() {
        val captureStarted = CountDownLatch(1)
        val releaseCapture = CountDownLatch(1)
        val staleCaptureReturned = CountDownLatch(1)
        val freshFramePublished = CountDownLatch(1)
        val factoryCalls = AtomicInteger()
        val publishedFrames = CopyOnWriteArrayList<BufferedImage>()
        val staleFrame = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val freshFrame = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val capture = DesktopScreenCapture(
            onFrame = { frame ->
                publishedFrames += frame
                if (frame === freshFrame) freshFramePublished.countDown()
            },
            captureFactory = {
                if (factoryCalls.incrementAndGet() == 1) {
                    {
                        captureStarted.countDown()
                        // Model a native screen-capture call that does not stop when its
                        // worker is interrupted. CountDownLatch.await() is interruptible,
                        // so retry until the simulated native call is explicitly released.
                        var interrupted = false
                        while (true) {
                            try {
                                releaseCapture.await()
                                break
                            } catch (_: InterruptedException) {
                                interrupted = true
                            }
                        }
                        if (interrupted) Thread.currentThread().interrupt()
                        staleCaptureReturned.countDown()
                        staleFrame
                    }
                } else {
                    { freshFrame }
                }
            }
        )
        val request = ScreenCaptureRequest(
            display = DisplayTarget(
                id = "test-display",
                label = "Test display",
                bounds = Rectangle(0, 0, 2, 2),
                refreshRateHz = 60,
                isPrimary = true
            ),
            region = Rectangle(0, 0, 2, 2)
        )

        try {
            capture.start(request)
            assertTrue("capture did not start", captureStarted.await(2, TimeUnit.SECONDS))

            // A blocked native capture is allowed to finish, but the old generation must not
            // publish its returned image or overlap a new capture session.
            capture.close()
            val restartWhileBlocked = runCatching { capture.start(request) }.exceptionOrNull()
            assertTrue("restart should be rejected while the old capture is alive", restartWhileBlocked is IllegalStateException)

            releaseCapture.countDown()
            assertTrue("blocked capture did not return", staleCaptureReturned.await(2, TimeUnit.SECONDS))
            capture.start(request)
            assertTrue("fresh capture did not publish", freshFramePublished.await(2, TimeUnit.SECONDS))
            assertFalse("stale frame was published after stop", publishedFrames.contains(staleFrame))
        } finally {
            releaseCapture.countDown()
            capture.close()
        }
    }
}
