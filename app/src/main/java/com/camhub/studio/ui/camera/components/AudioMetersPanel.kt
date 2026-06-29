package com.camhub.studio.ui.camera.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.theme.LedGreen
import com.camhub.studio.ui.theme.LedOff
import com.camhub.studio.ui.theme.LedRed
import com.camhub.studio.ui.theme.LedYellow
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.TextSecondary

private const val TOTAL_SEGMENTS = 24
private const val DB_MIN = -45f
private const val DB_MAX = 3f
private val DB_RANGE = DB_MAX - DB_MIN

private fun levelToDb(level: Float): Float {
    if (level <= 0f) return DB_MIN
    return DB_MIN + (DB_RANGE * level.coerceIn(0f, 1f))
}

private fun dbToSegment(db: Float): Int {
    val normalized = ((db - DB_MIN) / DB_RANGE).coerceIn(0f, 1f)
    return (normalized * TOTAL_SEGMENTS).toInt()
}

private fun segmentColor(segmentIndex: Int): androidx.compose.ui.graphics.Color {
    val db = DB_MIN + (segmentIndex.toFloat() / TOTAL_SEGMENTS) * DB_RANGE
    return when {
        db < -10f -> LedGreen
        db < -3f -> LedYellow
        else -> LedRed
    }
}

@Composable
fun AudioMetersPanel(
    levels: List<Float>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    statusText: String = "Idle",
    clientCount: Int = 0,
    restartCount: Int = 0
) {
    val ch1Level = levels.getOrElse(0) { 0f }
    val ch2Level = levels.getOrElse(1) { 0f }
    val ch1Db = levelToDb(ch1Level)
    val ch2Db = levelToDb(ch2Level)
    val ch1Lit = dbToSegment(ch1Db)
    val ch2Lit = dbToSegment(ch2Db)
    val statusColor = when (statusText) {
        "Capturing" -> LedGreen
        "Listening" -> LedYellow
        "Restarting", "Permission Needed", "Error" -> LedRed
        else -> TextSecondary
    }

    if (compact) {
        // Landscape compact: thin bars, no labels
        Column(
            modifier = modifier.padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = "MIC $statusText",
                color = statusColor,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            MeterBar(litSegments = ch1Lit, barHeight = 4.dp)
            MeterBar(litSegments = ch2Lit, barHeight = 4.dp)
        }
    } else {
        // Portrait standard: L/R labels, slightly thicker bars
        Column(
            modifier = modifier
                .background(SurfaceDark.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = "MIC $statusText · C$clientCount · R$restartCount",
                color = statusColor,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            MeterRow(label = "L", litSegments = ch1Lit, barHeight = 6.dp)
            MeterRow(label = "R", litSegments = ch2Lit, barHeight = 6.dp)
        }
    }
}

@Composable
private fun MeterRow(
    label: String,
    litSegments: Int,
    barHeight: Dp = 6.dp
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        MeterBar(litSegments = litSegments, barHeight = barHeight, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MeterBar(
    litSegments: Int,
    barHeight: Dp,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
    ) {
        val w = size.width
        val h = size.height
        val gap = 1f
        val segWidth = (w - gap * (TOTAL_SEGMENTS - 1)) / TOTAL_SEGMENTS

        for (i in 0 until TOTAL_SEGMENTS) {
            val isLit = i < litSegments
            val color = if (isLit) segmentColor(i) else LedOff
            val x = i * (segWidth + gap)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, 0f),
                size = Size(segWidth, h),
                cornerRadius = CornerRadius(1f)
            )
        }
    }
}
