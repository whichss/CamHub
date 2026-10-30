package com.camhub.studio.data.network

import java.util.Base64

/** Validates the authenticated TCP fallback advertised on a paired CamHub control channel. */
fun decodeTcpStreamAuthToken(
    encodedToken: String,
    supportsAuthenticatedTcp: Boolean,
    tcpPort: Int,
    sessionKey: ByteArray?
): ByteArray? {
    if (!supportsAuthenticatedTcp) {
        require(encodedToken.isBlank()) {
            "TCP auth token sent without the authenticated TCP capability"
        }
        return null
    }

    require(tcpPort in 1..65_535) { "Invalid authenticated TCP port" }
    require(sessionKey?.size == 32) { "Authenticated TCP requires a 256-bit session key" }
    require(encodedToken.isNotBlank()) { "Authenticated TCP authorization token is missing" }

    val decoded = Base64.getDecoder().decode(encodedToken)
    require(decoded.size == TCP_STREAM_AUTH_TOKEN_SIZE) {
        "Invalid TCP stream authorization token size"
    }
    return decoded
}

private const val TCP_STREAM_AUTH_TOKEN_SIZE = 32
