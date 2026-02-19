package com.camhub.studio.ui.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.settings.model.Protocol
import com.camhub.studio.ui.theme.CyanAccent
import com.camhub.studio.ui.theme.GlassBorder
import com.camhub.studio.ui.theme.GlassSurface
import com.camhub.studio.ui.theme.SpaceGroteskFamily
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

@Composable
fun ProtocolSelector(
    selectedProtocol: Protocol,
    onSelectProtocol: (Protocol) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "STREAMING PROTOCOL",
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            color = TextTertiary,
            letterSpacing = 1.5.sp
        )

        Spacer(modifier = Modifier.height(4.dp))

        Protocol.entries.forEach { protocol ->
            val isSelected = protocol == selectedProtocol
            ProtocolCard(
                protocol = protocol,
                isSelected = isSelected,
                onClick = { onSelectProtocol(protocol) }
            )
        }
    }
}

@Composable
private fun ProtocolCard(
    protocol: Protocol,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val borderColor = if (isSelected) CyanAccent else GlassBorder
    val bgColor = if (isSelected) CyanAccent.copy(alpha = 0.08f) else GlassSurface

    val (name, description) = when (protocol) {
        Protocol.WEBRTC -> "WebRTC" to "Low-latency peer-to-peer streaming with adaptive bitrate. Best for real-time monitoring over local networks."
        Protocol.NDI_HX -> "NDI HX" to "Network Device Interface for high-quality video over IP. Integrates with professional NDI ecosystems."
        Protocol.SRT -> "SRT" to "Secure Reliable Transport with AES encryption. Ideal for streaming over unreliable or public networks."
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bgColor)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Text(
            text = name,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = if (isSelected) CyanAccent else TextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            color = TextSecondary,
            lineHeight = 17.sp
        )
    }
}
