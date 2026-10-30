package com.camhub.studio.data.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class H264ParameterSetsTest {
    @Test fun `extracts parameter sets from keyframe after header`() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 42)
        val pps = byteArrayOf(0, 0, 1, 0x68, 17)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 4)
        assertArrayEquals(sps + pps, H264ParameterSets.extract(byteArrayOf(7, 8) + sps + pps + idr, 2))
    }

    @Test fun `extracts reversed parameter sets when final unit ends the frame`() {
        val sps = byteArrayOf(0, 0, 1, 0x67, 42)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68, 17)
        assertArrayEquals(sps + pps, H264ParameterSets.extract(pps + sps, 0))
    }

    @Test fun `equivalent start codes compare equally without ignoring changes`() {
        val a = byteArrayOf(0, 0, 0, 1, 0x67, 42, 0, 0, 0, 1, 0x68, 17)
        val b = byteArrayOf(0, 0, 1, 0x67, 42, 0, 0, 1, 0x68, 17)
        assertTrue(H264ParameterSets.equivalent(a, b))
        assertFalse(H264ParameterSets.equivalent(a, b.copyOf().also { it[it.lastIndex] = 18 }))
    }

    @Test fun `rejects incomplete parameter sets`() {
        assertNull(H264ParameterSets.extract(byteArrayOf(0, 0, 1, 0x67, 12, 0, 0, 1, 0x65, 1), 0))
        assertNull(H264ParameterSets.extract(byteArrayOf(0, 0, 1), 0))
        assertNull(H264ParameterSets.extract(byteArrayOf(0, 0, 1), -1))
        assertNull(H264ParameterSets.extract(byteArrayOf(0, 0, 1), 4))
    }
}
