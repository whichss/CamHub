package com.camhub.studio.data.gl

/** Keeps CPU readback enabled only for the active program/preview bitmap consumers. */
object BitmapOutputTargetPolicy {
    fun select(
        activeCameraNames: Set<String>,
        sharedGpuTargets: Set<String>,
        pgmCameraName: String?,
        pvwCameraName: String?,
        externalBitmapTargets: Set<String> = emptySet()
    ): Set<String> {
        val programCameras = listOfNotNull(pgmCameraName, pvwCameraName).toSet()
            .intersect(activeCameraNames)
        return buildSet {
            addAll(programCameras - sharedGpuTargets)
            addAll(externalBitmapTargets.intersect(activeCameraNames))
        }
    }
}
