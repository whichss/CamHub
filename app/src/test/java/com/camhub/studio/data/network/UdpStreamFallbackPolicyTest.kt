package com.camhub.studio.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UdpStreamFallbackPolicyTest {

    @Test
    fun `first frame gets its full startup window`() {
        assertNull(UdpStreamFallbackPolicy.reason(2_499, 1_000, 0))
        assertEquals(
            UdpFallbackReason.FIRST_FRAME_TIMEOUT,
            UdpStreamFallbackPolicy.reason(2_500, 1_000, 0)
        )
    }

    @Test
    fun `active stream falls back only after complete frames stop`() {
        assertNull(UdpStreamFallbackPolicy.reason(4_999, 1_000, 3_000))
        assertEquals(
            UdpFallbackReason.STREAM_STALLED,
            UdpStreamFallbackPolicy.reason(5_000, 1_000, 3_000)
        )
    }
}
