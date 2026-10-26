package com.camhub.desktop

import org.bytedeco.ffmpeg.global.avcodec
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegRuntimeTest {
    @Test
    fun `loads packaged native decoder`() {
        assertTrue(avcodec.avcodec_version() > 0)
    }
}
