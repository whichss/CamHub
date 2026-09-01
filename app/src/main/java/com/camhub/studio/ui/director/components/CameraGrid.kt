package com.camhub.studio.ui.director.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.director.model.CameraNode
import com.camhub.studio.ui.director.model.ConnectionStatus
import com.camhub.studio.data.network.VideoTransport
import com.camhub.studio.ui.theme.AmberYellow
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.ElectricRed
import com.camhub.studio.ui.theme.JetBrainsMonoFamily
import com.camhub.studio.ui.theme.NeonGreen
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.SurfaceLight
import com.camhub.studio.ui.theme.TextMuted
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary
import com.camhub.studio.ui.theme.TallyGreen
import com.camhub.studio.ui.theme.TallyRed

/**
 * Returns the status dot color for the given connection status.
 */
private fun statusDotColor(status: ConnectionStatus): Color {
    return when (status) {
        ConnectionStatus.LIVE -> NeonGreen
        ConnectionStatus.STANDBY -> AmberYellow
        ConnectionStatus.OFFLINE -> Color.Gray
    }
}

private fun audioStatusColor(status: String, level: Float): Color {
    return when (status) {
        "Live" -> if (level > 0.01f) NeonGreen else TextTertiary
        "Connecting", "Reconnecting" -> AmberYellow
        "Stale" -> ElectricRed
        else -> TextMuted
    }
}

private fun audioStatusLabel(status: String): String {
    return when (status) {
        "Live" -> "A:LIVE"
        "Connecting" -> "A:CONN"
        "Reconnecting" -> "A:RECON"
        "Stale" -> "A:STALE"
        "No Audio" -> "A:NO"
        else -> "A:OFF"
    }
}

private fun streamQualityColor(camera: CameraNode): Color {
    val healthLatencyMs = if (camera.latencySampleCount > 0) {
        camera.latencyP95Ms
    } else {
        camera.latencyMs
    }
    return when {
        camera.videoTransport == VideoTransport.UDP_RTP && camera.udpPacketLossPercent >= 5f -> ElectricRed
        healthLatencyMs >= 180 -> ElectricRed
        camera.droppedFrames >= 30 -> ElectricRed
        camera.videoTransport == VideoTransport.UDP_RTP && camera.udpPacketLossPercent >= 1f -> AmberYellow
        healthLatencyMs >= 90 -> AmberYellow
        camera.droppedFrames > 0 -> AmberYellow
        healthLatencyMs > 0 -> NeonGreen
        else -> ElectricRed
    }
}

private fun streamQualityLabel(camera: CameraNode): String {
    val resolution = when {
        camera.frameWidth > 0 && camera.frameHeight > 0 ->
            "${camera.frameWidth}×${camera.frameHeight}"
        camera.frameWidth > 0 -> "${camera.frameWidth}w"
        else -> "--"
    }
    val latency = if (!camera.isClockSynchronized || camera.latencySampleCount <= 0) {
        "SYNC"
    } else {
        "P95 ${camera.latencyP95Ms}ms"
    }
    val drops = if (camera.droppedFrames > 0) " · D${camera.droppedFrames}" else ""
    val loss = if (
        camera.videoTransport == VideoTransport.UDP_RTP &&
        camera.udpPacketsReceived > 0
    ) {
        val lossTenths = (camera.udpPacketLossPercent * 10f).toInt().coerceAtLeast(0)
        " · LOSS ${lossTenths / 10}.${lossTenths % 10}%"
    } else ""
    val fallback = if (camera.transportFallbackReason.isNotBlank()) " FALLBACK" else ""
    val fps = if (camera.ingressFps > 0 && camera.ingressFps != camera.fps) {
        "${camera.fps}/${camera.ingressFps}fps"
    } else {
        "${camera.fps}fps"
    }
    return "${camera.videoTransport.displayLabel}$fallback · $resolution · $fps · $latency$drops$loss"
}

