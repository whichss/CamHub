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
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.ElectricRed
import com.camhub.studio.ui.theme.JetBrainsMonoFamily
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.SurfaceLight
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextTertiary

/**
 * Master fader strip — professional console style with gold accent.
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
            .width(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceDark)
            .border(1.dp, AmberYellow.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // MST label badge
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(AmberYellow.copy(alpha = 0.15f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "MST",
                color = AmberYellow,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = JetBrainsMonoFamily,
                letterSpacing = 2.sp
            )
        }

        // LED Meter
        LedMeter(
            level = if (isMuted) 0f else level,
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
                text = formatMasterDb(dbValue),
                color = if (isMuted) ElectricRed else TextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = JetBrainsMonoFamily
            )
        }

        // Fader
        FaderSlider(
            value = faderValue,
            onValueChange = onFaderChange,
            thumbColor = AmberYellow,
            activeTrackColor = AmberYellow,
            inactiveTrackColor = SurfaceLight
        )

        // MUTE button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(if (isMuted) ElectricRed else SurfaceLight)
                .clickable(onClick = onToggleMute)
                .padding(vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "MUTE",
                color = if (isMuted) TextPrimary else TextTertiary,
                fontSize = 9.sp,
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
        db <= -59f -> "-∞"
        db >= 0f -> "+${String.format("%.0f", db)}"
        else -> String.format("%.0f", db)
    }
}
