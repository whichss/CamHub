package com.camhub.studio.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.camhub.studio.data.network.FrameCipher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

data class AudioChannelState(
    val level: Float = 0f,
    val isConnected: Boolean = false
)

@Singleton
class AudioStreamClient @Inject constructor() {

    companion object {
        private const val TAG = "AudioStreamClient"
        private const val SAMPLE_RATE = 44100
        private const val CHUNK_DURATION_MS = 20
        private const val SAMPLES_PER_CHUNK = SAMPLE_RATE * CHUNK_DURATION_MS / 1000  // 882
        private const val BYTES_PER_CHUNK = SAMPLES_PER_CHUNK * 2  // 1764
        private const val MAX_FRAME_SIZE = 64 * 1024  // 64KB max audio frame
        private const val MAX_RECONNECT_ATTEMPTS = Int.MAX_VALUE
        private const val RECONNECT_BASE_DELAY_MS = 500L
        private const val RECONNECT_MAX_DELAY_MS = 10_000L
        private const val META_HEADER_SIZE = 2
        // Ring buffer: 80ms capacity = 4 chunks (low-latency with jitter margin)
        private const val RING_BUFFER_CHUNKS = 4
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Per-channel state
    private val channelBuffers = ConcurrentHashMap<String, AudioRingBuffer>()
    private val channelFaders = ConcurrentHashMap<String, Float>()
    private val channelAfvEnabled = ConcurrentHashMap<String, Boolean>()
    private val channelSyncOffsets = ConcurrentHashMap<String, Int>()
    private val streamJobs = ConcurrentHashMap<String, Job>()

    // PGM cameras for AFV logic
    private val pgmCameraNames = ConcurrentHashMap.newKeySet<String>()

    // Master controls
    var masterFader: Float = 1.0f
    var isMasterMuted: Boolean = false

    // Output flows
    private val _channelStates = MutableStateFlow<Map<String, AudioChannelState>>(emptyMap())
    val channelStates: StateFlow<Map<String, AudioChannelState>> = _channelStates.asStateFlow()

    private val _masterLevel = MutableStateFlow(0f)
    val masterLevel: StateFlow<Float> = _masterLevel.asStateFlow()

    // AudioTrack for playback
    private var audioTrack: AudioTrack? = null
    private var mixingJob: Job? = null

    private fun ensureMixingStarted() {
        if (mixingJob != null) return
        startMixing()
    }

    private fun startMixing() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, BYTES_PER_CHUNK * 2)

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            audioTrack?.play()
            Log.d(TAG, "AudioTrack started: sampleRate=$SAMPLE_RATE, bufSize=$bufSize")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioTrack", e)
            audioTrack = null
        }

        mixingJob = scope.launch {
            val mixBuffer = FloatArray(SAMPLES_PER_CHUNK)
            val outputBuffer = ShortArray(SAMPLES_PER_CHUNK)
            val silenceBuffer = ShortArray(SAMPLES_PER_CHUNK) // pre-allocated silence

            while (isActive) {
                // Pace mixing at chunk interval to stay in sync with audio arrival rate.
                // Without pacing, the loop outruns network delivery → ring buffer starves
                // → levels stay at zero and AudioTrack underruns.
                delay(CHUNK_DURATION_MS.toLong())

                // Clear mix buffer
                mixBuffer.fill(0f)

                var anyData = false
                val currentChannelLevels = mutableMapOf<String, Float>()

                // Mix all channels
                for ((name, ringBuffer) in channelBuffers) {
                    val chunk = ringBuffer.read() ?: continue
                    anyData = true

                    val fader = channelFaders[name] ?: 0.75f
                    val afvEnabled = channelAfvEnabled[name] ?: false
                    val afvGain = if (afvEnabled && name !in pgmCameraNames) 0f else 1f
                    val gain = fader * afvGain

                    // Calculate channel RMS before mixing
                    var sumSquares = 0.0
                    for (i in chunk.indices) {
                        val sample = chunk[i].toFloat() / Short.MAX_VALUE
                        val gained = sample * gain
                        sumSquares += gained * gained
                        mixBuffer[i] += gained
                    }
                    val rms = sqrt(sumSquares / chunk.size).toFloat()
                    val normalizedLevel = rmsToNormalizedDb(rms)
                    currentChannelLevels[name] = normalizedLevel
                }

                // Update channel states
                val states = currentChannelLevels.map { (name, level) ->
                    name to AudioChannelState(level = level, isConnected = true)
                }.toMap()
                // Merge with existing connected channels that had no data this frame
                val merged = _channelStates.value.toMutableMap()
                for ((name, _) in channelBuffers) {
                    if (name !in states) {
                        merged[name] = AudioChannelState(level = 0f, isConnected = true)
                    }
                }
                merged.putAll(states)
                // Remove channels no longer connected
                merged.keys.retainAll(channelBuffers.keys)
                _channelStates.value = merged

                if (anyData) {
                    // Apply master fader and mute
                    val masterGain = if (isMasterMuted) 0f else masterFader
                    var masterSumSquares = 0.0

                    for (i in mixBuffer.indices) {
                        val sample = mixBuffer[i] * masterGain
                        // Clipping protection
                        val clamped = sample.coerceIn(-1f, 1f)
                        outputBuffer[i] = (clamped * Short.MAX_VALUE).toInt().toShort()
                        masterSumSquares += clamped * clamped.toDouble()
                    }

                    val masterRms = sqrt(masterSumSquares / mixBuffer.size).toFloat()
                    _masterLevel.value = rmsToNormalizedDb(masterRms)

                    // Write to AudioTrack
                    audioTrack?.write(outputBuffer, 0, outputBuffer.size)
                } else {
                    // Feed silence to AudioTrack to prevent underrun
                    audioTrack?.write(silenceBuffer, 0, silenceBuffer.size)
                }
            }
        }
    }

    fun connectToAudioStream(
        cameraName: String,
        ip: String,
        audioPort: Int,
        sessionKey: ByteArray? = null
    ) {
        // Cancel existing stream for this camera
        streamJobs[cameraName]?.cancel()
        channelBuffers[cameraName] = AudioRingBuffer(RING_BUFFER_CHUNKS, SAMPLES_PER_CHUNK)
        channelFaders.putIfAbsent(cameraName, 0.75f)
        channelAfvEnabled.putIfAbsent(cameraName, false)

        ensureMixingStarted()

        streamJobs[cameraName] = scope.launch {
            val cipher = if (sessionKey != null) FrameCipher(sessionKey) else null
            var attempt = 0

            while (isActive && attempt < MAX_RECONNECT_ATTEMPTS) {
                var socket: Socket? = null
                try {
                    if (attempt > 0) {
                        val backoff = (RECONNECT_BASE_DELAY_MS * (1L shl minOf(attempt - 1, 4)))
                            .coerceAtMost(RECONNECT_MAX_DELAY_MS)
                        Log.d(TAG, "Reconnecting audio to $cameraName (attempt $attempt, delay ${backoff}ms)")
                        delay(backoff)
                    }
                    Log.d(TAG, "Connecting audio to $cameraName at $ip:$audioPort")

                    socket = Socket(ip, audioPort).apply {
                        soTimeout = 15_000
                        keepAlive = true
                        tcpNoDelay = true
                    }
                    val input = DataInputStream(socket.getInputStream())
                    attempt = 0  // Reset on successful connect

                    while (isActive) {
                        val frameSize = input.readInt()
                        if (frameSize <= 0 || frameSize > MAX_FRAME_SIZE) {
                            Log.w(TAG, "Invalid audio frame size: $frameSize")
                            continue
                        }

                        val frameBytes = ByteArray(frameSize)
                        input.readFully(frameBytes)

                        val decrypted = if (cipher != null) {
                            try {
                                cipher.decrypt(frameBytes)
                            } catch (e: Exception) {
                                Log.w(TAG, "Audio decryption failed: ${e.message}")
                                continue
                            }
                        } else {
                            frameBytes
                        }

                        // Parse: [version(1)][channels(1)][PCM data...]
                        if (decrypted.size <= META_HEADER_SIZE) continue
                        val pcmBytes = decrypted.copyOfRange(META_HEADER_SIZE, decrypted.size)
                        val samples = bytesToShortArray(pcmBytes)

                        // Write to ring buffer (applying sync offset via buffer depth)
                        channelBuffers[cameraName]?.write(samples)
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Audio stream error for $cameraName: ${e.message}")
                        attempt++
                    }
                } finally {
                    try { socket?.close() } catch (_: Exception) {}
                }
            }

            Log.d(TAG, "Audio stream ended for $cameraName")
        }
    }

    private fun bytesToShortArray(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) {
            shorts[i] = ((bytes[i * 2].toInt() and 0xFF) or
                    (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
        return shorts
    }

    private fun rmsToNormalizedDb(rms: Float): Float {
        if (rms <= 0.001f) return 0f
        val db = 20f * log10(rms)
        return ((db + 60f) / 60f).coerceIn(0f, 1f)
    }

    // --- Mixer control methods ---

    fun setChannelFader(channelName: String, value: Float) {
        channelFaders[channelName] = value.coerceIn(0f, 1f)
    }

    fun setChannelAfv(channelName: String, enabled: Boolean) {
        channelAfvEnabled[channelName] = enabled
    }

    fun setChannelSyncOffset(channelName: String, offsetMs: Int) {
        channelSyncOffsets[channelName] = offsetMs
        // Adjust ring buffer read position based on offset
        val offsetChunks = offsetMs / CHUNK_DURATION_MS
        channelBuffers[channelName]?.setSyncOffset(offsetChunks)
    }

    fun setPgmCameras(names: Set<String>) {
        pgmCameraNames.clear()
        pgmCameraNames.addAll(names)
    }

    // --- Disconnect ---

    fun disconnectAudioStream(cameraName: String) {
        streamJobs.remove(cameraName)?.cancel()
        channelBuffers.remove(cameraName)
        channelFaders.remove(cameraName)
        channelAfvEnabled.remove(cameraName)
        channelSyncOffsets.remove(cameraName)

        val current = _channelStates.value.toMutableMap()
        current.remove(cameraName)
        _channelStates.value = current

        // Stop mixing if no channels left
        if (channelBuffers.isEmpty()) {
            stopMixing()
        }
    }

    fun disconnectAll() {
        streamJobs.values.forEach { it.cancel() }
        streamJobs.clear()
        channelBuffers.clear()
        channelFaders.clear()
        channelAfvEnabled.clear()
        channelSyncOffsets.clear()
        pgmCameraNames.clear()
        _channelStates.value = emptyMap()
        _masterLevel.value = 0f
        stopMixing()
    }

    private fun stopMixing() {
        mixingJob?.cancel()
        mixingJob = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    fun cleanup() {
        disconnectAll()
        scope.cancel()
    }
}

/**
 * Lock-free ring buffer for audio chunks.
 * Each slot holds one chunk of [chunkSize] samples.
 */
internal class AudioRingBuffer(
    private val capacity: Int,
    private val chunkSize: Int
) {
    private val buffer = Array(capacity) { ShortArray(chunkSize) }
    @Volatile private var writePos = 0
    @Volatile private var readPos = 0
    @Volatile private var count = 0
    private var syncOffset = 0

    @Synchronized
    fun write(samples: ShortArray) {
        val chunk = buffer[writePos % capacity]
        val copyLen = minOf(samples.size, chunkSize)
        System.arraycopy(samples, 0, chunk, 0, copyLen)
        // Zero-fill remainder if short
        if (copyLen < chunkSize) {
            chunk.fill(0, copyLen, chunkSize)
        }
        writePos++
        if (count < capacity) count++ else readPos++
    }

    @Synchronized
    fun read(): ShortArray? {
        // Apply sync offset: require extra buffered chunks before reading
        if (count <= syncOffset) return null
        val chunk = buffer[readPos % capacity]
        readPos++
        count--
        return chunk.copyOf()
    }

    @Synchronized
    fun setSyncOffset(chunks: Int) {
        syncOffset = chunks.coerceIn(0, capacity - 1)
    }
}
