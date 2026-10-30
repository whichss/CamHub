package com.camhub.studio.data.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic application-layer packet simulation, not a Wi-Fi performance model. */
class MultiCameraDeliverySimulationTest {
    @Test
    fun `four streams handle reorder duplication burst loss and deadlines independently`() {
        val sources = (0 until 4).map { camera -> ByteArray(4_000) { (it + camera).toByte() } }
        val packets = sources.mapIndexed { camera, data ->
            RtpVideoPacketCodec.packetize(data, camera.toLong(), 100, camera.toLong(), 0)
        }
        val receivers = List(4) { DeadlineFrameReassembler(frameDeadlineMs = 35) }

        for (camera in 0..1) {
            var completed: DeadlineFrameReassembler.CompletedFrame? = null
            packets[camera].reversed().forEachIndexed { index, packet ->
                val result = receivers[camera].offer(packet, index.toLong())
                if (index == 0) assertEquals(DeadlineFrameReassembler.OfferResult.Duplicate,
                    receivers[camera].offer(packet, index.toLong()))
                if (result is DeadlineFrameReassembler.OfferResult.Complete) completed = result.frame
            }
            assertArrayEquals(sources[camera], completed?.bytes)
        }

        // A burst removes the remaining fragments from camera 2. Camera 3's last packet is late.
        receivers[2].offer(packets[2][0], 0)
        assertEquals(DeadlineFrameReassembler.ExpirationStats(1, packets[2].size - 1),
            receivers[2].expireDetailed(35))
        packets[3].dropLast(1).forEachIndexed { index, packet -> receivers[3].offer(packet, index.toLong()) }
        assertTrue(receivers[3].offer(packets[3].last(), 35) is DeadlineFrameReassembler.OfferResult.Dropped)
        assertTrue(receivers.all { it.pendingFrameCount() == 0 })
    }
}
