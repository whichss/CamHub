package com.camhub.desktop

import com.camhub.studio.data.network.CamHubControlMessage
import java.awt.image.BufferedImage
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.prefs.AbstractPreferences
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopCameraConnectionPtzTest {
    @Test
    fun `transient camera disconnect reconnects and restores connected state`() {
        val serverIdentity = DesktopCameraIdentityStore(MemoryPreferences()).loadOrCreate()
        val server = serverIdentity.sslContext.serverSocketFactory
            .createServerSocket(0, 2, InetAddress.getLoopbackAddress()) as SSLServerSocket
        server.soTimeout = 8_000
        val clientPreferences = MemoryPreferences().node("client")
        val endpoint = CameraEndpoint("127.0.0.1", server.localPort, "Reconnecting test camera")
        clientPreferences.put(
            "camera_${endpoint.host}_${endpoint.signalingPort}",
            serverIdentity.certificateFingerprint
        )
        clientPreferences.put(
            "pairing_pin_${endpoint.host}_${endpoint.signalingPort}",
            serverIdentity.pairingPin
        )
        val firstConnected = CountDownLatch(1)
        val reconnected = CountDownLatch(1)
        val subscribedTwice = CountDownLatch(2)
        val connectionCount = java.util.concurrent.atomic.AtomicInteger(0)
        val reconnecting = CountDownLatch(1)
        val connection = DesktopCameraConnection(
            endpoint = endpoint,
            directorName = "Desktop test",
            trustedIdentities = clientPreferences,
            listener = object : DesktopCameraConnectionListener {
                override fun onState(connection: DesktopCameraConnection, snapshot: CameraConnectionSnapshot) {
                    if (snapshot.status == DesktopCameraStatus.CONNECTED) {
                        when (connectionCount.incrementAndGet()) {
                            1 -> firstConnected.countDown()
                            2 -> reconnected.countDown()
                        }
                    }
                    if (snapshot.status == DesktopCameraStatus.RECONNECTING) reconnecting.countDown()
                }

                override fun onImage(connection: DesktopCameraConnection, image: BufferedImage) = Unit
            }
        )
        val executor = Executors.newSingleThreadExecutor()
        val json = Json
        try {
            val sessions = executor.submit {
                repeat(2) { sessionIndex ->
                    (server.accept() as SSLSocket).use { peer ->
                        peer.soTimeout = 5_000
                        peer.startHandshake()
                        val reader = peer.inputStream.bufferedReader(Charsets.UTF_8)
                        val writer = peer.outputStream.writer(Charsets.UTF_8).buffered()
                        val hello = json.decodeFromString<CamHubControlMessage>(reader.readLine())
                        assertEquals("hello", hello.type)
                        writer.write(
                            json.encodeToString(
                                CamHubControlMessage(
                                    type = "hello_ack",
                                    deviceName = endpoint.discoveredName,
                                    sessionKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
                                    udpStreamPort = 50_000
                                )
                            )
                        )
                        writer.newLine()
                        writer.flush()

                        var subscribed = false
                        while (true) {
                            val line = reader.readLine() ?: break
                            val message = json.decodeFromString<CamHubControlMessage>(line)
                            if (message.command == "udp_subscribe") {
                                subscribed = true
                                subscribedTwice.countDown()
                                if (sessionIndex == 0) break // Simulate an abrupt camera/network drop.
                            }
                            if (message.command == "udp_unsubscribe") break
                        }
                        assertTrue("desktop never subscribed to camera video", subscribed)
                    }
                }
            }

            connection.start()
            assertTrue("first camera session did not connect", firstConnected.await(8, TimeUnit.SECONDS))
            assertTrue("desktop did not reconnect after camera disconnect", reconnecting.await(3, TimeUnit.SECONDS))
            assertTrue("second camera session did not connect", reconnected.await(8, TimeUnit.SECONDS))
            assertTrue("desktop did not resubscribe after reconnect", subscribedTwice.await(5, TimeUnit.SECONDS))
            connection.close()
            sessions.get(5, TimeUnit.SECONDS)
        } finally {
            connection.close()
            server.close()
            executor.shutdownNow()
            executor.awaitTermination(3, TimeUnit.SECONDS)
            clientPreferences.removeNode()
        }
    }

    @Test
    fun `pairing rejection becomes terminal error without automatic reconnect`() {
        val serverIdentity = DesktopCameraIdentityStore(MemoryPreferences()).loadOrCreate()
        val server = serverIdentity.sslContext.serverSocketFactory
            .createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        server.soTimeout = 8_000
        val clientPreferences = MemoryPreferences().node("client")
        val endpoint = CameraEndpoint("127.0.0.1", server.localPort, "Rejecting test camera")
        clientPreferences.put(
            "camera_${endpoint.host}_${endpoint.signalingPort}",
            serverIdentity.certificateFingerprint
        )
        clientPreferences.put(
            "pairing_pin_${endpoint.host}_${endpoint.signalingPort}",
            serverIdentity.pairingPin
        )
        val errorReported = CountDownLatch(1)
        val connection = DesktopCameraConnection(
            endpoint = endpoint,
            directorName = "Desktop test",
            trustedIdentities = clientPreferences,
            listener = object : DesktopCameraConnectionListener {
                override fun onState(connection: DesktopCameraConnection, snapshot: CameraConnectionSnapshot) {
                    if (snapshot.status == DesktopCameraStatus.ERROR) errorReported.countDown()
                }

                override fun onImage(connection: DesktopCameraConnection, image: BufferedImage) = Unit
            }
        )
        val executor = Executors.newSingleThreadExecutor()
        val json = Json
        try {
            val firstConnection = executor.submit {
                (server.accept() as SSLSocket).use { peer ->
                    peer.soTimeout = 5_000
                    peer.startHandshake()
                    val reader = peer.inputStream.bufferedReader(Charsets.UTF_8)
                    val writer = peer.outputStream.writer(Charsets.UTF_8).buffered()
                    val hello = json.decodeFromString<CamHubControlMessage>(reader.readLine())
                    assertEquals("hello", hello.type)
                    writer.write(json.encodeToString(CamHubControlMessage(type = "error")))
                    writer.newLine()
                    writer.flush()
                }
            }

            connection.start()
            assertTrue("pairing rejection was not reported", errorReported.await(8, TimeUnit.SECONDS))
            firstConnection.get(5, TimeUnit.SECONDS)
            assertEquals(
                null,
                clientPreferences.get("pairing_pin_${endpoint.host}_${endpoint.signalingPort}", null)
            )

            server.soTimeout = 1_200
            val reconnected = try {
                server.accept().use { true }
            } catch (_: SocketTimeoutException) {
                false
            }
            assertFalse("pairing rejection should wait for user action, not retry", reconnected)
        } finally {
            connection.close()
            server.close()
            executor.shutdownNow()
            executor.awaitTermination(3, TimeUnit.SECONDS)
            clientPreferences.removeNode()
        }
    }

    @Test
    fun `remote PTZ command is clamped and matching camera acknowledgement updates state`() {
        val serverIdentity = DesktopCameraIdentityStore(MemoryPreferences()).loadOrCreate()
        val server = serverIdentity.sslContext.serverSocketFactory
            .createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        server.soTimeout = 8_000
        val clientPreferences = MemoryPreferences().node("client")
        val endpoint = CameraEndpoint("127.0.0.1", server.localPort, "Test camera")
        clientPreferences.put(
            "camera_${endpoint.host}_${endpoint.signalingPort}",
            serverIdentity.certificateFingerprint
        )
        clientPreferences.put(
            "pairing_pin_${endpoint.host}_${endpoint.signalingPort}",
            serverIdentity.pairingPin
        )

        val connected = CountDownLatch(1)
        val ptzApplied = CountDownLatch(1)
        val appliedRequestId = java.util.concurrent.atomic.AtomicLong(0L)
        val connection = DesktopCameraConnection(
            endpoint = endpoint,
            directorName = "Desktop test",
            trustedIdentities = clientPreferences,
            listener = object : DesktopCameraConnectionListener {
                override fun onState(connection: DesktopCameraConnection, snapshot: CameraConnectionSnapshot) {
                    if (snapshot.status == DesktopCameraStatus.CONNECTED) connected.countDown()
                    if (snapshot.ptzAppliedRequestId > 0L) {
                        appliedRequestId.set(snapshot.ptzAppliedRequestId)
                        ptzApplied.countDown()
                    }
                }

                override fun onImage(connection: DesktopCameraConnection, image: BufferedImage) = Unit
            }
        )
        val executor = Executors.newSingleThreadExecutor()
        val json = Json
        try {
            val receivedRequest = executor.submit<CamHubControlMessage> {
                (server.accept() as SSLSocket).use { peer ->
                    peer.soTimeout = 8_000
                    peer.startHandshake()
                    val reader = peer.inputStream.bufferedReader(Charsets.UTF_8)
                    val writer = peer.outputStream.writer(Charsets.UTF_8).buffered()
                    val hello = json.decodeFromString<CamHubControlMessage>(reader.readLine())
                    assertEquals("hello", hello.type)
                    assertEquals(serverIdentity.pairingPin, hello.pin)
                    writer.apply {
                        write(
                            json.encodeToString(
                                CamHubControlMessage(
                                    type = "hello_ack",
                                    deviceName = "Test camera",
                                    sessionKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
                                    udpStreamPort = 50_000,
                                    supportsRemotePtz = true,
                                    minZoomRatio = 1f,
                                    maxZoomRatio = 4f
                                )
                            )
                        )
                        newLine()
                        flush()
                    }

                    repeat(4) {
                        val line = reader.readLine() ?: error("Control connection closed before PTZ request")
                        val message = json.decodeFromString<CamHubControlMessage>(line)
                        if (message.command == "set_ptz") {
                            writer.apply {
                                write(
                                    json.encodeToString(
                                        CamHubControlMessage(
                                            type = "ptz_applied",
                                            value = 2f,
                                            stringValue = "0.5,0.5",
                                            requestId = message.requestId + 1
                                        )
                                    )
                                )
                                newLine()
                                write(
                                    json.encodeToString(
                                        CamHubControlMessage(
                                            type = "ptz_applied",
                                            value = 3.5f,
                                            stringValue = "0.31,0.69",
                                            requestId = message.requestId
                                        )
                                    )
                                )
                                newLine()
                                flush()
                            }
                            return@submit message
                        }
                    }
                    error("Desktop did not send a PTZ request")
                }
            }

            connection.start()
            assertTrue("camera TLS handshake did not complete", connected.await(8, TimeUnit.SECONDS))
            assertTrue(connection.requestPtz(zoom = 8f, centerX = 2f, centerY = -1f))

            val request = receivedRequest.get(8, TimeUnit.SECONDS)
            assertEquals("command", request.type)
            assertEquals("set_ptz", request.command)
            assertEquals(4f, request.value, 0f)
            assertTrue(request.requestId > 0L)
            val coordinates = request.stringValue.split(',')
            assertEquals(0.875f, coordinates[0].toFloat(), 0.0001f)
            assertEquals(0.125f, coordinates[1].toFloat(), 0.0001f)
            assertTrue("camera PTZ acknowledgement was not applied", ptzApplied.await(5, TimeUnit.SECONDS))
            assertEquals(request.requestId, appliedRequestId.get())
        } finally {
            connection.close()
            server.close()
            executor.shutdownNow()
            executor.awaitTermination(3, TimeUnit.SECONDS)
            clientPreferences.removeNode()
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
