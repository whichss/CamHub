package com.camhub.desktop

import java.awt.Color
import java.awt.image.BufferedImage
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopFramingTest {
    @Test
    fun `zoom around pointer keeps its source point centered under pointer`() {
        val framing = DesktopFraming.IDENTITY.zoomBy(factor = 2.0, anchorX = 0.75, anchorY = 0.25)

        assertEquals(2.0, framing.zoom, 0.0)
        assertEquals(0.625, framing.centerX, 0.0)
        assertEquals(0.375, framing.centerY, 0.0)
    }

    @Test
    fun `pan clamps crop center to visible source bounds`() {
        val framing = DesktopFraming.IDENTITY.zoomBy(4.0).panBy(deltaX = -4.0, deltaY = 4.0)

        assertEquals(0.875, framing.centerX, 0.0)
        assertEquals(0.125, framing.centerY, 0.0)
    }

    @Test
    fun `zoom is bounded and reset returns full frame`() {
        val framing = DesktopFraming.IDENTITY.zoomBy(100.0)

        assertEquals(DesktopFraming.MAX_ZOOM, framing.zoom, 0.0)
        assertEquals(DesktopFraming.IDENTITY, framing.reset())
    }

    @Test
    fun `painter centers the selected crop in the output`() {
        val source = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 100) for (x in 0 until 100) {
            val color = when {
                x < 50 && y < 50 -> Color.RED
                x >= 50 && y < 50 -> Color.GREEN
                x < 50 -> Color.BLUE
                else -> Color.YELLOW
            }
            source.setRGB(x, y, color.rgb)
        }
        val output = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val framing = DesktopFraming.IDENTITY.zoomBy(2.0, anchorX = 0.75, anchorY = 0.25)

        val graphics = output.createGraphics()
        try {
            DesktopVideoPainter.draw(graphics, DesktopVideoFrame(source, framing = framing), 100, 100, false)
        } finally {
            graphics.dispose()
        }

        assertEquals(Color.GREEN.rgb, output.getRGB(50, 50))
    }

    @Test
    fun `remote crop leaves only the residual hub transform`() {
        val desired = DesktopFraming(zoom = 4.0, centerX = 0.75, centerY = 0.25)
        val remote = DesktopRemoteFraming(zoom = 2.0, centerX = 0.625, centerY = 0.375)

        assertEquals(DesktopFraming(zoom = 2.0, centerX = 0.75, centerY = 0.25), desired.residualAfter(remote))
    }

    @Test
    fun `camera and display PTZ coordinates round trip for every quarter turn`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val display = DesktopPtzCoordinates.cameraToDisplay(0.7, 0.2, rotation)
            val camera = DesktopPtzCoordinates.displayToCamera(display.first, display.second, rotation)

            assertEquals(0.7, camera.first, 1e-9)
            assertEquals(0.2, camera.second, 1e-9)
        }
    }
}
