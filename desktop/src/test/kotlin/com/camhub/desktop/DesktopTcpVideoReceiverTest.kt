package com.camhub.desktop

import com.camhub.studio.data.network.CamHubVideoPayload
import com.camhub.studio.data.network.CamHubVideoPayloadCodec
import com.camhub.studio.data.network.FrameCipher
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopTcpVideoReceiverTest {
    @Test
    fun `receives and decrypts Android-compatible framed video on worker thread`() {
        val server = ServerSocket(0)
        val key = ByteArray(32) { (it * 3).toByte() }
        val authToken = ByteArray(32) { (it * 7).toByte() }
        val h264 = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22)
        val encoded = CamHubVideoPayloadCodec.encode(
            width = 1_280,
            height = 720,
            rotationDegrees = 90,
            isKeyFrame = true,
            isCodecConfig = false,
            frameSequence = 42,
            captureAtWallMs = 100,
            encodedAtWallMs = 110,
            h264Bytes = h264
        )
        val received = AtomicReference<CamHubVideoPayload?>()
        val receiveThread = AtomicReference<String>()
        val latch = CountDownLatch(1)
        val receiver = DesktopTcpVideoReceiver(
            cameraName = "TCP Test",
            host = "127.0.0.1",
            port = server.localPort,
            sessionKey = key,
            authToken = authToken,
            onFrame = { frame ->
                received.set(frame)
                receiveThread.set(Thread.currentThread().name)
                latch.countDown()
            },
            onError = { throw AssertionError("Unexpected TCP receive error", it) }
        )
        var acceptedSocket: Socket? = null

        try {
            receiver.start()
            val client = server.accept()
            acceptedSocket = client
            val receivedToken = ByteArray(authToken.size)
            DataInputStream(client.getInputStream()).readFully(receivedToken)
            assertArrayEquals(authToken, receivedToken)
            val encrypted = FrameCipher(key).encrypt(encoded)
            val output = DataOutputStream(client.getOutputStream())
            output.writeInt(encrypted.size)
            output.write(encrypted)
            output.flush()
            assertTrue("TCP frame was not received", latch.await(2, TimeUnit.SECONDS))
            assertEquals(42L, received.get()?.frameSequence)
            assertEquals(90, received.get()?.rotationDegrees)
            assertArrayEquals(h264, received.get()?.h264Bytes)
            assertEquals("DesktopTcpReceive-TCP_Test", receiveThread.get())
        } finally {
            receiver.close()
            acceptedSocket?.close()
            server.close()
        }
    }
}
