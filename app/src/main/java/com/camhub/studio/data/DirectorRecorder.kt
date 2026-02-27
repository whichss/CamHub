package com.camhub.studio.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class RecordingInfo(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val startTimeMs: Long = 0L,
    val pausedDurationMs: Long = 0L,
    val outputPath: String = ""
)

@Singleton
class DirectorRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DirectorRecorder"
        private const val I_FRAME_INTERVAL = 2
        private const val DRAIN_TIMEOUT_US = 10_000L
        private const val STOP_DRAIN_TIMEOUT_MS = 3000L
    }

    var bitrateMbps: Int = 10
    var frameRate: Int = 30
    var useHevc: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var drainJob: Job? = null
    private var frameCount = 0

    private var pauseStartTimeMs: Long = 0L
    private var currentOutputPath: String? = null

    private val _recordingInfo = MutableStateFlow(RecordingInfo())
    val recordingInfo: StateFlow<RecordingInfo> = _recordingInfo.asStateFlow()

    @Volatile
    private var stopRequested = false

    fun getRecordingDirectory(): File {
        return context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
    }

    fun startRecording(
        width: Int,
        height: Int,
        sessionTimestamp: String = ""
    ): String? {
        if (_recordingInfo.value.isRecording) return null

        // Ensure dimensions are even (required by most encoders)
        val w = (width.coerceAtLeast(320) + 1) and 0x7FFFFFFE
        val h = (height.coerceAtLeast(240) + 1) and 0x7FFFFFFE

        val mimeType = if (useHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val effectiveBitrate = bitrateMbps * 1_000_000

        return try {
            val format = MediaFormat.createVideoFormat(mimeType, w, h).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, effectiveBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            Log.d(TAG, "Encoder config: ${w}x${h} ${mimeType} ${effectiveBitrate/1_000_000}Mbps ${frameRate}fps")

            encoder = MediaCodec.createEncoderByType(mimeType).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            val muxerResult = createMuxer(sessionTimestamp)
            muxer = muxerResult.first
            val displayPath = muxerResult.second

            frameCount = 0
            stopRequested = false
            currentOutputPath = displayPath

            _recordingInfo.value = RecordingInfo(
                isRecording = true,
                startTimeMs = System.currentTimeMillis(),
                outputPath = displayPath
            )

            drainJob = scope.launch { drainEncoder() }
            Log.d(TAG, "Recording started: $displayPath")
            displayPath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            releaseResources()
            null
        }
    }

    /** Creates MediaMuxer via MediaStore (gallery-visible) or fallback file path. Returns (muxer, displayPath). */
    private fun createMuxer(sessionTimestamp: String = ""): Pair<MediaMuxer, String> {
        val timestamp = sessionTimestamp.ifEmpty {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }
        val fileName = "CamHub_$timestamp-PGM.mp4"

        // Use MediaStore + FileDescriptor for Android O+ (API 26) so files appear in gallery
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/CamHub")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    val fd = context.contentResolver.openFileDescriptor(uri, "rw")
                    if (fd != null) {
                        pendingMediaUri = uri
                        pendingFd = fd
                        val m = MediaMuxer(
                            fd.fileDescriptor,
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                        )
                        Log.d(TAG, "Muxer created via MediaStore: $uri")
                        return Pair(m, "Movies/CamHub/$fileName")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore muxer failed, falling back to app dir", e)
            }
        }

        // Fallback: app-specific directory
        val dir = getRecordingDirectory()
        dir.mkdirs()
        val outputPath = File(dir, fileName).absolutePath
        val m = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return Pair(m, outputPath)
    }

    private var pendingMediaUri: android.net.Uri? = null
    private var pendingFd: android.os.ParcelFileDescriptor? = null

    private fun finalizeMediaStore() {
        val uri = pendingMediaUri ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, values, null, null)
                Log.d(TAG, "MediaStore finalized: $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore finalize error", e)
        }
        try { pendingFd?.close() } catch (_: Exception) {}
        pendingFd = null
        pendingMediaUri = null
    }

    fun onFrame(bitmap: Bitmap) {
        val surface = inputSurface ?: return
        if (!_recordingInfo.value.isRecording || _recordingInfo.value.isPaused) return

        try {
            val canvas = surface.lockCanvas(null)
            canvas.drawBitmap(
                bitmap,
                null,
                Rect(0, 0, canvas.width, canvas.height),
                null
            )
            surface.unlockCanvasAndPost(canvas)
            frameCount++
            if (frameCount == 1) {
                Log.d(TAG, "First frame written to encoder (bitmap ${bitmap.width}x${bitmap.height})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame write error: ${e.message}")
        }
    }

    fun pauseRecording() {
        val info = _recordingInfo.value
        if (!info.isRecording || info.isPaused) return
        pauseStartTimeMs = System.currentTimeMillis()
        _recordingInfo.value = info.copy(isPaused = true)
        Log.d(TAG, "Recording paused")
    }

    fun resumeRecording() {
        val info = _recordingInfo.value
        if (!info.isRecording || !info.isPaused) return
        val pausedElapsed = System.currentTimeMillis() - pauseStartTimeMs
        _recordingInfo.value = info.copy(
            isPaused = false,
            pausedDurationMs = info.pausedDurationMs + pausedElapsed
        )
        pauseStartTimeMs = 0L
        Log.d(TAG, "Recording resumed")
    }

    fun stopRecording() {
        if (!_recordingInfo.value.isRecording) return
        val path = currentOutputPath

        _recordingInfo.value = _recordingInfo.value.copy(isRecording = false, isPaused = false)
        pauseStartTimeMs = 0L

        scope.launch {
            stopRequested = true

            // Signal EOS to encoder
            try {
                encoder?.signalEndOfInputStream()
                Log.d(TAG, "EOS signaled, waiting for drain (frames written: $frameCount)")
            } catch (e: Exception) {
                Log.w(TAG, "signalEndOfInputStream error: ${e.message}")
            }

            // Wait for drain to finish with timeout
            try {
                withTimeout(STOP_DRAIN_TIMEOUT_MS) {
                    drainJob?.join()
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Drain timeout after ${STOP_DRAIN_TIMEOUT_MS}ms, forcing stop")
                drainJob?.cancel()
            }

            drainJob = null
            releaseResources()
            finalizeMediaStore()
            stopRequested = false
            Log.d(TAG, "Recording stopped: $path (total frames: $frameCount)")
        }
    }

    private suspend fun drainEncoder() {
        val enc = encoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        var drainedFrames = 0

        try {
            while (true) {
                val outputIndex = enc.dequeueOutputBuffer(bufferInfo, DRAIN_TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Do NOT break here even if stopRequested.
                        // We must wait for EOS buffer from the encoder.
                        // Only timeout in stopRecording() will force exit.
                        if (!stopRequested) {
                            // Normal operation: no output available yet, continue polling
                        }
                        // When stopRequested, keep polling - EOS should arrive soon
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val mux = muxer ?: break
                        videoTrackIndex = mux.addTrack(enc.outputFormat)
                        mux.start()
                        muxerStarted = true
                        Log.d(TAG, "Muxer started with format: ${enc.outputFormat}")
                    }
                    outputIndex >= 0 -> {
                        if (muxerStarted && bufferInfo.size > 0) {
                            val mux = muxer ?: break
                            val outputBuffer = enc.getOutputBuffer(outputIndex)
                            if (outputBuffer != null) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                mux.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                                drainedFrames++
                            }
                        }
                        enc.releaseOutputBuffer(outputIndex, false)

                        // Check for EOS flag
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.d(TAG, "EOS received, drain complete (drained $drainedFrames frames)")
                            break
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            Log.w(TAG, "Drain cancelled (drained $drainedFrames frames)")
        } catch (e: Exception) {
            Log.e(TAG, "drainEncoder error (drained $drainedFrames frames)", e)
        }
    }

    private fun releaseResources() {
        try { encoder?.stop() } catch (e: Exception) {
            Log.w(TAG, "encoder.stop() error: ${e.message}")
        }
        try { encoder?.release() } catch (_: Exception) {}
        try {
            if (muxerStarted) {
                muxer?.stop()
                Log.d(TAG, "Muxer stopped successfully")
            }
            muxer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Muxer stop/release error: ${e.message}")
        }

        encoder = null
        muxer = null
        inputSurface = null
        videoTrackIndex = -1
        muxerStarted = false
    }
}
