package com.camhub.studio.data

import org.junit.Assert.assertThrows
import org.junit.Test

class RecordingStartCancellationTest {
    @Test
    fun `cancelled request is rejected before allocating recorder resources`() {
        val cancellation = RecordingStartCancellation()
        cancellation.cancel()

        assertThrows(IllegalStateException::class.java) {
            cancellation.throwIfCancelled()
        }
    }
}
