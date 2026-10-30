package com.camhub.desktop

import java.awt.image.BufferedImage
import java.awt.Color
import java.io.DataInputStream
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeNoException

class DesktopPgmObsOutputTest {
    @Test
    fun `browser source carries PGM auto transition even when the source frame is unchanged`() {
        val image = BufferedImage(32, 18, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { graphics ->
            try { graphics.color = Color.RED; graphics.fillRect(0, 0, 32, 18) }
            finally { graphics.dispose() }
        }
        val frame = DesktopVideoFrame(image)
        val alpha = AtomicReference(0f)
        val output = DesktopPgmObsOutput(
            frameProvider = { frame },
            transitionAlphaProvider = alpha::get
        )
        var connection: HttpURLConnection? = null
        try {
            connection = URI(output.start()).resolve("pgm.mjpg").toURL().openConnection() as HttpURLConnection
            connection.readTimeout = 2_000
            val input = DataInputStream(connection.inputStream)
            assertTrue(Color(readFrame(input).getRGB(5, 5)).red > 200)

            alpha.set(1f)
            val becameBlack = (1..20).any {
                val pixel = Color(readFrame(input).getRGB(5, 5))
                pixel.red < 10 && pixel.green < 10 && pixel.blue < 10
            }
            assertTrue("OBS output should follow the current transition alpha", becameBlack)
        } finally {
            connection?.disconnect()
            output.close()
        }
    }

    @Test
    fun `stream replaces a lost PGM with black`() {
        val image = BufferedImage(32, 18, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { graphics ->
            try { graphics.color = Color.RED; graphics.fillRect(0, 0, 32, 18) }
            finally { graphics.dispose() }
        }
        val current = AtomicReference<DesktopVideoFrame?>(DesktopVideoFrame(image))
        val output = DesktopPgmObsOutput(frameProvider = { current.get() })
        var connection: HttpURLConnection? = null
        try {
            connection = URI(output.start()).resolve("pgm.mjpg").toURL().openConnection() as HttpURLConnection
            connection.readTimeout = 2_000
            val input = DataInputStream(connection.inputStream)
            val first = readFrame(input)
            assertTrue(Color(first.getRGB(5, 5)).red > 200)
            current.set(null)
            val becameBlack = (1..20).any {
                val pixel = Color(readFrame(input).getRGB(5, 5))
                pixel.red < 10 && pixel.green < 10 && pixel.blue < 10
            }
            assertTrue(becameBlack)
        } finally {
            connection?.disconnect()
            output.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun readFrame(input: DataInputStream): BufferedImage {
        var line: String
        do { line = input.readLine() ?: error("Missing frame boundary") } while (line.isEmpty())
        assertEquals("--camhub-frame", line)
        assertEquals("Content-Type: image/jpeg", input.readLine())
        val length = input.readLine().substringAfter("Content-Length: ").toInt()
        assertEquals("", input.readLine())
        return ImageIO.read(ByteArrayInputStream(input.readNBytes(length)))
    }
    @Test
    fun `browser source sends a blank frame when no PGM is available`() {
        val output = DesktopPgmObsOutput(frameProvider = { null })
        try {
            assertMjpegStream(output.start())
        } finally {
            output.close()
        }
    }

    @Test
    fun `browser source is loopback only and serves MJPEG across restart`() {
        val frame = DesktopVideoFrame(BufferedImage(32, 18, BufferedImage.TYPE_INT_RGB))
        val output = DesktopPgmObsOutput(frameProvider = { frame })

        try {
            val firstUrl = output.start()
            assertEquals("127.0.0.1", URI(firstUrl).host)
            assertLandingPage(firstUrl)
            assertMjpegStream(firstUrl)

            output.close()

            val restartedUrl = output.start()
            assertEquals("127.0.0.1", URI(restartedUrl).host)
            if (URI(firstUrl).port == 48765) assertEquals(firstUrl, restartedUrl)
            assertLandingPage(restartedUrl)
        } finally {
            output.close()
        }
    }

    @Test
    fun `browser source falls back when preferred port is occupied`() {
        val occupied = try {
            ServerSocket(48765, 1, InetAddress.getByName("127.0.0.1"))
        } catch (error: java.net.BindException) {
            assumeNoException(error)
            return
        }
        val output = DesktopPgmObsOutput(frameProvider = { null })
        try {
            val url = output.start()
            assertTrue(URI(url).port != 48765)
            assertLandingPage(url)
        } finally {
            output.close()
            occupied.close()
        }
    }

    @Test
    fun `third stream is rejected while page remains responsive`() {
        val frame = DesktopVideoFrame(BufferedImage(32, 18, BufferedImage.TYPE_INT_RGB))
        val output = DesktopPgmObsOutput(frameProvider = { frame })
        val connections = mutableListOf<HttpURLConnection>()
        try {
            val url = output.start()
            repeat(2) {
                val connection = URI(url).resolve("pgm.mjpg").toURL().openConnection() as HttpURLConnection
                connections += connection
                connection.connectTimeout = 2_000
                connection.readTimeout = 2_000
                assertEquals(200, connection.responseCode)
            }
            val extra = URI(url).resolve("pgm.mjpg").toURL().openConnection() as HttpURLConnection
            connections += extra
            extra.connectTimeout = 2_000
            extra.readTimeout = 2_000
            assertEquals(503, extra.responseCode)
            assertLandingPage(url)
        } finally {
            connections.forEach { it.disconnect() }
            output.close()
        }
    }

    @Test
    fun `restart accepts streams while previous connections are still open`() {
        val frame = DesktopVideoFrame(BufferedImage(32, 18, BufferedImage.TYPE_INT_RGB))
        val output = DesktopPgmObsOutput(frameProvider = { frame })
        val connections = mutableListOf<HttpURLConnection>()
        try {
            val firstUrl = output.start()
            repeat(2) {
                val connection = URI(firstUrl).resolve("pgm.mjpg").toURL().openConnection() as HttpURLConnection
                connections += connection
                connection.connectTimeout = 2_000
                connection.readTimeout = 2_000
                assertEquals(200, connection.responseCode)
            }
            output.close()
            val nextUrl = output.start()
            repeat(2) {
                val connection = URI(nextUrl).resolve("pgm.mjpg").toURL().openConnection() as HttpURLConnection
                connections += connection
                connection.connectTimeout = 2_000
                connection.readTimeout = 2_000
                assertEquals(200, connection.responseCode)
            }
        } finally {
            connections.forEach { it.disconnect() }
            output.close()
        }
    }

    @Test
    fun `health endpoint reports PGM signal and transition state`() {
        val current = AtomicReference<DesktopVideoFrame?>(
            DesktopVideoFrame(BufferedImage(32, 18, BufferedImage.TYPE_INT_RGB))
        )
        val alpha = AtomicReference(0.4f)
        val output = DesktopPgmObsOutput(
            frameProvider = { current.get() },
            transitionAlphaProvider = alpha::get
        )
        try {
            val healthUrl = URI(output.start()).resolve("healthz").toURL()
            val active = (healthUrl.openConnection() as HttpURLConnection).run {
                try {
                    connectTimeout = 2_000
                    readTimeout = 2_000
                    assertEquals(200, responseCode)
                    assertTrue(contentType.startsWith("application/json"))
                    inputStream.bufferedReader().use { it.readText() }
                } finally {
                    disconnect()
                }
            }
            assertTrue(active.contains("\"pgm\":\"active\""))
            assertTrue(active.contains("\"transitionAlpha\":0.4"))

            current.set(null)
            val noSignal = (healthUrl.openConnection() as HttpURLConnection).run {
                try {
                    connectTimeout = 2_000
                    readTimeout = 2_000
                    assertEquals(200, responseCode)
                    inputStream.bufferedReader().use { it.readText() }
                } finally {
                    disconnect()
                }
            }
            assertTrue(noSignal.contains("\"pgm\":\"no_signal\""))
        } finally {
            output.close()
        }
    }

    @Test
    fun `health endpoint reports stream clients and frame freshness`() {
        val frame = DesktopVideoFrame(BufferedImage(32, 18, BufferedImage.TYPE_INT_RGB))
        val output = DesktopPgmObsOutput(frameProvider = { frame })
        var connection: HttpURLConnection? = null
        try {
            val baseUrl = output.start()
            val healthUrl = URI(baseUrl).resolve("healthz").toURL()
            fun readHealth(): String = (healthUrl.openConnection() as HttpURLConnection).run {
                try {
                    connectTimeout = 2_000
                    readTimeout = 2_000
                    assertEquals(200, responseCode)
                    inputStream.bufferedReader().use { it.readText() }
                } finally {
                    disconnect()
                }
            }

            val idle = readHealth()
            assertTrue(idle.contains("\"activeClients\":0"))
            assertTrue(idle.contains("\"framesSent\":0"))
            assertTrue(idle.contains("\"lastFrameAgeMs\":null"))

            connection = URI(baseUrl).resolve("pgm.mjpg").toURL()
                .openConnection() as HttpURLConnection
            connection.readTimeout = 2_000
            val input = DataInputStream(connection.inputStream)
            readFrame(input)
            val connected = readHealth()
            assertTrue(connected.contains("\"activeClients\":1"))
            assertTrue(connected.contains("\"framesSent\":") &&
                !connected.contains("\"framesSent\":0"))
            assertTrue(connected.contains("\"lastFrameAgeMs\":") &&
                !connected.contains("\"lastFrameAgeMs\":null"))

            input.close()
            connection.disconnect()
            connection = null
            val deadline = System.nanoTime() + 2_000_000_000L
            while (output.activeStreamClientCount > 0 && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertEquals(0, output.activeStreamClientCount)
            assertTrue(readHealth().contains("\"activeClients\":0"))
        } finally {
            connection?.disconnect()
            output.close()
        }
    }

    private fun assertLandingPage(url: String) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            assertEquals(200, connection.responseCode)
            val page = connection.inputStream.bufferedReader().use { it.readText() }
            assertTrue(page.contains("/pgm.mjpg"))
            assertTrue(page.contains("pgm.onerror"))
        } finally {
            connection.disconnect()
        }
    }

    private fun assertMjpegStream(baseUrl: String) {
        val streamUrl = URI(baseUrl).resolve("pgm.mjpg").toURL()
        val connection = streamUrl.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            assertTrue(connection.contentType.startsWith("multipart/x-mixed-replace"))
            val firstChunk = connection.inputStream.use { it.readNBytes(512) }
            val header = firstChunk.toString(Charsets.ISO_8859_1)
            assertTrue(header.contains("--camhub-frame"))
            assertTrue(header.contains("Content-Type: image/jpeg"))
        } finally {
            connection.disconnect()
        }
    }
}
