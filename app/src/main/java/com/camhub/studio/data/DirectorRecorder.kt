package com.camhub.studio.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.camhub.studio.data.gl.PgmTransitionFrame
import com.camhub.studio.data.gl.PgmTransitionMode
import com.camhub.studio.data.gl.SharedGpuVideoFrame
import com.camhub.studio.data.gl.SpatialUpscaleWindowRenderer
import com.camhub.studio.data.ptz.HubPtzTransform
import com.camhub.studio.data.ptz.HybridPtzController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.ArrayDeque
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

data class RecordingInfo(
    val isRecording: Boolean = false,
    val isStarting: Boolean = false,
    val isStopping: Boolean = false,
    val isPaused: Boolean = false,
    val startTimeMs: Long = 0L,
    val pausedDurationMs: Long = 0L,
    val outputPath: String = "",
    val isSpatialUpscaling: Boolean = false,
    val outputHeight: Int = 0,
    val includesAudio: Boolean = false,
    val errorMessage: String? = null
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
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_CHANNEL_COUNT = 1
        private const val AUDIO_BITRATE = 96_000
        private const val MAX_PENDING_AUDIO_CHUNKS = 12
        private const val MAX_PRE_MUX_SAMPLES = 96
        private const val AUDIO_FORMAT_TIMEOUT_MS = 1_000L
    }

    var bitrateMbps: Int = 10
    var frameRate: Int = 30
    var useHevc: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private enum class LifecycleState { IDLE, STARTING, RECORDING, STOPPING }
    private var lifecycleState = LifecycleState.IDLE
    private var cancelStartRequested = false
    @Volatile private var recordingStartNs = 0L
    @Volatile private var accumulatedPauseNs = 0L
    @Volatile private var pauseStartNs = 0L

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var spatialUpscaleRenderer: SpatialUpscaleWindowRenderer? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var drainJob: Job? = null
    private var audioInputJob: Job? = null
    private var audioEncoder: MediaCodec? = null
    private var audioTrackIndex = -1
    private var videoFormatReady = false
    private var audioFormatReady = false
    @Volatile private var audioExpected = false
    private val audioInputQueue = ArrayBlockingQueue<AudioInputChunk>(MAX_PENDING_AUDIO_CHUNKS)
    private val preMuxSamples = ArrayDeque<PendingMuxSample>()
    private var recordingStartedElapsedMs = 0L
    private var frameCount = 0

    private var currentOutputPath: String? = null
    private val frameWriter: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DirectorRecorderFrameWriter").apply { isDaemon = true }
    }
    private val frameWriteInFlight = AtomicBoolean(false)
    private val droppedFrameCount = AtomicLong(0)

    private data class AudioInputChunk(val samples: ShortArray, val presentationTimeUs: Long)
    private data class PendingMuxSample(
        val isAudio: Boolean,
        val bytes: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int
    )

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
        sessionTimestamp: String = "",
        useSpatialUpscaling: Boolean = false,
        includeAudio: Boolean = false
    ): String? {
        synchronized(lifecycleLock) {
            if (lifecycleState != LifecycleState.IDLE) return null
            lifecycleState = LifecycleState.STARTING
            cancelStartRequested = false
            currentOutputPath = null
            _recordingInfo.value = _recordingInfo.value.copy(isStarting = true, errorMessage = null)
        }

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

            audioEncoder = if (includeAudio) createAudioEncoder() else null
            audioExpected = audioEncoder != null
            audioTrackIndex = -1
            videoFormatReady = false
            audioFormatReady = !audioExpected
            preMuxSamples.clear()
            audioInputQueue.clear()

            // Keep recording composition on its dedicated GL thread even when
            // the source is already at output resolution. This lets transitions
            // reach MediaCodec without allocating intermediate CPU bitmaps.
            val encoderSurface = inputSurface
                ?: throw IllegalStateException("Encoder input surface is unavailable")
            val glRenderer = SpatialUpscaleWindowRenderer(
                surface = encoderSurface,
                outputWidth = w,
                outputHeight = h,
                enableSpatialReconstruction = useSpatialUpscaling,
                onFrameRendered = { rendered ->
                    if (rendered) {
                        frameCount++
                        if (frameCount == 1) {
                            Log.d(TAG, "First frame rendered to encoder by OpenGL")
                        }
                    } else {
                        noteDroppedRecordFrame()
                    }
                    frameWriteInFlight.set(false)
                }
            )
            if (glRenderer.start()) {
                spatialUpscaleRenderer = glRenderer
            } else {
                Log.w(TAG, "OpenGL recording unavailable; using Canvas fallback")
            }

            val muxerResult = createMuxer(sessionTimestamp)
            muxer = muxerResult.first
            val displayPath = muxerResult.second

            frameCount = 0
            droppedFrameCount.set(0)
            stopRequested = false
            currentOutputPath = displayPath

            synchronized(lifecycleLock) {
                check(!cancelStartRequested) { "Recording start cancelled" }
                lifecycleState = LifecycleState.RECORDING
                recordingStartNs = System.nanoTime()
                accumulatedPauseNs = 0L
                pauseStartNs = 0L
                _recordingInfo.value = RecordingInfo(
                    isRecording = true,
                    startTimeMs = System.currentTimeMillis(),
                    outputPath = displayPath,
                    isSpatialUpscaling = useSpatialUpscaling && spatialUpscaleRenderer != null,
                    outputHeight = h,
                    includesAudio = audioExpected
                )
                recordingStartedElapsedMs = android.os.SystemClock.elapsedRealtime()
                if (audioExpected) startAudioInputPump()
                drainJob = scope.launch { drainEncoder() }
            }

            Log.d(TAG, "Recording started: $displayPath")
            displayPath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            releaseResources()
            discardUnstartedOutput()
            synchronized(lifecycleLock) {
                lifecycleState = LifecycleState.IDLE
                _recordingInfo.value = RecordingInfo(errorMessage = e.message ?: "Recording could not start")
            }
            null
        } finally {
            synchronized(lifecycleLock) {
                cancelStartRequested = false
                if (_recordingInfo.value.isStarting) {
                    _recordingInfo.value = _recordingInfo.value.copy(isStarting = false)
                }
            }
        }
    }

    /** Called from the audio mixer thread; only bounded, non-blocking enqueue work is done here. */
    fun offerMixedAudio(samples: ShortArray, timestampNs: Long) {
        if (!audioExpected || !_recordingInfo.value.isRecording || _recordingInfo.value.isPaused) return
        val presentationTimeUs = recordingPresentationTimeUs(timestampNs) ?: return
        val chunk = AudioInputChunk(samples.copyOf(), presentationTimeUs)
        if (!audioInputQueue.offer(chunk)) {
            audioInputQueue.poll()
            audioInputQueue.offer(chunk)
        }
    }

    private fun createAudioEncoder(): MediaCodec? {
        var codec: MediaCodec? = null
        return try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4_096)
            }
            val created = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = created
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
            created
        } catch (error: Exception) {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            Log.w(TAG, "AAC recorder unavailable; recording video without audio", error)
            null
        }
    }

    private fun startAudioInputPump() {
        audioInputJob?.cancel()
        audioInputJob = scope.launch(Dispatchers.IO) {
            while (isActive && (_recordingInfo.value.isRecording || audioInputQueue.isNotEmpty())) {
                val chunk = audioInputQueue.poll()
                if (chunk == null) {
                    delay(5)
                    continue
                }
                val codec = audioEncoder ?: break
                try {
                    val inputIndex = codec.dequeueInputBuffer(5_000)
                    if (inputIndex < 0) continue
                    val input = codec.getInputBuffer(inputIndex)
                    if (input == null) {
                        codec.queueInputBuffer(inputIndex, 0, 0, chunk.presentationTimeUs, 0)
                        continue
                    }
                    input.clear()
                    input.order(ByteOrder.nativeOrder())
                    val count = minOf(chunk.samples.size, input.remaining() / Short.SIZE_BYTES)
                    repeat(count) { input.putShort(chunk.samples[it]) }
                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        count * Short.SIZE_BYTES,
                        chunk.presentationTimeUs,
                        0
                    )
                } catch (error: Exception) {
                    Log.w(TAG, "AAC input failed; dropping mixed audio chunk", error)
                }
            }
        }
    }

    private suspend fun signalAudioEndOfStream() {
        val codec = audioEncoder ?: return
        val deadline = android.os.SystemClock.elapsedRealtime() + 500L
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val index = runCatching { codec.dequeueInputBuffer(10_000) }.getOrDefault(-1)
            if (index >= 0) {
                runCatching {
                    codec.queueInputBuffer(
                        index,
                        0,
                        0,
                        System.nanoTime() / 1_000L,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }
                return
            }
        }
        Log.w(TAG, "Could not signal AAC end-of-stream")
    }

    private fun startMuxerWhenReady() {
        if (muxerStarted || !videoFormatReady || !audioFormatReady) return
        val mux = muxer ?: return
        try {
            mux.start()
            muxerStarted = true
            while (preMuxSamples.isNotEmpty()) writeMuxSample(mux, preMuxSamples.removeFirst())
            Log.d(TAG, "A/V muxer started")
        } catch (error: Exception) {
            Log.e(TAG, "Could not start A/V muxer", error)
        }
    }

    private fun writeMuxSample(mux: MediaMuxer, sample: PendingMuxSample) {
        val track = if (sample.isAudio) audioTrackIndex else videoTrackIndex
        if (track < 0 || sample.bytes.isEmpty()) return
        val buffer = java.nio.ByteBuffer.wrap(sample.bytes)
        val info = MediaCodec.BufferInfo().apply {
            set(0, sample.bytes.size, sample.presentationTimeUs, sample.flags)
        }
        mux.writeSampleData(track, buffer, info)
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

    private fun discardUnstartedOutput() {
        val uri = pendingMediaUri
        if (uri != null) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            try { pendingFd?.close() } catch (_: Exception) {}
            pendingFd = null
            pendingMediaUri = null
        } else {
            currentOutputPath?.let { path -> runCatching { File(path).delete() } }
        }
        currentOutputPath = null
    }

    fun onFrame(
        bitmap: Bitmap,
        ptzTransform: HubPtzTransform = HybridPtzController.IDENTITY_TRANSFORM,
        transition: PgmTransitionFrame? = null
    ) {
        if (!_recordingInfo.value.isRecording || _recordingInfo.value.isPaused) return
        if (!frameWriteInFlight.compareAndSet(false, true)) {
            noteDroppedRecordFrame()
            return
        }

        val glRenderer = spatialUpscaleRenderer
        val presentationTimeNs = recordingPresentationTimeNs(System.nanoTime()) ?: run {
            frameWriteInFlight.set(false)
            return
        }
        if (glRenderer != null) {
            if (!glRenderer.render(bitmap, presentationTimeNs, ptzTransform, transition)) {
                frameWriteInFlight.set(false)
                noteDroppedRecordFrame()
            }
            return
        }

        try {
            frameWriter.execute {
                try {
                    val surface = inputSurface ?: return@execute
                    if (!_recordingInfo.value.isRecording || _recordingInfo.value.isPaused || stopRequested) {
                        return@execute
                    }
                    drawFrameToSurface(surface, bitmap, ptzTransform, transition)
                } finally {
                    frameWriteInFlight.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            frameWriteInFlight.set(false)
            Log.w(TAG, "Frame write rejected: ${e.message}")
        }
    }

    /**
     * Queues a decoder-owned shared texture directly to the encoder GL context. On true, the
     * renderer owns [frame] and releases it after a GPU fence; on false, ownership stays here.
     */
    fun onSharedGpuFrame(
        frame: SharedGpuVideoFrame,
        ptzTransform: HubPtzTransform = HybridPtzController.IDENTITY_TRANSFORM
    ): Boolean {
        if (!_recordingInfo.value.isRecording || _recordingInfo.value.isPaused || stopRequested) return false
        if (!frameWriteInFlight.compareAndSet(false, true)) {
            noteDroppedRecordFrame()
            return false
        }
        val presentationTimeNs = recordingPresentationTimeNs(System.nanoTime())
        val glRenderer = spatialUpscaleRenderer
        if (presentationTimeNs == null || glRenderer == null) {
            frameWriteInFlight.set(false)
            return false
        }
        val accepted = glRenderer.renderTexture(frame, presentationTimeNs, ptzTransform)
        if (!accepted) {
            frameWriteInFlight.set(false)
            noteDroppedRecordFrame()
        }
        return accepted
    }

    private fun drawFrameToSurface(
        surface: Surface,
        bitmap: Bitmap,
        ptzTransform: HubPtzTransform,
        transition: PgmTransitionFrame? = null
    ) {
        var posted = false
        val canvas = try {
            surface.lockCanvas(null)
        } catch (e: Exception) {
            Log.w(TAG, "Frame lock error: ${e.message}")
            return
        } ?: return

        try {
            canvas.drawColor(Color.BLACK)
            val progress = transition?.progress?.coerceIn(0f, 1f) ?: 0f
            when (transition?.mode) {
                PgmTransitionMode.MIX -> {
                    drawPgmBitmap(canvas, bitmap, ptzTransform)
                    drawPgmBitmap(
                        canvas,
                        transition.bitmap,
                        transition.ptzTransform,
                        progress * 255f
                    )
                }
                PgmTransitionMode.DIP -> {
                    if (progress <= 0.5f) {
                        drawPgmBitmap(canvas, bitmap, ptzTransform, (1f - progress * 2f) * 255f)
                    } else {
                        drawPgmBitmap(
                            canvas,
                            transition.bitmap,
                            transition.ptzTransform,
                            ((progress - 0.5f) * 2f) * 255f
                        )
                    }
                }
                PgmTransitionMode.WIPE -> {
                    drawPgmBitmap(canvas, bitmap, ptzTransform)
                    val saveCount = canvas.save()
                    canvas.clipRect(RectF(0f, 0f, canvas.width * progress, canvas.height.toFloat()))
                    drawPgmBitmap(canvas, transition.bitmap, transition.ptzTransform)
                    canvas.restoreToCount(saveCount)
                }
                else -> drawPgmBitmap(canvas, bitmap, ptzTransform)
            }
            surface.unlockCanvasAndPost(canvas)
            posted = true
            frameCount++
            if (frameCount == 1) {
                Log.d(TAG, "First frame written to encoder (bitmap ${bitmap.width}x${bitmap.height})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame write error: ${e.message}")
        } finally {
            if (!posted) {
                try { surface.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
            }
        }
    }

    private fun drawPgmBitmap(
        canvas: android.graphics.Canvas,
        bitmap: Bitmap,
        ptzTransform: HubPtzTransform,
        alpha: Float = 255f
    ) {
        if (bitmap.isRecycled || alpha <= 0f) return
        val fitScale = minOf(
            canvas.width.toFloat() / bitmap.width,
            canvas.height.toFloat() / bitmap.height
        )
        val outputScale = fitScale * ptzTransform.scale
        val matrix = Matrix().apply {
            setScale(outputScale, outputScale)
            postTranslate(
                canvas.width / 2f - ptzTransform.centerX * bitmap.width * outputScale,
                canvas.height / 2f - ptzTransform.centerY * bitmap.height * outputScale
            )
        }
        canvas.drawBitmap(
            bitmap,
            matrix,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                this.alpha = alpha.toInt().coerceIn(0, 255)
            }
        )
    }

    private fun noteDroppedRecordFrame() {
        val dropped = droppedFrameCount.incrementAndGet()
        if (dropped % 30L == 0L) {
            Log.d(TAG, "Dropped $dropped recording frames to keep live preview responsive")
        }
    }

    fun pauseRecording() {
        val info = _recordingInfo.value
        if (!info.isRecording || info.isPaused) return
        pauseStartNs = System.nanoTime()
        _recordingInfo.value = info.copy(isPaused = true)
        Log.d(TAG, "Recording paused")
    }

    fun resumeRecording() {
        val info = _recordingInfo.value
        if (!info.isRecording || !info.isPaused) return
        val nowNs = System.nanoTime()
        val pausedElapsed = ((nowNs - pauseStartNs).coerceAtLeast(0L)) / 1_000_000L
        accumulatedPauseNs += (nowNs - pauseStartNs).coerceAtLeast(0L)
        _recordingInfo.value = info.copy(
            isPaused = false,
            pausedDurationMs = info.pausedDurationMs + pausedElapsed
        )
        pauseStartNs = 0L
        Log.d(TAG, "Recording resumed")
    }

    fun stopRecording() {
        synchronized(lifecycleLock) {
            if (lifecycleState == LifecycleState.STARTING) {
                cancelStartRequested = true
                return
            }
            if (lifecycleState != LifecycleState.RECORDING) return
            lifecycleState = LifecycleState.STOPPING
        }
        val path = currentOutputPath

        _recordingInfo.value = _recordingInfo.value.copy(
            isRecording = false,
            isStopping = true,
            isPaused = false,
            isSpatialUpscaling = false
        )
        pauseStartNs = 0L

        scope.launch {
            finishRecording(path)
        }
    }

    private suspend fun finishRecording(path: String?) {
        stopRequested = true
        try {
            spatialUpscaleRenderer?.release()
            spatialUpscaleRenderer = null
            frameWriteInFlight.set(false)

            try {
                audioInputJob?.join()
                audioInputJob = null
                audioInputQueue.clear()
                signalAudioEndOfStream()
                encoder?.signalEndOfInputStream()
            } catch (error: Exception) {
                Log.w(TAG, "Could not signal encoder end-of-stream", error)
            }

            try {
                withTimeout(STOP_DRAIN_TIMEOUT_MS) { drainJob?.join() }
            } catch (error: TimeoutCancellationException) {
                Log.w(TAG, "Drain timeout after ${STOP_DRAIN_TIMEOUT_MS}ms, forcing stop")
                drainJob?.cancel()
            }
            drainJob = null
            Log.d(TAG, "Recording stopped: $path (total frames: $frameCount)")
        } catch (error: Exception) {
            Log.e(TAG, "Recording finalization failed", error)
            _recordingInfo.value = _recordingInfo.value.copy(
                errorMessage = error.message ?: "Recording could not be finalized"
            )
        } finally {
            releaseResources()
            finalizeMediaStore()
            stopRequested = false
            synchronized(lifecycleLock) {
                lifecycleState = LifecycleState.IDLE
                _recordingInfo.value = _recordingInfo.value.copy(isStopping = false)
            }
        }
    }

    private fun recordingPresentationTimeNs(timestampNs: Long): Long? {
        val startedAt = recordingStartNs
        if (startedAt == 0L || timestampNs < startedAt) return null
        val pausedAt = pauseStartNs
        val pausedSoFar = if (pausedAt > 0L) (timestampNs - pausedAt).coerceAtLeast(0L) else 0L
        return (timestampNs - startedAt - accumulatedPauseNs - pausedSoFar).coerceAtLeast(0L)
    }

    private fun recordingPresentationTimeUs(timestampNs: Long): Long? =
        recordingPresentationTimeNs(timestampNs)?.div(1_000L)

    private suspend fun drainEncoder() {
        val enc = encoder ?: return
        val videoInfo = MediaCodec.BufferInfo()
        val audioInfo = MediaCodec.BufferInfo()
        var drainedFrames = 0
        var videoEos = false
        var audioEos = !audioExpected

        try {
            while (!videoEos || !audioEos) {
                if (
                    audioExpected && !audioFormatReady &&
                    android.os.SystemClock.elapsedRealtime() - recordingStartedElapsedMs >= AUDIO_FORMAT_TIMEOUT_MS
                ) {
                    Log.w(TAG, "No mixed audio arrived; continuing this recording as video-only")
                    audioExpected = false
                    audioFormatReady = true
                    audioInputJob?.cancelAndJoin()
                    audioInputJob = null
                    audioInputQueue.clear()
                    runCatching { audioEncoder?.stop() }
                    runCatching { audioEncoder?.release() }
                    audioEncoder = null
                    audioEos = true
                    _recordingInfo.value = _recordingInfo.value.copy(includesAudio = false)
                    startMuxerWhenReady()
                }

                val videoIndex = enc.dequeueOutputBuffer(videoInfo, DRAIN_TIMEOUT_US)
                when {
                    videoIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrackIndex = muxer?.addTrack(enc.outputFormat) ?: -1
                        videoFormatReady = videoTrackIndex >= 0
                        startMuxerWhenReady()
                    }
                    videoIndex >= 0 -> {
                        copyEncodedSample(enc, videoIndex, videoInfo)?.let { bytes ->
                            enqueueOrWriteSample(
                                PendingMuxSample(false, bytes, videoInfo.presentationTimeUs, videoInfo.flags)
                            )
                            drainedFrames++
                        }
                        videoEos = videoInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        enc.releaseOutputBuffer(videoIndex, false)
                    }
                }

                val audio = audioEncoder
                if (audioExpected && audio != null) {
                    val audioIndex = audio.dequeueOutputBuffer(audioInfo, 0)
                    when {
                        audioIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            audioTrackIndex = muxer?.addTrack(audio.outputFormat) ?: -1
                            audioFormatReady = audioTrackIndex >= 0
                            startMuxerWhenReady()
                        }
                        audioIndex >= 0 -> {
                            copyEncodedSample(audio, audioIndex, audioInfo)?.let { bytes ->
                                enqueueOrWriteSample(
                                    PendingMuxSample(true, bytes, audioInfo.presentationTimeUs, audioInfo.flags)
                                )
                            }
                            audioEos = audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            audio.releaseOutputBuffer(audioIndex, false)
                        }
                    }
                } else {
                    audioEos = true
                }
            }
            Log.d(TAG, "A/V drain complete (video frames drained: $drainedFrames)")
        } catch (e: CancellationException) {
            Log.w(TAG, "Drain cancelled (drained $drainedFrames frames)")
        } catch (e: Exception) {
            Log.e(TAG, "drainEncoder error (drained $drainedFrames frames)", e)
            var shouldFinalize = false
            synchronized(lifecycleLock) {
                if (_recordingInfo.value.isRecording && lifecycleState == LifecycleState.RECORDING) {
                    lifecycleState = LifecycleState.STOPPING
                    shouldFinalize = true
                    _recordingInfo.value = _recordingInfo.value.copy(
                        isRecording = false,
                        isStopping = true,
                        isPaused = false,
                        errorMessage = e.message ?: "Encoder output failed"
                    )
                } else {
                    _recordingInfo.value = _recordingInfo.value.copy(
                        errorMessage = e.message ?: "Encoder output failed"
                    )
                }
            }
            if (shouldFinalize) scope.launch { finishRecording(currentOutputPath) }
        }
    }

    private fun copyEncodedSample(
        codec: MediaCodec,
        outputIndex: Int,
        info: MediaCodec.BufferInfo
    ): ByteArray? {
        if (info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return null
        val output = codec.getOutputBuffer(outputIndex) ?: return null
        output.position(info.offset)
        output.limit(info.offset + info.size)
        return ByteArray(info.size).also(output::get)
    }

    private fun enqueueOrWriteSample(sample: PendingMuxSample) {
        val mux = muxer ?: return
        if (muxerStarted) {
            writeMuxSample(mux, sample)
        } else if (preMuxSamples.size < MAX_PRE_MUX_SAMPLES) {
            preMuxSamples.addLast(sample)
        } else {
            preMuxSamples.removeFirst()
            preMuxSamples.addLast(sample)
            noteDroppedRecordFrame()
        }
    }

    private fun releaseResources() {
        audioInputJob?.cancel()
        audioInputJob = null
        audioInputQueue.clear()
        preMuxSamples.clear()
        try { audioEncoder?.stop() } catch (_: Exception) {}
        try { audioEncoder?.release() } catch (_: Exception) {}
        audioEncoder = null
        audioExpected = false
        audioTrackIndex = -1
        videoFormatReady = false
        audioFormatReady = false
        try { spatialUpscaleRenderer?.release() } catch (_: Exception) {}
        spatialUpscaleRenderer = null
        frameWriteInFlight.set(false)
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
        recordingStartNs = 0L
        accumulatedPauseNs = 0L
        pauseStartNs = 0L

        // Ensure ParcelFileDescriptor is closed even if finalizeMediaStore() hasn't been called yet
        // (e.g., abnormal stop, crash recovery)
        if (pendingFd != null) {
            try { pendingFd?.close() } catch (_: Exception) {}
            pendingFd = null
            Log.w(TAG, "ParcelFileDescriptor closed in releaseResources (fallback)")
        }
    }
}
