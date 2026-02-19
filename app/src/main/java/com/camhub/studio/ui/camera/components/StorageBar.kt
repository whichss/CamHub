package com.camhub.studio.ui.camera.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.camhub.studio.ui.theme.AmberYellow
import com.camhub.studio.ui.theme.ElectricRed
import com.camhub.studio.ui.theme.NeonGreen
import com.camhub.studio.ui.theme.Primary
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

@Composable
fun StorageBar(
    usedGb: Float,
    totalGb: Float,
    modifier: Modifier = Modifier
) {
    val fraction = if (totalGb > 0f) (usedGb / totalGb).coerceIn(0f, 1f) else 0f
    val percentage = (fraction * 100).toInt()
    val barColor = when {
        fraction > 0.90f -> ElectricRed
        fraction > 0.75f -> AmberYellow
        else -> Primary
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Progress bar
        Box(
            modifier = Modifier
                .width(60.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(SurfaceDark)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(barColor)
            )
        }

        // Percentage
        Text(
            text = "${percentage}%",
            color = if (fraction > 0.90f) ElectricRed else TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )

        // Total
        Text(
            text = String.format("%.0fGB", totalGb),
            color = TextTertiary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
