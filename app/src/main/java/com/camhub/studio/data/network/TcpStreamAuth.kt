package com.camhub.studio.data.network

import java.io.OutputStream

/** Sends the control-session token before reading frames from an authenticated TCP video source. */
internal fun writeTcpStreamAuthToken(output: OutputStream, token: ByteArray?) {
    if (token == null) return
    require(token.size == TCP_STREAM_AUTH_TOKEN_SIZE) { "Invalid TCP stream authorization token size" }
    output.write(token)
    output.flush()
}

internal const val TCP_STREAM_AUTH_TOKEN_SIZE = 32
