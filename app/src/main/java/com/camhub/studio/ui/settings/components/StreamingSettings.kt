package com.camhub.studio.ui.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.theme.CyanAccent
import com.camhub.studio.ui.theme.GlassBorder
import com.camhub.studio.ui.theme.GlassSurface
import com.camhub.studio.ui.theme.JetBrainsMonoFamily
import com.camhub.studio.ui.theme.Primary
import com.camhub.studio.ui.theme.SpaceGroteskFamily
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.TextMuted
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

private val fpsOptions = listOf(24, 30, 60)
private val resolutionOptions = listOf(720, 1080)

@Composable
fun StreamingSettings(
    fps: Int,
    maxResolution: Int,
    bitrateMbps: Int,
    isLowLatencyDecode: Boolean,
    onFpsChange: (Int) -> Unit,
    onResolutionChange: (Int) -> Unit,
    onBitrateChange: (Int) -> Unit,
    onToggleLowLatency: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "STREAMING QUALITY",
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = TextPrimary,
            letterSpacing = 1.5.sp
        )

        // FPS selector
        OptionSelector(
            label = "FRAME RATE",
            options = fpsOptions.map { "${it}fps" },
            selectedIndex = fpsOptions.indexOf(fps).coerceAtLeast(0),
            onSelect = { index -> onFpsChange(fpsOptions[index]) }
        )

        // Resolution selector
        OptionSelector(
            label = "MAX RESOLUTION",
            options = resolutionOptions.map { "${it}p" },
            selectedIndex = resolutionOptions.indexOf(maxResolution).coerceAtLeast(0),
            onSelect = { index -> onResolutionChange(resolutionOptions[index]) }
        )

        // Streaming bitrate slider
        StreamBitrateSlider(
            bitrateMbps = bitrateMbps,
            onBitrateChange = onBitrateChange
        )

        // Low-latency decode toggle
        LowLatencyToggle(
            isEnabled = isLowLatencyDecode,
            onToggle = onToggleLowLatency
        )
    }
}

@Composable
private fun OptionSelector(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            color = TextTertiary,
            letterSpacing = 1.5.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                val shape = RoundedCornerShape(10.dp)
                val borderColor = if (isSelected) CyanAccent else GlassBorder
                val bgColor = if (isSelected) CyanAccent.copy(alpha = 0.08f) else GlassSurface

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(shape)
                        .background(bgColor)
                        .border(width = 1.dp, color = borderColor, shape = shape)
                        .clickable { onSelect(index) }
                        .padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = option,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (isSelected) CyanAccent else TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamBitrateSlider(
    bitrateMbps: Int,
    onBitrateChange: (Int) -> Unit
) {
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassSurface)
            .border(width = 1.dp, color = GlassBorder, shape = shape)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "STREAM BITRATE",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                color = TextTertiary,
                letterSpacing = 1.5.sp
            )
            Text(
                text = "${bitrateMbps} Mbps",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = CyanAccent
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            value = bitrateMbps.toFloat(),
            onValueChange = { onBitrateChange(it.toInt()) },
            valueRange = 1f..10f,
            steps = 8,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = CyanAccent,
                activeTrackColor = CyanAccent,
                inactiveTrackColor = SurfaceDark
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "1 Mbps", fontFamily = SpaceGroteskFamily, fontSize = 10.sp, color = TextMuted)
            Text(text = "10 Mbps", fontFamily = SpaceGroteskFamily, fontSize = 10.sp, color = TextMuted)
        }
    }
}

@Composable
private fun LowLatencyToggle(
    isEnabled: Boolean,
    onToggle: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassSurface)
            .border(width = 1.dp, color = GlassBorder, shape = shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Low-Latency Decode",
                fontFamily = SpaceGroteskFamily,
                fontSize = 14.sp,
                color = TextPrimary,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Reduce decoder buffering (requires reconnect)",
                fontFamily = SpaceGroteskFamily,
                fontSize = 11.sp,
                color = TextTertiary
            )
        }
        Switch(
            checked = isEnabled,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextPrimary,
                checkedTrackColor = Primary,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceDark,
                uncheckedBorderColor = GlassBorder
            )
        )
    }
}
