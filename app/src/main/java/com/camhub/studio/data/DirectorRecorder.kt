package com.camhub.studio.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
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
        private const val DEFAULT_BIT_RATE = 10_000_000
        private const val DEFAULT_FRAME_RATE = 30
        private const val I_FRAME_INTERVAL = 1
    }

    var bitrateMbps: Int = 10
    var useHevc: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var drainJob: Job? = null

    private var pauseStartTimeMs: Long = 0L

    private val _recordingInfo = MutableStateFlow(RecordingInfo())
    val recordingInfo: StateFlow<RecordingInfo> = _recordingInfo.asStateFlow()

    fun getRecordingDirectory(): File {
        return context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
    }

    fun startRecording(
        width: Int = 1920,
        height: Int = 1080,
        bitrateMbps: Int = this.bitrateMbps,
        frameRate: Int = DEFAULT_FRAME_RATE
    ): String? {
        if (_recordingInfo.value.isRecording) return null

        val dir = getRecordingDirectory()
        dir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(dir, "CamHub_PGM_$timestamp.mp4")
        val outputPath = outputFile.absolutePath

        val mimeType = if (useHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val effectiveBitrate = bitrateMbps * 1_000_000

        return try {
            val format = MediaFormat.createVideoFormat(
                mimeType, width, height
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, effectiveBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            encoder = MediaCodec.createEncoderByType(mimeType).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            _recordingInfo.value = RecordingInfo(
                isRecording = true,
                startTimeMs = System.currentTimeMillis(),
                outputPath = outputPath
            )

            drainJob = scope.launch { drainEncoder() }
            Log.d(TAG, "Recording started: $outputPath")
            outputPath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            releaseResources()
            null
        }
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

        _recordingInfo.value = _recordingInfo.value.copy(isRecording = false, isPaused = false)
        pauseStartTimeMs = 0L

        scope.launch {
            try {
                encoder?.signalEndOfInputStream()
                delay(200)
            } catch (_: Exception) {}

            drainJob?.cancel()
            drainJob = null
            releaseResources()
            Log.d(TAG, "Recording stopped")
        }
    }

    private suspend fun drainEncoder() {
        val enc = encoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (_recordingInfo.value.isRecording) {
            val outputIndex = enc.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val mux = muxer ?: break
                    videoTrackIndex = mux.addTrack(enc.outputFormat)
                    mux.start()
                    muxerStarted = true
                }
                outputIndex >= 0 -> {
                    if (muxerStarted && bufferInfo.size > 0) {
                        val mux = muxer ?: break
                        val outputBuffer = enc.getOutputBuffer(outputIndex) ?: continue
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        mux.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                    }
                    enc.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    }

    private fun releaseResources() {
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        try {
            if (muxerStarted) muxer?.stop()
            muxer?.release()
        } catch (_: Exception) {}

        encoder = null
        muxer = null
        inputSurface = null
        videoTrackIndex = -1
        muxerStarted = false
    }
}
