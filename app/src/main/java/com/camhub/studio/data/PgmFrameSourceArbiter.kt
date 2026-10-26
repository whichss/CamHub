package com.camhub.studio.data

/** Prefers direct decoder textures and permits Bitmap fallback only after GPU output goes quiet. */
internal class PgmFrameSourceArbiter(
    private val gpuQuietWindowMs: Long = DEFAULT_GPU_QUIET_WINDOW_MS
) {
    private var gpuCamera: String? = null
    private var lastGpuAcceptedAtMs = Long.MIN_VALUE

    init {
        require(gpuQuietWindowMs >= 0L)
    }

    @Synchronized
    fun noteGpuAccepted(camera: String, nowMs: Long) {
        gpuCamera = camera
        lastGpuAcceptedAtMs = nowMs
    }

    @Synchronized
    fun allowBitmapFallback(camera: String, nowMs: Long): Boolean {
        if (gpuCamera != camera || lastGpuAcceptedAtMs == Long.MIN_VALUE) return true
        if (nowMs < lastGpuAcceptedAtMs) return true
        return nowMs - lastGpuAcceptedAtMs >= gpuQuietWindowMs
    }

    @Synchronized
    fun reset() {
        gpuCamera = null
        lastGpuAcceptedAtMs = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_GPU_QUIET_WINDOW_MS = 500L
    }
}
