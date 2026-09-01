package com.camhub.studio.data.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PipelineLatencyCalculatorTest {
    @Test
    fun `calculates all stages with remote clock correction`() {
        val result = PipelineLatencyCalculator.calculate(
            points = PipelineTimingPoints(
                frameSequence = 42,
                captureAtRemoteWallMs = 1_100,
                encodedAtRemoteWallMs = 1_108,
                receivedAtLocalWallMs = 1_025,
                receivedAtLocalElapsedMs = 2_000,
                decodedAtLocalElapsedMs = 2_006,
                readyAtLocalElapsedMs = 2_014
            ),
            remoteClockOffsetMs = 100
        )

        assertEquals(
            PipelineLatencyBreakdown(
                frameSequence = 42,
                captureToEncodeMs = 8,
                encodeToReceiveMs = 17,
                receiveToDecodeMs = 6,
                decodeToReadyMs = 8,
                totalToReadyMs = 39
            ),
            result
        )
    }

    @Test
    fun `rejects timestamps that move backwards`() {
        val result = PipelineLatencyCalculator.calculate(
            points = PipelineTimingPoints(
                frameSequence = 1,
                captureAtRemoteWallMs = 100,
                encodedAtRemoteWallMs = 99,
                receivedAtLocalWallMs = 100,
                receivedAtLocalElapsedMs = 200,
                decodedAtLocalElapsedMs = 201,
                readyAtLocalElapsedMs = 202
            ),
            remoteClockOffsetMs = 0
        )

        assertNull(result)
    }

    @Test
    fun `rejects legacy frame without sequence`() {
        val result = PipelineLatencyCalculator.calculate(
            points = PipelineTimingPoints(
                frameSequence = 0,
                captureAtRemoteWallMs = 100,
                encodedAtRemoteWallMs = 101,
                receivedAtLocalWallMs = 102,
                receivedAtLocalElapsedMs = 200,
                decodedAtLocalElapsedMs = 201,
                readyAtLocalElapsedMs = 202
            ),
            remoteClockOffsetMs = 0
        )

        assertNull(result)
    }

    @Test
    fun `adds compose draw stage to ready measurement`() {
        val ready = PipelineLatencyBreakdown(
            frameSequence = 7,
            captureToEncodeMs = 8,
            encodeToReceiveMs = 17,
            receiveToDecodeMs = 6,
            decodeToReadyMs = 8,
            totalToReadyMs = 39
        )

        val drawn = PipelineLatencyCalculator.includeDraw(
            ready = ready,
            readyAtLocalElapsedMs = 1_000,
            drawnAtLocalElapsedMs = 1_012
        )

        assertEquals(12, drawn?.readyToDrawMs)
        assertEquals(51, drawn?.totalToDrawMs)
    }

    @Test
    fun `calculates an independent external display sink`() {
        val ready = PipelineLatencyBreakdown(
            frameSequence = 9,
            captureToEncodeMs = 8,
            encodeToReceiveMs = 17,
            receiveToDecodeMs = 6,
            decodeToReadyMs = 8,
            totalToReadyMs = 39
        )

        val external = PipelineLatencyCalculator.calculateSinkDraw(
            ready = ready,
            readyAtLocalElapsedMs = 2_000,
            sinkDrawnAtLocalElapsedMs = 2_018
        )

        assertEquals(18, external?.readyToSinkDrawMs)
        assertEquals(57, external?.totalToSinkDrawMs)
    }
}
