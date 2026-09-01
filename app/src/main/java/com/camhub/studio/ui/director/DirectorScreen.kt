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
import androidx.compose.foundation.layout.navigationBars
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
import com.camhub.studio.ui.director.components.DeviceManagerPanel
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
    val hubProfileLabel = buildString {
        append(
            uiState.runtimeRecommendation?.shortLabel
                ?: uiState.hubCapabilityReport?.profile?.shortLabel
                ?: "CHECKING"
        )
        if (uiState.isAutomaticHubProfile) append(" · AUTO")
        if (uiState.isRecordingSpatialUpscaling) {
            append(" · REC↑${uiState.recordingOutputHeight}")
        } else if (uiState.isPgmSpatialUpscalingEnabled) {
            append(" · UP${uiState.pgmOutputHeight}")
        }
    }

    val safeInsets = WindowInsets.statusBars
        .union(WindowInsets.navigationBars)
        .union(WindowInsets.displayCutout)

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
                    latencyMs = uiState.latencyP95Ms,
                    timecode = uiState.timecode,
                    isRecording = uiState.isRecording,
                    isPaused = uiState.isPaused,
                    wifiStrength = uiState.wifiStrength,
                    batteryPercent = uiState.batteryPercent,
                    audioMasterLevel = uiState.audioMasterLevel,
                    connectedCameraCount = uiState.cameras.size,
                    hubProfileLabel = hubProfileLabel,
                    networkTransportLabel = uiState.networkTransportLabel,
                    onNavigateToSettings = onNavigateToSettings,
                    onToggleDeviceManager = { viewModel.toggleDeviceManager() }
                )

                Row(modifier = Modifier.weight(1f)) {
                    // Source multiview on the left: select a camera into PVW.
                    CameraGrid(
                        cameras = uiState.cameras,
                        onSelectPvw = { viewModel.selectPvw(it) },
                        onOpenCameraControl = { viewModel.showCameraControl(it) },
                        onDisconnect = { viewModel.disconnectCamera(it) },
                        onFrameDrawn = { cameraName, frameSequence ->
                            viewModel.onFrameDrawn(cameraName, frameSequence)
                        },
                        gridState = gridState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )

                    // PGM/PVW in the center, where framing and transitions are monitored.
                    ViewportPanel(
                        pgmCameraName = pgmCamera?.name ?: "---",
                        pvwCameraName = pvwCamera?.name ?: "---",
                        pgmFps = pgmCamera?.fps ?: 0,
                        pvwFps = pvwCamera?.fps ?: 0,
                        pgmBitmap = pgmCamera?.previewBitmap,
                        pgmSourceBitmap = pgmCamera?.previewSourceBitmap,
                        pvwBitmap = pvwCamera?.previewBitmap,
                        enablePgmSpatialUpscaling = uiState.isPgmSpatialUpscalingEnabled,
                        pgmSpatialUpscaleOutputHeight = uiState.pgmOutputHeight,
                        pgmFrameSequence = pgmCamera?.frameSequence ?: 0L,
                        pvwFrameSequence = pvwCamera?.frameSequence ?: 0L,
                        onFrameDrawn = { cameraName, frameSequence ->
                            viewModel.onFrameDrawn(cameraName, frameSequence)
                        },
                        isVertical = true,
                        transitionProgress = uiState.transitionProgress,
                        isTransitioning = uiState.isTransitioning,
                        selectedTransition = uiState.selectedTransition,
                        pgmPtzState = pgmCamera?.ptzState
                            ?: com.camhub.studio.data.ptz.HybridPtzState(),
                        pvwPtzState = pvwCamera?.ptzState
                            ?: com.camhub.studio.data.ptz.HybridPtzState(),
                        pgmMaxPtzZoom = pgmCamera?.let {
                            if (it.supportsRemotePtz) it.maxZoomRatio else 4f
                        } ?: 4f,
                        pvwMaxPtzZoom = pvwCamera?.let {
                            if (it.supportsRemotePtz) it.maxZoomRatio else 4f
                        } ?: 4f,
                        isLivePtzUnlocked = uiState.isLivePtzUnlocked,
                        onToggleLivePtzLock = viewModel::toggleLivePtzLock,
                        onPtzGesture = viewModel::applyPtzGesture,
                        onPtzDoubleTap = viewModel::doubleTapPtz,
                        modifier = Modifier
                            .weight(1.4f)
                            .fillMaxHeight()
                    )

                    // Live actions stay on the right edge for one-handed operation.
                    ControlBar(
                        isRecording = uiState.isRecording,
                        isPaused = uiState.isPaused,
                        selectedTransition = uiState.selectedTransition,
                        isVertical = true,
                        autoRecordCameras = uiState.autoRecordCameras,
                        onRecord = { viewModel.toggleRecording() },
                        onStop = { viewModel.toggleRecording() },
                        onPause = { viewModel.pauseRecording() },
                        onResume = { viewModel.resumeRecording() },
                        onCut = { viewModel.executeCut() },
                        onAuto = { viewModel.executeAuto() },
                        onSelectTransition = { viewModel.selectTransition(it) },
                        onToggleAudioMixer = { viewModel.toggleAudioMixer() },
                        onToggleAutoRecord = { viewModel.toggleAutoRecordCameras() },
                        modifier = Modifier.fillMaxHeight()
                    )
                }
            }
        } else {
            // ---- PORTRAIT LAYOUT ----
            Column(modifier = Modifier.fillMaxSize()) {
                // Status bar
                StatusBar(
                    bitrateKbps = uiState.bitrateKbps,
                    latencyMs = uiState.latencyP95Ms,
                    timecode = uiState.timecode,
                    isRecording = uiState.isRecording,
                    isPaused = uiState.isPaused,
                    wifiStrength = uiState.wifiStrength,
                    batteryPercent = uiState.batteryPercent,
                    audioMasterLevel = uiState.audioMasterLevel,
                    connectedCameraCount = uiState.cameras.size,
                    hubProfileLabel = hubProfileLabel,
                    networkTransportLabel = uiState.networkTransportLabel,
                    onNavigateToSettings = onNavigateToSettings,
                    onToggleDeviceManager = { viewModel.toggleDeviceManager() }
                )

                // Viewport panel (horizontal PVW+PGM)
                ViewportPanel(
                    pgmCameraName = pgmCamera?.name ?: "---",
                    pvwCameraName = pvwCamera?.name ?: "---",
                    pgmFps = pgmCamera?.fps ?: 0,
                    pvwFps = pvwCamera?.fps ?: 0,
                    pgmBitmap = pgmCamera?.previewBitmap,
                    pgmSourceBitmap = pgmCamera?.previewSourceBitmap,
                    pvwBitmap = pvwCamera?.previewBitmap,
                    enablePgmSpatialUpscaling = uiState.isPgmSpatialUpscalingEnabled,
                    pgmSpatialUpscaleOutputHeight = uiState.pgmOutputHeight,
                    pgmFrameSequence = pgmCamera?.frameSequence ?: 0L,
                    pvwFrameSequence = pvwCamera?.frameSequence ?: 0L,
                    onFrameDrawn = { cameraName, frameSequence ->
                        viewModel.onFrameDrawn(cameraName, frameSequence)
                    },
                    isVertical = false,
                    transitionProgress = uiState.transitionProgress,
                    isTransitioning = uiState.isTransitioning,
                    selectedTransition = uiState.selectedTransition,
                    pgmPtzState = pgmCamera?.ptzState
                        ?: com.camhub.studio.data.ptz.HybridPtzState(),
                    pvwPtzState = pvwCamera?.ptzState
                        ?: com.camhub.studio.data.ptz.HybridPtzState(),
                    pgmMaxPtzZoom = pgmCamera?.let {
                        if (it.supportsRemotePtz) it.maxZoomRatio else 4f
                    } ?: 4f,
                    pvwMaxPtzZoom = pvwCamera?.let {
                        if (it.supportsRemotePtz) it.maxZoomRatio else 4f
                    } ?: 4f,
                    isLivePtzUnlocked = uiState.isLivePtzUnlocked,
                    onToggleLivePtzLock = viewModel::toggleLivePtzLock,
                    onPtzGesture = viewModel::applyPtzGesture,
                    onPtzDoubleTap = viewModel::doubleTapPtz,
                    modifier = Modifier.fillMaxWidth()
                )

                // Control bar (horizontal)
                ControlBar(
                    isRecording = uiState.isRecording,
                    isPaused = uiState.isPaused,
                    selectedTransition = uiState.selectedTransition,
                    isVertical = false,
                    autoRecordCameras = uiState.autoRecordCameras,
                    onRecord = { viewModel.toggleRecording() },
                    onStop = { viewModel.toggleRecording() },
                    onPause = { viewModel.pauseRecording() },
                    onResume = { viewModel.resumeRecording() },
                    onCut = { viewModel.executeCut() },
                    onAuto = { viewModel.executeAuto() },
                    onSelectTransition = { viewModel.selectTransition(it) },
                    onToggleAudioMixer = { viewModel.toggleAudioMixer() },
                    onToggleAutoRecord = { viewModel.toggleAutoRecordCameras() },
                    modifier = Modifier.fillMaxWidth()
                )

                // Camera grid
                CameraGrid(
                    cameras = uiState.cameras,
                    onSelectPvw = { viewModel.selectPvw(it) },
                    onOpenCameraControl = { viewModel.showCameraControl(it) },
                    onDisconnect = { viewModel.disconnectCamera(it) },
                    onFrameDrawn = { cameraName, frameSequence ->
                        viewModel.onFrameDrawn(cameraName, frameSequence)
                    },
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

        // Device manager panel overlay
        if (uiState.showDeviceManager) {
            DeviceManagerPanel(
                discoveredPeers = uiState.discoveredPeers,
                onConnect = { viewModel.connectToPeer(it) },
                onDisconnect = { viewModel.disconnectPeer(it) },
                onConnectAll = { viewModel.connectToAllPeers() },
                onAddManual = { viewModel.addManualConnection(it) },
                onRescan = { viewModel.rescanDevices() },
                onDismiss = { viewModel.toggleDeviceManager() }
            )
        }

        // Camera control panel overlay
        if (uiState.showCameraControl) {
            val controlCamera = uiState.cameras.getOrNull(uiState.controlCameraIndex)
            CameraControlPanel(
                cameraName = controlCamera?.name ?: "Unknown",
                isRecording = controlCamera?.isRecording ?: false,
                currentZoomRatio = controlCamera?.ptzState?.zoom ?: 1f,
                maxZoomRatio = controlCamera?.let {
                    if (it.supportsRemotePtz) it.maxZoomRatio else 4f
                } ?: 4f,
                isPtzEnabled = controlCamera?.isPgm != true || uiState.isLivePtzUnlocked,
                onDismiss = { viewModel.hideCameraControl() },
                onSendCommand = { command, value, stringValue ->
                    if (command == "set_zoom" && controlCamera != null) {
                        viewModel.setPtzZoom(controlCamera.name, value)
                    } else {
                        viewModel.sendCameraCommand(command, value, stringValue)
                    }
                }
            )
        }
    }
}
