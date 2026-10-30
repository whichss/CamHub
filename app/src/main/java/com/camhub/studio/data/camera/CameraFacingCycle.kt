package com.camhub.studio.data.camera

/** CameraX lens-facing values are kept at the Android adapter boundary. */
enum class CameraInputFacing { BACK, FRONT, EXTERNAL }

object CameraFacingCycle {
    fun next(current: CameraInputFacing, externalAvailable: Boolean): CameraInputFacing {
        val available = if (externalAvailable) {
            listOf(CameraInputFacing.BACK, CameraInputFacing.FRONT, CameraInputFacing.EXTERNAL)
        } else {
            listOf(CameraInputFacing.BACK, CameraInputFacing.FRONT)
        }
        val currentIndex = available.indexOf(current)
        return available[(currentIndex + 1).mod(available.size)]
    }
}
