package com.camhub.studio.data.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import android.net.Network
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Provides TLS socket creation for encrypted signaling.
 * Uses a self-signed certificate (generated once on first launch).
 * PIN-based authentication is the trust mechanism, so clients use trust-all.
 */
@Singleton
class TlsHelper @Inject constructor() {
    companion object {
        private const val KEY_ALIAS = "camhub"
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val TLS_HANDSHAKE_TIMEOUT_MS = 5_000
        // Dynamic password generated per app session — keystore is in-memory only
        private val KS_PASSWORD: CharArray = ByteArray(16).also {
            SecureRandom().nextBytes(it)
        }.joinToString("") { "%02x".format(it) }.toCharArray()
    }

    private val serverSslContext: SSLContext by lazy { buildServerContext() }
    private val clientSslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAllManager), SecureRandom())
        }
    }

    private fun buildServerContext(): SSLContext {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)

        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom())
        }.generateKeyPair()

        val cert = buildSelfSignedCert(keyPair)
        ks.setKeyEntry(KEY_ALIAS, keyPair.private, KS_PASSWORD, arrayOf(cert))

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, KS_PASSWORD)

        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf(trustAllManager), SecureRandom())
        }
    }

    /** Camera side: create TLS server socket */
    fun createServerSocket(port: Int): SSLServerSocket {
        val ss = serverSslContext.serverSocketFactory.createServerSocket(port) as SSLServerSocket
        ss.needClientAuth = false
        return ss
    }

    /** Director side: create TLS client socket (trust-all; PIN handles auth) */
    fun createClientSocket(ip: String, port: Int, network: Network? = null): SSLSocket {
        val transport = (network?.socketFactory?.createSocket() ?: Socket()).apply {
            tcpNoDelay = true
            connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
        }
        return try {
            (clientSslContext.socketFactory.createSocket(transport, ip, port, true) as SSLSocket).apply {
                soTimeout = TLS_HANDSHAKE_TIMEOUT_MS
                startHandshake()
            }
        } catch (error: Exception) {
            runCatching { transport.close() }
            throw error
        }
    }

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    // ---- Self-signed X.509 certificate via raw DER ----

    private fun buildSelfSignedCert(keyPair: java.security.KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 86400000L)         // yesterday
        val notAfter = Date(now + 365L * 86400000L)   // +1 year
        val serial = BigInteger.valueOf(now)

        val tbs = buildTbs(keyPair.public.encoded, serial, notBefore, notAfter)
        val sig = java.security.Signature.getInstance("SHA256withRSA").apply {
            initSign(keyPair.private)
            update(tbs)
        }.sign()

        val certDer = seq(tbs + sigAlgSeq() + bitString(sig))
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
    }

    private fun buildTbs(
        pubKeyEncoded: ByteArray,
        serial: BigInteger,
        notBefore: Date,
        notAfter: Date
    ): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(explicit(0, integer(BigInteger.valueOf(2))))  // v3
        buf.write(integer(serial))
        buf.write(sigAlgSeq())
        buf.write(cnName("CamHub"))                             // issuer
        buf.write(seq(utcTime(notBefore) + utcTime(notAfter)))  // validity
        buf.write(cnName("CamHub"))                             // subject
        buf.write(pubKeyEncoded)                                // subjectPublicKeyInfo
        return seq(buf.toByteArray())
    }

    // SHA256withRSA OID = 1.2.840.113549.1.1.11
    private val sha256RsaOid = byteArrayOf(
        0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(),
        0x0D, 0x01, 0x01, 0x0B
    )

    private fun sigAlgSeq() = seq(oid(sha256RsaOid) + byteArrayOf(0x05, 0x00))

    private fun cnName(value: String): ByteArray {
        // CN OID = 2.5.4.3
        val cnOid = oid(byteArrayOf(0x55, 0x04, 0x03))
        val cnVal = utf8String(value)
        val atv = seq(cnOid + cnVal)
        val rdn = tagged(0x31, atv)
        return seq(rdn)
    }

    // DER primitives
    private fun seq(content: ByteArray) = tagged(0x30, content)
    private fun integer(v: BigInteger): ByteArray { val b = v.toByteArray(); return tagged(0x02, b) }
    private fun oid(bytes: ByteArray) = tagged(0x06, bytes)
    private fun utf8String(s: String): ByteArray { val b = s.toByteArray(Charsets.UTF_8); return tagged(0x0C, b) }
    private fun bitString(data: ByteArray) = tagged(0x03, byteArrayOf(0x00) + data)
    private fun explicit(tag: Int, content: ByteArray) = tagged(0xA0 + tag, content)

    @Suppress("SimpleDateFormat")
    private fun utcTime(date: Date): ByteArray {
        val fmt = java.text.SimpleDateFormat("yyMMddHHmmss'Z'").apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        return tagged(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII))
    }

    private fun tagged(tag: Int, content: ByteArray): ByteArray {
        val len = derLen(content.size)
        return byteArrayOf(tag.toByte()) + len + content
    }

    private fun derLen(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), (n and 0xFF).toByte())
    }
}
