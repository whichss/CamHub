package com.camhub.studio.ui.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.theme.AmberYellow
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.CyanAccent
import com.camhub.studio.ui.theme.ElectricRed
import com.camhub.studio.ui.theme.GlassBorder
import com.camhub.studio.ui.theme.JetBrainsMonoFamily
import com.camhub.studio.ui.theme.NeonGreen
import com.camhub.studio.ui.theme.SpaceGroteskFamily
import com.camhub.studio.ui.theme.TextMuted
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

@Composable
fun SettingsBottomBar(
    latencyMs: Int,
    minStreamFps: Int,
    droppedFrames: Int,
    activeStreams: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(BackgroundDarker)
            .navigationBarsPadding()
            .border(width = 1.dp, color = GlassBorder, shape = RoundedCornerShape(0.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BottomBarItem(
            label = "LATENCY",
            value = "${latencyMs}ms",
            valueColor = when {
                latencyMs > 100 -> ElectricRed
                latencyMs > 50 -> AmberYellow
                else -> NeonGreen
            }
        )

        BarDivider()

        BottomBarItem(
            label = "FPS",
            value = if (minStreamFps > 0) "$minStreamFps" else "--",
            valueColor = when {
                minStreamFps == 0 -> TextSecondary
                minStreamFps < 20 -> ElectricRed
                minStreamFps < 27 -> AmberYellow
                else -> NeonGreen
            }
        )

        BarDivider()

        BottomBarItem(
            label = "DROPPED",
            value = "$droppedFrames",
            valueColor = if (droppedFrames > 0) AmberYellow else TextSecondary
        )

        BarDivider()

        BottomBarItem(
            label = "STREAMS",
            value = "$activeStreams",
            valueColor = if (activeStreams > 0) CyanAccent else TextMuted
        )
    }
}

@Composable
private fun BottomBarItem(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 10.sp,
            color = TextTertiary,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = valueColor
        )
    }
}

@Composable
private fun BarDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(20.dp)
            .background(GlassBorder)
    )
}
