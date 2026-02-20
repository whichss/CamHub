package com.camhub.studio.ui.audio.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.camhub.studio.ui.theme.AmberYellow
import com.camhub.studio.ui.theme.ElectricRed
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
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

/**
 * Number of LED segments in the meter.
 */
private const val LED_SEGMENT_COUNT = 16

/**
 * A vertical LED meter showing audio levels with green/yellow/red segments.
 *
 * @param level Normalized audio level (0.0 - 1.0).
 */
@Composable
fun LedMeter(
    level: Float,
    modifier: Modifier = Modifier
) {
    val activeSegments = (level * LED_SEGMENT_COUNT).toInt().coerceIn(0, LED_SEGMENT_COUNT)

    Column(
        modifier = modifier
            .width(12.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Render from top (hot) to bottom (cold)
        for (i in LED_SEGMENT_COUNT - 1 downTo 0) {
            val segmentIndex = i
            val isActive = segmentIndex < activeSegments
            val segmentColor = when {
                !isActive -> LedOff
                segmentIndex >= (LED_SEGMENT_COUNT * 0.875f).toInt() -> LedRed      // top 2 = red (clip)
                segmentIndex >= (LED_SEGMENT_COUNT * 0.75f).toInt() -> LedYellow    // next 2 = yellow
                else -> LedGreen                                                     // rest = green
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(segmentColor)
            )
        }
    }
}

/**
 * A vertical fader slider styled for audio mixing.
 *
 * @param value Current fader value (0.0 - 1.0).
 * @param onValueChange Callback when fader position changes.
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
    // Vertical slider via rotation
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
    if (value <= 0.001f) return "-inf"
    // Map 0-1 to -60dB to +6dB using a logarithmic-like curve
    val db = (value * 66f - 60f).coerceIn(-60f, 6f)
    return if (db >= 0f) "+${String.format("%.1f", db)}" else String.format("%.1f", db)
}

/**
 * Vertical channel strip for audio mixing, containing a channel name, LED meter,
 * fader slider, dB value display, AFV toggle, and sync offset display.
 *
 * @param channelName Display name of the audio channel.
 * @param level Normalized audio level (0.0 - 1.0).
 * @param faderValue Fader position (0.0 - 1.0).
 * @param isAfv Whether Audio Follow Video is enabled.
 * @param syncOffsetMs Audio sync offset in milliseconds.
 * @param onFaderChange Callback when fader position changes.
 * @param onToggleAfv Callback when AFV button is toggled.
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
            .width(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceDark)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Channel name label
        Text(
            text = channelName,
            color = TextPrimary,
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = JetBrainsMonoFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(2.dp))

        // LED Meter
        LedMeter(
            level = level,
            modifier = Modifier.height(64.dp)
        )

        // Fader
        FaderSlider(
            value = faderValue,
            onValueChange = onFaderChange
        )

        // dB value text
        Text(
            text = faderToDb(faderValue),
            color = TextSecondary,
            fontSize = 8.sp,
            fontFamily = JetBrainsMonoFamily,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        // AFV toggle button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (isAfv) NeonGreen.copy(alpha = 0.2f) else SurfaceLight)
                .clickable(onClick = onToggleAfv)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "AFV",
                color = if (isAfv) NeonGreen else TextTertiary,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = JetBrainsMonoFamily
            )
        }

        // Sync offset text
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
