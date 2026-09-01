package com.camhub.studio.data.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.camhub.studio.data.LowLatencyWifiLock
import com.camhub.studio.data.gl.DecoderGlRenderer
import com.camhub.studio.data.metrics.LatencyPercentiles
import com.camhub.studio.data.metrics.FrameSinkLatencyBreakdown
import com.camhub.studio.data.metrics.PipelineLatencyBreakdown
import com.camhub.studio.data.metrics.PipelineLatencyCalculator
import com.camhub.studio.data.metrics.PipelineTimingPoints
import com.camhub.studio.data.metrics.RollingLatencyTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.thibaultbee.srtdroid.core.models.SrtSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import com.camhub.studio.data.StreamingConfig
import java.net.DatagramSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
    val latencyP50Ms: Int = 0,
    val latencyP95Ms: Int = 0,
    val latencyP99Ms: Int = 0,
    val latencySampleCount: Int = 0,
    val isClockSynchronized: Boolean = false,
    val pipelineLatency: PipelineLatencyBreakdown? = null,
    val externalDisplayLatency: FrameSinkLatencyBreakdown? = null,
    val externalLatencyMs: Int = 0,
    val externalLatencyP50Ms: Int = 0,
    val externalLatencyP95Ms: Int = 0,
    val externalLatencyP99Ms: Int = 0,
    val externalLatencySampleCount: Int = 0,
    val droppedFrames: Int = 0,
    val actualFps: Int = 0,
    val ingressFps: Int = 0,
    val frameSequence: Long = 0,
    val videoTransport: VideoTransport = VideoTransport.NONE,
    val udpPacketsReceived: Long = 0,
    val udpCompletedFrames: Long = 0,
    val udpDeadlineDroppedFrames: Long = 0,
    val udpEstimatedMissingPackets: Long = 0,
    val udpPacketLossPercent: Float = 0f,
    val transportFallbackReason: String = ""
)

