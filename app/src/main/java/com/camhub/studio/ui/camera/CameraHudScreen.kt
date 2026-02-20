package com.camhub.studio.ui.camera

import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CameraFront
import androidx.compose.material.icons.filled.CameraRear
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.camhub.studio.ui.camera.components.AudioMetersPanel
import com.camhub.studio.ui.camera.components.CameraStatusBar
import com.camhub.studio.ui.camera.components.ExposurePanel
import com.camhub.studio.ui.camera.components.FocusPanel
import com.camhub.studio.ui.camera.components.ViewfinderOverlay
import com.camhub.studio.ui.camera.components.ZoomControl
import com.camhub.studio.ui.components.StatusChip
import com.camhub.studio.ui.theme.AmberYellow
import com.camhub.studio.ui.theme.BackgroundDark
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.CyanAccent
import com.camhub.studio.ui.theme.ElectricRed
import com.camhub.studio.ui.theme.NeonGreen
import com.camhub.studio.ui.theme.Primary
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.TextMuted
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraHudScreen(
    viewModel: CameraHudViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

    // Keep screen on
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            viewModel.unbindCamera()
        }
    }

    // ScaleGestureDetector for pinch zoom
    val scaleGestureDetector = remember {
        android.view.ScaleGestureDetector(
            context,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    viewModel.onPinchZoom(detector.scaleFactor)
                    return true
                }
            }
        )
    }

    CameraPermissionHandler(
        onPermissionsGranted = {
            // Retry audio capture now that RECORD_AUDIO is granted
            viewModel.ensureAudioCapture()

            if (isLandscape) {
                LandscapeLayout(
                    uiState = uiState,
                    viewModel = viewModel,
                    lifecycleOwner = lifecycleOwner,
                    scaleGestureDetector = scaleGestureDetector
                )
            } else {
                PortraitLayout(
                    uiState = uiState,
                    viewModel = viewModel,
                    lifecycleOwner = lifecycleOwner,
                    scaleGestureDetector = scaleGestureDetector
                )
            }
        },
        onPermissionsDenied = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BackgroundDarker),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Camera & Audio permissions required",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Please grant permissions in Settings",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        }
    )
}

// ──────────────────────────────────────────────────────────────────
// PORTRAIT LAYOUT
// ──────────────────────────────────────────────────────────────────

