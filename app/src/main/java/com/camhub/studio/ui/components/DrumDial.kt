package com.camhub.studio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.Primary
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun DrumDial(
    values: List<String>,
    selectedIndex: Int,
    onIndexChanged: (Int) -> Unit,
    visibleItems: Int = 5,
    modifier: Modifier = Modifier
) {
    if (values.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    var currentIndex by remember(selectedIndex) { mutableIntStateOf(selectedIndex) }
    val half = visibleItems / 2

    Canvas(
        modifier = modifier
            .pointerInput(values.size) {
                detectVerticalDragGestures(
                    onDragStart = { dragAccumulator = 0f },
                    onDragEnd = {
                        dragAccumulator = 0f
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragAccumulator += dragAmount
                        val itemHeight = size.height.toFloat() / visibleItems
                        val steps = (dragAccumulator / itemHeight).roundToInt()
                        if (steps != 0) {
                            val newIndex = (currentIndex - steps).coerceIn(0, values.size - 1)
                            if (newIndex != currentIndex) {
                                currentIndex = newIndex
                                onIndexChanged(newIndex)
                            }
                            dragAccumulator -= steps * itemHeight
                        }
                    }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val itemHeight = h / visibleItems
        val centerY = h / 2f
        val maxFontSize = 16f
        val minFontSize = 10f

        // Draw visible items
        for (offset in -half..half) {
            val idx = currentIndex + offset
            if (idx < 0 || idx >= values.size) continue

            val distFromCenter = abs(offset).toFloat()
            val normalizedDist = distFromCenter / half.toFloat()
            val alpha = (1f - normalizedDist * 0.7f).coerceIn(0.15f, 1f)
            val fontSize = maxFontSize - (maxFontSize - minFontSize) * normalizedDist
            val yCenter = centerY + offset * itemHeight

            val isSelected = offset == 0
            val style = TextStyle(
                color = if (isSelected) Primary.copy(alpha = alpha) else Color.White.copy(alpha = alpha),
                fontSize = fontSize.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center
            )
            val layoutResult = textMeasurer.measure(
                text = values[idx],
                style = style,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1
            )
            drawText(
                textLayoutResult = layoutResult,
                topLeft = Offset(
                    x = (w - layoutResult.size.width) / 2f,
                    y = yCenter - layoutResult.size.height / 2f
                )
            )
        }

        // Selection indicator lines
        val lineColor = Primary.copy(alpha = 0.6f)
        val lineY1 = centerY - itemHeight / 2f
        val lineY2 = centerY + itemHeight / 2f
        val lineInset = w * 0.1f
        drawLine(lineColor, Offset(lineInset, lineY1), Offset(w - lineInset, lineY1), strokeWidth = 1.5f)
        drawLine(lineColor, Offset(lineInset, lineY2), Offset(w - lineInset, lineY2), strokeWidth = 1.5f)

        // Top gradient fade
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(BackgroundDarker, BackgroundDarker.copy(alpha = 0f)),
                startY = 0f,
                endY = itemHeight * 1.2f
            )
        )

        // Bottom gradient fade
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(BackgroundDarker.copy(alpha = 0f), BackgroundDarker),
                startY = h - itemHeight * 1.2f,
                endY = h
            )
        )
    }
}
