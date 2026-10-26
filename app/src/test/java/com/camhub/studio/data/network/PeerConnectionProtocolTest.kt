package com.camhub.studio.data.network

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PeerConnectionProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ptz request id survives signaling serialization`() {
        val source = HandshakeMessage(
            type = "command",
            command = "set_ptz",
            value = 2.5f,
            stringValue = "0.7,0.3",
            requestId = 42L
        )

        val decoded = json.decodeFromString<HandshakeMessage>(json.encodeToString(source))

        assertEquals(42L, decoded.requestId)
        assertEquals("set_ptz", decoded.command)
        assertEquals(2.5f, decoded.value)
        assertEquals("0.7,0.3", decoded.stringValue)
    }

    @Test
    fun `older signaling message defaults request id to zero`() {
        val decoded = json.decodeFromString<HandshakeMessage>(
            """{"type":"command","command":"set_zoom","value":2.0}"""
        )

        assertEquals(0L, decoded.requestId)
        assertEquals("set_zoom", decoded.command)
    }

    @Test
    fun `pairing rejection and temporary lockout have distinct response codes`() {
        val invalidPin = json.decodeFromString<HandshakeMessage>(
            json.encodeToString(HandshakeMessage(type = "error", stringValue = "invalid_pairing_pin"))
        )
        val rateLimited = json.decodeFromString<HandshakeMessage>(
            json.encodeToString(HandshakeMessage(type = "error", stringValue = "pairing_rate_limited"))
        )

        assertEquals("invalid_pairing_pin", invalidPin.stringValue)
        assertEquals("pairing_rate_limited", rateLimited.stringValue)
    }
}
