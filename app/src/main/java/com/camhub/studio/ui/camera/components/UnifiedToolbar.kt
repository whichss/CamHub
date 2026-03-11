package com.camhub.studio.ui.camera.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ShutterSpeed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.camera.model.MicDirection
import com.camhub.studio.ui.camera.model.ToolMode
import com.camhub.studio.ui.components.HorizontalControlSlider
import com.camhub.studio.ui.theme.AmberYellow
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.CyanAccent
import com.camhub.studio.ui.theme.NeonGreen
import com.camhub.studio.ui.theme.Primary
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.SurfaceLight
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

@Composable
fun UnifiedToolbar(
    activeToolMode: ToolMode,
    onToggleTool: (ToolMode) -> Unit,
    onResetAuto: () -> Unit,
    // Zoom
    zoomRatio: Float,
    minZoomRatio: Float,
    maxZoomRatio: Float,
    zoomSteps: List<String>,
    selectedZoomIndex: Int,
    onZoomChanged: (Float) -> Unit,
    // ISO
    isoValues: List<String>,
    selectedIsoIndex: Int,
    onIsoChanged: (Int) -> Unit,
    // Shutter
    shutterValues: List<String>,
    selectedShutterIndex: Int,
    onShutterChanged: (Int) -> Unit,
    // Focus
    focusDistances: List<String>,
    selectedFocusIndex: Int,
    onFocusChanged: (Int) -> Unit,
    isPeakingEnabled: Boolean = false,
    onTogglePeaking: (() -> Unit)? = null,
    // White Balance
    whiteBalanceValues: List<String>,
    selectedWhiteBalanceIndex: Int,
    onWhiteBalanceChanged: (Int) -> Unit,
    // Mic
    micDirection: MicDirection,
    onMicDirectionChanged: (MicDirection) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BackgroundDarker.copy(alpha = 0.8f))
    ) {
        // Slider area (animated show/hide)
        val showSlider = activeToolMode != ToolMode.NONE && activeToolMode != ToolMode.MIC
        AnimatedVisibility(
            visible = showSlider,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                when (activeToolMode) {
                    ToolMode.ZOOM -> {
                        val normalized = if (maxZoomRatio > minZoomRatio) {
                            (zoomRatio - minZoomRatio) / (maxZoomRatio - minZoomRatio)
                        } else 0f
                        HorizontalControlSlider(
                            values = zoomSteps,
                            selectedIndex = selectedZoomIndex,
                            onIndexChanged = {},
                            continuousValue = normalized,
                            onContinuousValueChanged = { fraction ->
                                val ratio = minZoomRatio + fraction * (maxZoomRatio - minZoomRatio)
                                onZoomChanged(ratio)
                            }
                        )
                    }
                    ToolMode.ISO -> HorizontalControlSlider(
                        values = isoValues,
                        selectedIndex = selectedIsoIndex,
                        onIndexChanged = onIsoChanged
                    )
                    ToolMode.SHUTTER -> HorizontalControlSlider(
                        values = shutterValues,
                        selectedIndex = selectedShutterIndex,
                        onIndexChanged = onShutterChanged
                    )
                    ToolMode.FOCUS -> Column {
                        HorizontalControlSlider(
                            values = focusDistances,
                            selectedIndex = selectedFocusIndex,
                            onIndexChanged = onFocusChanged
                        )
                        if (onTogglePeaking != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                val peakShape = RoundedCornerShape(6.dp)
                                Text(
                                    text = "PEAK",
                                    color = if (isPeakingEnabled) CyanAccent else TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(peakShape)
                                        .background(
                                            if (isPeakingEnabled) CyanAccent.copy(alpha = 0.15f) else SurfaceDark,
                                            peakShape
                                        )
                                        .clickable { onTogglePeaking() }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    ToolMode.WHITE_BALANCE -> HorizontalControlSlider(
                        values = whiteBalanceValues,
                        selectedIndex = selectedWhiteBalanceIndex,
                        onIndexChanged = onWhiteBalanceChanged
                    )
                    else -> {}
                }
            }
        }

        // Mic direction picker (animated show/hide)
        AnimatedVisibility(
            visible = activeToolMode == ToolMode.MIC,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MicDirection.entries.forEach { dir ->
                    val isSelected = dir == micDirection
                    val label = when (dir) {
                        MicDirection.FRONT -> "전면"
                        MicDirection.BACK -> "후면"
                        MicDirection.EXTERNAL -> "외부"
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) CyanAccent.copy(alpha = 0.15f)
                                else SurfaceDark,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onMicDirectionChanged(dir) }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) CyanAccent else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        // Tool tabs row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // AUTO button
            ToolTab(
                icon = Icons.Filled.AutoMode,
                label = "AUTO",
                isActive = false,
                activeColor = NeonGreen,
                onClick = onResetAuto
            )

            ToolTab(
                icon = Icons.Filled.ZoomIn,
                label = "줌",
                isActive = activeToolMode == ToolMode.ZOOM,
                onClick = { onToggleTool(ToolMode.ZOOM) }
            )
            ToolTab(
                icon = Icons.Filled.CameraAlt,
                label = "ISO",
                isActive = activeToolMode == ToolMode.ISO,
                onClick = { onToggleTool(ToolMode.ISO) }
            )
            ToolTab(
                icon = Icons.Filled.ShutterSpeed,
                label = "셔터",
                isActive = activeToolMode == ToolMode.SHUTTER,
                onClick = { onToggleTool(ToolMode.SHUTTER) }
            )
            ToolTab(
                icon = Icons.Filled.CenterFocusStrong,
                label = "포커스",
                isActive = activeToolMode == ToolMode.FOCUS,
                onClick = { onToggleTool(ToolMode.FOCUS) }
            )
            ToolTab(
                icon = Icons.Filled.Thermostat,
                label = "색온도",
                isActive = activeToolMode == ToolMode.WHITE_BALANCE,
                onClick = { onToggleTool(ToolMode.WHITE_BALANCE) }
            )
            ToolTab(
                icon = Icons.Filled.Mic,
                label = "마이크",
                isActive = activeToolMode == ToolMode.MIC,
                activeColor = AmberYellow,
                onClick = { onToggleTool(ToolMode.MIC) }
            )
        }
    }
}

@Composable
private fun ToolTab(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color = Primary,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) activeColor.copy(alpha = 0.15f) else SurfaceLight.copy(alpha = 0.3f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) activeColor else TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = label,
            color = if (isActive) activeColor else TextTertiary,
            fontSize = 8.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
        )
    }
}