@Singleton
class StreamClient @Inject constructor(
    private val streamingConfig: StreamingConfig,
    private val connectionManager: PeerConnectionManager,
    private val networkTransportManager: NetworkTransportManager,
    @ApplicationContext context: Context
) {

    companion object {
        private const val TAG = "StreamClient"
        private const val MAX_FRAME_SIZE = 2 * 1024 * 1024 // 2MB max frame
        private const val RECONNECT_BASE_DELAY_MS = 500L
        private const val RECONNECT_MAX_DELAY_MS = 10_000L
        private const val FLAG_KEYFRAME = 0x01
        private const val FLAG_CODEC_CONFIG = 0x02
        private const val FRAME_QUEUE_CAPACITY = 1
        private const val META_HEADER_V2_SIZE = 7
        private const val META_HEADER_V3_SIZE = 15
        private const val META_HEADER_V4_SIZE = 31
        private const val MAX_CONSECUTIVE_DECODE_ERRORS = 3
        private const val LATENCY_SNAPSHOT_INTERVAL_MS = 1_000L
        private const val MAX_PENDING_FRAME_TIMINGS = 120
        private const val DEFAULT_MULTIVIEW_FPS = 10
        private const val UDP_WATCHDOG_INTERVAL_MS = 250L
    }

    private data class H264FrameMeta(
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val flags: Int,
        val headerSize: Int,
        val frameSequence: Long,
        val captureAtWallMs: Long?,
        val encodedAtWallMs: Long?
    ) {
        val isKeyFrame: Boolean get() = (flags and FLAG_KEYFRAME) != 0
        val isConfig: Boolean get() = (flags and FLAG_CODEC_CONFIG) != 0
    }

    private data class ReceivedFrame(
        val bytes: ByteArray,
        val receivedAtWallMs: Long,
        val receivedAtElapsedMs: Long
    )

    private data class PendingFrameTiming(
        val meta: H264FrameMeta,
        val receivedAtWallMs: Long,
        val receivedAtElapsedMs: Long
    )

    private data class DecodedFrameResult(
        val bitmap: Bitmap,
        val timing: PendingFrameTiming?,
        val decodedAtElapsedMs: Long,
        val readyAtElapsedMs: Long,
        val fallbackMeta: H264FrameMeta
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _streams = MutableStateFlow<Map<String, CameraStreamState>>(emptyMap())
    val streams: StateFlow<Map<String, CameraStreamState>> = _streams.asStateFlow()

    private val streamJobs = ConcurrentHashMap<String, Job>()
    private val udpReceivers = ConcurrentHashMap<String, UdpVideoReceiver>()
    private val udpWatchdogJobs = ConcurrentHashMap<String, Job>()
    private val bytesReceivedMap = ConcurrentHashMap<String, AtomicLong>()
    private val lastBitrateTimeMap = ConcurrentHashMap<String, AtomicLong>()
    private val frameSequenceMap = ConcurrentHashMap<String, AtomicLong>()
    private val frameWindowCountMap = ConcurrentHashMap<String, AtomicLong>()
    private val lastFpsTimeMap = ConcurrentHashMap<String, AtomicLong>()
    private val ingressFrameWindowCountMap = ConcurrentHashMap<String, AtomicLong>()
    private val lastIngressFpsTimeMap = ConcurrentHashMap<String, AtomicLong>()
    private val droppedFrameMap = ConcurrentHashMap<String, AtomicLong>()
    private val decodeErrorMap = ConcurrentHashMap<String, AtomicLong>()
    private val latestFrameSentAtWallMsMap = ConcurrentHashMap<String, AtomicLong>()
    private val latestFrameRotationMap = ConcurrentHashMap<String, AtomicLong>()
    private val pendingFrameTimings = ConcurrentHashMap<String, ConcurrentHashMap<Long, PendingFrameTiming>>()
    private val readyAtElapsedByFrame = ConcurrentHashMap<String, ConcurrentHashMap<Long, Long>>()
    private val readyPipelineByFrame = ConcurrentHashMap<String, ConcurrentHashMap<Long, PipelineLatencyBreakdown>>()
    private val lastDrawnFrameSequence = ConcurrentHashMap<String, AtomicLong>()
    private val lastExternalDrawnFrameSequence = ConcurrentHashMap<String, AtomicLong>()
    private val externalLatencyTrackers = ConcurrentHashMap<String, RollingLatencyTracker>()
    private val lastExternalLatencySnapshotTimeMap = ConcurrentHashMap<String, AtomicLong>()
    private val latencyTrackers = ConcurrentHashMap<String, RollingLatencyTracker>()
    private val lastLatencySnapshotTimeMap = ConcurrentHashMap<String, AtomicLong>()
    private val decoders = ConcurrentHashMap<String, H264Decoder>()
    private val glRenderers = ConcurrentHashMap<String, DecoderGlRenderer>()
    private val renderFpsLimits = ConcurrentHashMap<String, Int>()
    private val wifiLock = LowLatencyWifiLock(context, "CamHubDirectorStream")

    fun connectToStream(
        cameraName: String,
        ip: String,
        streamPort: Int,
        sessionKey: ByteArray? = null,
        network: Network? = null,
        fallbackReason: String = ""
    ) {
        val previousUdpState = _streams.value[cameraName]
            ?.takeIf { fallbackReason.isNotBlank() }
        wifiLock.acquire()
        // Cancel existing stream for this camera
        streamJobs.remove(cameraName)?.cancel()
        udpReceivers.remove(cameraName)?.stop()
        udpWatchdogJobs.remove(cameraName)?.cancel()
        glRenderers.remove(cameraName)?.release()
        decoders.remove(cameraName)?.release()

        bytesReceivedMap[cameraName] = AtomicLong(0)
        lastBitrateTimeMap[cameraName] = AtomicLong(System.currentTimeMillis())
        frameSequenceMap[cameraName] = AtomicLong(0)
        frameWindowCountMap[cameraName] = AtomicLong(0)
        lastFpsTimeMap[cameraName] = AtomicLong(System.currentTimeMillis())
        ingressFrameWindowCountMap[cameraName] = AtomicLong(0)
        lastIngressFpsTimeMap[cameraName] = AtomicLong(System.currentTimeMillis())
        droppedFrameMap[cameraName] = AtomicLong(0)
        decodeErrorMap[cameraName] = AtomicLong(0)
        latestFrameSentAtWallMsMap[cameraName] = AtomicLong(0)
        latestFrameRotationMap[cameraName] = AtomicLong(0)
        pendingFrameTimings[cameraName] = ConcurrentHashMap()
        readyAtElapsedByFrame[cameraName] = ConcurrentHashMap()
        readyPipelineByFrame[cameraName] = ConcurrentHashMap()
        lastDrawnFrameSequence[cameraName] = AtomicLong(0)
        lastExternalDrawnFrameSequence[cameraName] = AtomicLong(0)
        externalLatencyTrackers[cameraName] = RollingLatencyTracker()
        lastExternalLatencySnapshotTimeMap[cameraName] = AtomicLong(0)
        latencyTrackers[cameraName] = RollingLatencyTracker()
        lastLatencySnapshotTimeMap[cameraName] = AtomicLong(0)
        updateStream(
            cameraName,
            CameraStreamState(
                cameraName = cameraName,
                udpPacketsReceived = previousUdpState?.udpPacketsReceived ?: 0,
                udpCompletedFrames = previousUdpState?.udpCompletedFrames ?: 0,
                udpDeadlineDroppedFrames = previousUdpState?.udpDeadlineDroppedFrames ?: 0,
                udpEstimatedMissingPackets = previousUdpState?.udpEstimatedMissingPackets ?: 0,
                udpPacketLossPercent = previousUdpState?.udpPacketLossPercent ?: 0f,
                transportFallbackReason = fallbackReason
            )
        )

        val job = scope.launch(start = CoroutineStart.LAZY) {
            var attempt = 0

            while (isActive) {
                try {
                    markStreamReconnecting(cameraName)
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
                            connectSrt(cameraName, ip, streamPort, sessionKey, network)
                            // A completed receive loop means the socket closed.
                            // Retry indefinitely with a short capped backoff.
                            attempt = 1
                            continue
                        } catch (e: Exception) {
                            if (!isActive) break
                            Log.w(TAG, "SRT failed for $cameraName, trying TCP: ${e.message}")
                        }
                    }

                    connectTcp(cameraName, ip, streamPort, sessionKey, network)
                    attempt = 1
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
                renderFpsLimits.remove(cameraName)
                decoders.remove(cameraName)?.release()
                bytesReceivedMap.remove(cameraName)
                lastBitrateTimeMap.remove(cameraName)
                frameSequenceMap.remove(cameraName)
                frameWindowCountMap.remove(cameraName)
                lastFpsTimeMap.remove(cameraName)
                ingressFrameWindowCountMap.remove(cameraName)
                lastIngressFpsTimeMap.remove(cameraName)
                droppedFrameMap.remove(cameraName)
                decodeErrorMap.remove(cameraName)
                latestFrameSentAtWallMsMap.remove(cameraName)
                latestFrameRotationMap.remove(cameraName)
                pendingFrameTimings.remove(cameraName)
                readyAtElapsedByFrame.remove(cameraName)
                readyPipelineByFrame.remove(cameraName)
                lastDrawnFrameSequence.remove(cameraName)
                lastExternalDrawnFrameSequence.remove(cameraName)
                externalLatencyTrackers.remove(cameraName)
                lastExternalLatencySnapshotTimeMap.remove(cameraName)
                latencyTrackers.remove(cameraName)
                lastLatencySnapshotTimeMap.remove(cameraName)
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

    /**
     * Opens one deadline-aware UDP receiver for a camera and connects completed
     * frames to the same latest-frame decoder pipeline used by SRT/TCP.
     * The returned local port is sent to the camera during the next negotiation step.
     */
    fun connectToUdpStream(
        cameraName: String,
        cameraIp: String,
        cameraUdpPort: Int,
        sessionKey: ByteArray? = null,
        network: Network? = null,
        onFallbackRequired: (reason: String) -> Unit = {}
    ): Int {
        wifiLock.acquire()
        streamJobs.remove(cameraName)?.cancel()
        udpReceivers.remove(cameraName)?.stop()
        udpWatchdogJobs.remove(cameraName)?.cancel()
        glRenderers.remove(cameraName)?.release()
        decoders.remove(cameraName)?.release()
        initializeTracking(cameraName)

        val frameQueue = Channel<ReceivedFrame>(capacity = FRAME_QUEUE_CAPACITY)
        val udpCipher = sessionKey?.let(::FrameCipher)
        val firstFrameReceived = AtomicBoolean(false)
        val fallbackRequested = AtomicBoolean(false)
        val receiverStartedAtMs = AtomicLong(SystemClock.elapsedRealtime())
        val lastCompleteFrameAtMs = AtomicLong(0L)

        fun requireFallback(reason: String) {
            if (fallbackRequested.compareAndSet(false, true)) {
                onFallbackRequired(reason)
            }
        }

        val processorJob = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            for (frame in frameQueue) {
                val plaintext = try {
                    udpCipher?.decrypt(frame.bytes) ?: frame.bytes
                } catch (_: Exception) {
                    noteDroppedFrame(cameraName)
                    continue
                }
                lastCompleteFrameAtMs.set(SystemClock.elapsedRealtime())
                if (firstFrameReceived.compareAndSet(false, true)) {
                    val current = _streams.value[cameraName]
                        ?: CameraStreamState(cameraName = cameraName)
                    updateStream(
                        cameraName,
                        current.copy(
                            isConnected = true,
                            videoTransport = VideoTransport.UDP_RTP
                        )
                    )
                    Log.d(TAG, "UDP/RTP first frame received from $cameraName")
                }
                processFrame(cameraName, frame.copy(bytes = plaintext))
            }
        }
        streamJobs[cameraName] = processorJob
        processorJob.start()

        val receiver = UdpVideoReceiver(
            cameraName = cameraName,
            expectedHost = cameraIp,
            expectedSourcePort = cameraUdpPort,
            onFrame = { bytes, receivedAtWallMs, receivedAtElapsedMs ->
                enqueueUdpFrame(
                    cameraName,
                    frameQueue,
                    ReceivedFrame(bytes, receivedAtWallMs, receivedAtElapsedMs)
                )
            },
            onPacketBytes = { bytes ->
                bytesReceivedMap[cameraName]?.addAndGet(bytes.toLong())
            },
            onDroppedFrames = { count ->
                repeat(count) { noteDroppedFrame(cameraName) }
            },
            onStats = { stats ->
                val current = _streams.value[cameraName]
                    ?: CameraStreamState(cameraName = cameraName)
                updateStream(
                    cameraName,
                    current.copy(
                        udpPacketsReceived = stats.packetsReceived,
                        udpCompletedFrames = stats.completedFrames,
                        udpDeadlineDroppedFrames = stats.deadlineDroppedFrames,
                        udpEstimatedMissingPackets = stats.estimatedMissingPackets,
                        udpPacketLossPercent = stats.estimatedPacketLossPercent
                    )
                )
            },
            onError = { error ->
                Log.w(TAG, "UDP receiver stopped for $cameraName: ${error.message}")
                markStreamReconnecting(cameraName)
                requireFallback("receiver_error")
            }
        )

        return try {
            udpReceivers[cameraName] = receiver
            val localPort = receiver.start(socketBinder(network))
            receiverStartedAtMs.set(SystemClock.elapsedRealtime())
            updateStream(cameraName, CameraStreamState(cameraName = cameraName, isConnected = false))
            udpWatchdogJobs[cameraName] = scope.launch {
                while (isActive) {
                    delay(UDP_WATCHDOG_INTERVAL_MS)
                    val now = SystemClock.elapsedRealtime()
                    val fallbackReason = UdpStreamFallbackPolicy.reason(
                        nowMs = now,
                        receiverStartedAtMs = receiverStartedAtMs.get(),
                        lastCompleteFrameAtMs = lastCompleteFrameAtMs.get()
                    )
                    if (fallbackReason != null) {
                        if (fallbackReason == UdpFallbackReason.STREAM_STALLED) {
                            markStreamReconnecting(cameraName)
                        }
                        requireFallback(fallbackReason.logLabel)
                        break
                    }
                }
            }
            Log.d(TAG, "UDP/RTP receiver ready for $cameraName on port $localPort")
            localPort
        } catch (error: Throwable) {
            udpReceivers.remove(cameraName, receiver)
            receiver.stop()
            udpWatchdogJobs.remove(cameraName)?.cancel()
            streamJobs.remove(cameraName, processorJob)
            processorJob.cancel()
            frameQueue.close()
            markStreamReconnecting(cameraName)
            if (streamJobs.isEmpty()) wifiLock.release()
            throw error
        }
    }

    private fun socketBinder(network: Network?): ((DatagramSocket) -> Unit)? =
        network?.let { selectedNetwork ->
            { socket -> selectedNetwork.bindSocket(socket) }
        }

    private fun initializeTracking(cameraName: String) {
        bytesReceivedMap[cameraName] = AtomicLong(0)
        lastBitrateTimeMap[cameraName] = AtomicLong(System.currentTimeMillis())
        frameSequenceMap[cameraName] = AtomicLong(0)
        frameWindowCountMap[cameraName] = AtomicLong(0)
        lastFpsTimeMap[cameraName] = AtomicLong(System.currentTimeMillis())
        ingressFrameWindowCountMap[cameraName] = AtomicLong(0)
        lastIngressFpsTimeMap[cameraName] = AtomicLong(System.currentTimeMillis())
        droppedFrameMap[cameraName] = AtomicLong(0)
        decodeErrorMap[cameraName] = AtomicLong(0)
        latestFrameSentAtWallMsMap[cameraName] = AtomicLong(0)
        latestFrameRotationMap[cameraName] = AtomicLong(0)
        pendingFrameTimings[cameraName] = ConcurrentHashMap()
        readyAtElapsedByFrame[cameraName] = ConcurrentHashMap()
        readyPipelineByFrame[cameraName] = ConcurrentHashMap()
        lastDrawnFrameSequence[cameraName] = AtomicLong(0)
        lastExternalDrawnFrameSequence[cameraName] = AtomicLong(0)
        externalLatencyTrackers[cameraName] = RollingLatencyTracker()
        lastExternalLatencySnapshotTimeMap[cameraName] = AtomicLong(0)
        latencyTrackers[cameraName] = RollingLatencyTracker()
        lastLatencySnapshotTimeMap[cameraName] = AtomicLong(0)
        updateStream(cameraName, CameraStreamState(cameraName = cameraName))
    }

    private fun enqueueUdpFrame(
        cameraName: String,
        frameQueue: Channel<ReceivedFrame>,
        frame: ReceivedFrame
    ) {
        if (isConfigFrame(frame.bytes)) {
            dropQueuedFrames(cameraName, frameQueue)
            if (frameQueue.trySend(frame).isFailure) noteDroppedFrame(cameraName)
            return
        }
        if (frameQueue.trySend(frame).isSuccess) return
        if (frameQueue.tryReceive().getOrNull() != null) noteDroppedFrame(cameraName)
        if (frameQueue.trySend(frame).isFailure) noteDroppedFrame(cameraName)
    }

    private fun resetDecoderStateForReconnect(cameraName: String) {
        glRenderers.remove(cameraName)?.release()
        decoders.remove(cameraName)?.release()
        decodeErrorMap[cameraName]?.set(0)
        latestFrameSentAtWallMsMap[cameraName]?.set(0)
        latestFrameRotationMap[cameraName]?.set(0)
        pendingFrameTimings[cameraName]?.clear()
        readyAtElapsedByFrame[cameraName]?.clear()
        readyPipelineByFrame[cameraName]?.clear()
        lastDrawnFrameSequence[cameraName]?.set(0)
        lastExternalDrawnFrameSequence[cameraName]?.set(0)
        externalLatencyTrackers[cameraName]?.clear()
        lastExternalLatencySnapshotTimeMap[cameraName]?.set(0)
    }

    // --- SRT caller mode ---

    private suspend fun connectSrt(
        cameraName: String,
        ip: String,
        port: Int,
        sessionKey: ByteArray,
        network: Network?
    ) {
        val passphrase = SrtTransport.sessionKeyToPassphrase(sessionKey)
        val srtSocket = SrtSocket()
        try {
            SrtTransport.configureSocket(srtSocket, passphrase = passphrase)
            networkTransportManager.localIpv4Address(network)?.let { localAddress ->
                srtSocket.bind(localAddress, 0)
            }
            srtSocket.connect(ip, port)

            val input = DataInputStream(srtSocket.getInputStream())

            Log.d(TAG, "SRT connected to $cameraName at $ip:$port")
            val current = _streams.value[cameraName]
                ?: CameraStreamState(cameraName = cameraName)
            updateStream(
                cameraName,
                current.copy(isConnected = true, videoTransport = VideoTransport.SRT)
            )

            runLatestFrameProcessor(cameraName) { frameQueue ->
                while (currentCoroutineContext().isActive) {
                    val frameSize = input.readInt()
                    if (frameSize <= 0 || frameSize > MAX_FRAME_SIZE) {
                        Log.w(TAG, "SRT invalid frame size: $frameSize")
                        continue
                    }

                    val frameBytes = ByteArray(frameSize)
                    input.readFully(frameBytes)
                    val receivedAtWallMs = System.currentTimeMillis()
                    val receivedAtElapsedMs = SystemClock.elapsedRealtime()

                    // Track bytes for bitrate calculation
                    bytesReceivedMap[cameraName]?.addAndGet(frameSize.toLong())

                    // SRT handles decryption internally — keep only the newest video frame
                    enqueueFrame(
                        cameraName,
                        frameQueue,
                        ReceivedFrame(frameBytes, receivedAtWallMs, receivedAtElapsedMs)
                    )
                }
            }
        } finally {
            try { srtSocket.close() } catch (_: Exception) {}
        }
    }

    // --- TCP fallback ---

    private suspend fun connectTcp(
        cameraName: String,
        ip: String,
        port: Int,
        sessionKey: ByteArray?,
        network: Network?
    ) {
        val cipher = if (sessionKey != null) FrameCipher(sessionKey) else null
        val socket = networkTransportManager.createBoundSocket(network)
        try {
            socket.connect(java.net.InetSocketAddress(ip, port), 2_500)
            socket.soTimeout = 2_000
            socket.keepAlive = true
            socket.tcpNoDelay = true
            socket.receiveBufferSize = 64 * 1024
            val input = DataInputStream(socket.getInputStream())

            Log.d(TAG, "TCP connected to stream $cameraName at $ip:$port")
            val current = _streams.value[cameraName]
                ?: CameraStreamState(cameraName = cameraName)
            updateStream(
                cameraName,
                current.copy(isConnected = true, videoTransport = VideoTransport.TCP)
            )

            runLatestFrameProcessor(cameraName) { frameQueue ->
                while (currentCoroutineContext().isActive) {
                    val frameSize = input.readInt()
                    if (frameSize <= 0 || frameSize > MAX_FRAME_SIZE) {
                        Log.w(TAG, "Invalid frame size: $frameSize")
                        continue
                    }

                    val frameBytes = ByteArray(frameSize)
                    input.readFully(frameBytes)
                    val receivedAtWallMs = System.currentTimeMillis()
                    val receivedAtElapsedMs = SystemClock.elapsedRealtime()

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

                    enqueueFrame(
                        cameraName,
                        frameQueue,
                        ReceivedFrame(decrypted, receivedAtWallMs, receivedAtElapsedMs)
                    )
                }
            }
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // --- Common frame processing (SRT / TCP shared) ---

    private suspend fun runLatestFrameProcessor(
        cameraName: String,
        readFrames: suspend CoroutineScope.(Channel<ReceivedFrame>) -> Unit
    ) = coroutineScope {
        val frameQueue = Channel<ReceivedFrame>(capacity = FRAME_QUEUE_CAPACITY)
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
        frameQueue: Channel<ReceivedFrame>,
        frame: ReceivedFrame
    ) {
        if (isConfigFrame(frame.bytes)) {
            dropQueuedFrames(cameraName, frameQueue)
            frameQueue.send(frame)
            return
        }

        val firstTry = frameQueue.trySend(frame)
        if (firstTry.isSuccess) return

        if (frameQueue.tryReceive().getOrNull() != null) {
            noteDroppedFrame(cameraName)
        }

        val secondTry = frameQueue.trySend(frame)
        if (secondTry.isFailure) {
            noteDroppedFrame(cameraName)
        }
    }

    private fun dropQueuedFrames(cameraName: String, frameQueue: Channel<ReceivedFrame>) {
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

    private fun processFrame(cameraName: String, receivedFrame: ReceivedFrame) {
        val decrypted = receivedFrame.bytes
        val meta = parseH264FrameMeta(decrypted) ?: return
        val ingressFps = if (meta.isConfig) {
            _streams.value[cameraName]?.ingressFps ?: 0
        } else {
            calculateIngressFps(cameraName)
        }

        // H.264 v2: version(1)+width(2)+height(2)+rotation(1)+flags(1)+H264
        // H.264 v3: v2 header + sender wall-clock timestamp ms(8)+H264
        // H.264 v4: v2 header + sequence(8)+capture wall ms(8)+encoded wall ms(8)+H264
        val decodedFrame = decodeH264(cameraName, receivedFrame, meta)

        // Always calculate bitrate from received bytes
        val bitrateKbps = calculateBitrate(cameraName)

        if (decodedFrame != null) {
            val rotationDegrees = decodedFrame.timing?.meta?.rotationDegrees
                ?: decodedFrame.fallbackMeta.rotationDegrees
            val rotatedBitmap = if (rotationDegrees != 0) {
                rotateBitmap(decodedFrame.bitmap, rotationDegrees)
            } else {
                decodedFrame.bitmap
            }
            val frameW = rotatedBitmap.width
            val frameH = rotatedBitmap.height
            val pipelineLatency = calculatePipelineLatency(
                cameraName,
                decodedFrame.timing,
                decodedFrame.decodedAtElapsedMs,
                decodedFrame.readyAtElapsedMs
            )
            val latency = if (pipelineLatency != null) {
                currentLatencySnapshot(cameraName)
            } else {
                calculateLatencyStats(cameraName, decodedFrame.fallbackMeta.encodedAtWallMs)
            }
            val actualFps = calculateFps(cameraName)
            val previousStream = _streams.value[cameraName]
            updateStream(
                cameraName,
                CameraStreamState(
                    cameraName = cameraName,
                    bitmap = rotatedBitmap,
                    isConnected = true,
                    bitrateKbps = bitrateKbps,
                    frameWidth = frameW,
                    frameHeight = frameH,
                    latencyMs = latency.latestMs,
                    latencyP50Ms = latency.p50Ms,
                    latencyP95Ms = latency.p95Ms,
                    latencyP99Ms = latency.p99Ms,
                    latencySampleCount = latency.sampleCount,
                    isClockSynchronized = connectionManager
                        .getClockSyncState(cameraName)
                        ?.isSynchronized == true,
                    pipelineLatency = pipelineLatency,
                    externalDisplayLatency = previousStream?.externalDisplayLatency,
                    externalLatencyMs = previousStream?.externalLatencyMs ?: 0,
                    externalLatencyP50Ms = previousStream?.externalLatencyP50Ms ?: 0,
                    externalLatencyP95Ms = previousStream?.externalLatencyP95Ms ?: 0,
                    externalLatencyP99Ms = previousStream?.externalLatencyP99Ms ?: 0,
                    externalLatencySampleCount = previousStream?.externalLatencySampleCount ?: 0,
                    droppedFrames = droppedFrameCount(cameraName),
                    actualFps = actualFps,
                    ingressFps = ingressFps,
                    frameSequence = nextFrameSequence(cameraName),
                    videoTransport = previousStream?.videoTransport ?: VideoTransport.NONE,
                    udpPacketsReceived = previousStream?.udpPacketsReceived ?: 0,
                    udpCompletedFrames = previousStream?.udpCompletedFrames ?: 0,
                    udpDeadlineDroppedFrames = previousStream?.udpDeadlineDroppedFrames ?: 0,
                    udpEstimatedMissingPackets = previousStream?.udpEstimatedMissingPackets ?: 0,
                    udpPacketLossPercent = previousStream?.udpPacketLossPercent ?: 0f,
                    transportFallbackReason = previousStream?.transportFallbackReason.orEmpty()
                )
            )
        } else if (bitrateKbps > 0) {
            // Receiving data but decode failed — update bitrate for diagnostics
            val currentState = _streams.value[cameraName]
            if (currentState != null) {
                updateStream(
                    cameraName,
                    currentState.copy(bitrateKbps = bitrateKbps, ingressFps = ingressFps)
                )
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
                H264FrameMeta(
                    width, height, rotationDegrees, flags, META_HEADER_V2_SIZE,
                    frameSequence = 0L,
                    captureAtWallMs = null,
                    encodedAtWallMs = null
                )
            }
            3 -> {
                if (decrypted.size < META_HEADER_V3_SIZE) return null
                val width = readUInt16(decrypted, 1)
                val height = readUInt16(decrypted, 3)
                val rotationDegrees = (decrypted[5].toInt() and 0xFF) * 90
                val flags = decrypted[6].toInt() and 0xFF
                val sentAtWallMs = readInt64(decrypted, 7)
                H264FrameMeta(
                    width, height, rotationDegrees, flags, META_HEADER_V3_SIZE,
                    frameSequence = 0L,
                    captureAtWallMs = null,
                    encodedAtWallMs = sentAtWallMs
                )
            }
            4 -> {
                if (decrypted.size < META_HEADER_V4_SIZE) return null
                val width = readUInt16(decrypted, 1)
                val height = readUInt16(decrypted, 3)
                val rotationDegrees = (decrypted[5].toInt() and 0xFF) * 90
                val flags = decrypted[6].toInt() and 0xFF
                H264FrameMeta(
                    width = width,
                    height = height,
                    rotationDegrees = rotationDegrees,
                    flags = flags,
                    headerSize = META_HEADER_V4_SIZE,
                    frameSequence = readInt64(decrypted, 7),
                    captureAtWallMs = readInt64(decrypted, 15),
                    encodedAtWallMs = readInt64(decrypted, 23)
                )
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

    private fun decodeH264(
        cameraName: String,
        receivedFrame: ReceivedFrame,
        meta: H264FrameMeta
    ): DecodedFrameResult? {
        val decrypted = receivedFrame.bytes
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
                val glRenderer = DecoderGlRenderer(
                    width = meta.width,
                    height = meta.height,
                    threadName = "DecoderGL-$cameraName"
                )
                glRenderer.setOutputFpsLimit(
                    renderFpsLimits[cameraName] ?: DEFAULT_MULTIVIEW_FPS
                )
                glRenderer.start()
                // Wait for GL thread to initialize surface (poll instead of fixed sleep)
                var waitMs = 0
                while (glRenderer.surface == null && waitMs < 200) {
                    Thread.sleep(5)
                    waitMs += 5
                }
                val surface = glRenderer.surface
                if (surface != null && decoder.configureSurface(meta.width, meta.height, configData, surface, streamingConfig.lowLatencyDecode)) {
                    glRenderer.onBitmapReady = { renderedFrame ->
                        val timing = pendingFrameTimings[cameraName]
                            ?.remove(renderedFrame.presentationTimeUs)
                        val rotationDegrees = timing?.meta?.rotationDegrees
                            ?: latestFrameRotationMap[cameraName]?.get()?.toInt()
                            ?: 0
                        val renderedBitmap = if (rotationDegrees != 0) {
                            rotateBitmap(renderedFrame.bitmap, rotationDegrees)
                        } else {
                            renderedFrame.bitmap
                        }
                        val bitrateKbps = calculateBitrate(cameraName)
                        val pipelineLatency = calculatePipelineLatency(
                            cameraName,
                            timing,
                            renderedFrame.decodedAtElapsedMs,
                            renderedFrame.readyAtElapsedMs
                        )
                        val fallbackEncodedAtWallMs = timing?.meta?.encodedAtWallMs
                            ?: latestFrameSentAtWallMsMap[cameraName]?.get()?.takeIf { it > 0L }
                        val latency = if (pipelineLatency != null) {
                            currentLatencySnapshot(cameraName)
                        } else {
                            calculateLatencyStats(cameraName, fallbackEncodedAtWallMs)
                        }
                        val actualFps = calculateFps(cameraName)
                        val previousStream = _streams.value[cameraName]
                        updateStream(
                            cameraName,
                            CameraStreamState(
                                cameraName = cameraName,
                                bitmap = renderedBitmap,
                                isConnected = true,
                                bitrateKbps = bitrateKbps,
                                frameWidth = renderedBitmap.width,
                                frameHeight = renderedBitmap.height,
                                latencyMs = latency.latestMs,
                                latencyP50Ms = latency.p50Ms,
                                latencyP95Ms = latency.p95Ms,
                                latencyP99Ms = latency.p99Ms,
                                latencySampleCount = latency.sampleCount,
                                isClockSynchronized = connectionManager
                                    .getClockSyncState(cameraName)
                                    ?.isSynchronized == true,
                                pipelineLatency = pipelineLatency,
                                externalDisplayLatency = previousStream?.externalDisplayLatency,
                                externalLatencyMs = previousStream?.externalLatencyMs ?: 0,
                                externalLatencyP50Ms = previousStream?.externalLatencyP50Ms ?: 0,
                                externalLatencyP95Ms = previousStream?.externalLatencyP95Ms ?: 0,
                                externalLatencyP99Ms = previousStream?.externalLatencyP99Ms ?: 0,
                                externalLatencySampleCount = previousStream?.externalLatencySampleCount ?: 0,
                                droppedFrames = droppedFrameCount(cameraName),
                                actualFps = actualFps,
                                ingressFps = previousStream?.ingressFps ?: 0,
                                frameSequence = nextFrameSequence(cameraName),
                                videoTransport = previousStream?.videoTransport ?: VideoTransport.NONE,
                                udpPacketsReceived = previousStream?.udpPacketsReceived ?: 0,
                                udpCompletedFrames = previousStream?.udpCompletedFrames ?: 0,
                                udpDeadlineDroppedFrames = previousStream?.udpDeadlineDroppedFrames ?: 0,
                                udpEstimatedMissingPackets = previousStream?.udpEstimatedMissingPackets ?: 0,
                                udpPacketLossPercent = previousStream?.udpPacketLossPercent ?: 0f,
                                transportFallbackReason = previousStream?.transportFallbackReason.orEmpty()
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

        val pendingTiming = PendingFrameTiming(
            meta = meta,
            receivedAtWallMs = receivedFrame.receivedAtWallMs,
            receivedAtElapsedMs = receivedFrame.receivedAtElapsedMs
        )
        if (meta.frameSequence > 0L) {
            val timings = pendingFrameTimings
                .getOrPut(cameraName) { ConcurrentHashMap() }
            timings[meta.frameSequence] = pendingTiming
            if (timings.size > MAX_PENDING_FRAME_TIMINGS) {
                val oldestAllowed = meta.frameSequence - MAX_PENDING_FRAME_TIMINGS.toLong()
                timings.keys
                    .filter { it < oldestAllowed }
                    .forEach { timings.remove(it) }
            }
        }
        meta.encodedAtWallMs?.let { latestFrameSentAtWallMsMap[cameraName]?.set(it) }
        latestFrameRotationMap
            .getOrPut(cameraName) { AtomicLong(0) }
            .set(meta.rotationDegrees.toLong())

        // If GL renderer is active, use surface decoding (bitmap via GL callback)
        val glRenderer = glRenderers[cameraName]
        if (glRenderer != null) {
            val accepted = decoder.decodeSurface(
                h264Data,
                h264Offset,
                h264Length,
                meta.isKeyFrame,
                meta.frameSequence
            )
            if (!accepted || decoder.lastDecodeFailed) {
                pendingFrameTimings[cameraName]?.remove(meta.frameSequence)
                handleDecodeFailure(cameraName, decoder, surfaceMode = true)
            } else {
                decodeErrorMap[cameraName]?.set(0)
            }
            return null // bitmap delivered via onBitmapReady callback
        }

        // Buffer mode fallback
        val decodedFrame = decoder.decode(
            h264Data,
            h264Offset,
            h264Length,
            meta.isKeyFrame,
            meta.frameSequence
        )
        if (decoder.lastDecodeFailed) {
            pendingFrameTimings[cameraName]?.remove(meta.frameSequence)
            handleDecodeFailure(cameraName, decoder, surfaceMode = false)
        } else if (decodedFrame != null) {
            decodeErrorMap[cameraName]?.set(0)
        }
        if (decodedFrame == null) return null

        val matchedTiming = if (decodedFrame.presentationTimeUs > 0L) {
            pendingFrameTimings[cameraName]?.remove(decodedFrame.presentationTimeUs)
        } else {
            null
        }
        return DecodedFrameResult(
            bitmap = decodedFrame.bitmap,
            timing = matchedTiming ?: pendingTiming.takeIf { meta.frameSequence <= 0L },
            decodedAtElapsedMs = decodedFrame.decodedAtElapsedMs,
            readyAtElapsedMs = SystemClock.elapsedRealtime(),
            fallbackMeta = meta
        )
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

    private fun calculateIngressFps(cameraName: String): Int {
        val frameCounter = ingressFrameWindowCountMap
            .getOrPut(cameraName) { AtomicLong(0) }
        val lastTime = lastIngressFpsTimeMap
            .getOrPut(cameraName) { AtomicLong(System.currentTimeMillis()) }
        frameCounter.incrementAndGet()

        val now = System.currentTimeMillis()
        val elapsed = now - lastTime.get()
        if (elapsed < 1000) return _streams.value[cameraName]?.ingressFps ?: 0
        val frames = frameCounter.getAndSet(0)
        lastTime.set(now)
        return ((frames * 1000L) / elapsed)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun calculateLatencyStats(
        cameraName: String,
        sentAtWallMs: Long?
    ): LatencyPercentiles {
        val current = _streams.value[cameraName]
        val previous = LatencyPercentiles(
            sampleCount = current?.latencySampleCount ?: 0,
            latestMs = current?.latencyMs ?: 0,
            p50Ms = current?.latencyP50Ms ?: 0,
            p95Ms = current?.latencyP95Ms ?: 0,
            p99Ms = current?.latencyP99Ms ?: 0
        )
        val sentAt = sentAtWallMs ?: return previous
        val remoteClockOffsetMs = connectionManager
            .getRemoteClockOffsetMs(cameraName)
            ?: return previous
        val delta = System.currentTimeMillis() - sentAt + remoteClockOffsetMs
        if (delta !in 0..10_000) return previous

        return recordLatency(cameraName, delta.toInt())
    }

    private fun calculatePipelineLatency(
        cameraName: String,
        timing: PendingFrameTiming?,
        decodedAtElapsedMs: Long,
        readyAtElapsedMs: Long
    ): PipelineLatencyBreakdown? {
        val frameTiming = timing ?: return null
        val captureAtWallMs = frameTiming.meta.captureAtWallMs ?: return null
        val encodedAtWallMs = frameTiming.meta.encodedAtWallMs ?: return null
        val remoteClockOffsetMs = connectionManager
            .getRemoteClockOffsetMs(cameraName)
            ?: return null

        val pipeline = PipelineLatencyCalculator.calculate(
            points = PipelineTimingPoints(
                frameSequence = frameTiming.meta.frameSequence,
                captureAtRemoteWallMs = captureAtWallMs,
                encodedAtRemoteWallMs = encodedAtWallMs,
                receivedAtLocalWallMs = frameTiming.receivedAtWallMs,
                receivedAtLocalElapsedMs = frameTiming.receivedAtElapsedMs,
                decodedAtLocalElapsedMs = decodedAtElapsedMs,
                readyAtLocalElapsedMs = readyAtElapsedMs
            ),
            remoteClockOffsetMs = remoteClockOffsetMs
        )
        if (pipeline != null) {
            val readyFrames = readyAtElapsedByFrame
                .getOrPut(cameraName) { ConcurrentHashMap() }
            val readyPipelines = readyPipelineByFrame
                .getOrPut(cameraName) { ConcurrentHashMap() }
            readyFrames[pipeline.frameSequence] = readyAtElapsedMs
            readyPipelines[pipeline.frameSequence] = pipeline
            if (readyFrames.size > MAX_PENDING_FRAME_TIMINGS) {
                val oldestAllowed = pipeline.frameSequence - MAX_PENDING_FRAME_TIMINGS.toLong()
                readyFrames.keys
                    .filter { it < oldestAllowed }
                    .forEach {
                        readyFrames.remove(it)
                        readyPipelines.remove(it)
                    }
            }
        }
        return pipeline
    }

    fun markFrameDrawn(cameraName: String, frameSequence: Long, drawnAtElapsedMs: Long) {
        if (frameSequence <= 0L || drawnAtElapsedMs <= 0L) return
        val lastDrawn = lastDrawnFrameSequence
            .getOrPut(cameraName) { AtomicLong(0) }
        while (true) {
            val previous = lastDrawn.get()
            if (frameSequence <= previous) return
            if (lastDrawn.compareAndSet(previous, frameSequence)) break
        }

        scope.launch {
            val readyAtElapsedMs = readyAtElapsedByFrame[cameraName]
                ?.get(frameSequence)
                ?: return@launch
            val readyPipeline = readyPipelineByFrame[cameraName]
                ?.get(frameSequence)
                ?: return@launch
            val drawnPipeline = PipelineLatencyCalculator.includeDraw(
                ready = readyPipeline,
                readyAtLocalElapsedMs = readyAtElapsedMs,
                drawnAtLocalElapsedMs = drawnAtElapsedMs
            ) ?: return@launch
            val totalToDrawMs = drawnPipeline.totalToDrawMs ?: return@launch
            val latency = recordLatency(cameraName, totalToDrawMs)

            val current = _streams.value[cameraName] ?: return@launch
            if (current.pipelineLatency?.frameSequence != frameSequence) return@launch
            updateStream(
                cameraName,
                current.copy(
                    latencyMs = latency.latestMs,
                    latencyP50Ms = latency.p50Ms,
                    latencyP95Ms = latency.p95Ms,
                    latencyP99Ms = latency.p99Ms,
                    latencySampleCount = latency.sampleCount,
                    pipelineLatency = drawnPipeline
                )
            )
        }
    }

    fun markExternalFrameDrawn(
        cameraName: String,
        frameSequence: Long,
        drawnAtElapsedMs: Long
    ) {
        if (frameSequence <= 0L || drawnAtElapsedMs <= 0L) return
        val lastDrawn = lastExternalDrawnFrameSequence
            .getOrPut(cameraName) { AtomicLong(0) }
        while (true) {
            val previous = lastDrawn.get()
            if (frameSequence <= previous) return
            if (lastDrawn.compareAndSet(previous, frameSequence)) break
        }

        scope.launch {
            val readyAtElapsedMs = readyAtElapsedByFrame[cameraName]
                ?.get(frameSequence)
                ?: return@launch
            val readyPipeline = readyPipelineByFrame[cameraName]
                ?.get(frameSequence)
                ?: return@launch
            val externalLatency = PipelineLatencyCalculator.calculateSinkDraw(
                ready = readyPipeline,
                readyAtLocalElapsedMs = readyAtElapsedMs,
                sinkDrawnAtLocalElapsedMs = drawnAtElapsedMs
            ) ?: return@launch
            val latency = recordExternalLatency(
                cameraName,
                externalLatency.totalToSinkDrawMs
            )
            val current = _streams.value[cameraName] ?: return@launch
            updateStream(
                cameraName,
                current.copy(
                    externalDisplayLatency = externalLatency,
                    externalLatencyMs = latency.latestMs,
                    externalLatencyP50Ms = latency.p50Ms,
                    externalLatencyP95Ms = latency.p95Ms,
                    externalLatencyP99Ms = latency.p99Ms,
                    externalLatencySampleCount = latency.sampleCount
                )
            )
        }
    }

    private fun currentLatencySnapshot(cameraName: String): LatencyPercentiles {
        val current = _streams.value[cameraName]
        return LatencyPercentiles(
            sampleCount = current?.latencySampleCount ?: 0,
            latestMs = current?.latencyMs ?: 0,
            p50Ms = current?.latencyP50Ms ?: 0,
            p95Ms = current?.latencyP95Ms ?: 0,
            p99Ms = current?.latencyP99Ms ?: 0
        )
    }

    private fun recordLatency(cameraName: String, latencyMs: Int): LatencyPercentiles {
        val current = _streams.value[cameraName]
        val previous = LatencyPercentiles(
            sampleCount = current?.latencySampleCount ?: 0,
            latestMs = current?.latencyMs ?: 0,
            p50Ms = current?.latencyP50Ms ?: 0,
            p95Ms = current?.latencyP95Ms ?: 0,
            p99Ms = current?.latencyP99Ms ?: 0
        )
        if (latencyMs !in 0..10_000) return previous

        val tracker = latencyTrackers
            .getOrPut(cameraName) { RollingLatencyTracker() }
        tracker.record(latencyMs)

        // Percentiles are diagnostic UI data. Re-sorting the rolling window for
        // every decoded frame would add avoidable work to the hot video path.
        val now = System.currentTimeMillis()
        val lastSnapshotTime = lastLatencySnapshotTimeMap
            .getOrPut(cameraName) { AtomicLong(0) }
        return if (
            previous.sampleCount == 0 ||
            now - lastSnapshotTime.get() >= LATENCY_SNAPSHOT_INTERVAL_MS
        ) {
            lastSnapshotTime.set(now)
            tracker.snapshot()
        } else {
            previous.copy(latestMs = latencyMs)
        }
    }

    private fun recordExternalLatency(
        cameraName: String,
        latencyMs: Int
    ): LatencyPercentiles {
        val current = _streams.value[cameraName]
        val previous = LatencyPercentiles(
            sampleCount = current?.externalLatencySampleCount ?: 0,
            latestMs = current?.externalLatencyMs ?: 0,
            p50Ms = current?.externalLatencyP50Ms ?: 0,
            p95Ms = current?.externalLatencyP95Ms ?: 0,
            p99Ms = current?.externalLatencyP99Ms ?: 0
        )
        if (latencyMs !in 0..10_000) return previous

        val tracker = externalLatencyTrackers
            .getOrPut(cameraName) { RollingLatencyTracker() }
        tracker.record(latencyMs)
        val now = System.currentTimeMillis()
        val lastSnapshotTime = lastExternalLatencySnapshotTimeMap
            .getOrPut(cameraName) { AtomicLong(0) }
        return if (
            previous.sampleCount == 0 ||
            now - lastSnapshotTime.get() >= LATENCY_SNAPSHOT_INTERVAL_MS
        ) {
            lastSnapshotTime.set(now)
            tracker.snapshot()
        } else {
            previous.copy(latestMs = latencyMs)
        }
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

    /**
     * Keeps PGM fluid while reducing GPU readback and Compose updates for
     * multiview-only cameras. Decode/network threads remain independent.
     */
    fun setRenderFpsLimit(cameraName: String, fps: Int) {
        val clamped = fps.coerceIn(1, 60)
        renderFpsLimits[cameraName] = clamped
        glRenderers[cameraName]?.setOutputFpsLimit(clamped)
    }

    fun disconnectStream(cameraName: String) {
        streamJobs.remove(cameraName)?.cancel()
        udpReceivers.remove(cameraName)?.stop()
        udpWatchdogJobs.remove(cameraName)?.cancel()
        glRenderers.remove(cameraName)?.release()
        renderFpsLimits.remove(cameraName)
        decoders.remove(cameraName)?.release()
        bytesReceivedMap.remove(cameraName)
        lastBitrateTimeMap.remove(cameraName)
        frameSequenceMap.remove(cameraName)
        frameWindowCountMap.remove(cameraName)
        lastFpsTimeMap.remove(cameraName)
        ingressFrameWindowCountMap.remove(cameraName)
        lastIngressFpsTimeMap.remove(cameraName)
        droppedFrameMap.remove(cameraName)
        decodeErrorMap.remove(cameraName)
        latestFrameSentAtWallMsMap.remove(cameraName)
        latestFrameRotationMap.remove(cameraName)
        pendingFrameTimings.remove(cameraName)
        readyAtElapsedByFrame.remove(cameraName)
        readyPipelineByFrame.remove(cameraName)
        lastDrawnFrameSequence.remove(cameraName)
        lastExternalDrawnFrameSequence.remove(cameraName)
        externalLatencyTrackers.remove(cameraName)
        lastExternalLatencySnapshotTimeMap.remove(cameraName)
        latencyTrackers.remove(cameraName)
        lastLatencySnapshotTimeMap.remove(cameraName)
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

    private fun markStreamReconnecting(cameraName: String) {
        val current = _streams.value[cameraName] ?: CameraStreamState(cameraName = cameraName)
        updateStream(
            cameraName,
            current.copy(
                isConnected = false,
                bitrateKbps = 0,
                actualFps = 0,
                videoTransport = VideoTransport.NONE
            )
        )
    }

    fun disconnectAll() {
        streamJobs.values.forEach { it.cancel() }
        streamJobs.clear()
        udpReceivers.values.forEach { it.stop() }
        udpReceivers.clear()
        udpWatchdogJobs.values.forEach { it.cancel() }
        udpWatchdogJobs.clear()
        glRenderers.values.forEach { it.release() }
        glRenderers.clear()
        decoders.values.forEach { it.release() }
        decoders.clear()
        bytesReceivedMap.clear()
        lastBitrateTimeMap.clear()
        frameSequenceMap.clear()
        frameWindowCountMap.clear()
        lastFpsTimeMap.clear()
        ingressFrameWindowCountMap.clear()
        lastIngressFpsTimeMap.clear()
        droppedFrameMap.clear()
        decodeErrorMap.clear()
        latestFrameSentAtWallMsMap.clear()
        latestFrameRotationMap.clear()
        pendingFrameTimings.clear()
        readyAtElapsedByFrame.clear()
        readyPipelineByFrame.clear()
        lastDrawnFrameSequence.clear()
        lastExternalDrawnFrameSequence.clear()
        externalLatencyTrackers.clear()
        lastExternalLatencySnapshotTimeMap.clear()
        latencyTrackers.clear()
        lastLatencySnapshotTimeMap.clear()
        _streams.value = emptyMap()
        wifiLock.release()
    }

    fun cleanup() {
        disconnectAll()
        scope.cancel()
    }
}
