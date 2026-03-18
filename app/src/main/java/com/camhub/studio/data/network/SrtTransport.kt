package com.camhub.studio.data.network

import android.util.Log
import io.github.thibaultbee.srtdroid.core.Srt
import io.github.thibaultbee.srtdroid.core.enums.SockOpt
import io.github.thibaultbee.srtdroid.core.enums.Transtype
import io.github.thibaultbee.srtdroid.core.models.SrtSocket

object SrtTransport {
    private const val TAG = "SrtTransport"
    const val DEFAULT_LATENCY_MS = 20

    @Volatile
    private var initialized = false

    fun startup(): Boolean {
        return try {
            val result = Srt.startUp()
            initialized = result >= 0
            if (initialized) Log.d(TAG, "SRT library initialized")
            else Log.w(TAG, "SRT startUp returned $result")
            initialized
        } catch (e: Throwable) {
            Log.w(TAG, "SRT library not available: ${e.message}")
            initialized = false
            false
        }
    }

    fun cleanup() {
        if (initialized) {
            try { Srt.cleanUp() } catch (_: Throwable) {}
            initialized = false
        }
    }

    fun isAvailable(): Boolean = initialized

    fun configureSocket(
        socket: SrtSocket,
        latencyMs: Int = DEFAULT_LATENCY_MS,
        passphrase: String? = null
    ) {
        // FILE mode = stream-oriented (no 1316-byte message size limit)
        // FILE mode disables TSBPD by design — reliable delivery without timestamp scheduling
        socket.setSockFlag(SockOpt.TRANSTYPE, Transtype.FILE)
        socket.setSockFlag(SockOpt.SNDBUF, 64 * 1024)
        socket.setSockFlag(SockOpt.RCVBUF, 64 * 1024)
        if (!passphrase.isNullOrEmpty()) {
            socket.setSockFlag(SockOpt.PBKEYLEN, 32)
            socket.setSockFlag(SockOpt.PASSPHRASE, passphrase)
        }
    }

    /** Convert 32-byte AES key to 64-char hex string (fits SRT's 10-79 char passphrase requirement) */
    fun sessionKeyToPassphrase(key: ByteArray): String {
        return key.joinToString("") { "%02x".format(it) }
    }
}
