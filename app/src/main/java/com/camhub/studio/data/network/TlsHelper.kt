package com.camhub.studio.data.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
import java.security.MessageDigest
import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Provides TLS socket creation for encrypted signaling.
 * Uses a stable self-signed camera certificate. Clients pin its SHA-256 fingerprint
 * only after the operator compares it with the value shown on the camera device.
 */
@Singleton
class TlsHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val KEY_ALIAS = "camhub"
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val TLS_HANDSHAKE_TIMEOUT_MS = 5_000
    }

    @Volatile private var serverCertificate: X509Certificate? = null
    private val serverSslContext: SSLContext by lazy { buildServerContext() }
    private val clientSslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAllManager), SecureRandom())
        }
    }

    private fun buildServerContext(): SSLContext {
        val identityFile = File(context.filesDir, "camhub-tls-identity.p12")
        val password = loadOrCreateIdentityPassword()
        val ks = KeyStore.getInstance("PKCS12")
        val loaded = runCatching {
            FileInputStream(identityFile).use { ks.load(it, password) }
            ks.containsAlias(KEY_ALIAS)
        }.getOrDefault(false)

        if (!loaded) {
            ks.load(null, null)
            val keyPair = KeyPairGenerator.getInstance("RSA").apply {
                initialize(2048, SecureRandom())
            }.generateKeyPair()
            val cert = buildSelfSignedCert(keyPair)
            ks.setKeyEntry(KEY_ALIAS, keyPair.private, password, arrayOf(cert))
            runCatching {
                FileOutputStream(identityFile).use { output ->
                    ks.store(output, password)
                    output.fd.sync()
                }
            }.onFailure { throw IllegalStateException("Unable to persist CamHub TLS identity", it) }
        }
        serverCertificate = ks.getCertificate(KEY_ALIAS) as X509Certificate

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, password)

        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf(trustAllManager), SecureRandom())
        }
    }

    private fun loadOrCreateIdentityPassword(): CharArray {
        val prefs = context.getSharedPreferences("camhub_tls_identity", Context.MODE_PRIVATE)
        val saved = prefs.getString("password", null)
        if (saved != null) return saved.toCharArray()
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        check(prefs.edit().putString("password", encoded).commit()) {
            "Unable to persist CamHub TLS identity password"
        }
        return encoded.toCharArray()
    }

    /** Stable SHA-256 certificate fingerprint shown on the camera for out-of-band pairing. */
    fun localCertificateFingerprint(): String {
        serverSslContext
        val cert = checkNotNull(serverCertificate)
        return fingerprint(cert)
    }

    fun isPeerFingerprintTrusted(ip: String, fingerprint: String): Boolean =
        context.getSharedPreferences("camhub_trusted_peers", Context.MODE_PRIVATE)
            .getString(ip, null)?.equals(fingerprint, ignoreCase = true) == true

    fun trustPeerFingerprint(ip: String, fingerprint: String) {
        require(ip.isNotBlank() && fingerprint.matches(Regex("[0-9A-Fa-f]{64}")))
        check(
            context.getSharedPreferences("camhub_trusted_peers", Context.MODE_PRIVATE)
                .edit().putString(ip, fingerprint.uppercase()).commit()
        ) { "Unable to save trusted CamHub identity" }
    }

    @Synchronized
    fun localPairingPin(): String {
        val prefs = context.getSharedPreferences("camhub_tls_identity", Context.MODE_PRIVATE)
        prefs.getString("pairing_pin", null)?.let { return it }
        val value = java.security.SecureRandom().nextInt(100_000_000).toString().padStart(8, '0')
        check(prefs.edit().putString("pairing_pin", value).commit()) {
            "Unable to persist CamHub pairing PIN"
        }
        return value
    }

    fun pairingPinForPeer(ip: String): String? =
        context.getSharedPreferences("camhub_trusted_peers", Context.MODE_PRIVATE)
            .getString("pin:$ip", null)

    fun savePeerPairingPin(ip: String, pin: String) {
        require(pin.matches(Regex("[0-9]{8}")))
        check(
            context.getSharedPreferences("camhub_trusted_peers", Context.MODE_PRIVATE)
                .edit().putString("pin:$ip", pin).commit()
        ) { "Unable to save camera pairing PIN" }
    }

    fun removePeerPairingPin(ip: String) {
        context.getSharedPreferences("camhub_trusted_peers", Context.MODE_PRIVATE)
            .edit().remove("pin:$ip").apply()
    }

    fun isLocalPairingPinValid(candidate: String): Boolean =
        candidate.matches(Regex("[0-9]{8}")) &&
            MessageDigest.isEqual(
                localPairingPin().toByteArray(Charsets.US_ASCII),
                candidate.toByteArray(Charsets.US_ASCII)
            )

    fun certificateFingerprint(socket: SSLSocket): String {
        val cert = socket.session.peerCertificates.firstOrNull() as? X509Certificate
            ?: throw IllegalStateException("Camera did not provide an X.509 certificate")
        return fingerprint(cert)
    }

    private fun fingerprint(cert: X509Certificate): String = MessageDigest
        .getInstance("SHA-256")
        .digest(cert.encoded)
        .joinToString("") { "%02X".format(it) }

    /** Camera side: create TLS server socket */
    fun createServerSocket(port: Int): SSLServerSocket {
        val ss = serverSslContext.serverSocketFactory.createServerSocket(port) as SSLServerSocket
        ss.needClientAuth = false
        return ss
    }

    /** Director side: establish TLS; the camera fingerprint is checked before app data is sent. */
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
