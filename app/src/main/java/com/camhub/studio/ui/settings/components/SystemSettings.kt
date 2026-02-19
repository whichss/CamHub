package com.camhub.studio.ui.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.theme.AmberYellow
import com.camhub.studio.ui.theme.CyanAccent
import com.camhub.studio.ui.theme.ElectricRed
import com.camhub.studio.ui.theme.GlassBorder
import com.camhub.studio.ui.theme.GlassSurface
import com.camhub.studio.ui.theme.JetBrainsMonoFamily
import com.camhub.studio.ui.theme.NeonGreen
import com.camhub.studio.ui.theme.Primary
import com.camhub.studio.ui.theme.SpaceGroteskFamily
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.TextMuted
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

@Composable
fun SystemSettings(
    batteryPercent: Int,
    wifiStrength: Int,
    storageUsedGb: Float,
    storageTotalGb: Float,
    isKioskModeEnabled: Boolean,
    isAutoStartEnabled: Boolean,
    isNavigationLocked: Boolean,
    screenTimeoutMinutes: Int,
    onToggleKioskMode: () -> Unit,
    onToggleAutoStart: () -> Unit,
    onToggleNavigationLock: () -> Unit,
    onUpdateScreenTimeout: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Device status bars
        DeviceStatusCard(
            batteryPercent = batteryPercent,
            wifiStrength = wifiStrength,
            storageUsedGb = storageUsedGb,
            storageTotalGb = storageTotalGb
        )

        // Toggles
        TogglesCard(
            isKioskModeEnabled = isKioskModeEnabled,
            isAutoStartEnabled = isAutoStartEnabled,
            isNavigationLocked = isNavigationLocked,
            onToggleKioskMode = onToggleKioskMode,
            onToggleAutoStart = onToggleAutoStart,
            onToggleNavigationLock = onToggleNavigationLock
        )

        // Screen timeout
        ScreenTimeoutSlider(
            timeoutMinutes = screenTimeoutMinutes,
            onTimeoutChange = onUpdateScreenTimeout
        )
    }
}

@Composable
private fun DeviceStatusCard(
    batteryPercent: Int,
    wifiStrength: Int,
    storageUsedGb: Float,
    storageTotalGb: Float
) {
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassSurface)
            .border(width = 1.dp, color = GlassBorder, shape = shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "DEVICE STATUS",
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            color = TextTertiary,
            letterSpacing = 1.5.sp
        )

        StatusBar(
            icon = Icons.Default.BatteryFull,
            label = "Battery",
            value = "$batteryPercent%",
            progress = batteryPercent / 100f,
            barColor = when {
                batteryPercent <= 15 -> ElectricRed
                batteryPercent <= 30 -> AmberYellow
                else -> NeonGreen
            }
        )

        StatusBar(
            icon = Icons.Default.Wifi,
            label = "WiFi",
            value = "$wifiStrength%",
            progress = wifiStrength / 100f,
            barColor = when {
                wifiStrength <= 25 -> ElectricRed
                wifiStrength <= 50 -> AmberYellow
                else -> CyanAccent
            }
        )

        val storagePercent = if (storageTotalGb > 0f) storageUsedGb / storageTotalGb else 0f
        StatusBar(
            icon = Icons.Default.SdStorage,
            label = "Storage",
            value = "%.1f/%.1f GB".format(storageUsedGb, storageTotalGb),
            progress = storagePercent,
            barColor = when {
                storagePercent >= 0.9f -> ElectricRed
                storagePercent >= 0.75f -> AmberYellow
                else -> Primary
            }
        )
    }
}

@Composable
private fun StatusBar(
    icon: ImageVector,
    label: String,
    value: String,
    progress: Float,
    barColor: Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = barColor,
                    modifier = Modifier.height(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    fontFamily = SpaceGroteskFamily,
                    fontSize = 13.sp,
                    color = TextPrimary
                )
            }
            Text(
                text = value,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.sp,
                color = TextSecondary
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(SurfaceDark)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor)
            )
        }
    }
}

@Composable
private fun TogglesCard(
    isKioskModeEnabled: Boolean,
    isAutoStartEnabled: Boolean,
    isNavigationLocked: Boolean,
    onToggleKioskMode: () -> Unit,
    onToggleAutoStart: () -> Unit,
    onToggleNavigationLock: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassSurface)
            .border(width = 1.dp, color = GlassBorder, shape = shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "APP BEHAVIOR",
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            color = TextTertiary,
            letterSpacing = 1.5.sp
        )
        Spacer(modifier = Modifier.height(4.dp))

        ToggleRow(
            label = "Kiosk Mode",
            description = "Lock device to this app",
            isChecked = isKioskModeEnabled,
            onToggle = onToggleKioskMode
        )

        ToggleRow(
            label = "Auto-Start",
            description = "Launch on device boot",
            isChecked = isAutoStartEnabled,
            onToggle = onToggleAutoStart
        )

        ToggleRow(
            label = "Navigation Lock",
            description = "Hide system navigation bars",
            isChecked = isNavigationLocked,
            onToggle = onToggleNavigationLock
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    isChecked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontFamily = SpaceGroteskFamily,
                fontSize = 14.sp,
                color = TextPrimary,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                fontFamily = SpaceGroteskFamily,
                fontSize = 11.sp,
                color = TextTertiary
            )
        }
        Switch(
            checked = isChecked,
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

@Composable
private fun ScreenTimeoutSlider(
    timeoutMinutes: Int,
    onTimeoutChange: (Int) -> Unit
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
                text = "SCREEN TIMEOUT",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                color = TextTertiary,
                letterSpacing = 1.5.sp
            )
            Text(
                text = if (timeoutMinutes >= 60) "${timeoutMinutes / 60}h ${timeoutMinutes % 60}m"
                else "${timeoutMinutes} min",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = CyanAccent
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            value = timeoutMinutes.toFloat(),
            onValueChange = { onTimeoutChange(it.toInt()) },
            valueRange = 1f..120f,
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
                text = "1 min",
                fontFamily = SpaceGroteskFamily,
                fontSize = 10.sp,
                color = TextMuted
            )
            Text(
                text = "2 hours",
                fontFamily = SpaceGroteskFamily,
                fontSize = 10.sp,
                color = TextMuted
            )
        }
    }
}
