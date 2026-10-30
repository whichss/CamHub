package com.camhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PgmSourceSwitcherTest {
    @Test
    fun `preview selection does not change program until cut`() {
        val switcher = PgmSourceSwitcher(
            program = PgmSource.Camera(0),
            preview = PgmSource.Camera(0)
        )

        switcher.selectPreview(PgmSource.Camera(1))

        assertEquals(PgmSource.Camera(0), switcher.program)
        assertEquals(PgmSource.Camera(1), switcher.preview)
        assertEquals(PgmSource.Camera(1), switcher.cut())
        assertEquals(PgmSource.Camera(1), switcher.program)
    }

    @Test
    fun `cut refuses an empty preview and clearing a source clears both buses`() {
        val switcher = PgmSourceSwitcher(
            program = PgmSource.Camera(0),
            preview = PgmSource.None
        )

        assertNull(switcher.cut())
        switcher.selectPreview(PgmSource.DesktopScreen)
        assertEquals(PgmSource.DesktopScreen, switcher.cut())

        switcher.clear(PgmSource.DesktopScreen)
        assertEquals(PgmSource.None, switcher.preview)
        assertEquals(PgmSource.None, switcher.program)
    }
}
