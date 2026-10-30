package com.camhub.studio.data.network

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CamHubControlMessageTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `hello matches shared Android wire fixture`() = assertFixture(
        "control_hello",
        CamHubControlMessage(type = "hello", deviceName = "Hub", pin = "12345678")
    )

    @Test
    fun `hello acknowledgement matches shared camera wire fixture`() = assertFixture(
        "control_hello_ack",
        CamHubControlMessage(
            type = "hello_ack",
            deviceName = "CamHub iOS",
            sessionKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            tcpStreamPort = 50_001,
            tcpAuthToken = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=",
            supportsAuthenticatedTcp = true,
            udpStreamPort = 50_000
        )
    )

    @Test
    fun `udp subscription matches shared Android wire fixture`() = assertFixture(
        "control_udp_subscribe",
        CamHubControlMessage(
            type = "command",
            deviceName = "Hub",
            command = "udp_subscribe",
            udpReceivePort = 40_000
        )
    )

    @Test
    fun `authenticated TCP capability defaults to false and round trips`() {
        val legacy = json.decodeFromString<CamHubControlMessage>("{\"type\":\"hello_ack\"}")
        assertEquals(false, legacy.supportsAuthenticatedTcp)
        val enabled = CamHubControlMessage(type = "hello_ack", supportsAuthenticatedTcp = true)
        assertEquals(enabled, json.decodeFromString<CamHubControlMessage>(json.encodeToString(enabled)))
    }

    private fun assertFixture(name: String, expected: CamHubControlMessage) {
        val wireJson = WireGoldenVectors.string(name)
        assertEquals(
            json.parseToJsonElement(wireJson),
            json.parseToJsonElement(json.encodeToString(expected))
        )
        assertEquals(expected, json.decodeFromString<CamHubControlMessage>(wireJson))
    }
}
