package com.camhub.studio.ui.director

import android.app.Activity
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.camhub.studio.MainActivity
import com.camhub.studio.ui.audio.AudioMixerPanel
import com.camhub.studio.ui.director.components.CameraControlPanel
import com.camhub.studio.ui.director.components.CameraGrid
import com.camhub.studio.ui.director.components.ControlBar
import com.camhub.studio.ui.director.components.StatusBar
import com.camhub.studio.ui.director.components.ViewportPanel
import com.camhub.studio.ui.theme.BackgroundDark
import kotlinx.coroutines.launch

/**
 * Director screen composable that orchestrates the multi-camera switching interface.
 *
 * Portrait layout: StatusBar -> ViewportPanel (horizontal PVW+PGM) -> ControlBar -> CameraGrid
 * Landscape layout: StatusBar -> Row { ViewportPanel(vertical) | ControlBar(vertical) | CameraGrid }
 *
 * @param viewModel DirectorViewModel providing UI state and actions.
 * @param onNavigateToSettings Callback to navigate to the settings screen.
 * @param onNavigateBack Callback to navigate back.
 */
@Composable
fun DirectorScreen(
    viewModel: DirectorViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()

    // Keep screen on
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Volume key handler for scrolling camera grid
    DisposableEffect(Unit) {
        val activity = context as? MainActivity
        activity?.volumeKeyHandler = { keyCode ->
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    coroutineScope.launch {
                        val targetIndex = (gridState.firstVisibleItemIndex - 2).coerceAtLeast(0)
                        gridState.animateScrollToItem(targetIndex)
                    }
                    true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    coroutineScope.launch {
                        val targetIndex = (gridState.firstVisibleItemIndex + 2)
                            .coerceAtMost((uiState.cameras.size - 1).coerceAtLeast(0))
                        gridState.animateScrollToItem(targetIndex)
                    }
                    true
                }
                else -> false
            }
        }
        onDispose {
            activity?.volumeKeyHandler = null
        }
    }

    // Resolve PGM and PVW camera data
    val pgmCamera = uiState.cameras.getOrNull(uiState.pgmCameraIndex)
    val pvwCamera = uiState.cameras.getOrNull(uiState.pvwCameraIndex)

    val safeInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .windowInsetsPadding(safeInsets)
    ) {
        if (isLandscape) {
            // ---- LANDSCAPE LAYOUT ----
            Column(modifier = Modifier.fillMaxSize()) {
                // Status bar at top
                StatusBar(
                    bitrateKbps = uiState.bitrateKbps,
                    latencyMs = uiState.latencyMs,
                    timecode = uiState.timecode,
                    isRecording = uiState.isRecording,
                    isPaused = uiState.isPaused,
                    wifiStrength = uiState.wifiStrength,
                    batteryPercent = uiState.batteryPercent,
                    onNavigateToSettings = onNavigateToSettings
                )

                Row(modifier = Modifier.weight(1f)) {
                    // ViewportPanel (vertical stacking)
                    ViewportPanel(
                        pgmCameraName = pgmCamera?.name ?: "---",
                        pvwCameraName = pvwCamera?.name ?: "---",
                        pgmFps = pgmCamera?.fps ?: 0,
                        pvwFps = pvwCamera?.fps ?: 0,
                        pgmBitmap = pgmCamera?.previewBitmap,
                        pvwBitmap = pvwCamera?.previewBitmap,
                        isVertical = true,
                        modifier = Modifier
                            .weight(1.4f)
                            .fillMaxHeight()
                    )

                    // Control bar (vertical)
                    ControlBar(
                        isRecording = uiState.isRecording,
                        isPaused = uiState.isPaused,
                        selectedTransition = uiState.selectedTransition,
                        isVertical = true,
                        onRecord = { viewModel.toggleRecording() },
                        onStop = { viewModel.toggleRecording() },
                        onPause = { viewModel.pauseRecording() },
                        onResume = { viewModel.resumeRecording() },
                        onCut = { viewModel.executeCut() },
                        onAuto = { viewModel.executeAuto() },
                        onSelectTransition = { viewModel.selectTransition(it) },
                        onToggleAudioMixer = { viewModel.toggleAudioMixer() },
                        modifier = Modifier.fillMaxHeight()
                    )

                    // Camera grid
                    CameraGrid(
                        cameras = uiState.cameras,
                        onSelectPvw = { viewModel.selectPvw(it) },
                        onOpenCameraControl = { viewModel.showCameraControl(it) },
                        onDisconnect = { viewModel.disconnectCamera(it) },
                        gridState = gridState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        } else {
            // ---- PORTRAIT LAYOUT ----
            Column(modifier = Modifier.fillMaxSize()) {
                // Status bar
                StatusBar(
                    bitrateKbps = uiState.bitrateKbps,
                    latencyMs = uiState.latencyMs,
                    timecode = uiState.timecode,
                    isRecording = uiState.isRecording,
                    isPaused = uiState.isPaused,
                    wifiStrength = uiState.wifiStrength,
                    batteryPercent = uiState.batteryPercent,
                    onNavigateToSettings = onNavigateToSettings
                )

                // Viewport panel (horizontal PVW+PGM)
                ViewportPanel(
                    pgmCameraName = pgmCamera?.name ?: "---",
                    pvwCameraName = pvwCamera?.name ?: "---",
                    pgmFps = pgmCamera?.fps ?: 0,
                    pvwFps = pvwCamera?.fps ?: 0,
                    pgmBitmap = pgmCamera?.previewBitmap,
                    pvwBitmap = pvwCamera?.previewBitmap,
                    isVertical = false,
                    modifier = Modifier.fillMaxWidth()
                )

                // Control bar (horizontal)
                ControlBar(
                    isRecording = uiState.isRecording,
                    isPaused = uiState.isPaused,
                    selectedTransition = uiState.selectedTransition,
                    isVertical = false,
                    onRecord = { viewModel.toggleRecording() },
                    onStop = { viewModel.toggleRecording() },
                    onPause = { viewModel.pauseRecording() },
                    onResume = { viewModel.resumeRecording() },
                    onCut = { viewModel.executeCut() },
                    onAuto = { viewModel.executeAuto() },
                    onSelectTransition = { viewModel.selectTransition(it) },
                    onToggleAudioMixer = { viewModel.toggleAudioMixer() },
                    modifier = Modifier.fillMaxWidth()
                )

                // Camera grid
                CameraGrid(
                    cameras = uiState.cameras,
                    onSelectPvw = { viewModel.selectPvw(it) },
                    onOpenCameraControl = { viewModel.showCameraControl(it) },
                    onDisconnect = { viewModel.disconnectCamera(it) },
                    gridState = gridState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }

        // Audio mixer bottom sheet overlay
        if (uiState.showAudioMixer) {
            AudioMixerPanel(
                onDismiss = { viewModel.toggleAudioMixer() }
            )
        }

        // Camera control panel overlay
        if (uiState.showCameraControl) {
            val controlCamera = uiState.cameras.getOrNull(uiState.controlCameraIndex)
            CameraControlPanel(
                cameraName = controlCamera?.name ?: "Unknown",
                onDismiss = { viewModel.hideCameraControl() },
                onSendCommand = { command, value, stringValue ->
                    viewModel.sendCameraCommand(command, value, stringValue)
                }
            )
        }
    }
}
