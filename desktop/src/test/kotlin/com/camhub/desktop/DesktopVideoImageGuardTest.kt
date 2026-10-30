package com.camhub.desktop

import java.awt.Color
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopVideoImageGuardTest {
    @Test
    fun `publishes converted image directly without a second image copy`() {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        var published: BufferedImage? = null

        val result = DesktopVideoImageGuard.convertAndPublish(
            previousImage = image,
            convert = {
                image.setRGB(0, 0, Color.BLUE.rgb)
                image
            },
            onPublished = { published = it }
        )

        assertSame(image, result)
        assertSame(image, published)
        assertEquals(Color.BLUE.rgb, requireNotNull(published).getRGB(0, 0))
    }

    @Test
    fun `serializes reused image conversion with painting`() {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val writerDone = CountDownLatch(1)
        val writer = Thread {
            DesktopVideoImageGuard.convertAndPublish(
                previousImage = image,
                convert = {
                    image.setRGB(0, 0, Color.RED.rgb)
                    image
                },
                onPublished = {}
            )
            writerDone.countDown()
        }

        DesktopVideoImageGuard.withLock(image) {
            writer.start()
            assertFalse(writerDone.await(50, TimeUnit.MILLISECONDS))
        }

        assertTrue(writerDone.await(1, TimeUnit.SECONDS))
        writer.join(1_000)
        assertEquals(Color.RED.rgb, image.getRGB(0, 0))
    }
}
