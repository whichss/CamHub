package com.camhub.studio.data.network

import java.io.OutputStream
import java.util.Base64

/** Sends the control-session token before reading frames from an authenticated TCP video source. */
internal fun writeTcpStreamAuthToken(output: OutputStream, token: ByteArray?) {
    if (token == null) return
    require(token.size == TCP_STREAM_AUTH_TOKEN_SIZE) { "Invalid TCP stream authorization token size" }
    output.write(token)
    output.flush()
}

internal const val TCP_STREAM_AUTH_TOKEN_SIZE = 32

internal fun decodeTcpStreamAuthToken(
    encodedToken: String,
    supportsAuthenticatedTcp: Boolean,
    tcpPort: Int,
    sessionKey: ByteArray?
): ByteArray? {
    if (encodedToken.isBlank()) return null
    require(supportsAuthenticatedTcp) { "TCP auth token sent without the authenticated TCP capability" }
    require(tcpPort in 1..65_535) { "Invalid authenticated TCP port" }
    require(sessionKey?.size == 32) { "Authenticated TCP requires a 256-bit session key" }

    val decoded = Base64.getDecoder().decode(encodedToken)
    require(decoded.size == TCP_STREAM_AUTH_TOKEN_SIZE) { "Invalid TCP stream authorization token size" }
    return decoded
}
