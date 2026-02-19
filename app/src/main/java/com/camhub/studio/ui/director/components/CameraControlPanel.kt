package com.camhub.studio.ui.director.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.GlassBorder
import com.camhub.studio.ui.theme.JetBrainsMonoFamily
import com.camhub.studio.ui.theme.Primary
import com.camhub.studio.ui.theme.SpaceGroteskFamily
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.SurfaceLight
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

/**
 * ISO preset values commonly used in video production.
 */
private val isoPresets = listOf(100, 200, 400, 800, 1600, 3200, 6400)

/**
 * Shutter speed preset values (denominator for 1/x seconds).
 */
private val shutterPresets = listOf(30, 48, 50, 60, 100, 125, 250, 500, 1000)

/**
 * A selectable chip for ISO or shutter presets.
 */
@Composable
private fun PresetChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) Primary else SurfaceLight
    val textColor = if (isSelected) TextPrimary else TextSecondary

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontFamily = JetBrainsMonoFamily
        )
    }
}

/**
 * A section header label for the control panel.
 */
@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        color = TextTertiary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = JetBrainsMonoFamily,
        letterSpacing = 1.sp,
        modifier = modifier.padding(bottom = 6.dp)
    )
}

/**
 * Dialog overlay for remote camera control, providing zoom slider, ISO preset chips,
 * and shutter speed preset chips.
 *
 * @param cameraName The name of the camera being controlled.
 * @param onDismiss Callback to close the panel.
 * @param onSendCommand Callback to send a camera command (command, floatValue, stringValue).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraControlPanel(
    cameraName: String,
    onDismiss: () -> Unit,
    onSendCommand: (command: String, value: Float, stringValue: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var zoomLevel by remember { mutableFloatStateOf(1f) }
    var selectedIso by remember { mutableFloatStateOf(400f) }
    var selectedShutter by remember { mutableFloatStateOf(50f) }

    // Dim background overlay
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDarker.copy(alpha = 0.7f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        // Control panel card
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceDark)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { /* consume click to prevent dismissing */ }
                )
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Camera Control",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = SpaceGroteskFamily
                    )
                    Text(
                        text = cameraName,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = JetBrainsMonoFamily
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(SurfaceLight)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Zoom slider
            SectionHeader(title = "ZOOM")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "1x",
                    color = TextTertiary,
                    fontSize = 10.sp,
                    fontFamily = JetBrainsMonoFamily
                )
                Slider(
                    value = zoomLevel,
                    onValueChange = { zoomLevel = it },
                    onValueChangeFinished = {
                        onSendCommand("zoom", zoomLevel, "")
                    },
                    valueRange = 1f..10f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Primary,
                        activeTrackColor = Primary,
                        inactiveTrackColor = SurfaceLight
                    )
                )
                Text(
                    text = "${String.format("%.1f", zoomLevel)}x",
                    color = Primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = JetBrainsMonoFamily,
                    modifier = Modifier.width(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ISO presets
            SectionHeader(title = "ISO")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                isoPresets.forEach { iso ->
                    PresetChip(
                        label = "ISO $iso",
                        isSelected = selectedIso.toInt() == iso,
                        onClick = {
                            selectedIso = iso.toFloat()
                            onSendCommand("iso", iso.toFloat(), "")
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Shutter presets
            SectionHeader(title = "SHUTTER SPEED")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                shutterPresets.forEach { shutter ->
                    PresetChip(
                        label = "1/$shutter",
                        isSelected = selectedShutter.toInt() == shutter,
                        onClick = {
                            selectedShutter = shutter.toFloat()
                            onSendCommand("shutter", shutter.toFloat(), "1/$shutter")
                        }
                    )
                }
            }
        }
    }
}
