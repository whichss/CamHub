package com.camhub.studio.data.network

import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpStreamAuthTest {
    @Test
    fun `writes the complete one-time token before reading the stream`() {
        val token = ByteArray(TCP_STREAM_AUTH_TOKEN_SIZE) { it.toByte() }
        val output = ByteArrayOutputStream()

        writeTcpStreamAuthToken(output, token)

        assertArrayEquals(token, output.toByteArray())
    }

    @Test
    fun `does not write bytes for legacy tcp streams without a token`() {
        val output = ByteArrayOutputStream()

        writeTcpStreamAuthToken(output, null)

        assertTrue(output.size() == 0)
    }

    @Test
    fun `decodes a valid session token from the paired control response`() {
        val token = ByteArray(TCP_STREAM_AUTH_TOKEN_SIZE) { it.toByte() }

        val decoded = decodeTcpStreamAuthToken(
            encodedToken = Base64.getEncoder().encodeToString(token),
            supportsAuthenticatedTcp = true,
            tcpPort = 8_123,
            sessionKey = ByteArray(32)
        )

        assertArrayEquals(token, decoded ?: error("Missing authenticated TCP token"))
    }

    @Test
    fun `accepts legacy responses that do not advertise a token`() {
        val decoded = decodeTcpStreamAuthToken(
            encodedToken = "",
            supportsAuthenticatedTcp = true,
            tcpPort = 8_123,
            sessionKey = ByteArray(32)
        )

        assertTrue(decoded == null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a token when the capability is not advertised`() {
        decodeTcpStreamAuthToken(
            encodedToken = Base64.getEncoder().encodeToString(ByteArray(32)),
            supportsAuthenticatedTcp = false,
            tcpPort = 8_123,
            sessionKey = ByteArray(32)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a token without an encrypted session key`() {
        decodeTcpStreamAuthToken(
            encodedToken = Base64.getEncoder().encodeToString(ByteArray(32)),
            supportsAuthenticatedTcp = true,
            tcpPort = 8_123,
            sessionKey = null
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects malformed token lengths`() {
        writeTcpStreamAuthToken(ByteArrayOutputStream(), byteArrayOf(1, 2, 3))
    }
}
