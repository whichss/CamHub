package com.camhub.desktop

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraDiscoveryServiceTest {
    @Test
    fun `discovers Android fallback announcement`() {
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
            service.refresh()
            val bytes = "{\"service\":\"camhub\",\"ip\":\"10.0.0.99\",\"port\":43123}"
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
