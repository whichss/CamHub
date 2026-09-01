package com.camhub.studio.data.capability

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceCapabilityProbe @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun probe(): HubCapabilityReport {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val decoder = findBestAvcCodec(isEncoder = false)
        val encoder = findBestAvcCodec(isEncoder = true)
        val decoderCapabilities = decoder?.capabilities

        val inputs = HubCapabilityInputs(
            cpuCoreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            totalMemoryMb = (memoryInfo.totalMem / (1024L * 1024L))
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
            glEsVersion = activityManager.deviceConfigurationInfo.reqGlEsVersion,
            avcDecoderMaxInstances = decoderCapabilities?.maxSupportedInstances ?: 0,
            supportsAvcDecode720p30 = decoderCapabilities.supports(1280, 720, 30.0),
            supportsAvcDecode1080p30 = decoderCapabilities.supports(1920, 1080, 30.0),
            supportsAvcEncode720p30 = encoder?.capabilities.supports(1280, 720, 30.0),
            supportsLowLatencyDecode = decoderCapabilities.supportsLowLatency()
        )
        val profile = HubCapabilityClassifier.classify(inputs)
        return HubCapabilityReport(
            inputs = inputs,
            profile = profile,
            decoderName = decoder?.name,
            encoderName = encoder?.name,
            warnings = buildWarnings(inputs)
        )
    }

    private data class CodecCandidate(
        val name: String,
        val capabilities: MediaCodecInfo.CodecCapabilities,
        val hardwareAccelerated: Boolean
    )

    private fun findBestAvcCodec(isEncoder: Boolean): CodecCandidate? {
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filter { it.isEncoder == isEncoder }
                .filter { info ->
                    info.supportedTypes.any {
                        it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
                    }
                }
                .mapNotNull { info ->
                    runCatching {
                        CodecCandidate(
                            name = info.name,
                            capabilities = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC),
                            hardwareAccelerated = isHardwareAccelerated(info)
                        )
                    }.getOrNull()
                }
                .maxWithOrNull(
                    compareBy<CodecCandidate> { it.hardwareAccelerated }
                        .thenBy { it.capabilities.maxSupportedInstances }
                        .thenBy { it.capabilities.supports(1920, 1080, 30.0) }
                )
        } catch (_: Exception) {
            null
        }
    }

    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            val name = info.name.lowercase()
            !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
        }
    }

    private fun MediaCodecInfo.CodecCapabilities?.supports(
        width: Int,
        height: Int,
        fps: Double
    ): Boolean {
        val video = this?.videoCapabilities ?: return false
        return runCatching { video.areSizeAndRateSupported(width, height, fps) }
            .getOrDefault(false)
    }

    private fun MediaCodecInfo.CodecCapabilities?.supportsLowLatency(): Boolean {
        if (this == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        }.getOrDefault(false)
    }

    private fun buildWarnings(inputs: HubCapabilityInputs): List<String> = buildList {
        if (inputs.avcDecoderMaxInstances < 4) add("AVC decoder reports fewer than four instances")
        if (!inputs.supportsAvcDecode1080p30) add("1080p30 AVC decode is unavailable")
        if (!inputs.supportsAvcEncode720p30) add("720p30 AVC encode is unavailable")
        if (!inputs.supportsLowLatencyDecode) add("Codec does not advertise low-latency decode")
        if (inputs.totalMemoryMb < 3_072) add("Less than 3 GB of RAM is available")
    }
}
