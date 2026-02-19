package com.camhub.studio.ui.settings

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.ArrowBack
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
import com.camhub.studio.ui.settings.components.SystemSettings
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

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val activity = LocalContext.current as? Activity

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
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
            SettingsTopBar(onBack = onNavigateBack)

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
            droppedFrames = uiState.droppedFrames,
            activeStreams = uiState.activeStreams
        )
    }
}

@Composable
private fun SettingsTopBar(
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BackgroundDarker)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = TextPrimary
            )
        }
        Text(
            text = "SETTINGS",
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = TextPrimary,
            letterSpacing = 1.sp
        )
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

    // mDNS toggle
    MdnsToggleCard(
        isEnabled = uiState.isMdnsEnabled,
        onToggle = { viewModel.toggleMdns() }
    )

    Spacer(modifier = Modifier.height(8.dp))

    DiscoveryPanel(
        nodes = uiState.discoveredNodes
    )
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

    RecordingSettings(
        storagePath = uiState.recordingStoragePath,
        onChangeStoragePath = {
            // Storage path change is handled externally via SAF / file picker
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
