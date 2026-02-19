package com.camhub.studio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.Primary
import com.camhub.studio.ui.theme.SurfaceLight

@Composable
fun FaderSlider(
    value: Float,
    onValueChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentValue by remember(value) { mutableFloatStateOf(value.coerceIn(0f, 1f)) }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        val trackTop = size.height * 0.05f
                        val trackBottom = size.height * 0.95f
                        val trackHeight = trackBottom - trackTop
                        val newValue = 1f - ((change.position.y - trackTop) / trackHeight).coerceIn(0f, 1f)
                        currentValue = newValue
                        onValueChanged(newValue)
                    }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val trackWidth = w * 0.35f
        val trackLeft = (w - trackWidth) / 2f
        val trackTop = h * 0.05f
        val trackBottom = h * 0.95f
        val trackHeight = trackBottom - trackTop
        val cornerRadius = CornerRadius(trackWidth / 2f)

        // Track background
        drawRoundRect(
            color = BackgroundDarker,
            topLeft = Offset(trackLeft, trackTop),
            size = Size(trackWidth, trackHeight),
            cornerRadius = cornerRadius
        )

        // Filled portion from bottom
        val fillHeight = trackHeight * currentValue
        val fillTop = trackBottom - fillHeight
        drawRoundRect(
            color = Primary.copy(alpha = 0.6f),
            topLeft = Offset(trackLeft, fillTop),
            size = Size(trackWidth, fillHeight),
            cornerRadius = cornerRadius
        )

        // Thumb
        val thumbWidth = w * 0.7f
        val thumbHeight = h * 0.08f
        val thumbY = fillTop - thumbHeight / 2f
        val thumbLeft = (w - thumbWidth) / 2f

        drawRoundRect(
            color = SurfaceLight,
            topLeft = Offset(thumbLeft, thumbY),
            size = Size(thumbWidth, thumbHeight),
            cornerRadius = CornerRadius(4f)
        )

        // Groove lines on thumb
        val grooveColor = Primary.copy(alpha = 0.4f)
        val grooveSpacing = thumbHeight / 4f
        for (i in 1..3) {
            val grooveY = thumbY + grooveSpacing * i
            drawLine(
                color = grooveColor,
                start = Offset(thumbLeft + thumbWidth * 0.2f, grooveY),
                end = Offset(thumbLeft + thumbWidth * 0.8f, grooveY),
                strokeWidth = 1.5f
            )
        }
    }
}
