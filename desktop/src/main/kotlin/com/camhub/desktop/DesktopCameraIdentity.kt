package com.camhub.desktop

import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.prefs.Preferences
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

internal data class DesktopCameraIdentity(
    val pairingPin: String,
    val certificateFingerprint: String,
    val sslContext: SSLContext
)

/** Stable self-signed RSA identity compatible with Android/iOS SHA256withRSA pinning. */
internal class DesktopCameraIdentityStore(
    private val preferences: Preferences = Preferences.userNodeForPackage(DesktopCameraIdentityStore::class.java)
) {
    companion object {
        private const val PRIVATE_KEY_KEY = "source_private_key_pkcs8"
        private const val CERTIFICATE_KEY = "source_certificate_der"
        private const val PAIRING_PIN_KEY = "source_pairing_pin"
        private const val KEY_ALIAS = "camhub"
    }

    @Synchronized
    fun loadOrCreate(): DesktopCameraIdentity {
        val (privateKey, certificate) = loadSavedIdentity() ?: createAndStoreIdentity()
        val pin = preferences.get(PAIRING_PIN_KEY, null)
            ?.takeIf { it.matches(Regex("[0-9]{8}")) }
            ?: SecureRandom().nextInt(100_000_000).toString().padStart(8, '0').also {
                preferences.put(PAIRING_PIN_KEY, it)
                preferences.flush()
            }
        val keyStorePassword = ByteArray(32).also(SecureRandom()::nextBytes)
            .let { Base64.getEncoder().encodeToString(it).toCharArray() }
        val keyStore = java.security.KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(KEY_ALIAS, privateKey, keyStorePassword, arrayOf(certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, keyStorePassword)
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(keyManagers.keyManagers, null, SecureRandom())
        }
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02X".format(it) }
        return DesktopCameraIdentity(pin, fingerprint, sslContext)
    }

    private fun loadSavedIdentity(): Pair<java.security.PrivateKey, X509Certificate>? = runCatching {
        val keyBytes = Base64.getDecoder().decode(preferences.get(PRIVATE_KEY_KEY, null) ?: return null)
        val certificateBytes = Base64.getDecoder().decode(preferences.get(CERTIFICATE_KEY, null) ?: return null)
        val privateKey = java.security.KeyFactory.getInstance("RSA")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyBytes))
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificateBytes)) as X509Certificate
        certificate.verify(certificate.publicKey)
        val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
        val proof = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(challenge)
            sign()
        }
        check(Signature.getInstance("SHA256withRSA").run {
            initVerify(certificate.publicKey)
            update(challenge)
            verify(proof)
        })
        privateKey to certificate
    }.getOrNull()

    private fun createAndStoreIdentity(): Pair<java.security.PrivateKey, X509Certificate> {
        val pair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2_048, SecureRandom())
        }.generateKeyPair()
        val certificateBytes = selfSignedCertificate(pair)
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificateBytes)) as X509Certificate
        preferences.put(PRIVATE_KEY_KEY, Base64.getEncoder().encodeToString(pair.private.encoded))
        preferences.put(CERTIFICATE_KEY, Base64.getEncoder().encodeToString(certificateBytes))
        preferences.flush()
        return pair.private to certificate
    }

    private fun selfSignedCertificate(pair: KeyPair): ByteArray {
        val algorithm = sequence(oid(byteArrayOf(
            0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B
        )) + byteArrayOf(0x05, 0x00))
        val name = sequence(set(sequence(
            oid(byteArrayOf(0x55, 0x04, 0x03)) + tagged(0x0C, "CamHub Desktop".toByteArray(Charsets.UTF_8))
        )))
        val validity = sequence(utcTime(Date(System.currentTimeMillis() - 86_400_000L)) +
            utcTime(Date(System.currentTimeMillis() + 10L * 365 * 86_400_000L)))
        val serial = ByteArray(16).also(SecureRandom()::nextBytes).apply {
            this[0] = (this[0].toInt() and 0x7f).toByte()
            if (all { it == 0.toByte() }) this[lastIndex] = 1
        }
        val tbs = sequence(
            tagged(0xA0, integer(byteArrayOf(0x02))) + integer(serial) + algorithm + name +
                validity + name + pair.public.encoded
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update(tbs)
            sign()
        }
        return sequence(tbs + algorithm + bitString(signature))
    }

    private fun sequence(content: ByteArray) = tagged(0x30, content)
    private fun set(content: ByteArray) = tagged(0x31, content)
    private fun oid(content: ByteArray) = tagged(0x06, content)
    private fun integer(content: ByteArray): ByteArray {
        val positive = if ((content.first().toInt() and 0x80) != 0) byteArrayOf(0) + content else content
        return tagged(0x02, positive)
    }
    private fun bitString(content: ByteArray) = tagged(0x03, byteArrayOf(0) + content)
    private fun utcTime(date: Date): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return tagged(0x17, format.format(date).toByteArray(Charsets.US_ASCII))
    }

    private fun tagged(tag: Int, content: ByteArray): ByteArray {
        val length = when {
            content.size < 0x80 -> byteArrayOf(content.size.toByte())
            content.size <= 0xff -> byteArrayOf(0x81.toByte(), content.size.toByte())
            content.size <= 0xffff -> byteArrayOf(
                0x82.toByte(), (content.size shr 8).toByte(), content.size.toByte()
            )
            else -> throw IllegalArgumentException("Certificate DER field is too large")
        }
        return byteArrayOf(tag.toByte()) + length + content
    }
}
