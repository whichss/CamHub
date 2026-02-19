package com.camhub.studio.ui.audio.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.theme.AmberYellow
import com.camhub.studio.ui.theme.ElectricRed
import com.camhub.studio.ui.theme.GlassBorder
import com.camhub.studio.ui.theme.JetBrainsMonoFamily
import com.camhub.studio.ui.theme.Primary
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.SurfaceLight
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

/**
 * Master fader strip with label, LED meter, fader slider, dB value, and MUTE button.
 *
 * @param level Normalized master audio level (0.0 - 1.0).
 * @param faderValue Master fader position (0.0 - 1.0).
 * @param dbValue Current decibel value for display.
 * @param isMuted Whether the master output is muted.
 * @param onFaderChange Callback when master fader position changes.
 * @param onToggleMute Callback when MUTE button is toggled.
 */
@Composable
fun MasterStrip(
    level: Float,
    faderValue: Float,
    dbValue: Float,
    isMuted: Boolean,
    onFaderChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(62.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceDark)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // MST label
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(AmberYellow.copy(alpha = 0.15f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "MST",
                color = AmberYellow,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = JetBrainsMonoFamily,
                letterSpacing = 1.sp
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        // LED Meter
        LedMeter(
            level = if (isMuted) 0f else level,
            modifier = Modifier.height(64.dp)
        )

        // Fader
        FaderSlider(
            value = faderValue,
            onValueChange = onFaderChange,
            thumbColor = AmberYellow,
            activeTrackColor = AmberYellow,
            inactiveTrackColor = SurfaceLight
        )

        // dB value
        Text(
            text = formatMasterDb(dbValue),
            color = if (isMuted) ElectricRed else TextSecondary,
            fontSize = 8.sp,
            fontFamily = JetBrainsMonoFamily,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        // MUTE button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (isMuted) ElectricRed else SurfaceLight)
                .clickable(onClick = onToggleMute)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "MUTE",
                color = if (isMuted) TextPrimary else TextTertiary,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = JetBrainsMonoFamily
            )
        }
    }
}

/**
 * Formats a master dB value for display.
 */
private fun formatMasterDb(db: Float): String {
    return when {
        db <= -59f -> "-inf"
        db >= 0f -> "+${String.format("%.1f", db)}"
        else -> String.format("%.1f", db)
    }
}
