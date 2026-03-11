package com.camhub.studio.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.theme.CyanAccent
import com.camhub.studio.ui.theme.Primary
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextTertiary
import kotlinx.coroutines.launch

/**
 * Horizontal smooth slider for camera controls.
 *
 * Supports two modes:
 * - Continuous: smooth dragging between min/max (for zoom)
 * - Discrete steps: snaps to values list (for ISO, shutter, focus, WB)
 *
 * @param values List of step labels to display as tick marks
 * @param selectedIndex Currently selected step index
 * @param onIndexChanged Callback when user drags to a new step
 * @param label Label displayed at the left side
 * @param continuousValue For continuous mode: current value (0f..1f normalized)
 * @param onContinuousValueChanged For continuous mode callback
 */
@Composable
fun HorizontalControlSlider(
    values: List<String>,
    selectedIndex: Int,
    onIndexChanged: (Int) -> Unit,
    label: String = "",
    continuousValue: Float? = null,
    onContinuousValueChanged: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (values.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val isContinuous = continuousValue != null && onContinuousValueChanged != null
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    // Animated position for smooth handle movement
    val animatedPosition = remember { Animatable(if (isContinuous) continuousValue!! else selectedIndex.toFloat() / (values.size - 1).coerceAtLeast(1)) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedIndex, continuousValue) {
        val target = if (isContinuous) continuousValue!! else selectedIndex.toFloat() / (values.size - 1).coerceAtLeast(1)
        animatedPosition.animateTo(target, spring(dampingRatio = 0.8f, stiffness = 300f))
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .pointerInput(values.size, isContinuous) {
                detectHorizontalDragGestures(
                    onDragStart = { dragAccumulator = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val trackPadding = size.width * 0.12f
                        val trackWidth = size.width - trackPadding * 2

                        if (isContinuous) {
                            val delta = dragAmount / trackWidth
                            val newVal = (animatedPosition.value + delta).coerceIn(0f, 1f)
                            scope.launch { animatedPosition.snapTo(newVal) }
                            onContinuousValueChanged?.invoke(newVal)
                        } else {
                            dragAccumulator += dragAmount
                            val stepWidth = trackWidth / (values.size - 1).coerceAtLeast(1)
                            val steps = (dragAccumulator / stepWidth).toInt()
                            if (steps != 0) {
                                val newIndex = (selectedIndex + steps).coerceIn(0, values.size - 1)
                                if (newIndex != selectedIndex) {
                                    onIndexChanged(newIndex)
                                }
                                dragAccumulator -= steps * stepWidth
                            }
                        }
                    }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val trackPadding = w * 0.12f
        val trackLeft = trackPadding
        val trackRight = w - trackPadding
        val trackWidth = trackRight - trackLeft
        val trackY = h * 0.55f
        val tickTop = h * 0.15f
        val tickBottom = h * 0.45f
        val labelY = h * 0.78f

        // Track background
        drawLine(
            color = SurfaceDark,
            start = Offset(trackLeft, trackY),
            end = Offset(trackRight, trackY),
            strokeWidth = 3f,
            cap = StrokeCap.Round
        )

        // Active track (filled portion)
        val handleX = trackLeft + trackWidth * animatedPosition.value
        drawLine(
            color = Primary.copy(alpha = 0.6f),
            start = Offset(trackLeft, trackY),
            end = Offset(handleX, trackY),
            strokeWidth = 3f,
            cap = StrokeCap.Round
        )

        // Tick marks & labels
        for (i in values.indices) {
            val stepX = trackLeft + trackWidth * i.toFloat() / (values.size - 1).coerceAtLeast(1)
            val isSelected = i == selectedIndex
            val tickColor = if (isSelected) CyanAccent else TextTertiary.copy(alpha = 0.5f)
            val tickHeight = if (isSelected) tickBottom - tickTop + 4f else tickBottom - tickTop - 4f

            drawLine(
                color = tickColor,
                start = Offset(stepX, tickTop + (tickBottom - tickTop - tickHeight) / 2f),
                end = Offset(stepX, tickTop + (tickBottom - tickTop + tickHeight) / 2f),
                strokeWidth = if (isSelected) 2f else 1f,
                cap = StrokeCap.Round
            )

            // Label
            val labelStyle = TextStyle(
                color = if (isSelected) TextPrimary else TextTertiary.copy(alpha = 0.6f),
                fontSize = if (isSelected) 9.sp else 8.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center
            )
            val layout = textMeasurer.measure(values[i], labelStyle, maxLines = 1)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(stepX - layout.size.width / 2f, labelY - layout.size.height / 2f)
            )
        }

        // Handle (circle)
        drawCircle(
            color = CyanAccent,
            radius = 8f,
            center = Offset(handleX, trackY)
        )
        // Handle glow
        drawCircle(
            color = CyanAccent.copy(alpha = 0.25f),
            radius = 14f,
            center = Offset(handleX, trackY)
        )

        // Current value text above handle
        val currentLabel = if (isContinuous) {
            // For continuous mode, show interpolated value
            values.getOrElse(selectedIndex) { "" }
        } else {
            values.getOrElse(selectedIndex) { "" }
        }
        val handleLabelStyle = TextStyle(
            color = CyanAccent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        val handleLayout = textMeasurer.measure(currentLabel, handleLabelStyle, maxLines = 1)
        drawText(
            textLayoutResult = handleLayout,
            topLeft = Offset(
                (handleX - handleLayout.size.width / 2f).coerceIn(0f, w - handleLayout.size.width),
                tickTop - handleLayout.size.height - 2f
            )
        )

        // Label on left
        if (label.isNotEmpty()) {
            val leftLabelStyle = TextStyle(
                color = TextTertiary,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                letterSpacing = 1.sp
            )
            val leftLayout = textMeasurer.measure(label, leftLabelStyle, maxLines = 1)
            drawText(
                textLayoutResult = leftLayout,
                topLeft = Offset(4f, trackY - leftLayout.size.height / 2f)
            )
        }
    }
}
