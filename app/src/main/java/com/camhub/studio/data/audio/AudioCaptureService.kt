package com.camhub.studio.data.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MicrophoneDirection
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.camhub.studio.data.network.FrameCipher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

@Singleton
class AudioCaptureService @Inject constructor() {

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val SAMPLE_RATE = 44100
        private const val OPUS_SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_DURATION_MS = 20
        private const val SAMPLES_PER_CHUNK = SAMPLE_RATE * CHUNK_DURATION_MS / 1000  // 882
        private const val BYTES_PER_CHUNK = SAMPLES_PER_CHUNK * 2  // 1764 bytes (16-bit)
        private const val MAX_CLIENTS = 4

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

    private var frameCipher: FrameCipher? = null
    private var pendingMicDirection: Int? = null

    private var opusEncoder: OpusEncoder? = null
    private var useOpus = false

    private val _audioLevels = MutableStateFlow<List<Float>>(listOf(0f, 0f))
    val audioLevels: StateFlow<List<Float>> = _audioLevels.asStateFlow()

    private class AudioClientConnection(
        val socket: Socket,
        val output: DataOutputStream
    )

    fun setSessionKey(key: ByteArray) {
        frameCipher = FrameCipher(key)
    }

    fun start(): Int {
        stop()

        serverSocket = ServerSocket(0)
        val port = serverSocket!!.localPort

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
                    val output = DataOutputStream(clientSocket.getOutputStream())
                    clients.add(AudioClientConnection(clientSocket, output))
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
        if (audioRecord != null) return  // already capturing
        if (serverSocket == null) return  // server not started
        Log.d(TAG, "Retrying audio capture after permission grant")
        startCapture()
    }

    private fun startCapture() {
        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufSize, BYTES_PER_CHUNK * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission not granted", e)
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return
        }

        // Try to initialise Opus encoder; fall back to PCM if it fails
        useOpus = false
        if (OpusEncoder.isSupported()) {
            val encoder = OpusEncoder()
            if (encoder.start()) {
                opusEncoder = encoder
                useOpus = true
                Log.d(TAG, "Opus encoding enabled")
            } else {
                Log.w(TAG, "Opus encoder failed to start, falling back to PCM")
            }
        } else {
            Log.d(TAG, "Opus not supported on this API level, using PCM")
        }

        // Apply pending mic direction if set
        applyMicDirection()

        audioRecord?.startRecording()

        if (useOpus) {
            startOpusCapture()
        } else {
            startPcmCapture()
        }
    }

    private fun startPcmCapture() {
        captureJob = scope.launch {
            val buffer = ShortArray(SAMPLES_PER_CHUNK)
            val metaHeader = byteArrayOf(META_HEADER_V1_VERSION, META_HEADER_V1_CHANNELS)

            while (isActive) {
                val read = audioRecord?.read(buffer, 0, SAMPLES_PER_CHUNK) ?: break
                if (read <= 0) continue

                // Calculate RMS level
                val rms = calculateRms(buffer, read)
                val dbLevel = rmsToNormalizedDb(rms)
                _audioLevels.value = listOf(dbLevel, dbLevel)  // mono duplicated to L/R

                // Convert to bytes: meta header + PCM
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
                val read = audioRecord?.read(buffer, 0, SAMPLES_PER_CHUNK) ?: break
                if (read <= 0) continue

                // Calculate RMS level from raw PCM
                val rms = calculateRms(buffer, read)
                val dbLevel = rmsToNormalizedDb(rms)
                _audioLevels.value = listOf(dbLevel, dbLevel)

                // Resample 44100 -> 48000
                val resampled = OpusEncoder.resample44100to48000(buffer, read)

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

        val disconnected = mutableListOf<AudioClientConnection>()

        for (client in clients) {
            try {
                client.output.writeInt(encrypted.size)
                client.output.write(encrypted)
                client.output.flush()
            } catch (e: Exception) {
                disconnected.add(client)
            }
        }

        for (client in disconnected) {
            clients.remove(client)
            try { client.socket.close() } catch (_: Exception) {}
            Log.d(TAG, "Audio client disconnected")
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
        captureJob?.cancel()
        captureJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        opusEncoder?.release()
        opusEncoder = null
        useOpus = false

        serverJob?.cancel()
        serverJob = null

        clients.forEach {
            try { it.socket.close() } catch (_: Exception) {}
        }
        clients.clear()

        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null

        frameCipher = null
        _audioLevels.value = listOf(0f, 0f)
    }

    fun cleanup() {
        stop()
        scope.cancel()
    }
}
