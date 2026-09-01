package com.camhub.studio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.theme.AmberYellow
import com.camhub.studio.ui.theme.CyanAccent
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextTertiary

/**
 * Spring-loaded zoom rocker. The handle always returns to center; its distance
 * from center controls zoom speed rather than an absolute zoom value.
 */
@Composable
fun ZoomVelocityControl(
    zoomRatio: Float,
    onVelocityChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    var leverPosition by remember { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) {
        onDispose { onVelocityChanged(0f) }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .pointerInput(onVelocityChanged) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val trackPadding = size.width * 0.12f
                    val trackWidth = (size.width - trackPadding * 2f).coerceAtLeast(1f)

                    fun positionFor(x: Float): Float {
                        val centerX = size.width / 2f
                        return ((x - centerX) / (trackWidth / 2f)).coerceIn(-1f, 1f)
                    }

                    try {
                        val initial = positionFor(down.position.x)
                        leverPosition = initial
                        onVelocityChanged(initial)

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val position = positionFor(change.position.x)
                            leverPosition = position
                            onVelocityChanged(position)
                        }
                    } finally {
                        onVelocityChanged(0f)
                        leverPosition = 0f
                    }
                }
            }
    ) {
        val trackLeft = size.width * 0.12f
        val trackRight = size.width - trackLeft
        val centerX = size.width / 2f
        val trackY = size.height * 0.58f
        val handleX = centerX + (trackRight - trackLeft) * 0.5f * leverPosition
        val directionColor = if (leverPosition < 0f) AmberYellow else CyanAccent

        drawLine(
            color = SurfaceDark,
            start = Offset(trackLeft, trackY),
            end = Offset(trackRight, trackY),
            strokeWidth = 8f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = directionColor.copy(alpha = 0.8f),
            start = Offset(centerX, trackY),
            end = Offset(handleX, trackY),
            strokeWidth = 8f,
            cap = StrokeCap.Round
        )

        // Center/dead-zone markers make the neutral position unambiguous.
        val deadZoneHalfWidth = (trackRight - trackLeft) * 0.06f
        drawLine(
            color = TextTertiary.copy(alpha = 0.55f),
            start = Offset(centerX - deadZoneHalfWidth, trackY - 7f),
            end = Offset(centerX - deadZoneHalfWidth, trackY + 7f),
            strokeWidth = 2f
        )
        drawLine(
            color = TextTertiary.copy(alpha = 0.55f),
            start = Offset(centerX + deadZoneHalfWidth, trackY - 7f),
            end = Offset(centerX + deadZoneHalfWidth, trackY + 7f),
            strokeWidth = 2f
        )
        drawCircle(
            color = directionColor.copy(alpha = 0.22f),
            radius = 17f,
            center = Offset(handleX, trackY)
        )
        drawCircle(
            color = if (leverPosition == 0f) TextPrimary else directionColor,
            radius = 9f,
            center = Offset(handleX, trackY)
        )

        val ratioLayout = textMeasurer.measure(
            text = String.format("%.2fx", zoomRatio),
            style = TextStyle(
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        )
        drawText(
            textLayoutResult = ratioLayout,
            topLeft = Offset(centerX - ratioLayout.size.width / 2f, 2f)
        )

        val edgeStyle = TextStyle(
            color = TextTertiary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
        val wide = textMeasurer.measure("W  축소", edgeStyle)
        val tele = textMeasurer.measure("확대  T", edgeStyle)
        drawText(wide, topLeft = Offset(trackLeft, size.height - wide.size.height))
        drawText(
            tele,
            topLeft = Offset(trackRight - tele.size.width, size.height - tele.size.height)
        )
    }
}
