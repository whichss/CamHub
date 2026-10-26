package com.camhub.studio.data.gl

import android.graphics.Bitmap
import com.camhub.studio.data.ptz.HubPtzTransform

enum class PgmTransitionMode(val shaderValue: Int) {
    NONE(0),
    MIX(1),
    DIP(2),
    WIPE(3)
}

data class PgmTransitionFrame(
    val bitmap: Bitmap,
    val cameraName: String,
    val frameSequence: Long,
    val ptzTransform: HubPtzTransform,
    val mode: PgmTransitionMode,
    val progress: Float
)

/** GPU-backed transition source; its texture lease transfers with the output frame. */
data class PgmGpuTransitionFrame(
    val frame: SharedGpuVideoFrame,
    val cameraName: String,
    val frameSequence: Long,
    val ptzTransform: HubPtzTransform,
    val mode: PgmTransitionMode,
    val progress: Float
)
