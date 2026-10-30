package com.camhub.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DesktopCameraSourceLifecycleTest {
    @Test
    fun `closed source rejects startup and repeated close is safe`() {
        val source = DesktopCameraSourceServer()
        source.close()
        source.close()

        assertFalse(source.isRunning)
        assertThrows(IllegalStateException::class.java) { source.start() }
    }
}
