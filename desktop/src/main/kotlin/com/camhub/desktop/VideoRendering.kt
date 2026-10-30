package com.camhub.desktop

import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.WeakHashMap

/** Serializes mutation of JavaCV-owned decoded images against Swing painting. */
internal object DesktopVideoImageGuard {
    private val locks = WeakHashMap<BufferedImage, Any>()

    private fun lockFor(image: BufferedImage): Any = synchronized(locks) {
        locks.getOrPut(image) { Any() }
    }

    /**
     * Converts directly into JavaCV's image and publishes it without a second
     * full-frame copy. On subsequent frames, conversion and painting of the reused
     * image are serialized. Locks are per image so independent cameras stay parallel.
     */
    fun convertAndPublish(
        previousImage: BufferedImage?,
        convert: () -> BufferedImage?,
        onPublished: (BufferedImage) -> Unit
    ): BufferedImage? {
        val previousLock = previousImage?.let(::lockFor)
        if (previousLock == null) {
            val image = convert() ?: return null
            synchronized(lockFor(image)) { onPublished(image) }
            return image
        }

        return synchronized(previousLock) {
            val image = convert() ?: return@synchronized null
            synchronized(lockFor(image)) { onPublished(image) }
            image
        }
    }

    fun <T> withLock(image: BufferedImage, block: () -> T): T =
        synchronized(lockFor(image), block)
}

data class DesktopVideoFrame(
    val image: BufferedImage,
    val rotationDegrees: Int = 0,
    val framing: DesktopFraming = DesktopFraming.IDENTITY
)

data class DesktopRemoteFraming(val zoom: Double = 1.0, val centerX: Double = 0.5, val centerY: Double = 0.5) {
    init {
        require(zoom.isFinite() && zoom in 1.0..32.0)
        require(centerX.isFinite() && centerY.isFinite())
    }
}

object DesktopPtzCoordinates {
    fun displayToCamera(x: Double, y: Double, rotationDegrees: Int): Pair<Double, Double> =
        when (normalizedRotation(rotationDegrees)) {
            90 -> y to (1.0 - x)
            180 -> (1.0 - x) to (1.0 - y)
            270 -> (1.0 - y) to x
            else -> x to y
        }

    fun cameraToDisplay(x: Double, y: Double, rotationDegrees: Int): Pair<Double, Double> =
        when (normalizedRotation(rotationDegrees)) {
            90 -> (1.0 - y) to x
            180 -> (1.0 - x) to (1.0 - y)
            270 -> y to (1.0 - x)
            else -> x to y
        }

    private fun normalizedRotation(rotationDegrees: Int) = ((rotationDegrees % 360) + 360) % 360
}

/** Normalized local viewport crop. Camera-side PTZ can later replace this hub-side crop. */
data class DesktopFraming(
    val zoom: Double = 1.0,
    val centerX: Double = 0.5,
    val centerY: Double = 0.5
) {
    init {
        require(zoom.isFinite() && zoom in MIN_ZOOM..MAX_ZOOM)
        require(centerX.isFinite() && centerY.isFinite())
        require(centerX in 0.5 / zoom..(1.0 - 0.5 / zoom))
        require(centerY in 0.5 / zoom..(1.0 - 0.5 / zoom))
    }

    fun panBy(deltaX: Double, deltaY: Double): DesktopFraming {
        if (!deltaX.isFinite() || !deltaY.isFinite()) return this
        return at(zoom, centerX - deltaX / zoom, centerY - deltaY / zoom)
    }

    /** Keeps the point under the pointer fixed while changing the local crop scale. */
    fun zoomBy(factor: Double, anchorX: Double = 0.5, anchorY: Double = 0.5): DesktopFraming {
        if (!factor.isFinite() || factor <= 0 || !anchorX.isFinite() || !anchorY.isFinite()) return this
        val nextZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val sourceX = centerX + (anchorX - 0.5) / zoom
        val sourceY = centerY + (anchorY - 0.5) / zoom
        return at(
            nextZoom,
            sourceX - (anchorX - 0.5) / nextZoom,
            sourceY - (anchorY - 0.5) / nextZoom
        )
    }

    fun reset(): DesktopFraming = IDENTITY

    /** Remaining hub crop after the camera has already applied part of the framing. */
    fun residualAfter(remoteApplied: DesktopRemoteFraming): DesktopFraming {
        val residualZoom = (zoom / remoteApplied.zoom).coerceAtLeast(1.0)
        return at(
            residualZoom,
            0.5 + (centerX - remoteApplied.centerX) * remoteApplied.zoom,
            0.5 + (centerY - remoteApplied.centerY) * remoteApplied.zoom
        )
    }

    companion object {
        const val MIN_ZOOM = 1.0
        const val MAX_ZOOM = 8.0
        val IDENTITY = DesktopFraming()

        private fun at(zoom: Double, centerX: Double, centerY: Double): DesktopFraming {
            val halfVisible = 0.5 / zoom
            return DesktopFraming(
                zoom = zoom,
                centerX = centerX.coerceIn(halfVisible, 1.0 - halfVisible),
                centerY = centerY.coerceIn(halfVisible, 1.0 - halfVisible)
            )
        }
    }
}

object DesktopVideoPainter {
    fun draw(
        graphics: Graphics2D,
        frame: DesktopVideoFrame,
        targetWidth: Int,
        targetHeight: Int,
        highQualitySpatialScale: Boolean
    ) = DesktopVideoImageGuard.withLock(frame.image) {
        if (targetWidth <= 0 || targetHeight <= 0) return@withLock
        val rotation = ((frame.rotationDegrees % 360) + 360) % 360
        val swapsDimensions = rotation == 90 || rotation == 270
        val contentWidth = if (swapsDimensions) frame.image.height else frame.image.width
        val contentHeight = if (swapsDimensions) frame.image.width else frame.image.height
        if (contentWidth <= 0 || contentHeight <= 0) return@withLock

        val scale = minOf(
            targetWidth.toDouble() / contentWidth,
            targetHeight.toDouble() / contentHeight
        )
        val target = graphics.create() as Graphics2D
        try {
            target.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                if (highQualitySpatialScale) {
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC
                } else {
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR
                }
            )
            target.translate(targetWidth / 2.0, targetHeight / 2.0)
            target.scale(scale * frame.framing.zoom, scale * frame.framing.zoom)
            target.translate(
                (0.5 - frame.framing.centerX) * contentWidth,
                (0.5 - frame.framing.centerY) * contentHeight
            )
            target.rotate(Math.toRadians(rotation.toDouble()))
            target.drawImage(
                frame.image,
                -frame.image.width / 2,
                -frame.image.height / 2,
                null
            )
        } finally {
            target.dispose()
        }
    }
}