/**
 * A single camera card showing preview, status, tally, and metadata.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CameraCard(
    camera: CameraNode,
    index: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDisconnect: () -> Unit,
    onFrameDrawn: (String, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    TallyBorder(
        isPgm = camera.isPgm,
        isPvw = camera.isPvw,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .background(SurfaceDark, RoundedCornerShape(8.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
        ) {
            // Preview area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(BackgroundDarker)
                    .drawWithContent {
                        drawContent()
                        val sequence = camera.frameSequence
                        if (sequence > 0L) {
                            onFrameDrawn(camera.name, sequence)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val previewBitmap = camera.previewBitmap
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap,
                        contentDescription = "${camera.name} preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = "NO SIGNAL",
                        color = TextMuted,
                        fontSize = 8.sp,
                        fontFamily = JetBrainsMonoFamily,
                        letterSpacing = 1.sp
                    )
                }

                if (camera.status != ConnectionStatus.LIVE) {
                    val recoveryLabel = when (camera.status) {
                        ConnectionStatus.STANDBY -> "RECONNECTING · LAST FRAME"
                        ConnectionStatus.OFFLINE -> "OFFLINE · LAST FRAME"
                        ConnectionStatus.LIVE -> ""
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(BackgroundDarker.copy(alpha = 0.52f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = recoveryLabel,
                            color = if (camera.status == ConnectionStatus.STANDBY) {
                                AmberYellow
                            } else {
                                ElectricRed
                            },
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = JetBrainsMonoFamily,
                            letterSpacing = 0.8.sp
                        )
                    }
                }

                if (camera.status != ConnectionStatus.OFFLINE) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .background(BackgroundDarker.copy(alpha = 0.86f), RoundedCornerShape(4.dp))
                            .border(1.dp, streamQualityColor(camera).copy(alpha = 0.28f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = streamQualityLabel(camera),
                            color = streamQualityColor(camera),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = JetBrainsMonoFamily
                        )
                    }
                }

                // PGM/PVW tally label overlay
                if (camera.isPgm || camera.isPvw) {
                    val tallyLabel = if (camera.isPgm) "PGM" else "PVW"
                    val tallyColor = if (camera.isPgm) TallyRed else TallyGreen
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .background(tallyColor.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = tallyLabel,
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = JetBrainsMonoFamily
                        )
                    }
                }
            }

            // Info bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark)
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status dot
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusDotColor(camera.status))
                )

                Spacer(modifier = Modifier.width(4.dp))

                // Camera name (monospace 9sp)
                Text(
                    text = camera.name,
                    color = TextPrimary,
                    fontSize = 9.sp,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = audioStatusLabel(camera.audioStatus),
                    color = audioStatusColor(camera.audioStatus, camera.audioLevel),
                    fontSize = 8.sp,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.width(4.dp))

                // Bitrate / FPS info
                Text(
                    text = if (camera.adaptiveBitrateTargetMbps > 0) {
                        "${camera.bitrateKbps}k→${camera.adaptiveBitrateTargetMbps}M"
                    } else {
                        "${camera.bitrateKbps}k"
                    },
                    color = TextTertiary,
                    fontSize = 8.sp,
                    fontFamily = JetBrainsMonoFamily
                )

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = "${camera.fps}fps",
                    color = TextTertiary,
                    fontSize = 8.sp,
                    fontFamily = JetBrainsMonoFamily
                )

                Spacer(modifier = Modifier.width(2.dp))

                // Disconnect button
                IconButton(
                    onClick = onDisconnect,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.LinkOff,
                        contentDescription = "Disconnect ${camera.name}",
                        tint = ElectricRed.copy(alpha = 0.6f),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

/**
 * Grid of connected camera nodes displayed in a 2-column layout.
 *
 * @param cameras List of camera nodes to display.
 * @param onSelectPvw Callback when a camera is tapped (select as PVW).
 * @param onOpenCameraControl Callback when a camera is long-pressed (open camera control).
 * @param onDisconnect Callback when the disconnect button is pressed.
 * @param gridState LazyGridState for external scroll control (e.g. volume keys).
 */
@Composable
fun CameraGrid(
    cameras: List<CameraNode>,
    onSelectPvw: (Int) -> Unit,
    onOpenCameraControl: (Int) -> Unit,
    onDisconnect: (Int) -> Unit,
    onFrameDrawn: (String, Long) -> Unit,
    gridState: LazyGridState = rememberLazyGridState(),
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = modifier.padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(
            items = cameras,
            key = { _, camera -> camera.id }
        ) { index, camera ->
            CameraCard(
                camera = camera,
                index = index,
                onClick = { onSelectPvw(index) },
                onLongClick = { onOpenCameraControl(index) },
                onDisconnect = { onDisconnect(index) },
                onFrameDrawn = onFrameDrawn
            )
        }
    }
}
