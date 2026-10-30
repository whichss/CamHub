package com.camhub.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.BindException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriter
import javax.imageio.stream.MemoryCacheImageOutputStream

/** Local-only OBS PGM output. Frames are sent as bounded-rate multipart MJPEG. */
class DesktopPgmObsOutput(
    private val frameProvider: () -> DesktopVideoFrame?,
    private val framesPerSecond: Int = 30,
    private val maxWidth: Int = 1920,
    private val maxHeight: Int = 1080,
    private val transitionAlphaProvider: () -> Float = { 0f }
) : AutoCloseable {
    companion object {
        private const val BOUNDARY = "camhub-frame"
        private const val DEFAULT_PORT = 48765
    }

    @Volatile private var running: AtomicBoolean? = null
    private val activeStreamClients = AtomicInteger(0)
    private val framesSent = AtomicLong(0)
    private val lastFrameSentAtNanos = AtomicLong(0)
    private data class CachedJpeg(val frame: DesktopVideoFrame, val alpha: Float, val bytes: ByteArray)
    private val emptyFrame = DesktopVideoFrame(BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB))
    @Volatile private var cachedJpeg: CachedJpeg? = null
    private var scaledCanvas: BufferedImage? = null
    private val jpegBuffer = ByteArrayOutputStream(512 * 1024)
    private var jpegWriter: ImageWriter? = null
    @Volatile private var executor: ExecutorService? = null
    @Volatile private var server: HttpServer? = null

    val isRunning: Boolean get() = running?.get() == true
    val activeStreamClientCount: Int get() = activeStreamClients.get()
    val hasProgramSignal: Boolean get() = frameProvider() != null
    val lastFrameAgeMs: Long?
        get() = lastFrameSentAtNanos.get().takeIf { it > 0L }?.let {
            (System.nanoTime() - it).coerceAtLeast(0L) / 1_000_000L
        }

    init {
        require(framesPerSecond in 1..30)
        require(maxWidth in 320..3_840 && maxHeight in 180..2_160)
    }

    @Synchronized
    fun start(): String {
        server?.let { return sourceUrl(it.address.port) }
        val loopback = InetAddress.getByName("127.0.0.1")
        val http = try {
            HttpServer.create(InetSocketAddress(loopback, DEFAULT_PORT), 0)
        } catch (_: BindException) {
            // Another local service owns the preferred port; expose the actual URL to the user.
            HttpServer.create(InetSocketAddress(loopback, 0), 0)
        }
        val workers = ThreadPoolExecutor(
            4,
            4,
            30,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(8),
            { task -> Thread(task, "CamHubObsOutput").apply { isDaemon = true } },
            ThreadPoolExecutor.CallerRunsPolicy()
        )
        val active = AtomicBoolean(true)
        val streamSlots = Semaphore(2)
        http.executor = workers
        http.createContext("/") { exchange -> serveLandingPage(exchange) }
        http.createContext("/healthz") { exchange -> serveHealth(exchange) }
        http.createContext("/pgm.mjpg") { exchange -> streamMjpeg(exchange, active, streamSlots) }
        running = active
        http.start()
        executor = workers
        server = http
        return sourceUrl(http.address.port)
    }

    @Synchronized
    override fun close() {
        running?.set(false)
        running = null
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
        cachedJpeg = null
        scaledCanvas = null
        jpegWriter?.dispose()
        jpegWriter = null
        jpegBuffer.reset()
    }

    private fun sourceUrl(port: Int): String = "http://127.0.0.1:$port/"

    private fun serveLandingPage(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            exchange.sendResponseHeaders(405, -1)
            exchange.close()
            return
        }
        val page = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}
            img{width:100%;height:100%;object-fit:contain}</style></head>
            <body><img id="pgm" alt="CamHub PGM">
            <script>
            const pgm = document.getElementById("pgm");
            pgm.onerror = () => setTimeout(() => { pgm.src = "/pgm.mjpg?t=" + Date.now(); }, 1000);
            pgm.src = "/pgm.mjpg";
            </script></body></html>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(200, page.size.toLong())
        exchange.responseBody.use { it.write(page) }
    }

    private fun serveHealth(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            exchange.sendResponseHeaders(405, -1)
            exchange.close()
            return
        }
        val hasProgram = frameProvider() != null
        val transitionAlpha = transitionAlphaProvider().coerceIn(0f, 1f)
        val activeClients = activeStreamClients.get()
        val frames = framesSent.get()
        val lastSentAt = lastFrameSentAtNanos.get()
        val lastFrameAgeMs = if (lastSentAt == 0L) "null" else
            ((System.nanoTime() - lastSentAt).coerceAtLeast(0L) / 1_000_000L).toString()
        val payload = """{"status":"ok","pgm":"${if (hasProgram) "active" else "no_signal"}","transitionAlpha":$transitionAlpha,"activeClients":$activeClients,"framesSent":$frames,"lastFrameAgeMs":$lastFrameAgeMs}"""
            .toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(200, payload.size.toLong())
        exchange.responseBody.use { it.write(payload) }
    }

    private fun streamMjpeg(exchange: HttpExchange, active: AtomicBoolean, streamSlots: Semaphore) {
        if (exchange.requestMethod != "GET") {
            exchange.sendResponseHeaders(405, -1)
            exchange.close()
            return
        }
        if (!streamSlots.tryAcquire()) {
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
            return
        }
        activeStreamClients.incrementAndGet()
        try {
            exchange.responseHeaders.set("Content-Type", "multipart/x-mixed-replace; boundary=$BOUNDARY")
            exchange.responseHeaders.set("Cache-Control", "no-store, no-cache, must-revalidate")
            exchange.responseHeaders.set("Pragma", "no-cache")
            runCatching {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { output ->
                    val intervalNanos = 1_000_000_000L / framesPerSecond
                    while (active.get()) {
                        val startedAt = System.nanoTime()
                        // Replace the last image with black when PGM disappears, rather than
                        // leaving a disconnected camera's last frame visible indefinitely.
                        val frame = frameProvider() ?: emptyFrame
                        val alpha = transitionAlphaProvider().coerceIn(0f, 1f)
                        run {
                            val jpeg = encodeJpeg(frame, alpha)
                            val header = "--$BOUNDARY\r\n" +
                                "Content-Type: image/jpeg\r\n" +
                                "Content-Length: ${jpeg.size}\r\n\r\n"
                            output.write(header.toByteArray(Charsets.US_ASCII))
                            output.write(jpeg)
                            output.write("\r\n".toByteArray(Charsets.US_ASCII))
                            output.flush()
                            framesSent.incrementAndGet()
                            lastFrameSentAtNanos.set(System.nanoTime())
                        }
                        val remainingNanos = intervalNanos - (System.nanoTime() - startedAt)
                        if (remainingNanos > 0) {
                            TimeUnit.NANOSECONDS.sleep(remainingNanos)
                        }
                    }
                }
            }.onFailure {
                // OBS/browser clients normally close the socket when the source is removed.
            }
        } finally {
            try {
                exchange.close()
            } finally {
                activeStreamClients.decrementAndGet()
                streamSlots.release()
            }
        }
    }

    @Synchronized
    private fun encodeJpeg(frame: DesktopVideoFrame, alpha: Float): ByteArray {
        cachedJpeg?.takeIf { it.frame === frame && it.alpha == alpha }?.let { return it.bytes }
        val rotation = ((frame.rotationDegrees % 360) + 360) % 360
        val swapsDimensions = rotation == 90 || rotation == 270
        val sourceWidth = if (swapsDimensions) frame.image.height else frame.image.width
        val sourceHeight = if (swapsDimensions) frame.image.width else frame.image.height
        val scale = minOf(
            maxWidth.toDouble() / sourceWidth,
            maxHeight.toDouble() / sourceHeight
        )
        val width = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).toInt().coerceAtLeast(1)
        val outputImage = scaledCanvas?.takeIf { it.width == width && it.height == height }
            ?: BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { scaledCanvas = it }
        val graphics = outputImage.createGraphics()
        try {
            graphics.color = Color.BLACK
            graphics.fillRect(0, 0, width, height)
            DesktopVideoPainter.draw(
                graphics = graphics,
                frame = frame,
                targetWidth = width,
                targetHeight = height,
                highQualitySpatialScale = true
            )
            if (alpha > 0f) {
                graphics.color = Color(0, 0, 0, (alpha * 255).toInt())
                graphics.fillRect(0, 0, width, height)
            }
        } finally {
            graphics.dispose()
        }
        val writer = jpegWriter ?: ImageIO.getImageWritersByFormatName("jpeg").asSequence()
            .firstOrNull()
            ?.also { jpegWriter = it }
            ?: error("JPEG writer unavailable")
        jpegBuffer.reset()
        val imageOutput = MemoryCacheImageOutputStream(jpegBuffer)
        try {
            writer.output = imageOutput
            writer.write(null, IIOImage(outputImage, null, null), writer.defaultWriteParam)
            imageOutput.flush()
        } finally {
            imageOutput.close()
        }
        val encoded = jpegBuffer.toByteArray()
        cachedJpeg = CachedJpeg(frame, alpha, encoded)
        return encoded
    }
}
