package com.camhub.studio.data.network

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import com.camhub.studio.data.gl.DecoderGlRenderer
import io.github.thibaultbee.srtdroid.core.models.SrtSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class CameraStreamState(
    val cameraName: String,
    val bitmap: Bitmap? = null,
    val isConnected: Boolean = false,
    val bitrateKbps: Int = 0,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0
)

@Singleton
class StreamClient @Inject constructor() {

    companion object {
        private const val TAG = "StreamClient"
        private const val MAX_FRAME_SIZE = 2 * 1024 * 1024 // 2MB max frame
        private const val MAX_RECONNECT_ATTEMPTS = Int.MAX_VALUE // unlimited reconnect
        private const val RECONNECT_BASE_DELAY_MS = 500L
        private const val RECONNECT_MAX_DELAY_MS = 10_000L
        private const val FLAG_KEYFRAME = 0x01
        private const val FLAG_CODEC_CONFIG = 0x02
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _streams = MutableStateFlow<Map<String, CameraStreamState>>(emptyMap())
    val streams: StateFlow<Map<String, CameraStreamState>> = _streams.asStateFlow()

    private val streamJobs = ConcurrentHashMap<String, Job>()
    private val bytesReceivedMap = ConcurrentHashMap<String, AtomicLong>()
    private val lastBitrateTimeMap = ConcurrentHashMap<String, AtomicLong>()
    private val decoders = ConcurrentHashMap<String, H264Decoder>()
    private val glRenderers = ConcurrentHashMap<String, DecoderGlRenderer>()

    fun connectToStream(cameraName: String, ip: String, streamPort: Int, sessionKey: ByteArray? = null) {
        // Cancel existing stream for this camera
        streamJobs[cameraName]?.cancel()
        decoders.remove(cameraName)?.release()

        bytesReceivedMap[cameraName] = AtomicLong(0)
        lastBitrateTimeMap[cameraName] = AtomicLong(System.currentTimeMillis())

        streamJobs[cameraName] = scope.launch {
            var attempt = 0

            while (isActive && attempt < MAX_RECONNECT_ATTEMPTS) {
                try {
                    if (attempt > 0) {
                        val backoff = (RECONNECT_BASE_DELAY_MS * (1L shl minOf(attempt - 1, 4)))
                            .coerceAtMost(RECONNECT_MAX_DELAY_MS)
                        Log.d(TAG, "Reconnecting to $cameraName (attempt $attempt, delay ${backoff}ms)")
                        delay(backoff)
                    }
                    Log.d(TAG, "Connecting to stream $cameraName at $ip:$streamPort (encrypted=${sessionKey != null})")

                    // Reset decoder on reconnect
                    decoders.remove(cameraName)?.release()

                    // Try SRT first, fall back to TCP on failure
                    if (SrtTransport.isAvailable() && sessionKey != null) {
                        try {
                            connectSrt(cameraName, ip, streamPort, sessionKey)
                            attempt = 0
                            continue
                        } catch (e: Exception) {
                            if (!isActive) break
                            Log.w(TAG, "SRT failed for $cameraName, trying TCP: ${e.message}")
                        }
                    }

                    connectTcp(cameraName, ip, streamPort, sessionKey)
                    attempt = 0
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Stream error for $cameraName: ${e.message}")
                        attempt++
                    }
                }
            }

            // Cancelled (peer disconnected or app closed)
            glRenderers.remove(cameraName)?.release()
            decoders.remove(cameraName)?.release()
            bytesReceivedMap.remove(cameraName)
            lastBitrateTimeMap.remove(cameraName)
            updateStream(cameraName, CameraStreamState(cameraName, null, false))
            Log.d(TAG, "Stream ended for $cameraName")
        }
    }

    // --- SRT caller mode ---

