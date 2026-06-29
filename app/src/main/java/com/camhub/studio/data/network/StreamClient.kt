package com.camhub.studio.data.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import com.camhub.studio.data.LowLatencyWifiLock
import com.camhub.studio.data.gl.DecoderGlRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.thibaultbee.srtdroid.core.models.SrtSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.net.Socket
import com.camhub.studio.data.StreamingConfig
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
    val frameHeight: Int = 0,
    val latencyMs: Int = 0,
    val droppedFrames: Int = 0,
    val actualFps: Int = 0,
    val frameSequence: Long = 0
)

@Singleton
class StreamClient @Inject constructor(
    private val streamingConfig: StreamingConfig,
    @ApplicationContext context: Context
) {

    companion object {
        private const val TAG = "StreamClient"
        private const val MAX_FRAME_SIZE = 2 * 1024 * 1024 // 2MB max frame
        private const val MAX_RECONNECT_ATTEMPTS = 60
        private const val RECONNECT_BASE_DELAY_MS = 500L
        private const val RECONNECT_MAX_DELAY_MS = 10_000L
        private const val FLAG_KEYFRAME = 0x01
        private const val FLAG_CODEC_CONFIG = 0x02
        private const val FRAME_QUEUE_CAPACITY = 1
        private const val META_HEADER_V2_SIZE = 7
        private const val META_HEADER_V3_SIZE = 15
        private const val MAX_CONSECUTIVE_DECODE_ERRORS = 3
    }

    private data class H264FrameMeta(
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val flags: Int,
        val headerSize: Int,
        val sentAtWallMs: Long?
    ) {
        val isKeyFrame: Boolean get() = (flags and FLAG_KEYFRAME) != 0
        val isConfig: Boolean get() = (flags and FLAG_CODEC_CONFIG) != 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _streams = MutableStateFlow<Map<String, CameraStreamState>>(emptyMap())
    val streams: StateFlow<Map<String, CameraStreamState>> = _streams.asStateFlow()

    private val streamJobs = ConcurrentHashMap<String, Job>()
    private val bytesReceivedMap = ConcurrentHashMap<String, AtomicLong>()
    private val lastBitrateTimeMap = ConcurrentHashMap<String, AtomicLong>()
    private val frameSequenceMap = ConcurrentHashMap<String, AtomicLong>()
    private val frameWindowCountMap = ConcurrentHashMap<String, AtomicLong>()
    private val lastFpsTimeMap = ConcurrentHashMap<String, AtomicLong>()
    private val droppedFrameMap = ConcurrentHashMap<String, AtomicLong>()
    private val decodeErrorMap = ConcurrentHashMap<String, AtomicLong>()
    private val latestFrameSentAtWallMsMap = ConcurrentHashMap<String, AtomicLong>()
    private val latestFrameRotationMap = ConcurrentHashMap<String, AtomicLong>()
    private val decoders = ConcurrentHashMap<String, H264Decoder>()
    private val glRenderers = ConcurrentHashMap<String, DecoderGlRenderer>()
    private val wifiLock = LowLatencyWifiLock(context, "CamHubDirectorStream")

    fun connectToStream(cameraName: String, ip: String, streamPort: Int, sessionKey: ByteArray? = null) {
        wifiLock.acquire()
        // Cancel existing stream for this camera
        streamJobs.remove(cameraName)?.cancel()
        glRenderers.remove(cameraName)?.release()
        decoders.remove(cameraName)?.release()

        bytesReceivedMap[cameraName] = AtomicLong(0)
        lastBitrateTimeMap[cameraName] = AtomicLong(System.currentTimeMillis())
        frameSequenceMap[cameraName] = AtomicLong(0)
        frameWindowCountMap[cameraName] = AtomicLong(0)
        lastFpsTimeMap[cameraName] = AtomicLong(System.currentTimeMillis())
        droppedFrameMap[cameraName] = AtomicLong(0)
        decodeErrorMap[cameraName] = AtomicLong(0)
        latestFrameSentAtWallMsMap[cameraName] = AtomicLong(0)
        latestFrameRotationMap[cameraName] = AtomicLong(0)

        val job = scope.launch(start = CoroutineStart.LAZY) {
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

                    // Reset decoder/render state on reconnect. A previous socket may
                    // have died while the GL renderer still has a pending callback;
                    // clear it before waiting for the next config frame.
                    resetDecoderStateForReconnect(cameraName)

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
            val currentJob = coroutineContext[Job]
            if (streamJobs[cameraName] == currentJob) {
                streamJobs.remove(cameraName)
                glRenderers.remove(cameraName)?.release()
                decoders.remove(cameraName)?.release()
                bytesReceivedMap.remove(cameraName)
                lastBitrateTimeMap.remove(cameraName)
                frameSequenceMap.remove(cameraName)
                frameWindowCountMap.remove(cameraName)
                lastFpsTimeMap.remove(cameraName)
                droppedFrameMap.remove(cameraName)
                decodeErrorMap.remove(cameraName)
                latestFrameSentAtWallMsMap.remove(cameraName)
                latestFrameRotationMap.remove(cameraName)
                updateStream(cameraName, CameraStreamState(cameraName, null, false))
                if (streamJobs.isEmpty()) {
                    wifiLock.release()
                }
                Log.d(TAG, "Stream ended for $cameraName")
            } else {
                Log.d(TAG, "Ignoring stale stream cleanup for $cameraName")
            }
        }
        streamJobs[cameraName] = job
        job.start()
    }

    private fun resetDecoderStateForReconnect(cameraName: String) {
        glRenderers.remove(cameraName)?.release()
        decoders.remove(cameraName)?.release()
        decodeErrorMap[cameraName]?.set(0)
        latestFrameSentAtWallMsMap[cameraName]?.set(0)
        latestFrameRotationMap[cameraName]?.set(0)
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

            runLatestFrameProcessor(cameraName) { frameQueue ->
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

                    // SRT handles decryption internally — keep only the newest video frame
                    enqueueFrame(cameraName, frameQueue, frameBytes)
                }
            }
        } finally {
            try { srtSocket.close() } catch (_: Exception) {}
        }
    }

    // --- TCP fallback ---

    private suspend fun connectTcp(cameraName: String, ip: String, port: Int, sessionKey: ByteArray?) {
        val cipher = if (sessionKey != null) FrameCipher(sessionKey) else null
        val socket = Socket(ip, port)
        try {
            socket.soTimeout = 2_000
            socket.keepAlive = true
            socket.tcpNoDelay = true
            socket.receiveBufferSize = 64 * 1024
            val input = DataInputStream(socket.getInputStream())

            Log.d(TAG, "TCP connected to stream $cameraName at $ip:$port")
            updateStream(cameraName, CameraStreamState(cameraName, null, true))

            runLatestFrameProcessor(cameraName) { frameQueue ->
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

                    enqueueFrame(cameraName, frameQueue, decrypted)
                }
            }
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // --- Common frame processing (SRT / TCP shared) ---

    private suspend fun runLatestFrameProcessor(
        cameraName: String,
        readFrames: suspend CoroutineScope.(Channel<ByteArray>) -> Unit
    ) = coroutineScope {
        val frameQueue = Channel<ByteArray>(capacity = FRAME_QUEUE_CAPACITY)
        val processor = launch(Dispatchers.Default) {
            for (frame in frameQueue) {
                processFrame(cameraName, frame)
            }
        }

        try {
            readFrames(frameQueue)
        } finally {
            frameQueue.close()
            processor.cancelAndJoin()
        }
    }

    private suspend fun enqueueFrame(
        cameraName: String,
        frameQueue: Channel<ByteArray>,
        frameBytes: ByteArray
    ) {
        if (isConfigFrame(frameBytes)) {
            dropQueuedFrames(cameraName, frameQueue)
            frameQueue.send(frameBytes)
            return
        }

        val firstTry = frameQueue.trySend(frameBytes)
        if (firstTry.isSuccess) return

        if (frameQueue.tryReceive().getOrNull() != null) {
            noteDroppedFrame(cameraName)
        }

        val secondTry = frameQueue.trySend(frameBytes)
        if (secondTry.isFailure) {
            noteDroppedFrame(cameraName)
        }
    }

    private fun dropQueuedFrames(cameraName: String, frameQueue: Channel<ByteArray>) {
        while (frameQueue.tryReceive().getOrNull() != null) {
            noteDroppedFrame(cameraName)
        }
    }

    private fun noteDroppedFrame(cameraName: String) {
        val dropped = droppedFrameMap
            .getOrPut(cameraName) { AtomicLong(0) }
            .incrementAndGet()
        if (dropped % 30L == 0L) {
            Log.d(TAG, "Dropped $dropped stale video frames for $cameraName to keep latency bounded")
        }
    }

    private fun isConfigFrame(frameBytes: ByteArray): Boolean {
        return parseH264FrameMeta(frameBytes)?.isConfig == true
    }

    private fun processFrame(cameraName: String, decrypted: ByteArray) {
        val meta = parseH264FrameMeta(decrypted) ?: return

        // H.264 v2: version(1)+width(2)+height(2)+rotation(1)+flags(1)+H264
        // H.264 v3: v2 header + sender wall-clock timestamp ms(8)+H264
        val bitmap = decodeH264(cameraName, decrypted, meta)

        // Always calculate bitrate from received bytes
        val bitrateKbps = calculateBitrate(cameraName)

        if (bitmap != null) {
            val rotatedBitmap = if (meta.rotationDegrees != 0) {
                rotateBitmap(bitmap, meta.rotationDegrees)
            } else {
                bitmap
            }
            val frameW = rotatedBitmap.width
            val frameH = rotatedBitmap.height
            val latencyMs = calculateLatencyMs(cameraName, meta.sentAtWallMs)
            val actualFps = calculateFps(cameraName)
            updateStream(
                cameraName,
                CameraStreamState(
                    cameraName = cameraName,
                    bitmap = rotatedBitmap,
                    isConnected = true,
                    bitrateKbps = bitrateKbps,
                    frameWidth = frameW,
                    frameHeight = frameH,
                    latencyMs = latencyMs,
                    droppedFrames = droppedFrameCount(cameraName),
                    actualFps = actualFps,
                    frameSequence = nextFrameSequence(cameraName)
                )
            )
        } else if (bitrateKbps > 0) {
            // Receiving data but decode failed — update bitrate for diagnostics
            val currentState = _streams.value[cameraName]
            if (currentState != null) {
                updateStream(cameraName, currentState.copy(bitrateKbps = bitrateKbps))
            }
        }
    }

    private fun parseH264FrameMeta(decrypted: ByteArray): H264FrameMeta? {
        if (decrypted.size < META_HEADER_V2_SIZE) return null

        return when (val version = decrypted[0].toInt()) {
            2 -> {
                val width = readUInt16(decrypted, 1)
                val height = readUInt16(decrypted, 3)
                val rotationDegrees = (decrypted[5].toInt() and 0xFF) * 90
                val flags = decrypted[6].toInt() and 0xFF
                H264FrameMeta(width, height, rotationDegrees, flags, META_HEADER_V2_SIZE, null)
            }
            3 -> {
                if (decrypted.size < META_HEADER_V3_SIZE) return null
                val width = readUInt16(decrypted, 1)
                val height = readUInt16(decrypted, 3)
                val rotationDegrees = (decrypted[5].toInt() and 0xFF) * 90
                val flags = decrypted[6].toInt() and 0xFF
                val sentAtWallMs = readInt64(decrypted, 7)
                H264FrameMeta(width, height, rotationDegrees, flags, META_HEADER_V3_SIZE, sentAtWallMs)
            }
            else -> {
                Log.w(TAG, "Unknown frame version: $version")
                null
            }
        }
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)
    }

    private fun readInt64(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xFFL)
        }
        return value
    }

    private fun decodeH264(cameraName: String, decrypted: ByteArray, meta: H264FrameMeta): Bitmap? {
        val h264Offset = meta.headerSize
        val h264Length = decrypted.size - meta.headerSize
        if (h264Length <= 0) return null
        val h264Data = decrypted  // use offset-based access to avoid copy

        if (meta.isConfig) {
            // SPS/PPS config frame — configure decoder with GL Surface mode
            decoders.remove(cameraName)?.release()
            glRenderers.remove(cameraName)?.release()
            decodeErrorMap[cameraName]?.set(0)

            // Config data needs a copy since decoder stores reference
            val configData = decrypted.copyOfRange(h264Offset, decrypted.size)

            val decoder = H264Decoder()
            var surfaceConfigured = false

            try {
                val glRenderer = DecoderGlRenderer(meta.width, meta.height)
                glRenderer.start()
                // Wait for GL thread to initialize surface (poll instead of fixed sleep)
                var waitMs = 0
                while (glRenderer.surface == null && waitMs < 200) {
                    Thread.sleep(5)
                    waitMs += 5
                }
                val surface = glRenderer.surface
                if (surface != null && decoder.configureSurface(meta.width, meta.height, configData, surface, streamingConfig.lowLatencyDecode)) {
                    glRenderer.onBitmapReady = { bitmap ->
                        val rotationDegrees = latestFrameRotationMap[cameraName]?.get()?.toInt() ?: 0
                        val renderedBitmap = if (rotationDegrees != 0) {
                            rotateBitmap(bitmap, rotationDegrees)
                        } else {
                            bitmap
                        }
                        val bitrateKbps = calculateBitrate(cameraName)
                        val sentAtWallMs = latestFrameSentAtWallMsMap[cameraName]?.get()?.takeIf { it > 0L }
                        val latencyMs = calculateLatencyMs(cameraName, sentAtWallMs)
                        val actualFps = calculateFps(cameraName)
                        updateStream(
                            cameraName,
                            CameraStreamState(
                                cameraName = cameraName,
                                bitmap = renderedBitmap,
                                isConnected = true,
                                bitrateKbps = bitrateKbps,
                                frameWidth = renderedBitmap.width,
                                frameHeight = renderedBitmap.height,
                                latencyMs = latencyMs,
                                droppedFrames = droppedFrameCount(cameraName),
                                actualFps = actualFps,
                                frameSequence = nextFrameSequence(cameraName)
                            )
                        )
                    }
                    glRenderers[cameraName] = glRenderer
                    decoders[cameraName] = decoder
                    surfaceConfigured = true
                    Log.d(TAG, "H.264 decoder configured (GPU surface) for $cameraName: ${meta.width}x${meta.height}")
                } else {
                    glRenderer.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "GL surface decoder setup failed for $cameraName, falling back to buffer mode", e)
            }

            // Fallback to buffer mode
            if (!surfaceConfigured) {
                if (decoder.configure(meta.width, meta.height, configData, streamingConfig.lowLatencyDecode)) {
                    decoders[cameraName] = decoder
                    Log.d(TAG, "H.264 decoder configured (CPU buffer) for $cameraName: ${meta.width}x${meta.height}")
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
            meta.sentAtWallMs?.let { latestFrameSentAtWallMsMap[cameraName]?.set(it) }
            latestFrameRotationMap
                .getOrPut(cameraName) { AtomicLong(0) }
                .set(meta.rotationDegrees.toLong())
            val accepted = decoder.decodeSurface(h264Data, h264Offset, h264Length, meta.isKeyFrame)
            if (!accepted || decoder.lastDecodeFailed) {
                handleDecodeFailure(cameraName, decoder, surfaceMode = true)
            } else {
                decodeErrorMap[cameraName]?.set(0)
            }
            return null // bitmap delivered via onBitmapReady callback
        }

        // Buffer mode fallback
        val bitmap = decoder.decode(h264Data, h264Offset, h264Length, meta.isKeyFrame)
        if (decoder.lastDecodeFailed) {
            handleDecodeFailure(cameraName, decoder, surfaceMode = false)
        } else if (bitmap != null) {
            decodeErrorMap[cameraName]?.set(0)
        }
        return bitmap
    }

    private fun handleDecodeFailure(cameraName: String, decoder: H264Decoder, surfaceMode: Boolean) {
        noteDroppedFrame(cameraName)
        val failures = decodeErrorMap
            .getOrPut(cameraName) { AtomicLong(0) }
            .incrementAndGet()

        if (failures < MAX_CONSECUTIVE_DECODE_ERRORS) return

        Log.w(TAG, "Decoder failed $failures times for $cameraName, flushing for resync")
        decodeErrorMap[cameraName]?.set(0)
        if (!decoder.flushAfterError()) {
            decoders.remove(cameraName)?.release()
            if (surfaceMode) {
                glRenderers.remove(cameraName)?.release()
            }
            Log.w(TAG, "Decoder flush failed for $cameraName; waiting for next config frame")
        }
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
        return ((bytes * 8L) / elapsed).toInt() // kbps
    }

    private fun nextFrameSequence(cameraName: String): Long {
        return frameSequenceMap
            .getOrPut(cameraName) { AtomicLong(0) }
            .incrementAndGet()
    }

    private fun calculateFps(cameraName: String): Int {
        val frameCounter = frameWindowCountMap
            .getOrPut(cameraName) { AtomicLong(0) }
        val lastTime = lastFpsTimeMap
            .getOrPut(cameraName) { AtomicLong(System.currentTimeMillis()) }
        frameCounter.incrementAndGet()

        val now = System.currentTimeMillis()
        val elapsed = now - lastTime.get()
        if (elapsed < 1000) {
            return _streams.value[cameraName]?.actualFps ?: 0
        }

        val frames = frameCounter.getAndSet(0)
        lastTime.set(now)
        return ((frames * 1000L) / elapsed)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun calculateLatencyMs(cameraName: String, sentAtWallMs: Long?): Int {
        val previous = _streams.value[cameraName]?.latencyMs ?: 0
        val sentAt = sentAtWallMs ?: return previous
        val delta = System.currentTimeMillis() - sentAt
        return if (delta in 0..10_000) delta.toInt() else previous
    }

    private fun droppedFrameCount(cameraName: String): Int {
        return (droppedFrameMap[cameraName]?.get() ?: 0L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun getTotalBitrateKbps(): Int {
        return _streams.value.values
            .filter { it.isConnected }
            .sumOf { it.bitrateKbps }
    }

    fun getTotalDroppedFrames(): Int {
        return droppedFrameMap.values
            .sumOf { it.get() }
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun disconnectStream(cameraName: String) {
        streamJobs.remove(cameraName)?.cancel()
        glRenderers.remove(cameraName)?.release()
        decoders.remove(cameraName)?.release()
        bytesReceivedMap.remove(cameraName)
        lastBitrateTimeMap.remove(cameraName)
        frameSequenceMap.remove(cameraName)
        frameWindowCountMap.remove(cameraName)
        lastFpsTimeMap.remove(cameraName)
        droppedFrameMap.remove(cameraName)
        decodeErrorMap.remove(cameraName)
        latestFrameSentAtWallMsMap.remove(cameraName)
        latestFrameRotationMap.remove(cameraName)
        val current = _streams.value.toMutableMap()
        current.remove(cameraName)
        _streams.value = current
        if (streamJobs.isEmpty()) {
            wifiLock.release()
        }
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
        bytesReceivedMap.clear()
        lastBitrateTimeMap.clear()
        frameSequenceMap.clear()
        droppedFrameMap.clear()
        decodeErrorMap.clear()
        latestFrameSentAtWallMsMap.clear()
        latestFrameRotationMap.clear()
        _streams.value = emptyMap()
        wifiLock.release()
    }

    fun cleanup() {
        disconnectAll()
        scope.cancel()
    }
}
