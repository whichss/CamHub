package com.camhub.studio.ui.connection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.data.network.DiscoveredPeer
import com.camhub.studio.ui.connection.model.AppRole
import com.camhub.studio.ui.connection.model.ConnectionState
import com.camhub.studio.ui.theme.AmberYellow
import com.camhub.studio.ui.theme.BackgroundDark
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.CyanAccent
import com.camhub.studio.ui.theme.ElectricRed
import com.camhub.studio.ui.theme.GlassBorder
import com.camhub.studio.ui.theme.GlassSurface
import com.camhub.studio.ui.theme.JetBrainsMonoFamily
import com.camhub.studio.ui.theme.NeonGreen
import com.camhub.studio.ui.theme.Primary
import com.camhub.studio.ui.theme.SpaceGroteskFamily
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.TextMuted
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

@Composable
fun ConnectionSetupScreen(
    viewModel: ConnectionViewModel,
    onNavigateToCameraHud: () -> Unit,
    onNavigateToDirector: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            ConnectionTopBar(
                role = uiState.role,
                onBack = onNavigateBack
            )

            // Content
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }

                when (uiState.role) {
                    AppRole.CAMERA -> {
                        item {
                            CameraDeviceInfoCard(
                                deviceName = uiState.deviceName,
                                localIp = uiState.localIp,
                                port = uiState.port
                            )
                        }
                        item {
                            CameraStatusCard(
                                connectionState = uiState.connectionState,
                                errorMessage = uiState.errorMessage
                            )
                        }
                        if (uiState.connectedPeerNames.isNotEmpty()) {
                            item {
                                ConnectedPeersList(
                                    peerNames = uiState.connectedPeerNames,
                                    title = "Connected Directors"
                                )
                            }
                        }
                    }

                    AppRole.DIRECTOR -> {
                        item {
                            DiscoveryCard(
                                peersFound = uiState.discoveredPeers.size,
                                onRefresh = { viewModel.rescan() },
                                onConnectAll = { viewModel.connectToAll() }
                            )
                        }
                        item {
                            ManualIpEntry(
                                onAdd = { viewModel.addManualConnection(it) }
                            )
                        }
                        item {
                            HotspotCard(
                                isActive = uiState.hotspotActive,
                                ssid = uiState.hotspotSsid,
                                password = uiState.hotspotPassword,
                                error = uiState.hotspotError,
                                onStart = { viewModel.startHotspot() },
                                onStop = { viewModel.stopHotspot() }
                            )
                        }
                        if (uiState.discoveredPeers.isNotEmpty()) {
                            item {
                                SectionLabel(text = "DISCOVERED CAMERAS")
                            }
                            items(
                                items = uiState.discoveredPeers,
                                key = { it.name }
                            ) { peer ->
                                PeerCard(
                                    peer = peer,
                                    onConnect = { viewModel.connectToPeer(peer) },
                                    onDisconnect = { viewModel.disconnectPeer(peer.name) }
                                )
                            }
                        }
                        if (uiState.connectedPeerCount > 0) {
                            item {
                                ConnectedCountBadge(count = uiState.connectedPeerCount)
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(80.dp)) }
            }

            // Proceed button
            AnimatedVisibility(
                visible = uiState.isReadyToProceed,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ProceedButton(
                    role = uiState.role,
                    onClick = {
                        when (uiState.role) {
                            AppRole.CAMERA -> onNavigateToCameraHud()
                            AppRole.DIRECTOR -> onNavigateToDirector()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ConnectionTopBar(
    role: AppRole,
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
            text = if (role == AppRole.CAMERA) "CAMERA SETUP" else "DIRECTOR SETUP",
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = TextPrimary,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun CameraDeviceInfoCard(
    deviceName: String,
    localIp: String,
    port: Int
) {
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassSurface)
            .border(width = 1.dp, color = GlassBorder, shape = shape)
            .padding(16.dp)
    ) {
        Text(
            text = "DEVICE INFO",
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            color = TextTertiary,
            letterSpacing = 1.5.sp
        )
        Spacer(modifier = Modifier.height(12.dp))

        InfoRow(label = "Name", value = deviceName)
        Spacer(modifier = Modifier.height(8.dp))
        InfoRow(label = "IP Address", value = localIp)
        Spacer(modifier = Modifier.height(8.dp))
        InfoRow(label = "Port", value = if (port > 0) port.toString() else "Initializing...")
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontFamily = SpaceGroteskFamily,
            fontSize = 13.sp,
            color = TextSecondary
        )
        Text(
            text = value,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 13.sp,
            color = CyanAccent,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun CameraStatusCard(
    connectionState: ConnectionState,
    errorMessage: String?
) {
    val shape = RoundedCornerShape(12.dp)

    val (statusColor, statusText, showSpinner) = when (connectionState) {
        ConnectionState.WAITING -> Triple(AmberYellow, "Waiting for director...", true)
        ConnectionState.CONNECTING -> Triple(CyanAccent, "Connecting...", true)
        ConnectionState.CONNECTED -> Triple(NeonGreen, "Connected", false)
        ConnectionState.ERROR -> Triple(ElectricRed, errorMessage ?: "Connection error", false)
    }

    val borderColor by animateColorAsState(
        targetValue = statusColor.copy(alpha = 0.4f),
        animationSpec = tween(500),
        label = "statusBorder"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassSurface)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (connectionState) {
            ConnectionState.WAITING, ConnectionState.CONNECTING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = statusColor,
                    strokeWidth = 2.dp
                )
            }
            ConnectionState.CONNECTED -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Connected",
                    tint = NeonGreen,
                    modifier = Modifier.size(32.dp)
                )
            }
            ConnectionState.ERROR -> {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Error",
                    tint = ElectricRed,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = statusText,
            fontFamily = SpaceGroteskFamily,
            fontSize = 14.sp,
            color = statusColor,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ConnectedPeersList(
    peerNames: List<String>,
    title: String
) {
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassSurface)
            .border(width = 1.dp, color = GlassBorder, shape = shape)
            .padding(16.dp)
    ) {
        Text(
            text = title.uppercase(),
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            color = TextTertiary,
            letterSpacing = 1.5.sp
        )
        Spacer(modifier = Modifier.height(12.dp))

        peerNames.forEach { name ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(NeonGreen)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = name,
                    fontFamily = SpaceGroteskFamily,
                    fontSize = 13.sp,
                    color = TextPrimary
                )
            }
        }
    }
}

@Composable
private fun DiscoveryCard(
    peersFound: Int,
    onRefresh: () -> Unit,
    onConnectAll: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)

    val infiniteTransition = rememberInfiniteTransition(label = "scanPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = Modifier
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(CyanAccent.copy(alpha = pulseAlpha))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "SCANNING",
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    color = CyanAccent,
                    letterSpacing = 1.5.sp
                )
            }
            IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "$peersFound camera${if (peersFound != 1) "s" else ""} found",
            fontFamily = SpaceGroteskFamily,
            fontSize = 14.sp,
            color = TextPrimary,
            fontWeight = FontWeight.Medium
        )

        if (peersFound > 0) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onConnectAll,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = TextPrimary
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Connect All",
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun ManualIpEntry(
    onAdd: (String) -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    var ipText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassSurface)
            .border(width = 1.dp, color = GlassBorder, shape = shape)
            .padding(16.dp)
    ) {
        Text(
            text = "MANUAL CONNECTION",
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            color = TextTertiary,
            letterSpacing = 1.5.sp
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = ipText,
                onValueChange = { ipText = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = "192.168.1.100:5000",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 13.sp,
                        color = TextMuted
                    )
                },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 13.sp,
                    color = TextPrimary
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (ipText.isNotBlank()) {
                            onAdd(ipText)
                            ipText = ""
                        }
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanAccent,
                    unfocusedBorderColor = GlassBorder,
                    cursorColor = CyanAccent,
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark
                ),
                shape = RoundedCornerShape(8.dp)
            )

            IconButton(
                onClick = {
                    if (ipText.isNotBlank()) {
                        onAdd(ipText)
                        ipText = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Primary)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add",
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun HotspotCard(
    isActive: Boolean,
    ssid: String,
    password: String,
    error: String?,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    val borderColor = if (isActive) NeonGreen.copy(alpha = 0.4f) else GlassBorder

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassSurface)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isActive) Icons.Default.Wifi else Icons.Default.WifiOff,
                    contentDescription = null,
                    tint = if (isActive) NeonGreen else TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "HOTSPOT",
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    color = if (isActive) NeonGreen else TextTertiary,
                    letterSpacing = 1.5.sp
                )
            }

            Button(
                onClick = if (isActive) onStop else onStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) ElectricRed.copy(alpha = 0.2f) else Primary.copy(alpha = 0.2f),
                    contentColor = if (isActive) ElectricRed else Primary
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (isActive) "Stop" else "Start",
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp
                )
            }
        }

        if (isActive && ssid.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            InfoRow(label = "SSID", value = ssid)
            Spacer(modifier = Modifier.height(6.dp))
            InfoRow(label = "Password", value = password)
        }

        if (error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                fontFamily = SpaceGroteskFamily,
                fontSize = 12.sp,
                color = ElectricRed
            )
        }
    }
}

