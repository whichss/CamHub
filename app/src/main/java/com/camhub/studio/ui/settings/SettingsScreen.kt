package com.camhub.studio.ui.settings

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.settings.components.DiscoveryPanel
import com.camhub.studio.ui.settings.components.DisplaySettings
import com.camhub.studio.ui.settings.components.ProtocolSelector
import com.camhub.studio.ui.settings.components.RecordingSettings
import com.camhub.studio.ui.settings.components.SettingsBottomBar
import com.camhub.studio.ui.settings.components.SettingsSidebar
import com.camhub.studio.ui.settings.components.SettingsTabBar
import com.camhub.studio.ui.settings.components.StreamingSettings
import com.camhub.studio.ui.settings.components.SystemSettings
import com.camhub.studio.data.network.NetworkSelectionMode
import com.camhub.studio.ui.theme.BackgroundDark
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.GlassBorder
import com.camhub.studio.ui.theme.GlassSurface
import com.camhub.studio.ui.theme.Primary
import com.camhub.studio.ui.theme.SpaceGroteskFamily
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary
import com.camhub.studio.ui.components.CamHubScreenBackground
import com.camhub.studio.ui.components.CamHubTopBar
import com.camhub.studio.ui.components.StatusChip

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val activity = LocalContext.current as? Activity

    CamHubScreenBackground {
    Column(modifier = Modifier.fillMaxSize()) {
        CamHubTopBar(
            title = "Settings",
            subtitle = "CAMHUB SYSTEM & PRODUCTION PREFERENCES",
            onBack = onNavigateBack,
            trailing = {
                StatusChip(
                    label = listOf("NETWORK", "RECORD", "DISPLAY", "SYSTEM")
                        .getOrElse(uiState.selectedSettingsTab) { "SETTINGS" },
                    color = Primary
                )
            }
        )
        if (isLandscape) {
            // Landscape: sidebar + content, bottom bar at very bottom
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                SettingsSidebar(
                    selectedTab = uiState.selectedSettingsTab,
                    onSelectTab = { viewModel.selectTab(it) }
                )

                SettingsContentArea(
                    viewModel = viewModel,
                    activity = activity,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        } else {
            // Portrait: top bar, horizontal tabs, content, bottom bar
            SettingsTabBar(
                selectedTab = uiState.selectedSettingsTab,
                onSelectTab = { viewModel.selectTab(it) }
            )

            SettingsContentArea(
                viewModel = viewModel,
                activity = activity,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }

        SettingsBottomBar(
            latencyMs = uiState.latencyMs,
            minStreamFps = uiState.minStreamFps,
            droppedFrames = uiState.droppedFrames,
            activeStreams = uiState.activeStreams
        )
    }
    }
}

@Composable
private fun SettingsContentArea(
    viewModel: SettingsViewModel,
    activity: Activity?,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (uiState.selectedSettingsTab) {
            0 -> ConnectionTabContent(
                viewModel = viewModel
            )
            1 -> RecordingTabContent(
                viewModel = viewModel
            )
            2 -> DisplayTabContent(
                viewModel = viewModel,
                activity = activity
            )
            3 -> SystemTabContent(
                viewModel = viewModel,
                activity = activity
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ConnectionTabContent(
    viewModel: SettingsViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

    ProtocolSelector(
        selectedProtocol = uiState.selectedProtocol,
        onSelectProtocol = { viewModel.selectProtocol(it) }
    )

    Spacer(modifier = Modifier.height(8.dp))

    NetworkRouteCard(
        selectedMode = uiState.networkSelectionMode,
        activeLabel = uiState.activeNetworkLabel,
        hasEthernet = uiState.hasEthernet,
        hasWifi = uiState.hasWifi,
        onSelect = viewModel::selectNetworkMode
    )

    Spacer(modifier = Modifier.height(8.dp))

    // mDNS toggle
    MdnsToggleCard(
        isEnabled = uiState.isMdnsEnabled,
        onToggle = { viewModel.toggleMdns() }
    )

    Spacer(modifier = Modifier.height(8.dp))

    DiscoveryPanel(
        nodes = uiState.discoveredNodes
    )

    Spacer(modifier = Modifier.height(8.dp))

    StreamingSettings(
        fps = uiState.streamFps,
        maxResolution = uiState.streamMaxResolution,
        bitrateMbps = uiState.streamBitrateMbps,
        isLowLatencyDecode = uiState.isLowLatencyDecode,
        isAdaptiveBitrate = uiState.isAdaptiveBitrate,
        adaptiveBitrateStatus = uiState.adaptiveBitrateStatus,
        isAutomaticHubProfile = uiState.isAutomaticHubProfile,
        onFpsChange = { viewModel.updateStreamFps(it) },
        onResolutionChange = { viewModel.updateStreamResolution(it) },
        onBitrateChange = { viewModel.updateStreamBitrate(it) },
        onToggleLowLatency = { viewModel.toggleLowLatencyDecode() },
        onToggleAdaptiveBitrate = { viewModel.toggleAdaptiveBitrate() },
        onToggleAutomaticHubProfile = { viewModel.toggleAutomaticHubProfile() }
    )
}

@Composable
private fun NetworkRouteCard(
    selectedMode: NetworkSelectionMode,
    activeLabel: String,
    hasEthernet: Boolean,
    hasWifi: Boolean,
    onSelect: (NetworkSelectionMode) -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GlassSurface, shape)
            .border(1.dp, GlassBorder, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Network Route",
                    fontFamily = SpaceGroteskFamily,
                    fontSize = 14.sp,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "AUTO prefers a reachable Ethernet route, then Wi-Fi",
                    fontFamily = SpaceGroteskFamily,
                    fontSize = 11.sp,
                    color = TextTertiary
                )
            }
            Text(
                text = activeLabel,
                fontFamily = SpaceGroteskFamily,
                fontSize = 10.sp,
                color = Primary,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NetworkSelectionMode.entries.forEach { mode ->
                val available = when (mode) {
                    NetworkSelectionMode.AUTO -> hasEthernet || hasWifi
                    NetworkSelectionMode.WIFI -> hasWifi
                    NetworkSelectionMode.ETHERNET -> hasEthernet
                }
                val selected = mode == selectedMode
                Text(
                    text = when (mode) {
                        NetworkSelectionMode.AUTO -> "AUTO"
                        NetworkSelectionMode.WIFI -> "WI-FI"
                        NetworkSelectionMode.ETHERNET -> "ETHERNET"
                    },
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            color = if (selected) Primary.copy(alpha = 0.18f) else SurfaceDark,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = if (selected) Primary else GlassBorder,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable(enabled = available || selected) { onSelect(mode) }
                        .padding(vertical = 10.dp),
                    color = when {
                        selected -> Primary
                        available -> TextSecondary
                        else -> TextTertiary.copy(alpha = 0.45f)
                    },
                    fontFamily = SpaceGroteskFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun MdnsToggleCard(
    isEnabled: Boolean,
    onToggle: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GlassSurface, shape)
            .border(width = 1.dp, color = GlassBorder, shape = shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "mDNS Auto-Discovery",
                fontFamily = SpaceGroteskFamily,
                fontSize = 14.sp,
                color = TextPrimary,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Automatically find cameras on the network",
                fontFamily = SpaceGroteskFamily,
                fontSize = 11.sp,
                color = TextTertiary
            )
        }
        Switch(
            checked = isEnabled,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextPrimary,
                checkedTrackColor = Primary,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceDark,
                uncheckedBorderColor = GlassBorder
            )
        )
    }
}

@Composable
private fun RecordingTabContent(
    viewModel: SettingsViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val storagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(it, flags)
            } catch (_: SecurityException) { /* best effort */ }
            viewModel.updateStoragePath(it.toString())
        }
    }

    RecordingSettings(
        storagePath = uiState.recordingStoragePath,
        onChangeStoragePath = {
            storagePicker.launch(null)
        },
        selectedFormat = uiState.recordingFormat,
        onSelectFormat = { viewModel.selectRecordingFormat(it) },
        bitrateMbps = uiState.recordingBitrateMbps,
        onBitrateChange = { viewModel.updateRecordingBitrate(it) },
        storageUsedGb = uiState.storageUsedGb,
        storageTotalGb = uiState.storageTotalGb
    )
}

