package com.camhub.studio.data.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmFrameAccumulatorTest {
    @Test
    fun `partial and multiple frames preserve sample ordering`() {
        val accumulator = PcmFrameAccumulator(4)
        val frames = mutableListOf<ShortArray>()
        val consume: (ShortArray) -> Unit = { frames += it.copyOf() }
        accumulator.offer(shortArrayOf(1, 2, 99), 2, consume)
        accumulator.offer(shortArrayOf(3, 4, 5, 6, 7, 8, 9), consume = consume)
        assertEquals(2, frames.size)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), frames[0])
        assertArrayEquals(shortArrayOf(5, 6, 7, 8), frames[1])
        accumulator.offer(shortArrayOf(10, 11, 12), consume = consume)
        assertArrayEquals(shortArrayOf(9, 10, 11, 12), frames[2])
    }
}