@Composable
private fun PeerCard(
    peer: DiscoveredPeer,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val borderColor = if (peer.isConnected) NeonGreen.copy(alpha = 0.3f) else GlassBorder

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SurfaceDark)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (peer.isConnected) NeonGreen else AmberYellow)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = peer.name,
                    fontFamily = SpaceGroteskFamily,
                    fontSize = 13.sp,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${peer.ip}:${peer.port}",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 11.sp,
                    color = TextTertiary
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (peer.isConnected) {
            IconButton(
                onClick = onDisconnect,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ElectricRed.copy(alpha = 0.15f))
            ) {
                Icon(
                    imageVector = Icons.Default.LinkOff,
                    contentDescription = "Disconnect",
                    tint = ElectricRed,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            IconButton(
                onClick = onConnect,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Primary.copy(alpha = 0.15f))
            ) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = "Connect",
                    tint = Primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ConnectedCountBadge(count: Int) {
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(NeonGreen.copy(alpha = 0.08f))
            .border(width = 1.dp, color = NeonGreen.copy(alpha = 0.2f), shape = shape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = NeonGreen,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$count camera${if (count != 1) "s" else ""} connected",
            fontFamily = SpaceGroteskFamily,
            fontSize = 13.sp,
            color = NeonGreen,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        color = TextTertiary,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun ProceedButton(
    role: AppRole,
    onClick: () -> Unit
) {
    val label = when (role) {
        AppRole.CAMERA -> "Enter Camera HUD"
        AppRole.DIRECTOR -> "Enter Director"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        BackgroundDark.copy(alpha = 0f),
                        BackgroundDark
                    )
                )
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Primary,
                contentColor = TextPrimary
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = label,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                letterSpacing = 0.5.sp
            )
        }
    }
}
