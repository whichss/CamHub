package com.camhub.desktop

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.prefs.AbstractPreferences
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopCameraIdentityStoreTest {
    @Test
    fun `identity persists and completes a pinned TLS handshake`() {
        val testPreferences = MemoryPreferences().node("camhub")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = DesktopCameraIdentityStore(testPreferences).loadOrCreate()
            val second = DesktopCameraIdentityStore(testPreferences).loadOrCreate()
            assertEquals(first.pairingPin, second.pairingPin)
            assertEquals(first.certificateFingerprint, second.certificateFingerprint)
            assertTrue(first.pairingPin.matches(Regex("[0-9]{8}")))

            val server = first.sslContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
            server.soTimeout = 5_000
            try {
                val serverFingerprint = executor.submit<String> {
                    (server.accept() as SSLSocket).use { peer ->
                        peer.soTimeout = 5_000
                        peer.startHandshake()
                        val certificate = peer.session.localCertificates.first() as X509Certificate
                        MessageDigest.getInstance("SHA-256")
                            .digest(certificate.encoded)
                            .joinToString("") { "%02X".format(it) }
                    }
                }
                val clientContext = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf(object : javax.net.ssl.X509TrustManager {
                        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    }), null)
                }
                (clientContext.socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket).use {
                    it.soTimeout = 5_000
                    it.startHandshake()
                }
                assertEquals(
                    first.certificateFingerprint,
                    serverFingerprint.get(5, TimeUnit.SECONDS)
                )
            } finally {
                server.close()
            }
        } finally {
            executor.shutdownNow()
            testPreferences.removeNode()
        }
    }

    private class MemoryPreferences(
        parent: AbstractPreferences? = null,
        name: String = ""
    ) : AbstractPreferences(parent, name) {
        private val values = ConcurrentHashMap<String, String>()
        private val children = ConcurrentHashMap<String, MemoryPreferences>()

        override fun putSpi(key: String, value: String) { values[key] = value }
        override fun getSpi(key: String): String? = values[key]
        override fun removeSpi(key: String) { values.remove(key) }
        override fun removeNodeSpi() {
            values.clear()
            children.clear()
        }
        override fun keysSpi(): Array<String> = values.keys.toTypedArray()
        override fun childrenNamesSpi(): Array<String> = children.keys.toTypedArray()
        override fun childSpi(name: String): AbstractPreferences =
            children.computeIfAbsent(name) { childName -> MemoryPreferences(this, childName) }
        override fun syncSpi() = Unit
        override fun flushSpi() = Unit
    }
}
