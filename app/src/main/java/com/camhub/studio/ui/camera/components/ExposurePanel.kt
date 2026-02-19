package com.camhub.studio.ui.camera.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.components.DrumDial
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.Primary
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

@Composable
fun ExposurePanel(
    isoValues: List<String>,
    selectedIsoIndex: Int,
    onIsoChanged: (Int) -> Unit,
    shutterValues: List<String>,
    selectedShutterIndex: Int,
    onShutterChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(BackgroundDarker.copy(alpha = 0.60f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ISO Control
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "ISO",
                color = TextTertiary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            DrumDial(
                values = isoValues,
                selectedIndex = selectedIsoIndex,
                onIndexChanged = onIsoChanged,
                visibleItems = 5,
                modifier = Modifier
                    .width(80.dp)
                    .height(140.dp)
            )
            Text(
                text = isoValues.getOrElse(selectedIsoIndex) { "--" },
                color = Primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        // Shutter Control
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "SHUTTER",
                color = TextTertiary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            DrumDial(
                values = shutterValues,
                selectedIndex = selectedShutterIndex,
                onIndexChanged = onShutterChanged,
                visibleItems = 5,
                modifier = Modifier
                    .width(80.dp)
                    .height(140.dp)
            )
            Text(
                text = shutterValues.getOrElse(selectedShutterIndex) { "--" },
                color = Primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
