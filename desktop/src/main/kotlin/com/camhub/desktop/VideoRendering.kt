package com.camhub.desktop

import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage

data class DesktopVideoFrame(
    val image: BufferedImage,
    val rotationDegrees: Int = 0
)

object DesktopVideoPainter {
    fun draw(
        graphics: Graphics2D,
        frame: DesktopVideoFrame,
        targetWidth: Int,
        targetHeight: Int,
        highQualitySpatialScale: Boolean
    ) {
        if (targetWidth <= 0 || targetHeight <= 0) return
        val rotation = ((frame.rotationDegrees % 360) + 360) % 360
        val swapsDimensions = rotation == 90 || rotation == 270
        val contentWidth = if (swapsDimensions) frame.image.height else frame.image.width
        val contentHeight = if (swapsDimensions) frame.image.width else frame.image.height
        if (contentWidth <= 0 || contentHeight <= 0) return

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
            target.scale(scale, scale)
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
