package com.camhub.studio.ui.director

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamConnectionEpochsTest {
    @Test
    fun `replacement connection invalidates fallback from previous attempt`() {
        val epochs = StreamConnectionEpochs()
        val oldAttempt = epochs.begin("Camera 1")
        val currentAttempt = epochs.begin("Camera 1")

        assertFalse(epochs.isCurrent("Camera 1", oldAttempt))
        assertTrue(epochs.isCurrent("Camera 1", currentAttempt))
    }

    @Test
    fun `disconnect invalidates callbacks and leaves other camera attempts intact`() {
        val epochs = StreamConnectionEpochs()
        val cameraOne = epochs.begin("Camera 1")
        val cameraTwo = epochs.begin("Camera 2")

        epochs.invalidate("Camera 1")

        assertFalse(epochs.isCurrent("Camera 1", cameraOne))
        assertTrue(epochs.isCurrent("Camera 2", cameraTwo))
    }
}
