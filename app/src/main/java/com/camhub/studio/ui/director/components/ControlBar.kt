package com.camhub.studio.ui.director.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
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
import com.camhub.studio.ui.director.model.TransitionType
import com.camhub.studio.ui.theme.BackgroundDark
import com.camhub.studio.ui.theme.ElectricRed
import com.camhub.studio.ui.theme.GlassBorder
import com.camhub.studio.ui.theme.GlassSurface
import com.camhub.studio.ui.theme.JetBrainsMonoFamily
import com.camhub.studio.ui.theme.NeonGreen
import com.camhub.studio.ui.theme.Primary
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.SurfaceLight
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

/**
 * A compact action button used in the control bar.
 */
@Composable
private fun ControlButton(
    label: String,
    backgroundColor: Color,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor.copy(alpha = alpha))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = textColor.copy(alpha = alpha),
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = label,
                color = textColor.copy(alpha = alpha),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = JetBrainsMonoFamily,
                letterSpacing = 0.5.sp
            )
        }
    }
}

/**
 * Transition type selection pill.
 */
@Composable
private fun TransitionPill(
    type: TransitionType,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) Primary else GlassSurface
    val borderColor = if (isSelected) Primary else GlassBorder
    val textColor = if (isSelected) TextPrimary else TextTertiary

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = type.name,
            color = textColor,
            fontSize = 9.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontFamily = JetBrainsMonoFamily,
            letterSpacing = 0.5.sp
        )
    }
}

/**
 * Horizontal control bar with recording controls, transition buttons, and audio mixer toggle.
 *
 * @param isRecording Whether recording is currently active.
 * @param isPaused Whether recording is paused.
 * @param selectedTransition The currently selected transition type.
 * @param isVertical When true, render as a vertical bar (for landscape layout).
 * @param onRecord Start recording.
 * @param onStop Stop recording.
 * @param onPause Pause recording.
 * @param onResume Resume recording.
 * @param onCut Execute a CUT transition.
 * @param onAuto Execute an AUTO transition.
 * @param onSelectTransition Select a transition type.
 * @param onToggleAudioMixer Toggle the audio mixer panel.
 */
@Composable
fun ControlBar(
    isRecording: Boolean,
    isPaused: Boolean,
    selectedTransition: TransitionType,
    isVertical: Boolean = false,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCut: () -> Unit,
    onAuto: () -> Unit,
    onSelectTransition: (TransitionType) -> Unit,
    onToggleAudioMixer: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isVertical) {
        ControlBarVertical(
            isRecording = isRecording,
            isPaused = isPaused,
            selectedTransition = selectedTransition,
            onRecord = onRecord,
            onStop = onStop,
            onPause = onPause,
            onResume = onResume,
            onCut = onCut,
            onAuto = onAuto,
            onSelectTransition = onSelectTransition,
            onToggleAudioMixer = onToggleAudioMixer,
            modifier = modifier
        )
    } else {
        ControlBarHorizontal(
            isRecording = isRecording,
            isPaused = isPaused,
            selectedTransition = selectedTransition,
            onRecord = onRecord,
            onStop = onStop,
            onPause = onPause,
            onResume = onResume,
            onCut = onCut,
            onAuto = onAuto,
            onSelectTransition = onSelectTransition,
            onToggleAudioMixer = onToggleAudioMixer,
            modifier = modifier
        )
    }
}

@Composable
private fun ControlBarHorizontal(
    isRecording: Boolean,
    isPaused: Boolean,
    selectedTransition: TransitionType,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCut: () -> Unit,
    onAuto: () -> Unit,
    onSelectTransition: (TransitionType) -> Unit,
    onToggleAudioMixer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceDark)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Recording controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isRecording) {
                ControlButton(
                    label = "REC",
                    backgroundColor = ElectricRed,
                    textColor = TextPrimary,
                    onClick = onRecord
                )
            } else {
                ControlButton(
                    label = "STOP",
                    backgroundColor = SurfaceLight,
                    textColor = ElectricRed,
                    onClick = onStop,
                    icon = Icons.Filled.Stop
                )
                if (isPaused) {
                    ControlButton(
                        label = "RSM",
                        backgroundColor = SurfaceLight,
                        textColor = NeonGreen,
                        onClick = onResume,
                        icon = Icons.Filled.PlayArrow
                    )
                } else {
                    ControlButton(
                        label = "PSE",
                        backgroundColor = SurfaceLight,
                        textColor = TextSecondary,
                        onClick = onPause,
                        icon = Icons.Filled.Pause
                    )
                }
            }
        }

        // Transition controls
        ControlButton(
            label = "CUT",
            backgroundColor = SurfaceLight,
            textColor = TextPrimary,
            onClick = onCut
        )
        ControlButton(
            label = "AUTO",
            backgroundColor = NeonGreen.copy(alpha = 0.2f),
            textColor = TextPrimary,
            onClick = onAuto
        )

        // Transition type pills
        TransitionType.entries.forEach { type ->
            TransitionPill(
                type = type,
                isSelected = type == selectedTransition,
                onClick = { onSelectTransition(type) }
            )
        }

        // Audio mixer button
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(SurfaceLight)
                .clickable(onClick = onToggleAudioMixer)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = "Audio Mixer",
                tint = Primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ControlBarVertical(
    isRecording: Boolean,
    isPaused: Boolean,
    selectedTransition: TransitionType,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCut: () -> Unit,
    onAuto: () -> Unit,
    onSelectTransition: (TransitionType) -> Unit,
    onToggleAudioMixer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(72.dp)
            .background(SurfaceDark)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Recording controls
        if (!isRecording) {
            ControlButton(
                label = "REC",
                backgroundColor = ElectricRed,
                textColor = TextPrimary,
                onClick = onRecord
            )
        } else {
            ControlButton(
                label = "STOP",
                backgroundColor = SurfaceLight,
                textColor = ElectricRed,
                onClick = onStop,
                icon = Icons.Filled.Stop
            )
            if (isPaused) {
                ControlButton(
                    label = "RSM",
                    backgroundColor = SurfaceLight,
                    textColor = NeonGreen,
                    onClick = onResume,
                    icon = Icons.Filled.PlayArrow
                )
            } else {
                ControlButton(
                    label = "PSE",
                    backgroundColor = SurfaceLight,
                    textColor = TextSecondary,
                    onClick = onPause,
                    icon = Icons.Filled.Pause
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Transition buttons
        ControlButton(
            label = "CUT",
            backgroundColor = SurfaceLight,
            textColor = TextPrimary,
            onClick = onCut
        )
        ControlButton(
            label = "AUTO",
            backgroundColor = NeonGreen.copy(alpha = 0.2f),
            textColor = TextPrimary,
            onClick = onAuto
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Transition type pills
        TransitionType.entries.forEach { type ->
            TransitionPill(
                type = type,
                isSelected = type == selectedTransition,
                onClick = { onSelectTransition(type) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Audio mixer button
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(SurfaceLight)
                .clickable(onClick = onToggleAudioMixer)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = "Audio Mixer",
                tint = Primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
