package com.camhub.studio.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class H264DecoderInputPolicyTest {
    @Test
    fun `accepts a complete access unit within negotiated decoder input capacity`() {
        assertEquals(
            4,
            H264DecoderInputPolicy.acceptedByteCount(
                sourceSize = 8,
                offset = 2,
                length = 4,
                bufferCapacity = 4
            )
        )
    }

    @Test
    fun `rejects access units larger than the decoder buffer instead of truncating`() {
        assertNull(
            H264DecoderInputPolicy.acceptedByteCount(
                sourceSize = 16,
                offset = 0,
                length = 9,
                bufferCapacity = 8
            )
        )
    }

    @Test
    fun `accepts the shared maximum access unit and rejects larger input`() {
        val max = H264DecoderInputPolicy.MAX_ACCESS_UNIT_BYTES
        assertEquals(
            max,
            H264DecoderInputPolicy.acceptedByteCount(max, 0, max, max)
        )
        assertNull(
            H264DecoderInputPolicy.acceptedByteCount(max + 1, 0, max + 1, max + 1)
        )
    }

    @Test
    fun `rejects invalid source slices and empty input`() {
        assertNull(H264DecoderInputPolicy.acceptedByteCount(8, 6, 3, 8))
        assertNull(H264DecoderInputPolicy.acceptedByteCount(8, -1, 1, 8))
        assertNull(H264DecoderInputPolicy.acceptedByteCount(8, 0, 0, 8))
    }
}
