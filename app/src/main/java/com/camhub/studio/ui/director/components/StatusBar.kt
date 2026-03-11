package com.camhub.studio.ui.director.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.theme.AmberYellow
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.CyanAccent
import com.camhub.studio.ui.theme.ElectricRed
import com.camhub.studio.ui.theme.JetBrainsMonoFamily
import com.camhub.studio.ui.theme.NeonGreen
import com.camhub.studio.ui.theme.LedGreen
import com.camhub.studio.ui.theme.LedRed
import com.camhub.studio.ui.theme.LedYellow
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.SurfaceLight
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

/**
 * Returns an appropriate color for the battery level percentage.
 */
private fun batteryColor(percent: Int) = when {
    percent > 50 -> NeonGreen
    percent > 20 -> AmberYellow
    else -> ElectricRed
}

/**
 * Returns an appropriate color for the wifi signal strength (0-4).
 */
private fun wifiColor(strength: Int) = when {
    strength >= 3 -> NeonGreen
    strength >= 2 -> AmberYellow
    else -> ElectricRed
}

/**
 * Director status bar displaying network stats, recording state with timecode,
 * and device status indicators.
 *
 * @param bitrateKbps Total bitrate in kbps.
 * @param latencyMs Network latency in milliseconds.
 * @param timecode Current timecode string.
 * @param isRecording Whether recording is active.
 * @param isPaused Whether recording is paused.
 * @param wifiStrength WiFi signal strength (0-4).
 * @param batteryPercent Battery level percentage (0-100).
 */
@Composable
fun StatusBar(
    bitrateKbps: Int,
    latencyMs: Int,
    timecode: String,
    isRecording: Boolean,
    isPaused: Boolean,
    wifiStrength: Int,
    batteryPercent: Int,
    audioMasterLevel: Float = 0f,
    connectedCameraCount: Int = 0,
    onNavigateToSettings: (() -> Unit)? = null,
    onToggleDeviceManager: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Blinking animation for recording indicator
    val infiniteTransition = rememberInfiniteTransition(label = "rec_blink")
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rec_blink_alpha"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(BackgroundDarker)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left section: Bitrate + Latency
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Bitrate
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Speed,
                    contentDescription = "Bitrate",
                    tint = CyanAccent,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = if (bitrateKbps >= 1000) "${bitrateKbps / 1000}Mbps" else "${bitrateKbps}kbps",
                    color = CyanAccent,
                    fontSize = 10.sp,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Medium
                )
            }

            // Latency
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Timer,
                    contentDescription = "Latency",
                    tint = NeonGreen,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "${latencyMs}ms",
                    color = NeonGreen,
                    fontSize = 10.sp,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Center section: Recording indicator + Timecode
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isRecording) {
                val dotAlpha = if (isPaused) 0.4f else blinkAlpha
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .alpha(dotAlpha)
                        .clip(CircleShape)
                        .background(ElectricRed)
                )
                Text(
                    text = if (isPaused) "PAUSED" else "REC",
                    color = if (isPaused) AmberYellow else ElectricRed,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = JetBrainsMonoFamily
                )
            }

            Text(
                text = timecode,
                color = if (isRecording) TextPrimary else TextSecondary,
                fontSize = 12.sp,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp
            )
        }

        // Audio meter (compact)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = "AU",
                color = TextTertiary,
                fontSize = 8.sp,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold
            )
            MiniAudioMeter(level = audioMasterLevel)
        }

        // Right section: WiFi + Battery
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // WiFi
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Wifi,
                    contentDescription = "WiFi strength",
                    tint = wifiColor(wifiStrength),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "$wifiStrength",
                    color = wifiColor(wifiStrength),
                    fontSize = 9.sp,
                    fontFamily = JetBrainsMonoFamily
                )
            }

            // Battery
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.BatteryFull,
                    contentDescription = "Battery",
                    tint = batteryColor(batteryPercent),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "${batteryPercent}%",
                    color = batteryColor(batteryPercent),
                    fontSize = 9.sp,
                    fontFamily = JetBrainsMonoFamily
                )
            }

            // Devices button
            if (onToggleDeviceManager != null) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(SurfaceLight.copy(alpha = 0.6f))
                        .clickable(onClick = onToggleDeviceManager),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Devices,
                        contentDescription = "Devices",
                        tint = if (connectedCameraCount > 0) CyanAccent else TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // Settings button (if callback provided)
            if (onNavigateToSettings != null) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(SurfaceLight.copy(alpha = 0.6f))
                        .clickable(onClick = onNavigateToSettings),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniAudioMeter(
    level: Float,
    modifier: Modifier = Modifier
) {
    // Smooth decay: animate toward target level so the meter doesn't flicker
    val animatedLevel by androidx.compose.animation.core.animateFloatAsState(
        targetValue = level,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = if (level > 0f) 50 else 150  // fast attack, slow release
        ),
        label = "audio_meter"
    )

    val segments = 10
    val filledSegments = (animatedLevel * segments).toInt().coerceIn(0, segments)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(segments) { i ->
            val isActive = i < filledSegments
            val color = when {
                i >= 8 -> if (isActive) LedRed else SurfaceDark
                i >= 6 -> if (isActive) LedYellow else SurfaceDark
                else -> if (isActive) LedGreen else SurfaceDark
            }
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(10.dp)
                    .background(color, RoundedCornerShape(1.dp))
            )
        }
    }
}