@ExperimentalCamera2Interop
@Composable
private fun PortraitLayout(
    uiState: com.camhub.studio.ui.camera.model.CameraUiState,
    viewModel: CameraHudViewModel,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    scaleGestureDetector: android.view.ScaleGestureDetector
) {
    val safeInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDarker)
            .windowInsetsPadding(safeInsets)
    ) {
        // 1. Timecode bar
        TimecodeBar(
            timecode = uiState.timecode,
            isRecording = uiState.isRecording,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp)
        )

        // 2. Camera preview — 9:16 (full) or 16:9 (compact), GL renderer handles aspect ratio
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (uiState.isPortraitFullPreview) Modifier.weight(1f)
                    else Modifier.aspectRatio(16f / 9f)
                )
                .clip(RoundedCornerShape(4.dp))
        ) {
            CameraPreview(
                viewModel = viewModel,
                lifecycleOwner = lifecycleOwner,
                scaleGestureDetector = scaleGestureDetector,
                onTapToFocus = { x, y, w, h ->
                    viewModel.tapToFocus(x, y, w, h)
                },
                modifier = Modifier.fillMaxSize()
            )
            ViewfinderOverlay(
                isPgm = uiState.isPgm,
                focusPointX = uiState.focusPointX,
                focusPointY = uiState.focusPointY,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 3. Control panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (!uiState.isPortraitFullPreview) Modifier.weight(1f)
                    else Modifier
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Record button + Audio meters + Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RecordButton(
                    isRecording = uiState.isRecording,
                    onClick = { viewModel.toggleRecording() }
                )
                AudioMetersPanel(
                    levels = uiState.audioLevels,
                    modifier = Modifier.weight(1f)
                )
                // Compact status info
                CameraStatusBar(
                    bitrate = uiState.bitrate,
                    wifiStrength = uiState.wifiStrength,
                    storageUsedGb = uiState.storageUsedGb,
                    storageTotalGb = uiState.storageTotalGb
                )
            }

            // Camera params bar
            CameraParamsBar(uiState = uiState)

            // Tool icons row + aspect ratio toggle
            ToolIconsRow(
                isFrontCamera = uiState.isFrontCamera,
                showExposure = uiState.showExposurePanel,
                showFocus = uiState.showFocusPanel,
                isPortraitFull = uiState.isPortraitFullPreview,
                onSwitchCamera = { viewModel.switchCamera() },
                onToggleExposure = { viewModel.toggleExposurePanel() },
                onToggleFocus = { viewModel.toggleFocusPanel() },
                onToggleAspect = { viewModel.togglePreviewAspect() }
            )

            // Exposure panel (animated show/hide)
            AnimatedVisibility(
                visible = uiState.showExposurePanel,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                ExposurePanel(
                    isoValues = uiState.isoValues,
                    selectedIsoIndex = uiState.selectedIsoIndex,
                    onIsoChanged = { viewModel.updateIso(it) },
                    shutterValues = uiState.shutterValues,
                    selectedShutterIndex = uiState.selectedShutterIndex,
                    onShutterChanged = { viewModel.updateShutter(it) }
                )
            }

            // Focus panel (animated show/hide)
            AnimatedVisibility(
                visible = uiState.showFocusPanel,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                FocusPanel(
                    focusDistances = uiState.focusDistances,
                    selectedFocusIndex = uiState.selectedFocusIndex,
                    onFocusChanged = { viewModel.updateFocus(it) },
                    isPeakingEnabled = uiState.isPeakingEnabled,
                    onTogglePeaking = { viewModel.togglePeaking() }
                )
            }

            // Zoom control
            if (uiState.zoomSteps.size > 1) {
                ZoomControl(
                    zoomSteps = uiState.zoomSteps,
                    selectedZoomIndex = uiState.selectedZoomIndex,
                    onZoomIndexChanged = { viewModel.setZoomByIndex(it) }
                )
            }

            // In 16:9 mode, push remaining space down
            if (!uiState.isPortraitFullPreview) {
                Spacer(modifier = Modifier.weight(1f))
                CameraStatusBar(
                    bitrate = uiState.bitrate,
                    wifiStrength = uiState.wifiStrength,
                    storageUsedGb = uiState.storageUsedGb,
                    storageTotalGb = uiState.storageTotalGb,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// LANDSCAPE LAYOUT — Blackmagic-style full-screen overlay
// ──────────────────────────────────────────────────────────────────

@ExperimentalCamera2Interop
@Composable
private fun LandscapeLayout(
    uiState: com.camhub.studio.ui.camera.model.CameraUiState,
    viewModel: CameraHudViewModel,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    scaleGestureDetector: android.view.ScaleGestureDetector
) {
    val safeInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDarker)
    ) {
        // 1. Full-screen camera preview
        CameraPreview(
            viewModel = viewModel,
            lifecycleOwner = lifecycleOwner,
            scaleGestureDetector = scaleGestureDetector,
            onTapToFocus = { x, y, w, h ->
                viewModel.tapToFocus(x, y, w, h)
            },
            modifier = Modifier.fillMaxSize()
        )
        ViewfinderOverlay(
            isPgm = uiState.isPgm,
            focusPointX = uiState.focusPointX,
            focusPointY = uiState.focusPointY,
            modifier = Modifier.fillMaxSize()
        )

        // All HUD overlays respect safe insets
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(safeInsets)
        ) {
            // 2. Left side: camera info labels
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                InfoPill(value = "30", label = "FPS")
                InfoPill(value = "FHD", label = "RES")
                InfoPill(value = "H.264", label = "")
            }

            // 3. Left bottom: status info
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // LIVE/OFF
                Text(
                    text = uiState.bitrate,
                    color = if (uiState.bitrate == "LIVE") NeonGreen else TextTertiary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(BackgroundDark.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                // Storage
                val storagePct = if (uiState.storageTotalGb > 0f)
                    ((uiState.storageUsedGb / uiState.storageTotalGb) * 100).toInt() else 0
                Text(
                    text = "${String.format("%.0f", uiState.storageTotalGb)}GB ${storagePct}%",
                    color = NeonGreen,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(BackgroundDark.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                // Battery
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(BackgroundDark.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BatteryFull,
                        contentDescription = "Battery",
                        tint = when {
                            uiState.batteryPercent <= 15 -> ElectricRed
                            uiState.batteryPercent <= 30 -> AmberYellow
                            else -> NeonGreen
                        },
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "${uiState.batteryPercent}%",
                        color = TextSecondary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // 4. Right side: circular control buttons
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Camera switch
                HudCircleButton(
                    icon = if (uiState.isFrontCamera) Icons.Default.CameraFront else Icons.Default.CameraRear,
                    label = "",
                    onClick = { viewModel.switchCamera() }
                )

                // Focus / AF
                HudCircleButton(
                    icon = Icons.Default.CenterFocusStrong,
                    label = uiState.focusDistances.getOrElse(uiState.selectedFocusIndex) { "AF" },
                    isActive = uiState.showFocusPanel,
                    activeColor = CyanAccent,
                    onClick = { viewModel.toggleFocusPanel() }
                )

                // Exposure
                HudCircleButton(
                    icon = Icons.Default.Exposure,
                    label = uiState.isoValues.getOrElse(uiState.selectedIsoIndex) { "--" },
                    isActive = uiState.showExposurePanel,
                    activeColor = Primary,
                    onClick = { viewModel.toggleExposurePanel() }
                )

                // Shutter
                val shutterVal = uiState.shutterValues.getOrElse(uiState.selectedShutterIndex) { "--" }
                HudCircleButton(
                    icon = null,
                    label = shutterVal,
                    customText = "SHTR",
                    onClick = { viewModel.toggleExposurePanel() }
                )

                // Zoom
                HudCircleButton(
                    icon = null,
                    label = "${String.format("%.1f", uiState.zoomRatio)}x",
                    customText = "ZOOM",
                    onClick = { /* zoom control via pinch */ }
                )
            }

            // 5. Bottom center: Record button + timecode
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Timecode above record button
                Text(
                    text = uiState.timecode,
                    color = if (uiState.isRecording) ElectricRed else TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(BackgroundDark.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
                // Big record button
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(SurfaceDark.copy(alpha = 0.5f), CircleShape)
                        .border(2.5.dp, TextMuted.copy(alpha = 0.7f), CircleShape)
                        .clickable { viewModel.toggleRecording() },
                    contentAlignment = Alignment.Center
                ) {
                    if (uiState.isRecording) {
                        // Stop square
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(ElectricRed, RoundedCornerShape(4.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(ElectricRed, CircleShape)
                        )
                    }
                }
            }

            // 6. Bottom right: compact audio meters
            AudioMetersPanel(
                levels = uiState.audioLevels,
                compact = true,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 12.dp)
                    .width(100.dp)
            )

            // 7. Remote override indicator
            if (uiState.isRemoteOverride) {
                Text(
                    text = "REMOTE",
                    color = AmberYellow,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp)
                        .background(AmberYellow.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            // 8. Exposure/Focus panels overlay (center-left)
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 60.dp)
                    .width(240.dp)
            ) {
                AnimatedVisibility(
                    visible = uiState.showExposurePanel,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    ExposurePanel(
                        isoValues = uiState.isoValues,
                        selectedIsoIndex = uiState.selectedIsoIndex,
                        onIsoChanged = { viewModel.updateIso(it) },
                        shutterValues = uiState.shutterValues,
                        selectedShutterIndex = uiState.selectedShutterIndex,
                        onShutterChanged = { viewModel.updateShutter(it) }
                    )
                }
                AnimatedVisibility(
                    visible = uiState.showFocusPanel,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    FocusPanel(
                        focusDistances = uiState.focusDistances,
                        selectedFocusIndex = uiState.selectedFocusIndex,
                        onFocusChanged = { viewModel.updateFocus(it) },
                        isPeakingEnabled = uiState.isPeakingEnabled,
                        onTogglePeaking = { viewModel.togglePeaking() }
                    )
                }
            }
        }
    }
}

/** Info pill label used on the left side of landscape HUD */
@Composable
private fun InfoPill(
    value: String,
    label: String
) {
    Row(
        modifier = Modifier
            .background(BackgroundDark.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        if (label.isNotEmpty()) {
            Text(
                text = label,
                color = TextTertiary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/** Circular HUD button used on the right side of landscape layout */
@Composable
private fun HudCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    label: String,
    onClick: () -> Unit,
    isActive: Boolean = false,
    activeColor: androidx.compose.ui.graphics.Color = Primary,
    customText: String? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) activeColor.copy(alpha = 0.2f)
                    else BackgroundDark.copy(alpha = 0.7f),
                    CircleShape
                )
                .then(
                    if (isActive) Modifier.border(1.5.dp, activeColor, CircleShape)
                    else Modifier
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isActive) activeColor else TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            } else if (customText != null) {
                Text(
                    text = customText,
                    color = TextSecondary,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        if (label.isNotEmpty()) {
            Text(
                text = label,
                color = if (isActive) activeColor else TextSecondary,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// SHARED COMPONENTS
// ──────────────────────────────────────────────────────────────────

@ExperimentalCamera2Interop
@Composable
private fun CameraPreview(
    viewModel: CameraHudViewModel,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    scaleGestureDetector: android.view.ScaleGestureDetector,
    onTapToFocus: (Float, Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        val surface = Surface(surfaceTexture)
                        viewModel.onViewfinderSurfaceReady(
                            lifecycleOwner,
                            surface,
                            Size(width, height)
                        )
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        viewModel.onViewfinderSurfaceDestroyed()
                        val surface = Surface(surfaceTexture)
                        viewModel.onViewfinderSurfaceReady(
                            lifecycleOwner,
                            surface,
                            Size(width, height)
                        )
                    }

                    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                        viewModel.onViewfinderSurfaceDestroyed()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                        /* no-op */ }
                }

                // Handle touch events for pinch zoom and tap-to-focus
                setOnTouchListener { v, event ->
                    scaleGestureDetector.onTouchEvent(event)
                    if (event.action == android.view.MotionEvent.ACTION_UP &&
                        !scaleGestureDetector.isInProgress
                    ) {
                        onTapToFocus(event.x, event.y, v.width.toFloat(), v.height.toFloat())
                    }
                    true
                }
            }
        },
        modifier = modifier
    )
}

@Composable
private fun TimecodeBar(
    timecode: String,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(ElectricRed, CircleShape)
                )
            }
            Text(
                text = timecode,
                color = if (isRecording) ElectricRed else TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun RecordButton(
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(SurfaceDark, CircleShape)
            .border(2.dp, TextMuted, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isRecording) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = "Stop Recording",
                tint = ElectricRed,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(ElectricRed, CircleShape)
            )
        }
    }
}

@Composable
private fun CameraParamsBar(
    uiState: com.camhub.studio.ui.camera.model.CameraUiState,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(BackgroundDark.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Lens info
        ParamLabel(
            label = uiState.lens.model.ifEmpty { "LENS" },
            value = if (uiState.lens.focalLength.isNotEmpty()) "${uiState.lens.focalLength}mm" else "--"
        )

        // FPS
        ParamLabel(label = "FPS", value = "30")

        // Shutter with auto badge
        Row(verticalAlignment = Alignment.CenterVertically) {
            ParamLabel(
                label = "SHTR",
                value = uiState.shutterValues.getOrElse(uiState.selectedShutterIndex) { "--" }
            )
            if (!uiState.isManualExposureSupported) {
                AutoBadge()
            }
        }

        // Timecode (compact)
        Text(
            text = uiState.timecode,
            color = if (uiState.isRecording) ElectricRed else TextSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )

        // ISO with auto badge
        Row(verticalAlignment = Alignment.CenterVertically) {
            ParamLabel(
                label = "ISO",
                value = uiState.isoValues.getOrElse(uiState.selectedIsoIndex) { "--" }
            )
            if (!uiState.isManualExposureSupported) {
                AutoBadge()
            }
        }

        // WB
        ParamLabel(label = "WB", value = "AWB")

        // Format badge
        StatusChip(
            label = uiState.format.ifEmpty { "--" },
            color = Primary
        )

        // Battery
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.BatteryFull,
                contentDescription = "Battery",
                tint = when {
                    uiState.batteryPercent <= 15 -> ElectricRed
                    uiState.batteryPercent <= 30 -> AmberYellow
                    else -> NeonGreen
                },
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "${uiState.batteryPercent}%",
                color = TextSecondary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun ParamLabel(
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = TextTertiary,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun AutoBadge() {
    Text(
        text = "A",
        color = AmberYellow,
        fontSize = 8.sp,
        fontWeight = FontWeight.ExtraBold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .padding(start = 2.dp)
            .background(AmberYellow.copy(alpha = 0.15f), RoundedCornerShape(2.dp))
            .padding(horizontal = 3.dp, vertical = 1.dp)
    )
}

@ExperimentalCamera2Interop
@Composable
private fun ToolIconsRow(
    isFrontCamera: Boolean,
    showExposure: Boolean,
    showFocus: Boolean,
    isPortraitFull: Boolean = true,
    onSwitchCamera: () -> Unit,
    onToggleExposure: () -> Unit,
    onToggleFocus: () -> Unit,
    onToggleAspect: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Switch camera
        Icon(
            imageVector = if (isFrontCamera) Icons.Default.CameraFront else Icons.Default.CameraRear,
            contentDescription = "Switch Camera",
            tint = TextSecondary,
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .clickable { onSwitchCamera() }
        )

        // Exposure toggle
        Icon(
            imageVector = Icons.Default.Exposure,
            contentDescription = "Exposure",
            tint = if (showExposure) Primary else TextSecondary,
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .clickable { onToggleExposure() }
        )

        // Focus toggle
        Icon(
            imageVector = Icons.Default.CenterFocusStrong,
            contentDescription = "Focus",
            tint = if (showFocus) CyanAccent else TextSecondary,
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .clickable { onToggleFocus() }
        )

        // Aspect ratio toggle (portrait only)
        if (onToggleAspect != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (!isPortraitFull) Primary.copy(alpha = 0.15f)
                        else BackgroundDark.copy(alpha = 0.5f),
                        RoundedCornerShape(4.dp)
                    )
                    .clickable { onToggleAspect() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (isPortraitFull) "9:16" else "16:9",
                    color = if (!isPortraitFull) Primary else TextSecondary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
