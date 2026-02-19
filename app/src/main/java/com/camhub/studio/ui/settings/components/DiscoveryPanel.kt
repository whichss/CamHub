package com.camhub.studio.ui.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.settings.model.DiscoveredNode
import com.camhub.studio.ui.settings.model.NodeStatus
import com.camhub.studio.ui.theme.AmberYellow
import com.camhub.studio.ui.theme.GlassBorder
import com.camhub.studio.ui.theme.GlassSurface
import com.camhub.studio.ui.theme.JetBrainsMonoFamily
import com.camhub.studio.ui.theme.NeonGreen
import com.camhub.studio.ui.theme.SpaceGroteskFamily
import com.camhub.studio.ui.theme.TextMuted
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

@Composable
fun DiscoveryPanel(
    nodes: List<DiscoveredNode>,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassSurface)
            .border(width = 1.dp, color = GlassBorder, shape = shape)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DISCOVERED NODES",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                color = TextTertiary,
                letterSpacing = 1.5.sp
            )
            Text(
                text = "${nodes.size} node${if (nodes.size != 1) "s" else ""}",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                color = TextSecondary
            )
        }

        if (nodes.isEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No nodes discovered",
                fontFamily = SpaceGroteskFamily,
                fontSize = 13.sp,
                color = TextMuted,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            Spacer(modifier = Modifier.height(12.dp))
            nodes.forEach { node ->
                NodeRow(node = node)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun NodeRow(node: DiscoveredNode) {
    val statusColor = when (node.status) {
        NodeStatus.CONNECTED -> NeonGreen
        NodeStatus.IDLE -> AmberYellow
        NodeStatus.OFFLINE -> TextMuted
    }

    val statusLabel = when (node.status) {
        NodeStatus.CONNECTED -> "Connected"
        NodeStatus.IDLE -> "Idle"
        NodeStatus.OFFLINE -> "Offline"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(GlassSurface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = node.name,
                    fontFamily = SpaceGroteskFamily,
                    fontSize = 13.sp,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = node.ip,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    color = TextTertiary
                )
            }
        }
        Text(
            text = statusLabel,
            fontFamily = SpaceGroteskFamily,
            fontSize = 11.sp,
            color = statusColor,
            fontWeight = FontWeight.Medium
        )
    }
}
