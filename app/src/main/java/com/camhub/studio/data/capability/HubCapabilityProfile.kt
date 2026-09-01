package com.camhub.studio.data.capability

enum class HubPerformanceTier {
    HIGH,
    BALANCED,
    CONSTRAINED,
    UNSUPPORTED
}

data class HubCapabilityInputs(
    val cpuCoreCount: Int,
    val totalMemoryMb: Int,
    val glEsVersion: Int,
    val avcDecoderMaxInstances: Int,
    val supportsAvcDecode720p30: Boolean,
    val supportsAvcDecode1080p30: Boolean,
    val supportsAvcEncode720p30: Boolean,
    val supportsLowLatencyDecode: Boolean
)

data class HubRuntimeProfile(
    val tier: HubPerformanceTier,
    val recommendedCameraCount: Int,
    val multiviewHeight: Int,
    val multiviewFps: Int,
    val pgmHeight: Int,
    val pgmFps: Int,
    val enableSpatialUpscaling: Boolean
) {
    val shortLabel: String
        get() = when (tier) {
            HubPerformanceTier.UNSUPPORTED -> "UNSUPPORTED"
            else -> "${tier.name} · ${recommendedCameraCount}CAM · PGM${pgmHeight}"
        }
}

data class HubCapabilityReport(
    val inputs: HubCapabilityInputs,
    val profile: HubRuntimeProfile,
    val decoderName: String?,
    val encoderName: String?,
    val warnings: List<String>
)

object HubCapabilityClassifier {
    private const val GLES_3_0 = 0x00030000
    private const val GLES_3_1 = 0x00030001

    fun classify(inputs: HubCapabilityInputs): HubRuntimeProfile {
        if (!inputs.supportsAvcDecode720p30 || inputs.glEsVersion < GLES_3_0) {
            return HubRuntimeProfile(
                tier = HubPerformanceTier.UNSUPPORTED,
                recommendedCameraCount = 0,
                multiviewHeight = 0,
                multiviewFps = 0,
                pgmHeight = 0,
                pgmFps = 0,
                enableSpatialUpscaling = false
            )
        }

        val high = inputs.avcDecoderMaxInstances >= 4 &&
            inputs.supportsAvcDecode1080p30 &&
            inputs.cpuCoreCount >= 6 &&
            inputs.totalMemoryMb >= 5_120 &&
            inputs.glEsVersion >= GLES_3_1
        if (high) {
            return HubRuntimeProfile(
                tier = HubPerformanceTier.HIGH,
                recommendedCameraCount = 4,
                multiviewHeight = 720,
                multiviewFps = 30,
                pgmHeight = 1080,
                pgmFps = 30,
                enableSpatialUpscaling = true
            )
        }

        val balanced = inputs.avcDecoderMaxInstances >= 4 &&
            inputs.cpuCoreCount >= 4 &&
            inputs.totalMemoryMb >= 3_072
        if (balanced) {
            return HubRuntimeProfile(
                tier = HubPerformanceTier.BALANCED,
                recommendedCameraCount = 4,
                multiviewHeight = 720,
                multiviewFps = 24,
                pgmHeight = if (inputs.supportsAvcDecode1080p30) 1080 else 720,
                pgmFps = 30,
                enableSpatialUpscaling = inputs.glEsVersion >= GLES_3_1
            )
        }

        return HubRuntimeProfile(
            tier = HubPerformanceTier.CONSTRAINED,
            recommendedCameraCount = inputs.avcDecoderMaxInstances.coerceIn(1, 2),
            multiviewHeight = 540,
            multiviewFps = 15,
            pgmHeight = 720,
            pgmFps = 30,
            enableSpatialUpscaling = false
        )
    }
}
