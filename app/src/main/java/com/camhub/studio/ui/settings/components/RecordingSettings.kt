package com.camhub.studio.ui.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.settings.model.RecordingFormat
import com.camhub.studio.ui.theme.AmberYellow
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

@Composable
fun RecordingSettings(
    storagePath: String,
    onChangeStoragePath: () -> Unit,
    selectedFormat: RecordingFormat,
    onSelectFormat: (RecordingFormat) -> Unit,
    bitrateMbps: Int,
    onBitrateChange: (Int) -> Unit,
    storageUsedGb: Float,
    storageTotalGb: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Storage path
        StoragePathCard(
            path = storagePath,
            onChangePath = onChangeStoragePath
        )

        // Format selection
        FormatSelector(
            selectedFormat = selectedFormat,
            onSelectFormat = onSelectFormat
        )

        // Bitrate slider
        BitrateSlider(
            bitrateMbps = bitrateMbps,
            onBitrateChange = onBitrateChange
        )

        // Storage usage
        StorageUsageInfo(
            usedGb = storageUsedGb,
            totalGb = storageTotalGb
        )
    }
}

@Composable
private fun StoragePathCard(
    path: String,
    onChangePath: () -> Unit
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
        Text(
            text = "STORAGE PATH",
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            color = TextTertiary,
            letterSpacing = 1.5.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = AmberYellow,
                    modifier = Modifier.padding(end = 10.dp)
                )
                Text(
                    text = path.ifEmpty { "Not set" },
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onChangePath) {
                Text(
                    text = "Change",
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    color = Primary
                )
            }
        }
    }
}

@Composable
private fun FormatSelector(
    selectedFormat: RecordingFormat,
    onSelectFormat: (RecordingFormat) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "RECORDING FORMAT",
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
            FormatCard(
                label = "H.264",
                subtitle = "MP4",
                isSelected = selectedFormat == RecordingFormat.MP4_H264,
                onClick = { onSelectFormat(RecordingFormat.MP4_H264) },
                modifier = Modifier.weight(1f)
            )
            FormatCard(
                label = "H.265",
                subtitle = "MP4",
                isSelected = selectedFormat == RecordingFormat.MP4_H265,
                onClick = { onSelectFormat(RecordingFormat.MP4_H265) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun FormatCard(
    label: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    val borderColor = if (isSelected) CyanAccent else GlassBorder
    val bgColor = if (isSelected) CyanAccent.copy(alpha = 0.08f) else GlassSurface

    Column(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = if (isSelected) CyanAccent else TextPrimary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            fontFamily = SpaceGroteskFamily,
            fontSize = 11.sp,
            color = TextTertiary
        )
    }
}

@Composable
private fun BitrateSlider(
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
                text = "BITRATE",
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
            valueRange = 1f..50f,
            steps = 48,
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
            Text(
                text = "1 Mbps",
                fontFamily = SpaceGroteskFamily,
                fontSize = 10.sp,
                color = TextMuted
            )
            Text(
                text = "50 Mbps",
                fontFamily = SpaceGroteskFamily,
                fontSize = 10.sp,
                color = TextMuted
            )
        }
    }
}

@Composable
private fun StorageUsageInfo(
    usedGb: Float,
    totalGb: Float
) {
    val shape = RoundedCornerShape(10.dp)
    val usedPercent = if (totalGb > 0f) (usedGb / totalGb).coerceIn(0f, 1f) else 0f
    val freeGb = (totalGb - usedGb).coerceAtLeast(0f)

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
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "STORAGE",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                color = TextTertiary,
                letterSpacing = 1.5.sp
            )
            Text(
                text = "%.1f GB free".format(freeGb),
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                color = TextSecondary
            )
        }
        Spacer(modifier = Modifier.height(10.dp))

        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(SurfaceDark)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(usedPercent)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        when {
                            usedPercent > 0.9f -> AmberYellow
                            usedPercent > 0.75f -> AmberYellow
                            else -> CyanAccent
                        }
                    )
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "%.1f / %.1f GB used".format(usedGb, totalGb),
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            color = TextMuted
        )
    }
}
