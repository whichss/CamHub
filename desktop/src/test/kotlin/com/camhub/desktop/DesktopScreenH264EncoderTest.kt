package com.camhub.desktop

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopScreenH264EncoderTest {
    @Test
    fun `bundled ffmpeg creates low delay h264 keyframe access units`() {
        val image = BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().also { graphics ->
                try {
                    graphics.color = Color(26, 128, 220)
                    graphics.fillRect(0, 0, width, height)
                } finally {
                    graphics.dispose()
                }
            }
        }
        val encoder = DesktopScreenH264Encoder(
            width = 320,
            height = 180,
            framesPerSecond = 15,
            bitrateBitsPerSecond = 500_000
        )

        try {
            assertTrue(
                "Unexpected H.264 encoder: ${encoder.selectedCodecName}",
                encoder.selectedCodecName in setOf(
                    "h264_videotoolbox",
                    "h264_mf",
                    "libx264",
                    "libopenh264"
                )
            )
            val first = encoder.encode(image, captureAtWallMs = 1_700_000_000_000L)
            assertNotNull("No H.264 access unit was emitted", first)
            assertTrue("The first access unit must contain an IDR", first!!.isKeyFrame)
            assertEquals(320, first.width)
            assertEquals(180, first.height)
            assertTrue(first.bytes.isNotEmpty())
            val firstNalTypes = nalTypes(first.bytes)
            assertTrue("The first access unit must include SPS", 7 in firstNalTypes)
            assertTrue("The first access unit must include PPS", 8 in firstNalTypes)

            val changedImage = BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().also { graphics ->
                    try {
                        graphics.color = Color(220, 64, 32)
                        graphics.fillRect(0, 0, width, height)
                    } finally {
                        graphics.dispose()
                    }
                }
            }
            val second = encoder.encode(changedImage, captureAtWallMs = 1_700_000_000_033L)
            assertNotNull("The second H.264 access unit was not emitted", second)
            assertEquals(2L, second!!.sequence)

            val grabber = FFmpegFrameGrabber(ByteArrayInputStream(first.bytes + second.bytes)).apply {
                format = "h264"
                setOption("probesize", "2048")
                setOption("analyzeduration", "0")
            }
            try {
                grabber.start()
                val decoded = grabber.grabImage()
                assertNotNull("The emitted H.264 access units could not be decoded", decoded)
                assertEquals(320, decoded!!.imageWidth)
                assertEquals(180, decoded.imageHeight)
            } finally {
                runCatching { grabber.stop() }
                runCatching { grabber.release() }
            }
        } finally {
            encoder.close()
        }
    }

    private fun nalTypes(bytes: ByteArray): Set<Int> {
        val types = mutableSetOf<Int>()
        var index = 0
        while (index + 3 < bytes.size) {
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
            if (nalIndex < bytes.size) types += bytes[nalIndex].toInt() and 0x1f
            index = nalIndex
        }
        return types
    }
}
