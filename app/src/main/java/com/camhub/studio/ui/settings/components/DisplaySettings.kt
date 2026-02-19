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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.camhub.studio.ui.settings.model.DisplayResolution
import com.camhub.studio.ui.theme.CyanAccent
import com.camhub.studio.ui.theme.ElectricRed
import com.camhub.studio.ui.theme.GlassBorder
import com.camhub.studio.ui.theme.GlassSurface
import com.camhub.studio.ui.theme.NeonGreen
import com.camhub.studio.ui.theme.Primary
import com.camhub.studio.ui.theme.SpaceGroteskFamily
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

@Composable
fun DisplaySettings(
    isExternalDisplayConnected: Boolean,
    isExternalDisplayEnabled: Boolean,
    selectedResolution: DisplayResolution,
    onToggleExternalDisplay: () -> Unit,
    onSelectResolution: (DisplayResolution) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // External display status
        ExternalDisplayStatusCard(
            isConnected = isExternalDisplayConnected,
            isEnabled = isExternalDisplayEnabled,
            onToggle = onToggleExternalDisplay
        )

        // Resolution selector
        ResolutionSelector(
            selectedResolution = selectedResolution,
            onSelectResolution = onSelectResolution,
            enabled = isExternalDisplayConnected
        )
    }
}

@Composable
private fun ExternalDisplayStatusCard(
    isConnected: Boolean,
    isEnabled: Boolean,
    onToggle: () -> Unit
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
            text = "EXTERNAL DISPLAY",
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            color = TextTertiary,
            letterSpacing = 1.5.sp
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) NeonGreen else ElectricRed)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isConnected) "Connected" else "Not Connected",
                    fontFamily = SpaceGroteskFamily,
                    fontSize = 14.sp,
                    color = if (isConnected) TextPrimary else TextSecondary,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Enable Output",
                fontFamily = SpaceGroteskFamily,
                fontSize = 14.sp,
                color = TextPrimary
            )
            Switch(
                checked = isEnabled,
                onCheckedChange = { onToggle() },
                enabled = isConnected,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TextPrimary,
                    checkedTrackColor = Primary,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = SurfaceDark,
                    uncheckedBorderColor = GlassBorder,
                    disabledCheckedTrackColor = Primary.copy(alpha = 0.3f),
                    disabledUncheckedTrackColor = SurfaceDark.copy(alpha = 0.5f)
                )
            )
        }
    }
}

@Composable
private fun ResolutionSelector(
    selectedResolution: DisplayResolution,
    onSelectResolution: (DisplayResolution) -> Unit,
    enabled: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "OUTPUT RESOLUTION",
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
            ResolutionCard(
                label = "Match Source",
                resolution = DisplayResolution.MATCH_SOURCE,
                isSelected = selectedResolution == DisplayResolution.MATCH_SOURCE,
                enabled = enabled,
                onClick = { onSelectResolution(DisplayResolution.MATCH_SOURCE) },
                modifier = Modifier.weight(1f)
            )
            ResolutionCard(
                label = "1080p",
                resolution = DisplayResolution.HD_1080P,
                isSelected = selectedResolution == DisplayResolution.HD_1080P,
                enabled = enabled,
                onClick = { onSelectResolution(DisplayResolution.HD_1080P) },
                modifier = Modifier.weight(1f)
            )
            ResolutionCard(
                label = "4K",
                resolution = DisplayResolution.UHD_4K,
                isSelected = selectedResolution == DisplayResolution.UHD_4K,
                enabled = enabled,
                onClick = { onSelectResolution(DisplayResolution.UHD_4K) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ResolutionCard(
    label: String,
    resolution: DisplayResolution,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    val alpha = if (enabled) 1f else 0.4f
    val borderColor = when {
        isSelected && enabled -> CyanAccent
        else -> GlassBorder
    }
    val bgColor = when {
        isSelected && enabled -> CyanAccent.copy(alpha = 0.08f)
        else -> GlassSurface
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .border(width = 1.dp, color = borderColor.copy(alpha = alpha), shape = shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = SpaceGroteskFamily,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 13.sp,
            color = when {
                isSelected && enabled -> CyanAccent
                enabled -> TextPrimary
                else -> TextTertiary
            }
        )
    }
}
