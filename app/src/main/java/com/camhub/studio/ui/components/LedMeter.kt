package com.camhub.studio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.camhub.studio.ui.theme.LedGreen
import com.camhub.studio.ui.theme.LedOff
import com.camhub.studio.ui.theme.LedRed
import com.camhub.studio.ui.theme.LedYellow

@Composable
fun LedMeter(
    level: Float,
    segments: Int = 12,
    isHorizontal: Boolean = false,
    modifier: Modifier = Modifier
) {
    val clampedLevel = level.coerceIn(0f, 1f)
    val litSegments = (clampedLevel * segments).toInt()

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val gap = if (isHorizontal) w * 0.01f else h * 0.01f

        for (i in 0 until segments) {
            val fraction = i.toFloat() / segments
            val isLit = i < litSegments

            val segmentColor = if (isLit) {
                when {
                    fraction < 0.67f -> LedGreen
                    fraction < 0.83f -> LedYellow
                    else -> LedRed
                }
            } else {
                LedOff
            }

            if (isHorizontal) {
                val segWidth = (w - gap * (segments - 1)) / segments
                val x = i * (segWidth + gap)
                drawRoundRect(
                    color = segmentColor,
                    topLeft = Offset(x, 0f),
                    size = Size(segWidth, h),
                    cornerRadius = CornerRadius(2f)
                )
            } else {
                val segHeight = (h - gap * (segments - 1)) / segments
                // Draw bottom-to-top: segment 0 at bottom
                val y = h - (i + 1) * segHeight - i * gap
                drawRoundRect(
                    color = segmentColor,
                    topLeft = Offset(0f, y),
                    size = Size(w, segHeight),
                    cornerRadius = CornerRadius(2f)
                )
            }
        }
    }
}
