package com.camhub.studio.ui.audio.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.GlassBorder
import com.camhub.studio.ui.theme.JetBrainsMonoFamily
import com.camhub.studio.ui.theme.LedGreen
import com.camhub.studio.ui.theme.LedOff
import com.camhub.studio.ui.theme.LedRed
import com.camhub.studio.ui.theme.LedYellow
import com.camhub.studio.ui.theme.NeonGreen
import com.camhub.studio.ui.theme.Primary
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.SurfaceLight
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextTertiary

/**
 * Number of LED segments in the meter.
 */
private const val LED_SEGMENT_COUNT = 20

/**
 * A vertical LED meter showing audio levels with green/yellow/red segments.
 */
@Composable
fun LedMeter(
    level: Float,
    modifier: Modifier = Modifier
) {
    val activeSegments = (level * LED_SEGMENT_COUNT).toInt().coerceIn(0, LED_SEGMENT_COUNT)

    Column(
        modifier = modifier
            .width(14.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        for (i in LED_SEGMENT_COUNT - 1 downTo 0) {
            val isActive = i < activeSegments
            val segmentColor = when {
                !isActive -> LedOff
                i >= (LED_SEGMENT_COUNT * 0.9f).toInt() -> LedRed
                i >= (LED_SEGMENT_COUNT * 0.75f).toInt() -> LedYellow
                else -> LedGreen
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(1.dp))
                    .background(segmentColor)
            )
        }
    }
}

/**
 * A vertical fader slider styled for audio mixing.
 */
@Composable
fun FaderSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    thumbColor: Color = Primary,
    activeTrackColor: Color = Primary,
    inactiveTrackColor: Color = SurfaceLight
) {
    Box(
        modifier = modifier
            .height(120.dp)
            .width(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            modifier = Modifier
                .width(120.dp)
                .rotate(-90f),
            colors = SliderDefaults.colors(
                thumbColor = thumbColor,
                activeTrackColor = activeTrackColor,
                inactiveTrackColor = inactiveTrackColor
            )
        )
    }
}

/**
 * Converts a fader value (0-1) to a decibel display value.
 */
private fun faderToDb(value: Float): String {
    if (value <= 0.001f) return "-∞"
    val db = (value * 66f - 60f).coerceIn(-60f, 6f)
    return if (db >= 0f) "+${String.format("%.0f", db)}" else String.format("%.0f", db)
}

/**
 * Vertical channel strip for audio mixing — professional console style.
 */
@Composable
fun ChannelStrip(
    channelName: String,
    level: Float,
    faderValue: Float,
    isAfv: Boolean,
    syncOffsetMs: Int,
    onFaderChange: (Float) -> Unit,
    onToggleAfv: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceDark)
            .border(1.dp, GlassBorder.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(vertical = 10.dp, horizontal = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Channel name badge
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(Primary.copy(alpha = 0.1f))
                .padding(horizontal = 4.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = channelName,
                color = Primary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = JetBrainsMonoFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        // LED Meter
        LedMeter(
            level = level,
            modifier = Modifier.height(72.dp)
        )

        // dB value in dark recessed display
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(3.dp))
                .background(BackgroundDarker)
                .padding(vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = faderToDb(faderValue),
                color = TextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = JetBrainsMonoFamily
            )
        }

        // Fader
        FaderSlider(
            value = faderValue,
            onValueChange = onFaderChange
        )

        // AFV toggle button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(if (isAfv) NeonGreen.copy(alpha = 0.2f) else SurfaceLight)
                .clickable(onClick = onToggleAfv)
                .padding(vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "AFV",
                color = if (isAfv) NeonGreen else TextTertiary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = JetBrainsMonoFamily
            )
        }

        // Sync offset (only when non-zero)
        if (syncOffsetMs != 0) {
            Text(
                text = if (syncOffsetMs >= 0) "+${syncOffsetMs}ms" else "${syncOffsetMs}ms",
                color = TextTertiary,
                fontSize = 7.sp,
                fontFamily = JetBrainsMonoFamily,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