@Composable
private fun DisplayTabContent(
    viewModel: SettingsViewModel,
    activity: Activity?
) {
    val uiState by viewModel.uiState.collectAsState()

    DisplaySettings(
        isExternalDisplayConnected = uiState.isExternalDisplayConnected,
        isExternalDisplayEnabled = uiState.isExternalDisplayEnabled,
        selectedResolution = uiState.externalDisplayResolution,
        onToggleExternalDisplay = {
            activity?.let { viewModel.toggleExternalDisplay(it) }
        },
        onSelectResolution = { viewModel.selectDisplayResolution(it) }
    )
}

@Composable
private fun SystemTabContent(
    viewModel: SettingsViewModel,
    activity: Activity?
) {
    val uiState by viewModel.uiState.collectAsState()

    SystemSettings(
        batteryPercent = uiState.batteryPercent,
        wifiStrength = uiState.wifiStrength,
        storageUsedGb = uiState.storageUsedGb,
        storageTotalGb = uiState.storageTotalGb,
        isKioskModeEnabled = uiState.isKioskModeEnabled,
        isAutoStartEnabled = uiState.isAutoStartEnabled,
        isNavigationLocked = uiState.isNavigationLocked,
        screenTimeoutMinutes = uiState.screenTimeoutMinutes,
        onToggleKioskMode = {
            viewModel.toggleKioskMode()
            activity?.let { viewModel.applyKioskMode(it) }
        },
        onToggleAutoStart = { viewModel.toggleAutoStart() },
        onToggleNavigationLock = {
            viewModel.toggleNavigationLock()
            activity?.let { viewModel.applyNavigationLock(it) }
        },
        onUpdateScreenTimeout = { viewModel.updateScreenTimeout(it) }
    )
}
