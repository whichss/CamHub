package com.camhub.desktop

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CameraDiscoveryServiceTest {
    @Test
    fun `announcement name remains compatible with old peers`() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = json.decodeFromString<CameraAnnouncement>("{\"ip\":\"192.0.2.1\",\"port\":4000}")
        assertEquals("", legacy.deviceName)
        val current = CameraAnnouncement(ip = "192.0.2.1", port = 4000, deviceName = "Camera-A")
        assertEquals(current, json.decodeFromString<CameraAnnouncement>(json.encodeToString(current)))
    }

    @Test
    fun `legacy broadcast does not replace a named camera`() {
        val named = CountDownLatch(1)
        val updated = CountDownLatch(1)
        val awaitingLegacy = AtomicBoolean(false)
        val result = AtomicReference<CameraEndpoint>()
        val service = CameraDiscoveryService(onSnapshot = { endpoints ->
            endpoints.firstOrNull { it.signalingPort == 43124 }?.let {
                result.set(it)
                if (it.discoveredName == "Studio Camera") named.countDown()
                if (awaitingLegacy.get()) updated.countDown()
            }
        })
        val sender = DatagramSocket()
        try {
            service.start()
            val target = InetAddress.getLoopbackAddress()
            val first = "{\"port\":43124,\"deviceName\":\"Studio Camera\"}".toByteArray()
            repeat(10) {
                if (named.count == 0L) return@repeat
                sender.send(DatagramPacket(first, first.size, target, CameraDiscoveryService.ANNOUNCEMENT_PORT))
                named.await(100, TimeUnit.MILLISECONDS)
            }
            assertTrue(named.await(1, TimeUnit.SECONDS))
            awaitingLegacy.set(true)
            val legacy = "{\"port\":43124}".toByteArray()
            sender.send(DatagramPacket(legacy, legacy.size, target, CameraDiscoveryService.ANNOUNCEMENT_PORT))
            assertTrue(updated.await(1, TimeUnit.SECONDS))
            assertEquals("Studio Camera", result.get().discoveredName)
        } finally {
            sender.close()
            service.close()
        }
    }

    @Test
    fun `concurrent refresh and close complete without blocking`() {
        val service = CameraDiscoveryService(onSnapshot = {})
        val gate = CountDownLatch(1)
        val done = CountDownLatch(2)
        try {
            service.start()
            val refresh = Thread { try { gate.await(); service.refresh() } finally { done.countDown() } }
            val close = Thread { try { gate.await(); service.close() } finally { done.countDown() } }
            refresh.isDaemon = true
            close.isDaemon = true
            refresh.start()
            close.start()
            gate.countDown()
            assertTrue("concurrent search operations did not finish", done.await(8, TimeUnit.SECONDS))
        } finally {
            service.close()
        }
    }

    @Test
    fun `search receives announcements after repeated refresh`() {
        val latch = CountDownLatch(1)
        val service = CameraDiscoveryService(onSnapshot = { endpoints ->
            if (endpoints.any { it.signalingPort == 43210 }) latch.countDown()
        })
        val sender = DatagramSocket()
        try {
            service.start()
            repeat(3) { service.refresh() }
            val bytes = "{\"port\":43210}".toByteArray()
            repeat(20) {
                sender.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), CameraDiscoveryService.ANNOUNCEMENT_PORT))
                if (latch.await(100, TimeUnit.MILLISECONDS)) return@repeat
            }
            assertTrue("refreshed search did not receive an announcement", latch.await(1, TimeUnit.SECONDS))
        } finally {
            sender.close()
            service.close()
        }
    }

    @Test
    fun `discovers Android fallback announcement when default service field is omitted`() {
        val result = AtomicReference<CameraEndpoint>()
        val latch = CountDownLatch(1)
        val service = CameraDiscoveryService(
            onSnapshot = { endpoints ->
                endpoints.firstOrNull()?.let {
                    result.set(it)
                    latch.countDown()
                }
            }
        )
        val sender = DatagramSocket()

        try {
            service.start()
            val unrelated = "{\"service\":\"not-camhub\",\"ip\":\"10.0.0.98\",\"port\":40000}"
                .toByteArray()
            sender.send(
                DatagramPacket(
                    unrelated,
                    unrelated.size,
                    InetAddress.getLoopbackAddress(),
                    CameraDiscoveryService.ANNOUNCEMENT_PORT
                )
            )
            // NsdDiscoveryManager encodes UdpAnnounce with kotlinx.serialization's
            // encodeDefaults=false default, so service="camhub" is absent on the wire.
            val bytes = "{\"ip\":\"10.0.0.99\",\"port\":43123}"
                .toByteArray()
            repeat(5) {
                sender.send(
                    DatagramPacket(
                        bytes,
                        bytes.size,
                        InetAddress.getLoopbackAddress(),
                        CameraDiscoveryService.ANNOUNCEMENT_PORT
                    )
                )
                if (latch.await(100, TimeUnit.MILLISECONDS)) return@repeat
            }

            assertTrue(latch.await(1, TimeUnit.SECONDS))
            assertEquals(43_123, result.get().signalingPort)
            assertEquals(InetAddress.getLoopbackAddress().hostAddress, result.get().host)
        } finally {
            sender.close()
            service.close()
        }
    }
}
