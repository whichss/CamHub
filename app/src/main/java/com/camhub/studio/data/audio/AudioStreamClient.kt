package com.camhub.studio.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Network
import android.os.Build
import android.util.Log
import com.camhub.studio.data.network.FrameCipher
import com.camhub.studio.data.network.NetworkTransportManager
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
    val isConnected: Boolean = false,
    val statusText: String = "Disconnected",
    val lastPacketAgeMs: Long = 0L
)

@Singleton
class AudioStreamClient @Inject constructor(
    private val networkTransportManager: NetworkTransportManager
) {

    companion object {
        private const val TAG = "AudioStreamClient"
        private const val SAMPLE_RATE_PCM = 48000
        private const val SAMPLE_RATE_OPUS = 48000
        private const val CHUNK_DURATION_MS = 20
        private const val SAMPLES_PER_CHUNK_PCM = SAMPLE_RATE_PCM * CHUNK_DURATION_MS / 1000  // 960
        private const val SAMPLES_PER_CHUNK_OPUS = SAMPLE_RATE_OPUS * CHUNK_DURATION_MS / 1000  // 960
        private const val BYTES_PER_CHUNK_PCM = SAMPLES_PER_CHUNK_PCM * 2  // 1920
        private const val MAX_FRAME_SIZE = 64 * 1024  // 64KB max audio frame
        private const val MAX_RECONNECT_ATTEMPTS = 60
        private const val RECONNECT_BASE_DELAY_MS = 500L
        private const val RECONNECT_MAX_DELAY_MS = 10_000L
        private const val AUDIO_STALE_AFTER_MS = 1_500L
        private const val META_HEADER_V1_SIZE = 2
        private const val META_HEADER_V2_SIZE = 5
        // Ring buffer: 60ms capacity = 3 chunks. This absorbs small Wi-Fi jitter while
        // still overwriting stale chunks instead of growing latency without bound.
        private const val RING_BUFFER_CHUNKS = 3
        private const val MAX_CONSECUTIVE_ZERO_WRITES = 5

        private const val CODEC_PCM: Byte = 0x00
        private const val CODEC_OPUS: Byte = 0x01
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Per-channel state
    private val channelBuffers = ConcurrentHashMap<String, AudioRingBuffer>()
    private val channelFaders = ConcurrentHashMap<String, Float>()
    private val channelAfvEnabled = ConcurrentHashMap<String, Boolean>()
    private val channelSyncOffsets = ConcurrentHashMap<String, Int>()
    private val streamJobs = ConcurrentHashMap<String, Job>()
    private val channelDecoders = ConcurrentHashMap<String, OpusDecoder>()
    private val channelSampleRates = ConcurrentHashMap<String, Int>()
    private val channelLastPacketMs = ConcurrentHashMap<String, Long>()

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
    private var currentPlaybackRate: Int = SAMPLE_RATE_PCM
    private var consecutiveZeroWrites = 0

    private fun ensureMixingStarted() {
        if (mixingJob != null) return
        startMixing()
    }

    /**
     * Determine the playback sample rate based on connected channels.
     * If any channel uses Opus (48 kHz), use 48 kHz for the AudioTrack;
     * otherwise default to 44100 Hz.
     */
    private fun resolvePlaybackSampleRate(): Int {
        return if (channelSampleRates.values.any { it == SAMPLE_RATE_OPUS }) {
            SAMPLE_RATE_OPUS
        } else {
            SAMPLE_RATE_PCM
        }
    }

    /**
     * Restart the AudioTrack if the required sample rate has changed.
     */
    private fun ensureCorrectSampleRate() {
        val needed = resolvePlaybackSampleRate()
        if (needed != currentPlaybackRate && audioTrack != null) {
            Log.d(TAG, "Sample rate changed ($currentPlaybackRate -> $needed), restarting AudioTrack")
            stopMixing()
            startMixing()
        }
    }

    private fun startMixing() {
        val sampleRate = resolvePlaybackSampleRate()
        currentPlaybackRate = sampleRate
        val samplesPerChunk = sampleRate * CHUNK_DURATION_MS / 1000
        val bytesPerChunk = samplesPerChunk * 2

        audioTrack = createPlaybackTrack(sampleRate, bytesPerChunk)

        mixingJob = scope.launch {
            val mixBuffer = FloatArray(samplesPerChunk)
            val outputBuffer = ShortArray(samplesPerChunk)
            while (isActive) {
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
                    val mixLen = minOf(chunk.size, samplesPerChunk)
                    for (i in 0 until mixLen) {
                        val sample = chunk[i].toFloat() / Short.MAX_VALUE
                        val gained = sample * gain
                        sumSquares += gained * gained
                        mixBuffer[i] += gained
                    }
                    val rms = sqrt(sumSquares / mixLen).toFloat()
                    val normalizedLevel = rmsToNormalizedDb(rms)
                    currentChannelLevels[name] = normalizedLevel
                }

                // Update channel states
                val states = currentChannelLevels.map { (name, level) ->
                    val lastPacketAgeMs = System.currentTimeMillis() - (channelLastPacketMs[name] ?: 0L)
                    name to AudioChannelState(
                        level = level,
                        isConnected = lastPacketAgeMs <= AUDIO_STALE_AFTER_MS,
                        statusText = if (lastPacketAgeMs <= AUDIO_STALE_AFTER_MS) "Live" else "Stale",
                        lastPacketAgeMs = lastPacketAgeMs.coerceAtLeast(0L)
                    )
                }.toMap()
                // Merge with existing connected channels that had no data this frame
                val merged = _channelStates.value.toMutableMap()
                for ((name, _) in channelBuffers) {
                    if (name !in states) {
                        val lastPacketAt = channelLastPacketMs[name] ?: 0L
                        val lastPacketAgeMs = if (lastPacketAt > 0L) {
                            System.currentTimeMillis() - lastPacketAt
                        } else {
                            0L
                        }
                        val previous = merged[name]
                        val status = when {
                            previous?.statusText == "Reconnecting" -> "Reconnecting"
                            lastPacketAt == 0L -> previous?.statusText ?: "Connecting"
                            lastPacketAgeMs > AUDIO_STALE_AFTER_MS -> "Stale"
                            else -> "Live"
                        }
                        merged[name] = AudioChannelState(
                            level = 0f,
                            isConnected = status == "Live",
                            statusText = status,
                            lastPacketAgeMs = lastPacketAgeMs.coerceAtLeast(0L)
                        )
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

                    // Non-blocking write keeps live monitoring from growing latency.
                    // If the audio device dies or becomes invalid, rebuild it in-place.
                    writeOutputBuffer(outputBuffer, sampleRate, bytesPerChunk)
                } else {
                    _masterLevel.value = 0f
                }

                // Pace mixing at chunk interval without pre-filling AudioTrack with silence.
                delay(CHUNK_DURATION_MS.toLong())
            }
        }
    }

    private fun createPlaybackTrack(sampleRate: Int, bytesPerChunk: Int): AudioTrack? {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, bytesPerChunk * 2)

        return try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
                .also {
                    it.play()
                    Log.d(TAG, "AudioTrack started: sampleRate=$sampleRate, bufSize=$bufSize")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioTrack", e)
            null
        }
    }

    private fun writeOutputBuffer(buffer: ShortArray, sampleRate: Int, bytesPerChunk: Int) {
        val track = audioTrack ?: return
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack is not initialized, rebuilding playback")
            rebuildPlaybackTrack(sampleRate, bytesPerChunk)
            return
        }

        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            try {
                track.play()
                Log.d(TAG, "AudioTrack playback resumed")
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack play failed, rebuilding: ${e.message}")
                rebuildPlaybackTrack(sampleRate, bytesPerChunk)
                return
            }
        }

        val written = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                track.write(buffer, 0, buffer.size, AudioTrack.WRITE_NON_BLOCKING)
            } else {
                track.write(buffer, 0, buffer.size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack write failed, rebuilding: ${e.message}")
            rebuildPlaybackTrack(sampleRate, bytesPerChunk)
            return
        }

        if (written < 0) {
            Log.w(TAG, "AudioTrack write returned $written, rebuilding playback")
            rebuildPlaybackTrack(sampleRate, bytesPerChunk)
            return
        }

        if (written == 0) {
            consecutiveZeroWrites++
            if (consecutiveZeroWrites >= MAX_CONSECUTIVE_ZERO_WRITES) {
                Log.w(TAG, "AudioTrack accepted no samples for $consecutiveZeroWrites chunks, rebuilding playback")
                rebuildPlaybackTrack(sampleRate, bytesPerChunk)
            }
            return
        }

        consecutiveZeroWrites = 0
    }

    private fun rebuildPlaybackTrack(sampleRate: Int, bytesPerChunk: Int) {
        consecutiveZeroWrites = 0
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = createPlaybackTrack(sampleRate, bytesPerChunk)
    }

    fun connectToAudioStream(
        cameraName: String,
        ip: String,
        audioPort: Int,
        sessionKey: ByteArray? = null,
        network: Network? = null
    ) {
        // Cancel existing stream for this camera
        streamJobs.remove(cameraName)?.cancel()
        // Default to the largest supported 20 ms chunk size; sample rate is updated
        // after the first frame header is parsed.
        channelBuffers[cameraName] = AudioRingBuffer(RING_BUFFER_CHUNKS, SAMPLES_PER_CHUNK_OPUS)
        channelFaders.putIfAbsent(cameraName, 0.75f)
        channelAfvEnabled.putIfAbsent(cameraName, false)
        channelLastPacketMs.remove(cameraName)
        updateChannelState(cameraName, AudioChannelState(statusText = "Connecting"))

        // Release any previous decoder for this channel
        channelDecoders.remove(cameraName)?.release()

        val job = scope.launch(start = CoroutineStart.LAZY) {
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
                    resetAudioReceiveStateForReconnect(cameraName)
                    updateChannelState(cameraName, AudioChannelState(statusText = "Connecting"))

                    socket = networkTransportManager.createBoundSocket(network).apply {
                        connect(java.net.InetSocketAddress(ip, audioPort), 2_500)
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

                        val samples = parseAudioFrame(cameraName, decrypted) ?: continue
                        channelLastPacketMs[cameraName] = System.currentTimeMillis()
                        ensureMixingStarted()

                        // Write to ring buffer (applying sync offset via buffer depth)
                        channelBuffers[cameraName]?.write(samples)
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Audio stream error for $cameraName: ${e.message}")
                        updateChannelState(cameraName, AudioChannelState(statusText = "Reconnecting"))
                        attempt++
                    }
                } finally {
                    try { socket?.close() } catch (_: Exception) {}
                }
            }

            val currentJob = coroutineContext[Job]
            if (streamJobs[cameraName] == currentJob) {
                streamJobs.remove(cameraName)
                releaseAudioReceiveState(cameraName)
                Log.d(TAG, "Audio stream ended for $cameraName")
                updateChannelState(cameraName, AudioChannelState(statusText = "Disconnected"))
                if (channelBuffers.isEmpty()) {
                    stopMixing()
                }
            } else {
                Log.d(TAG, "Ignoring stale audio cleanup for $cameraName")
            }
        }
        streamJobs[cameraName] = job
        job.start()
    }

    private fun resetAudioReceiveStateForReconnect(cameraName: String) {
        val previousOffset = channelSyncOffsets[cameraName] ?: 0
        val offsetChunks = previousOffset / CHUNK_DURATION_MS
        channelBuffers[cameraName] = AudioRingBuffer(RING_BUFFER_CHUNKS, SAMPLES_PER_CHUNK_OPUS).also {
            it.setSyncOffset(offsetChunks)
        }
        channelLastPacketMs.remove(cameraName)
    }

    private fun releaseAudioReceiveState(cameraName: String) {
        channelBuffers.remove(cameraName)
        channelSampleRates.remove(cameraName)
        channelLastPacketMs.remove(cameraName)
        channelDecoders.remove(cameraName)?.release()
    }

    private fun updateChannelState(cameraName: String, state: AudioChannelState) {
        val current = _channelStates.value.toMutableMap()
        current[cameraName] = state
        _channelStates.value = current
    }

    /**
     * Parse a decrypted audio frame, handling both v1 and v2 headers.
     *
     * v1: [version=1][channels(1)][PCM data...]
     * v2: [version=2][channels(1)][codec(1)][sample_rate_hi(1)][sample_rate_lo(1)][audio data...]
     *
     * @return decoded PCM samples, or null if the frame should be skipped
     */
    private fun parseAudioFrame(cameraName: String, decrypted: ByteArray): ShortArray? {
        if (decrypted.size < META_HEADER_V1_SIZE) return null

        val version = decrypted[0].toInt()

        return when (version) {
            1 -> {
                // v1 protocol: raw PCM after 2-byte header
                if (decrypted.size <= META_HEADER_V1_SIZE) return null
                channelSampleRates[cameraName] = SAMPLE_RATE_PCM
                ensureCorrectSampleRate()
                val pcmBytes = decrypted.copyOfRange(META_HEADER_V1_SIZE, decrypted.size)
                bytesToShortArray(pcmBytes)
            }
            2 -> {
                // v2 protocol
                if (decrypted.size < META_HEADER_V2_SIZE) return null
                val codec = decrypted[2]
                val sampleRate = ((decrypted[3].toInt() and 0xFF) shl 8 or
                        (decrypted[4].toInt() and 0xFF)) * 100
                channelSampleRates[cameraName] = sampleRate
                ensureCorrectSampleRate()
                val audioData = decrypted.copyOfRange(META_HEADER_V2_SIZE, decrypted.size)

                when (codec) {
                    CODEC_PCM -> {
                        bytesToShortArray(audioData)
                    }
                    CODEC_OPUS -> {
                        // Lazy-initialise decoder for this channel
                        val decoder = getOrCreateOpusDecoder(cameraName) ?: return null
                        decoder.decode(audioData)
                    }
                    else -> {
                        Log.w(TAG, "Unknown codec: $codec")
                        null
                    }
                }
            }
            else -> {
                Log.w(TAG, "Unknown audio header version: $version")
                null
            }
        }
    }

    private fun getOrCreateOpusDecoder(cameraName: String): OpusDecoder? {
        channelDecoders[cameraName]?.let { return it }
        val decoder = OpusDecoder()
        return if (decoder.start()) {
            channelDecoders[cameraName] = decoder
            decoder
        } else {
            decoder.release()
            Log.e(TAG, "Failed to start Opus decoder for $cameraName")
            null
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
        channelSampleRates.remove(cameraName)
        channelLastPacketMs.remove(cameraName)
        channelDecoders.remove(cameraName)?.release()

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
        channelSampleRates.clear()
        channelLastPacketMs.clear()
        channelDecoders.values.forEach { it.release() }
        channelDecoders.clear()
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
 * Synchronized ring buffer for audio chunks.
 * Each slot holds one chunk of [chunkSize] samples.
 */
internal class AudioRingBuffer(
    private val capacity: Int,
    private val chunkSize: Int
) {
    private val buffer = Array(capacity) { ShortArray(chunkSize) }
    private var writePos = 0
    private var readPos = 0
    private var count = 0
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
