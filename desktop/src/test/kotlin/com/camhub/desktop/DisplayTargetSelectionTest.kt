package com.camhub.desktop

import java.awt.Rectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayTargetSelectionTest {
    private val primary = DisplayTarget(
        id = "primary",
        label = "Primary",
        bounds = Rectangle(0, 0, 1920, 1080),
        refreshRateHz = 60,
        isPrimary = true
    )
    private val secondary = DisplayTarget(
        id = "secondary",
        label = "Display 2",
        bounds = Rectangle(1920, 0, 2560, 1440),
        refreshRateHz = 60,
        isPrimary = false
    )

    @Test
    fun `keeps preferred display when it still exists`() {
        assertEquals(
            secondary,
            DisplayTargetSelection.select(listOf(primary, secondary), "secondary")
        )
    }

    @Test
    fun `falls back to primary when preferred display disappears`() {
        assertEquals(
            primary,
            DisplayTargetSelection.select(listOf(secondary, primary), "missing")
        )
    }

    @Test
    fun `returns null when no display exists`() {
        assertNull(DisplayTargetSelection.select(emptyList(), null))
    }
}
