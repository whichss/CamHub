package com.camhub.desktop

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPgmRecorderTest {
    @Test
    fun `records latest PGM frames to a decodable MP4 and retains transition fade`() {
        val output = Files.createTempDirectory("camhub-pgm-test").resolve("program.mp4")
        val snapshot = AtomicReference<DesktopRecordingSnapshot>()
        val recorder = DesktopPgmRecorder(
            width = 320,
            height = 180,
            framesPerSecond = 20,
            bitrateBitsPerSecond = 800_000,
            onState = snapshot::set
        )
        val redImage = BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().let { graphics ->
                try { graphics.color = Color.RED; graphics.fillRect(0, 0, width, height) }
                finally { graphics.dispose() }
            }
        }

        try {
            recorder.start(output)
            assertTrue(
                "Recorder did not start: ${snapshot.get()}",
                await { snapshot.get()?.state == DesktopRecordingState.RECORDING }
            )
            repeat(8) {
                recorder.offerFrame(DesktopVideoFrame(redImage))
                Thread.sleep(60)
            }
            recorder.offerFrame(DesktopVideoFrame(redImage), transitionAlpha = 1f)
            recorder.stop()
            assertTrue(await { snapshot.get()?.state == DesktopRecordingState.IDLE })
            assertTrue(Files.size(output) > 1_000)
            assertTrue(snapshot.get().framesWritten >= 2)

            val grabber = FFmpegFrameGrabber(output.toString())
            val converter = Java2DFrameConverter()
            try {
                grabber.start()
                var decodedFrames = 0
                var lastImage: BufferedImage? = null
                while (true) {
                    val decoded = grabber.grabImage() ?: break
                    lastImage = converter.convert(decoded) ?: lastImage
                    decodedFrames++
                }
                assertTrue("MP4 should contain decodable video frames", decodedFrames >= 2)
                val lastPixel = Color(requireNotNull(lastImage).getRGB(160, 90))
                assertTrue("last frame should contain the AUTO fade to black", lastPixel.red < 35)
            } finally {
                runCatching { grabber.stop() }
                runCatching { grabber.release() }
                converter.close()
            }
        } finally {
            recorder.close()
            Files.deleteIfExists(output)
            Files.deleteIfExists(output.parent)
        }
    }

    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
