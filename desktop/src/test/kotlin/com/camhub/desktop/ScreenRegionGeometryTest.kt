package com.camhub.desktop

import java.awt.Point
import java.awt.Rectangle
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenRegionGeometryTest {
    @Test
    fun `drag accepts reverse direction and offsets into global display coordinates`() {
        val display = Rectangle(-1920, 0, 1920, 1080)

        val region = ScreenRegionGeometry.fromDrag(display, Point(900, 700), Point(100, 40))

        assertEquals(Rectangle(-1820, 40, 800, 660), region)
    }

    @Test
    fun `drag clamps selection to display bounds`() {
        val display = Rectangle(100, 50, 800, 600)

        val region = ScreenRegionGeometry.fromDrag(display, Point(700, 500), Point(900, 700))

        assertEquals(Rectangle(800, 550, 100, 100), region)
    }
}
