package com.camhub.studio.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryProbeSchedulerTest {
    @Test
    fun `four cameras take turns even when evaluation timing is irregular`() {
        val scheduler = RecoveryProbeScheduler<String>()
        val groups = mapOf("lan" to listOf("D", "B", "C", "A"))
        val selected = (0 until 8).map { scheduler.select(groups).getValue("lan") }
        assertEquals(listOf("A", "B", "C", "D", "A", "B", "C", "D"), selected)
    }

    @Test
    fun `networks rotate independently and removed groups are forgotten`() {
        val scheduler = RecoveryProbeScheduler<String>()
        assertEquals(mapOf("one" to "A", "two" to "X"), scheduler.select(
            mapOf("one" to listOf("A", "B"), "two" to listOf("X", "Y"))))
        assertEquals(mapOf("two" to "Y"), scheduler.select(mapOf("two" to listOf("X", "Y"))))
        assertEquals(mapOf("one" to "A"), scheduler.select(mapOf("one" to listOf("A", "B"))))
    }
}
