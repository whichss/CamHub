package com.camhub.studio.ui.camera.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.camhub.studio.ui.theme.ElectricRed
import com.camhub.studio.ui.theme.TextMuted

@Composable
fun ViewfinderOverlay(
    isPgm: Boolean,
    focusPointX: Float? = null,
    focusPointY: Float? = null,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "vf_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "vf_pulse_alpha"
    )

    val gridColor = TextMuted.copy(alpha = 0.30f)
    val crosshairColor = TextMuted.copy(alpha = 0.30f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Rule-of-thirds: 2 vertical lines + 2 horizontal lines
        val thirdW = w / 3f
        val thirdH = h / 3f

        // Vertical lines
        drawLine(gridColor, Offset(thirdW, 0f), Offset(thirdW, h), strokeWidth = 0.8f)
        drawLine(gridColor, Offset(thirdW * 2, 0f), Offset(thirdW * 2, h), strokeWidth = 0.8f)

        // Horizontal lines
        drawLine(gridColor, Offset(0f, thirdH), Offset(w, thirdH), strokeWidth = 0.8f)
        drawLine(gridColor, Offset(0f, thirdH * 2), Offset(w, thirdH * 2), strokeWidth = 0.8f)

        // Center crosshair
        val centerX = w / 2f
        val centerY = h / 2f
        val crossSize = 12.dp.toPx()
        drawLine(crosshairColor, Offset(centerX - crossSize, centerY), Offset(centerX + crossSize, centerY), strokeWidth = 1f)
        drawLine(crosshairColor, Offset(centerX, centerY - crossSize), Offset(centerX, centerY + crossSize), strokeWidth = 1f)

        // Tap-to-focus indicator
        if (focusPointX != null && focusPointY != null) {
            val focusSize = 40.dp.toPx()
            drawRect(
                color = Color.White.copy(alpha = 0.7f),
                topLeft = Offset(focusPointX - focusSize / 2f, focusPointY - focusSize / 2f),
                size = Size(focusSize, focusSize),
                style = Stroke(width = 1.5f)
            )
        }

        // PGM pulsing red border
        if (isPgm) {
            val borderWidth = 3.dp.toPx()
            drawRect(
                color = ElectricRed.copy(alpha = pulseAlpha),
                topLeft = Offset(borderWidth / 2f, borderWidth / 2f),
                size = Size(w - borderWidth, h - borderWidth),
                style = Stroke(width = borderWidth)
            )
        }
    }
}
