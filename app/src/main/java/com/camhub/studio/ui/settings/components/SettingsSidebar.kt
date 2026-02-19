package com.camhub.studio.ui.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
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
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.CyanAccent
import com.camhub.studio.ui.theme.GlassBorder
import com.camhub.studio.ui.theme.SpaceGroteskFamily
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextTertiary

private data class SettingsTab(
    val index: Int,
    val label: String,
    val icon: ImageVector
)

private val settingsTabs = listOf(
    SettingsTab(0, "Connection", Icons.Default.Wifi),
    SettingsTab(1, "Recording", Icons.Default.FiberManualRecord),
    SettingsTab(2, "Display", Icons.Default.DisplaySettings),
    SettingsTab(3, "System", Icons.Default.Settings)
)

@Composable
fun SettingsSidebar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(220.dp)
            .fillMaxHeight()
            .background(BackgroundDarker)
            .border(width = 1.dp, color = GlassBorder, shape = RoundedCornerShape(0.dp))
            .padding(vertical = 16.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "SETTINGS",
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = TextPrimary,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        settingsTabs.forEach { tab ->
            SidebarTabItem(
                tab = tab,
                isSelected = selectedTab == tab.index,
                onClick = { onSelectTab(tab.index) }
            )
        }
    }
}

@Composable
fun SettingsTabBar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(BackgroundDarker)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        settingsTabs.forEach { tab ->
            HorizontalTabItem(
                tab = tab,
                isSelected = selectedTab == tab.index,
                onClick = { onSelectTab(tab.index) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SidebarTabItem(
    tab: SettingsTab,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val bgColor = if (isSelected) CyanAccent.copy(alpha = 0.12f) else SurfaceDark.copy(alpha = 0f)
    val contentColor = if (isSelected) CyanAccent else TextTertiary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = tab.label,
            fontFamily = SpaceGroteskFamily,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            fontSize = 14.sp,
            color = if (isSelected) TextPrimary else TextTertiary
        )
    }
}

@Composable
private fun HorizontalTabItem(
    tab: SettingsTab,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    val bgColor = if (isSelected) CyanAccent.copy(alpha = 0.12f) else SurfaceDark.copy(alpha = 0f)
    val contentColor = if (isSelected) CyanAccent else TextTertiary

    Column(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = tab.label,
            fontFamily = SpaceGroteskFamily,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            fontSize = 10.sp,
            color = if (isSelected) TextPrimary else TextTertiary
        )
    }
}
