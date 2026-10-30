package com.camhub.studio.data.audio

/** Single-threaded accumulator. The callback must consume the frame synchronously. */
internal class PcmFrameAccumulator(private val frameSize: Int) {
    init { require(frameSize > 0) }
    private val frame = ShortArray(frameSize)
    private var used = 0

    fun offer(samples: ShortArray, count: Int = samples.size, consume: (ShortArray) -> Unit) {
        require(count in 0..samples.size)
        var offset = 0
        while (offset < count) {
            val length = minOf(count - offset, frameSize - used)
            samples.copyInto(frame, used, offset, offset + length)
            used += length
            offset += length
            if (used == frameSize) {
                consume(frame)
                used = 0
            }
        }
    }
}
