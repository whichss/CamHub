package com.camhub.studio.data.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class HandshakeMessageUdpTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `UDP ports survive TLS message serialization`() {
        val source = HandshakeMessage(
            type = "hello_ack",
            deviceName = "camera",
            streamPort = 8_000,
            tcpStreamPort = 8_003,
            tcpAuthToken = "paired-token",
            udpStreamPort = 8_001,
            udpReceivePort = 8_002,
            supportsAuthenticatedTcp = true
        )

        val decoded = json.decodeFromString<HandshakeMessage>(json.encodeToString(source))
        assertEquals(8_001, decoded.udpStreamPort)
        assertEquals(8_003, decoded.tcpStreamPort)
        assertEquals("paired-token", decoded.tcpAuthToken)
        assertEquals(8_002, decoded.udpReceivePort)
        assertEquals(true, decoded.supportsAuthenticatedTcp)
    }

    @Test
    fun `older peers remain compatible when UDP fields are absent`() {
        val decoded = json.decodeFromString<HandshakeMessage>(
            "{\"type\":\"hello_ack\",\"streamPort\":8000}"
        )
        assertEquals(0, decoded.udpStreamPort)
        assertEquals(0, decoded.tcpStreamPort)
        assertEquals("", decoded.tcpAuthToken)
        assertEquals(0, decoded.udpReceivePort)
    }
}
