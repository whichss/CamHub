package com.camhub.studio.ui.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.camhub.studio.ui.audio.components.ChannelStrip
import com.camhub.studio.ui.audio.components.MasterStrip
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.GlassBorder
import com.camhub.studio.ui.theme.SpaceGroteskFamily
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.SurfaceLight
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

/**
 * Bottom sheet overlay for the audio mixer panel.
 *
 * Displays horizontally scrollable channel strips for each connected camera audio source
 * plus a master fader strip. Presented over a dimmed background with a drag handle.
 *
 * @param onDismiss Callback to close the mixer panel.
 * @param viewModel AudioMixerViewModel instance (injected via Hilt).
 */
@Composable
fun AudioMixerPanel(
    onDismiss: () -> Unit,
    viewModel: AudioMixerViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    // Dim background overlay
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDarker.copy(alpha = 0.6f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Bottom sheet panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(SurfaceDark)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { /* consume click */ }
                )
                .padding(top = 8.dp)
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(GlassBorder)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Header: title + close button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Audio Mixer",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = SpaceGroteskFamily
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(SurfaceLight)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close audio mixer",
                        tint = TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Channel strips + Master strip (horizontally scrollable)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Individual channel strips
                uiState.channels.forEach { channel ->
                    ChannelStrip(
                        channelName = channel.label,
                        level = channel.level,
                        faderValue = channel.faderValue,
                        isAfv = channel.isAfv,
                        syncOffsetMs = channel.syncOffsetMs,
                        onFaderChange = { value ->
                            viewModel.updateChannelFader(channel.id, value)
                        },
                        onToggleAfv = { viewModel.toggleAfv(channel.id) }
                    )
                }

                // Separator
                if (uiState.channels.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(280.dp)
                            .background(GlassBorder)
                    )
                }

                // Master strip
                MasterStrip(
                    level = uiState.masterLevel,
                    faderValue = uiState.masterFaderValue,
                    dbValue = uiState.masterDbValue,
                    isMuted = uiState.isMasterMuted,
                    onFaderChange = { value ->
                        viewModel.updateMasterFader(value)
                    },
                    onToggleMute = { viewModel.toggleMasterMute() }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
