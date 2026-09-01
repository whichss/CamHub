package com.camhub.studio.data.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MicrophoneDirection
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.camhub.studio.data.network.FrameCipher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.math.log10
import kotlin.math.sqrt

data class AudioCaptureStatus(
    val statusText: String = "Idle",
    val clientCount: Int = 0,
    val restartCount: Int = 0,
    val lastError: String? = null
)

@Singleton
class AudioCaptureService @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val SAMPLE_RATE = 48000
        private const val OPUS_SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_DURATION_MS = 20
        private const val SAMPLES_PER_CHUNK = SAMPLE_RATE * CHUNK_DURATION_MS / 1000  // 960
        private const val BYTES_PER_CHUNK = SAMPLES_PER_CHUNK * 2  // 1920 bytes (16-bit)
        private const val MAX_CLIENTS = 4
        private const val PREFER_OPUS = false
        private const val MAX_CONSECUTIVE_READ_ERRORS = 3
        private const val AUDIO_CAPTURE_RESTART_DELAY_MS = 1_000L
        private const val AUDIO_RECORD_ERROR_DEAD_OBJECT = -6

        // v1 header: [version=1][channels=1]
        private const val META_HEADER_V1_VERSION: Byte = 1
        private const val META_HEADER_V1_CHANNELS: Byte = 1
        private const val META_HEADER_V1_SIZE = 2

        // v2 header: [version=2][channels=1][codec][sample_rate_hi][sample_rate_lo]
        private const val META_HEADER_V2_VERSION: Byte = 2
        private const val META_HEADER_V2_CHANNELS: Byte = 1
        private const val META_HEADER_V2_SIZE = 5
        private const val CODEC_PCM: Byte = 0x00
        private const val CODEC_OPUS: Byte = 0x01
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val clients = CopyOnWriteArrayList<AudioClientConnection>()
    private val audioSenderId = AtomicInteger(0)
    private val sendExecutor = Executors.newFixedThreadPool(MAX_CLIENTS) { runnable ->
        Thread(runnable, "AudioStreamSend-${audioSenderId.incrementAndGet()}").apply {
            isDaemon = true
        }
    }

    private var frameCipher: FrameCipher? = null
    private var pendingMicDirection: Int? = null

    private var opusEncoder: OpusEncoder? = null
    private var useOpus = false
    @Volatile
    private var restartingCapture = false

    private val _audioLevels = MutableStateFlow<List<Float>>(listOf(0f, 0f))
    val audioLevels: StateFlow<List<Float>> = _audioLevels.asStateFlow()

    private val _captureStatus = MutableStateFlow(AudioCaptureStatus())
    val captureStatus: StateFlow<AudioCaptureStatus> = _captureStatus.asStateFlow()

    private class AudioClientConnection(
        val socket: Socket,
        val output: DataOutputStream
    ) {
        @Volatile
        var isSending: Boolean = false
    }

    fun setSessionKey(key: ByteArray) {
        frameCipher = FrameCipher(key)
    }

    fun start(): Int {
        stop()

        serverSocket = ServerSocket(0)
        val port = serverSocket!!.localPort
        updateCaptureStatus(statusText = "Listening", clientCount = 0, lastError = null)

        // Accept client connections
        serverJob = scope.launch {
            Log.d(TAG, "Audio server started on port $port")
            while (isActive) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    if (clients.size >= MAX_CLIENTS) {
                        Log.w(TAG, "Max audio clients ($MAX_CLIENTS) reached, rejecting")
                        try { clientSocket.close() } catch (_: Exception) {}
                        continue
                    }
                    clientSocket.tcpNoDelay = true
                    clientSocket.keepAlive = true
                    clientSocket.sendBufferSize = 16 * 1024
                    val output = DataOutputStream(clientSocket.getOutputStream())
                    clients.add(AudioClientConnection(clientSocket, output))
                    updateCaptureStatus(clientCount = clients.size)
                    Log.d(TAG, "Audio client connected: ${clientSocket.inetAddress.hostAddress}")
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Audio accept error", e)
                    break
                }
            }
        }

        // Try audio capture (may fail if RECORD_AUDIO not yet granted)
        startCapture()

        return port
    }

    /**
     * Retry audio capture after RECORD_AUDIO permission is granted.
     * Safe to call multiple times — no-op if already capturing.
     */
    @Synchronized
    fun ensureCapture() {
        if (serverSocket == null) return  // server not started

        val recorder = audioRecord
        val isRecording = recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (recorder != null && isRecording && captureJob?.isActive == true) {
            return  // already capturing
        }

        Log.d(TAG, "Ensuring audio capture is active")
        releaseCaptureResources(cancelJob = true)
        startCapture()
    }

    @Synchronized
    private fun startCapture() {
        if (serverSocket == null) return
        if (captureJob?.isActive == true || audioRecord != null) return

        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Microphone permission not granted yet; continuing in video-only mode")
            updateCaptureStatus(
                statusText = "Permission Needed",
                lastError = "Microphone permission not granted"
            )
            return
        }

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(
            minBufSize.takeIf { it > 0 } ?: 0,
            BYTES_PER_CHUNK * 2
        )

        try {
            audioRecord = createAudioRecord(bufferSize)
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission not granted", e)
            updateCaptureStatus(statusText = "Permission Needed", lastError = "Microphone permission not granted")
            return
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord is unavailable; continuing in video-only mode", e)
            audioRecord = null
            updateCaptureStatus(
                statusText = "Video Only",
                lastError = e.message ?: "Microphone unavailable"
            )
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            updateCaptureStatus(statusText = "Error", lastError = "AudioRecord init failed")
            return
        }

        // Prefer raw 48 kHz PCM for live monitoring until sender/receiver codec negotiation exists.
        // It costs more bandwidth than Opus, but removes codec startup failures and codec delay.
        useOpus = false
        if (PREFER_OPUS && OpusEncoder.isSupported()) {
            val encoder = OpusEncoder()
            if (encoder.start()) {
                opusEncoder = encoder
                useOpus = true
                Log.d(TAG, "Opus encoding enabled")
            } else {
                Log.w(TAG, "Opus encoder failed to start, falling back to PCM")
            }
        } else {
            Log.d(TAG, "Using PCM audio transport")
        }

        // Apply pending mic direction if set
        applyMicDirection()

        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord failed to start recording", e)
            releaseCaptureResources(cancelJob = false)
            scheduleCaptureRestart("startRecording failed")
            return
        }

        if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "AudioRecord did not enter RECORDSTATE_RECORDING")
            releaseCaptureResources(cancelJob = false)
            scheduleCaptureRestart("AudioRecord not recording")
            return
        }

        updateCaptureStatus(statusText = "Capturing", lastError = null)

        if (useOpus) {
            startOpusCapture()
        } else {
            startPcmCapture()
        }
    }

    private fun createAudioRecord(bufferSize: Int): AudioRecord {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        }

        val format = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .build()
        val audioSources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.DEFAULT
        )
        var lastFailure: Exception? = null

        for (audioSource in audioSources) {
            val recorder = try {
                AudioRecord.Builder()
                    .setAudioSource(audioSource)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } catch (e: Exception) {
                lastFailure = e
                Log.w(TAG, "Audio source $audioSource unavailable: ${e.message}")
                null
            }
            if (recorder != null) {
                if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d(TAG, "AudioRecord initialized with source=$audioSource")
                    return recorder
                }
                try { recorder.release() } catch (_: Exception) {}
            }
        }

        throw UnsupportedOperationException(
            "No compatible microphone input is available",
            lastFailure
        )
    }

    private fun startPcmCapture() {
        captureJob = scope.launch {
            val buffer = ShortArray(SAMPLES_PER_CHUNK)
            var consecutiveReadErrors = 0
            val sampleRateDiv100 = SAMPLE_RATE / 100
            val metaHeader = byteArrayOf(
                META_HEADER_V2_VERSION,
                META_HEADER_V2_CHANNELS,
                CODEC_PCM,
                (sampleRateDiv100 shr 8).toByte(),
                (sampleRateDiv100 and 0xFF).toByte()
            )

            while (isActive) {
                val read = try {
                    audioRecord?.read(buffer, 0, SAMPLES_PER_CHUNK) ?: break
                } catch (e: Exception) {
                    Log.w(TAG, "PCM AudioRecord read threw, restarting capture: ${e.message}")
                    scheduleCaptureRestart("PCM read exception")
                    break
                }

                if (read <= 0) {
                    if (read < 0) {
                        consecutiveReadErrors++
                        if (handleReadError("PCM", read, consecutiveReadErrors)) break
                    }
                    continue
                }
                consecutiveReadErrors = 0

                // Calculate RMS level
                val rms = calculateRms(buffer, read)
                val dbLevel = rmsToNormalizedDb(rms)
                _audioLevels.value = listOf(dbLevel, dbLevel)  // mono duplicated to L/R

                // Convert to bytes: v2 meta header + PCM
                val pcmBytes = shortArrayToBytes(buffer, read)
                val payload = metaHeader + pcmBytes

                // Encrypt and broadcast
                broadcastAudio(payload)
            }
        }
    }

    private fun startOpusCapture() {
        captureJob = scope.launch {
            val buffer = ShortArray(SAMPLES_PER_CHUNK)
            var consecutiveReadErrors = 0
            // v2 header: [version=2][channels=1][codec=OPUS][sampleRate/100 as 2 bytes big-endian]
            val sampleRateDiv100 = OPUS_SAMPLE_RATE / 100  // 480
            val metaHeader = byteArrayOf(
                META_HEADER_V2_VERSION,
                META_HEADER_V2_CHANNELS,
                CODEC_OPUS,
                (sampleRateDiv100 shr 8).toByte(),
                (sampleRateDiv100 and 0xFF).toByte()
            )

            // Accumulator for resampled samples to fill complete Opus frames
            var resampledAccum = ShortArray(0)

            while (isActive) {
                val read = try {
                    audioRecord?.read(buffer, 0, SAMPLES_PER_CHUNK) ?: break
                } catch (e: Exception) {
                    Log.w(TAG, "Opus AudioRecord read threw, restarting capture: ${e.message}")
                    scheduleCaptureRestart("Opus read exception")
                    break
                }

                if (read <= 0) {
                    if (read < 0) {
                        consecutiveReadErrors++
                        if (handleReadError("Opus", read, consecutiveReadErrors)) break
                    }
                    continue
                }
                consecutiveReadErrors = 0

                // Calculate RMS level from raw PCM
                val rms = calculateRms(buffer, read)
                val dbLevel = rmsToNormalizedDb(rms)
                _audioLevels.value = listOf(dbLevel, dbLevel)

                val resampled = if (SAMPLE_RATE == OPUS_SAMPLE_RATE) {
                    buffer.copyOf(read)
                } else {
                    OpusEncoder.resample44100to48000(buffer, read)
                }

                // Accumulate resampled samples
                resampledAccum = resampledAccum + resampled

                // Encode complete Opus frames (960 samples each)
                while (resampledAccum.size >= OpusEncoder.FRAME_SIZE) {
                    val frame = resampledAccum.copyOfRange(0, OpusEncoder.FRAME_SIZE)
                    resampledAccum = resampledAccum.copyOfRange(OpusEncoder.FRAME_SIZE, resampledAccum.size)

                    val encoded = opusEncoder?.encode(frame)
                    if (encoded != null) {
                        val payload = metaHeader + encoded
                        broadcastAudio(payload)
                    }
                }
            }
        }
    }

    private suspend fun handleReadError(codec: String, read: Int, consecutiveErrors: Int): Boolean {
        Log.w(TAG, "$codec AudioRecord read error=$read consecutive=$consecutiveErrors")
        if (read == AUDIO_RECORD_ERROR_DEAD_OBJECT ||
            consecutiveErrors >= MAX_CONSECUTIVE_READ_ERRORS ||
            read == AudioRecord.ERROR_INVALID_OPERATION
        ) {
            scheduleCaptureRestart("$codec read error $read")
            return true
        }
        delay(CHUNK_DURATION_MS.toLong())
        return false
    }

    private fun scheduleCaptureRestart(reason: String) {
        if (serverSocket == null || restartingCapture) return
        restartingCapture = true
        scope.launch {
            Log.w(TAG, "Restarting audio capture: $reason")
            updateCaptureStatus(
                statusText = "Restarting",
                restartCount = _captureStatus.value.restartCount + 1,
                lastError = reason
            )
            releaseCaptureResources(cancelJob = true)
            delay(AUDIO_CAPTURE_RESTART_DELAY_MS)
            captureJob = null
            restartingCapture = false
            if (serverSocket != null) {
                startCapture()
            }
        }
    }

    @Synchronized
    private fun releaseCaptureResources(cancelJob: Boolean) {
        if (cancelJob) {
            captureJob?.cancel()
            captureJob = null
        }

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        opusEncoder?.release()
        opusEncoder = null
        useOpus = false
        _audioLevels.value = listOf(0f, 0f)
    }

    private fun updateCaptureStatus(
        statusText: String = _captureStatus.value.statusText,
        clientCount: Int = _captureStatus.value.clientCount,
        restartCount: Int = _captureStatus.value.restartCount,
        lastError: String? = _captureStatus.value.lastError
    ) {
        _captureStatus.value = AudioCaptureStatus(
            statusText = statusText,
            clientCount = clientCount,
            restartCount = restartCount,
            lastError = lastError
        )
    }

    private fun calculateRms(samples: ShortArray, count: Int): Float {
        if (count == 0) return 0f
        var sumSquares = 0.0
        for (i in 0 until count) {
            val sample = samples[i].toFloat() / Short.MAX_VALUE
            sumSquares += sample * sample
        }
        return sqrt(sumSquares / count).toFloat()
    }

    private fun rmsToNormalizedDb(rms: Float): Float {
        if (rms <= 0.001f) return 0f
        val db = 20f * log10(rms)  // dB range: roughly -60 to 0
        // Normalize to 0..1 range where -60dB=0, 0dB=1
        return ((db + 60f) / 60f).coerceIn(0f, 1f)
    }

    private fun shortArrayToBytes(samples: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            bytes[i * 2] = (samples[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (samples[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    private fun broadcastAudio(payload: ByteArray) {
        if (clients.isEmpty()) return

        val cipher = frameCipher
        val encrypted = if (cipher != null) {
            cipher.encrypt(payload)
        } else {
            payload
        }

        for (client in clients) {
            if (client.isSending) {
                continue
            }
            client.isSending = true
            sendExecutor.execute {
                try {
                    client.output.writeInt(encrypted.size)
                    client.output.write(encrypted)
                    client.output.flush()
                } catch (e: Exception) {
                    clients.remove(client)
                    try { client.socket.close() } catch (_: Exception) {}
                    updateCaptureStatus(clientCount = clients.size)
                    Log.d(TAG, "Audio client disconnected")
                } finally {
                    client.isSending = false
                }
            }
        }
    }

    /**
     * Set preferred microphone direction.
     * @param direction MicrophoneDirection constant (FRONT, BACK, EXTERNAL)
     */
    fun setPreferredMicrophoneDirection(direction: Int) {
        pendingMicDirection = direction
        applyMicDirection()
    }

    private fun applyMicDirection() {
        val dir = pendingMicDirection ?: return
        val recorder = audioRecord ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                recorder.setPreferredMicrophoneDirection(dir)
                Log.d(TAG, "Mic direction set to $dir")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set mic direction: ${e.message}")
            }
        }
    }

    fun stop() {
        restartingCapture = false
        releaseCaptureResources(cancelJob = true)

        serverJob?.cancel()
        serverJob = null

        clients.forEach {
            try { it.socket.close() } catch (_: Exception) {}
        }
        clients.clear()

        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null

        frameCipher = null
        _captureStatus.value = AudioCaptureStatus()
    }

    fun cleanup() {
        stop()
        sendExecutor.shutdownNow()
        scope.cancel()
    }
}
