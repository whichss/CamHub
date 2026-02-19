package com.camhub.studio.ui.camera.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.components.DrumDial
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.CyanAccent
import com.camhub.studio.ui.theme.Primary
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

@Composable
fun FocusPanel(
    focusDistances: List<String>,
    selectedFocusIndex: Int,
    onFocusChanged: (Int) -> Unit,
    isPeakingEnabled: Boolean,
    onTogglePeaking: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BackgroundDarker.copy(alpha = 0.60f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FOCUS",
                color = TextTertiary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            // PEAK toggle button
            val peakShape = RoundedCornerShape(6.dp)
            val peakBackground = if (isPeakingEnabled) CyanAccent.copy(alpha = 0.15f) else SurfaceDark
            val peakBorder = if (isPeakingEnabled) CyanAccent else SurfaceDark
            val peakTextColor = if (isPeakingEnabled) CyanAccent else TextSecondary

            Text(
                text = "PEAK",
                color = peakTextColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(peakShape)
                    .background(peakBackground, peakShape)
                    .border(1.dp, peakBorder, peakShape)
                    .clickable { onTogglePeaking() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        DrumDial(
            values = focusDistances,
            selectedIndex = selectedFocusIndex,
            onIndexChanged = onFocusChanged,
            visibleItems = 5,
            modifier = Modifier
                .width(120.dp)
                .height(140.dp)
        )

        Text(
            text = focusDistances.getOrElse(selectedFocusIndex) { "--" },
            color = Primary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}
