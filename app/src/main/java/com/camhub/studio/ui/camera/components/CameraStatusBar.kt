package com.camhub.studio.ui.camera.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.theme.NeonGreen
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

@Composable
fun CameraStatusBar(
    bitrate: String,
    wifiStrength: Int,
    networkTransportLabel: String,
    pairingFingerprint: String,
    pairingPin: String,
    storageUsedGb: Float,
    storageTotalGb: Float,
    modifier: Modifier = Modifier
) {
    val isLive = bitrate.startsWith("LIVE")
    var isPairingPinVisible by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Stream status and the short identity code that a director must compare
        // before trusting this camera's certificate for the first time.
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isEthernet = "LAN" in networkTransportLabel
                val isConnected = isEthernet || wifiStrength > 0
                Icon(
                    imageVector = when {
                        isEthernet -> Icons.Default.SettingsEthernet
                        isConnected -> Icons.Default.Wifi
                        else -> Icons.Default.WifiOff
                    },
                    contentDescription = networkTransportLabel,
                    tint = if (isConnected) NeonGreen else TextTertiary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "$bitrate · $networkTransportLabel",
                    color = if (isLive) NeonGreen else TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (pairingFingerprint.isNotBlank()) {
                Text(
                    text = "VERIFY · $pairingFingerprint  /  PAIR PIN · ${if (isPairingPinVisible) pairingPin else "••••••••"}",
                    color = TextTertiary,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { isPairingPinVisible = !isPairingPinVisible }
                )
            }
        }

        // Storage info
        StorageBar(
            usedGb = storageUsedGb,
            totalGb = storageTotalGb,
            modifier = Modifier
        )
    }
}
