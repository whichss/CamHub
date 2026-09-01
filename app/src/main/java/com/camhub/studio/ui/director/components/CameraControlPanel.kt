package com.camhub.studio.ui.director.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.components.DrumDial
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.ElectricRed
import com.camhub.studio.ui.theme.JetBrainsMonoFamily
import com.camhub.studio.ui.theme.Primary
import com.camhub.studio.ui.theme.SpaceGroteskFamily
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.SurfaceLight
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

private val isoValues = listOf("100", "200", "400", "800", "1600", "3200", "6400")
private val shutterValues = listOf("1/30", "1/48", "1/50", "1/60", "1/100", "1/125", "1/250", "1/500", "1/1000")
private val allZoomValues = listOf("1.0x", "1.5x", "2.0x", "2.5x", "3.0x", "3.5x", "4.0x", "5.0x", "6.0x", "8.0x", "10.0x")

/**
 * Dialog overlay for remote camera control using DrumDial selectors
 * for ISO, Shutter Speed, and Zoom.
 */
@Composable
fun CameraControlPanel(
    cameraName: String,
    isRecording: Boolean = false,
    currentZoomRatio: Float = 1f,
    maxZoomRatio: Float = 10f,
    isPtzEnabled: Boolean = true,
    onDismiss: () -> Unit,
    onSendCommand: (command: String, value: Float, stringValue: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedIsoIndex by remember { mutableIntStateOf(2) } // default 400
    var selectedShutterIndex by remember { mutableIntStateOf(2) } // default 1/50
    val zoomValues = remember(maxZoomRatio) {
        allZoomValues.filter {
            (it.removeSuffix("x").toFloatOrNull() ?: 1f) <= maxZoomRatio + 0.01f
        }.ifEmpty { listOf("1.0x") }
    }
    val initialZoomIndex = zoomValues.indices.minByOrNull { index ->
        kotlin.math.abs(
            (zoomValues[index].removeSuffix("x").toFloatOrNull() ?: 1f) - currentZoomRatio
        )
    } ?: 0
    var selectedZoomIndex by remember(cameraName, currentZoomRatio, maxZoomRatio) {
        mutableIntStateOf(initialZoomIndex)
    }

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
                .fillMaxWidth(0.92f)
                .widthIn(max = 720.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(SurfaceDark)
                .navigationBarsPadding()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { /* consume click */ }
                )
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
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

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(BackgroundDarker.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PTZ  ${"%.1f".format(java.util.Locale.US, currentZoomRatio)}x",
                    color = if (isPtzEnabled) Primary else TextTertiary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = JetBrainsMonoFamily
                )
                Text(
                    text = if (isPtzEnabled) {
                        "READY · MAX ${"%.1f".format(java.util.Locale.US, maxZoomRatio)}x"
                    } else {
                        "PGM PTZ LOCKED"
                    },
                    color = if (isPtzEnabled) TextSecondary else ElectricRed,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = JetBrainsMonoFamily
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Three DrumDials in a Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top
            ) {
                // ISO DrumDial
                DrumDialColumn(
                    title = "ISO",
                    values = isoValues,
                    selectedIndex = selectedIsoIndex,
                    onIndexChanged = { index ->
                        selectedIsoIndex = index
                        val iso = isoValues[index]
                        onSendCommand("set_iso", iso.toFloatOrNull() ?: 400f, iso)
                    }
                )

                // SHUTTER DrumDial
                DrumDialColumn(
                    title = "SHUTTER",
                    values = shutterValues,
                    selectedIndex = selectedShutterIndex,
                    onIndexChanged = { index ->
                        selectedShutterIndex = index
                        val shutter = shutterValues[index]
                        val denom = shutter.removePrefix("1/").toFloatOrNull() ?: 50f
                        onSendCommand("set_shutter", denom, shutter)
                    }
                )

                // ZOOM DrumDial
                DrumDialColumn(
                    title = "ZOOM",
                    values = zoomValues,
                    selectedIndex = selectedZoomIndex,
                    enabled = isPtzEnabled,
                    onIndexChanged = { index ->
                        selectedZoomIndex = index
                        val zoomStr = zoomValues[index]
                        val zoomVal = zoomStr.removeSuffix("x").toFloatOrNull() ?: 1f
                        onSendCommand("set_zoom", zoomVal, "")
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // REC / STOP button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isRecording) SurfaceLight else ElectricRed)
                    .clickable {
                        if (isRecording) {
                            onSendCommand("stop_recording", 0f, "")
                        } else {
                            onSendCommand("start_recording", 0f, "")
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!isRecording) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(TextPrimary)
                        )
                    }
                    Text(
                        text = if (isRecording) "ISO STOP" else "ISO REC",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = JetBrainsMonoFamily,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun DrumDialColumn(
    title: String,
    values: List<String>,
    selectedIndex: Int,
    enabled: Boolean = true,
    onIndexChanged: (Int) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            color = TextTertiary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = JetBrainsMonoFamily,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        DrumDial(
            values = values,
            selectedIndex = selectedIndex,
            enabled = enabled,
            onIndexChanged = onIndexChanged,
            visibleItems = 5,
            modifier = Modifier
                .width(90.dp)
                .height(150.dp)
                .background(BackgroundDarker.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = values[selectedIndex],
            color = if (enabled) Primary else TextTertiary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = JetBrainsMonoFamily
        )
    }
}
