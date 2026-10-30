package com.camhub.desktop

import java.awt.Color
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegLowLatencyDecoderTest {
    @Test
    fun `software decoder fallback decodes an H264 keyframe into a reusable image`() {
        val input = BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().also { graphics ->
                try {
                    graphics.color = Color(32, 120, 210)
                    graphics.fillRect(0, 0, width, height)
                } finally {
                    graphics.dispose()
                }
            }
        }
        val encoder = DesktopScreenH264Encoder(320, 180, 15, 500_000)
        val encoded = try {
            requireNotNull(encoder.encode(input, captureAtWallMs = 1_700_000_000_000L))
                .also { assertTrue("Test input must start with an IDR", it.isKeyFrame) }
        } finally {
            encoder.close()
        }

        val decoder = FfmpegLowLatencyDecoder(forceSoftware = true)
        val decodedImage = AtomicReference<BufferedImage?>()
        val completed = CountDownLatch(1)
        try {
            decoder.decode(encoded.bytes, pts = 1L, isKeyFrame = true) { frame ->
                decodedImage.set(decoder.toBufferedImage(frame, decodedImage.get()))
                completed.countDown()
            }

            assertTrue("Software decoder did not emit the H.264 keyframe", completed.await(1, TimeUnit.SECONDS))
            val image = assertNotNullAndGet(decodedImage.get())
            assertEquals(320, image.width)
            assertEquals(180, image.height)
            assertEquals("software decode", decoder.activePathLabel)
        } finally {
            decoder.close()
        }
    }

    private fun assertNotNullAndGet(image: BufferedImage?): BufferedImage {
        assertNotNull("Decoder returned no image", image)
        return requireNotNull(image)
    }
}