    private suspend fun connectSrt(cameraName: String, ip: String, port: Int, sessionKey: ByteArray) {
        val passphrase = SrtTransport.sessionKeyToPassphrase(sessionKey)
        val srtSocket = SrtSocket()
        try {
            SrtTransport.configureSocket(srtSocket, passphrase = passphrase)
            srtSocket.connect(ip, port)

            val input = DataInputStream(srtSocket.getInputStream())

            Log.d(TAG, "SRT connected to $cameraName at $ip:$port")
            updateStream(cameraName, CameraStreamState(cameraName, null, true))

            while (currentCoroutineContext().isActive) {
                val frameSize = input.readInt()
                if (frameSize <= 0 || frameSize > MAX_FRAME_SIZE) {
                    Log.w(TAG, "SRT invalid frame size: $frameSize")
                    continue
                }

                val frameBytes = ByteArray(frameSize)
                input.readFully(frameBytes)

                // Track bytes for bitrate calculation
                bytesReceivedMap[cameraName]?.addAndGet(frameSize.toLong())

                // SRT handles decryption internally — process frame directly
                processFrame(cameraName, frameBytes)
            }
        } finally {
            try { srtSocket.close() } catch (_: Exception) {}
        }
    }

    // --- TCP fallback ---

    private suspend fun connectTcp(cameraName: String, ip: String, port: Int, sessionKey: ByteArray?) {
        val cipher = if (sessionKey != null) FrameCipher(sessionKey) else null
        val socket = Socket(ip, port).apply {
            soTimeout = 2_000
            keepAlive = true
            tcpNoDelay = true
            receiveBufferSize = 256 * 1024
        }
        try {
            val input = DataInputStream(socket.getInputStream())

            Log.d(TAG, "TCP connected to stream $cameraName at $ip:$port")
            updateStream(cameraName, CameraStreamState(cameraName, null, true))

            while (currentCoroutineContext().isActive) {
                val frameSize = input.readInt()
                if (frameSize <= 0 || frameSize > MAX_FRAME_SIZE) {
                    Log.w(TAG, "Invalid frame size: $frameSize")
                    continue
                }

                val frameBytes = ByteArray(frameSize)
                input.readFully(frameBytes)

                // Track bytes for bitrate calculation
                bytesReceivedMap[cameraName]?.addAndGet(frameSize.toLong())

                val decrypted = if (cipher != null) {
                    try {
                        cipher.decrypt(frameBytes)
                    } catch (e: Exception) {
                        Log.w(TAG, "Frame decryption failed: ${e.message}")
                        continue
                    }
                } else {
                    frameBytes
                }

                processFrame(cameraName, decrypted)
            }
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // --- Common frame processing (SRT / TCP shared) ---

    private fun processFrame(cameraName: String, decrypted: ByteArray) {
        if (decrypted.size < 6) return

        val version = decrypted[0].toInt()
        if (version != 2) {
            Log.w(TAG, "Unknown frame version: $version")
            return
        }

        // H.264 (v2): version(1)+width(2)+height(2)+rotation(1)+flags(1)+H264
        val bitmap = decodeV2H264(cameraName, decrypted)

        // Always calculate bitrate from received bytes
        val bitrateKbps = calculateBitrate(cameraName)

        if (bitmap != null) {
            val buf = ByteBuffer.wrap(decrypted, 1, 5)
            val rawW = buf.short.toInt() and 0xFFFF
            val rawH = buf.short.toInt() and 0xFFFF
            val rot = buf.get().toInt()
            val rotationDegrees = rot * 90
            val rotatedBitmap = if (rotationDegrees != 0) {
                rotateBitmap(bitmap, rotationDegrees)
            } else {
                bitmap
            }
            val frameW = rotatedBitmap.width
            val frameH = rotatedBitmap.height
            updateStream(cameraName, CameraStreamState(cameraName, rotatedBitmap, true, bitrateKbps, frameW, frameH))
        } else if (bitrateKbps > 0) {
            // Receiving data but decode failed — update bitrate for diagnostics
            val currentState = _streams.value[cameraName]
            if (currentState != null) {
                updateStream(cameraName, currentState.copy(bitrateKbps = bitrateKbps))
            }
        }
    }

    private fun decodeV2H264(cameraName: String, decrypted: ByteArray): Bitmap? {
        if (decrypted.size < 7) return null

        val buf = ByteBuffer.wrap(decrypted, 1, 6)
        val width = buf.short.toInt() and 0xFFFF
        val height = buf.short.toInt() and 0xFFFF
        val flags = decrypted[6].toInt() and 0xFF
        val isKeyFrame = (flags and FLAG_KEYFRAME) != 0
        val isConfig = (flags and FLAG_CODEC_CONFIG) != 0

        val h264Data = decrypted.copyOfRange(7, decrypted.size)

        if (isConfig) {
            // SPS/PPS config frame — configure decoder with GL Surface mode
            decoders.remove(cameraName)?.release()
            glRenderers.remove(cameraName)?.release()

            val decoder = H264Decoder()
            var surfaceConfigured = false

            try {
                val glRenderer = DecoderGlRenderer(width, height)
                glRenderer.start()
                // Wait for GL thread to initialize surface (poll instead of fixed sleep)
                var waitMs = 0
                while (glRenderer.surface == null && waitMs < 200) {
                    Thread.sleep(5)
                    waitMs += 5
                }
                val surface = glRenderer.surface
                if (surface != null && decoder.configureSurface(width, height, h264Data, surface)) {
                    glRenderer.onBitmapReady = { bitmap ->
                        val bitrateKbps = calculateBitrate(cameraName)
                        updateStream(cameraName, CameraStreamState(cameraName, bitmap, true, bitrateKbps, bitmap.width, bitmap.height))
                    }
                    glRenderers[cameraName] = glRenderer
                    decoders[cameraName] = decoder
                    surfaceConfigured = true
                    Log.d(TAG, "H.264 decoder configured (GPU surface) for $cameraName: ${width}x${height}")
                } else {
                    glRenderer.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "GL surface decoder setup failed for $cameraName, falling back to buffer mode", e)
            }

            // Fallback to buffer mode
            if (!surfaceConfigured) {
                if (decoder.configure(width, height, h264Data)) {
                    decoders[cameraName] = decoder
                    Log.d(TAG, "H.264 decoder configured (CPU buffer) for $cameraName: ${width}x${height}")
                } else {
                    decoder.release()
                    Log.w(TAG, "Failed to configure H.264 decoder for $cameraName")
                }
            }
            return null
        }

        val decoder = decoders[cameraName]
        if (decoder == null) {
            Log.w(TAG, "No decoder for $cameraName, waiting for config frame")
            return null
        }

        // If GL renderer is active, use surface decoding (bitmap via GL callback)
        val glRenderer = glRenderers[cameraName]
        if (glRenderer != null) {
            decoder.decodeSurface(h264Data, isKeyFrame)
            return null // bitmap delivered via onBitmapReady callback
        }

        // Buffer mode fallback
        return decoder.decode(h264Data, isKeyFrame)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
    }

    private fun calculateBitrate(cameraName: String): Int {
        val bytesCounter = bytesReceivedMap[cameraName] ?: return 0
        val lastTime = lastBitrateTimeMap[cameraName] ?: return 0
        val now = System.currentTimeMillis()
        val elapsed = now - lastTime.get()
        if (elapsed < 1000) return _streams.value[cameraName]?.bitrateKbps ?: 0

        val bytes = bytesCounter.getAndSet(0)
        lastTime.set(now)
        return ((bytes * 8) / elapsed).toInt() // kbps
    }

    fun getTotalBitrateKbps(): Int {
        return _streams.value.values
            .filter { it.isConnected }
            .sumOf { it.bitrateKbps }
    }

    fun disconnectStream(cameraName: String) {
        streamJobs.remove(cameraName)?.cancel()
        glRenderers.remove(cameraName)?.release()
        decoders.remove(cameraName)?.release()
        val current = _streams.value.toMutableMap()
        current.remove(cameraName)
        _streams.value = current
    }

    private fun updateStream(cameraName: String, state: CameraStreamState) {
        val current = _streams.value.toMutableMap()
        current[cameraName] = state
        _streams.value = current
    }

    fun disconnectAll() {
        streamJobs.values.forEach { it.cancel() }
        streamJobs.clear()
        glRenderers.values.forEach { it.release() }
        glRenderers.clear()
        decoders.values.forEach { it.release() }
        decoders.clear()
        _streams.value = emptyMap()
    }

    fun cleanup() {
        disconnectAll()
        scope.cancel()
    }
}
