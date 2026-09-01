package com.camhub.studio.navigation

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.automirrored.filled.Dvr
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.theme.BackgroundDark
import com.camhub.studio.ui.theme.GlassBorder
import com.camhub.studio.ui.theme.Primary
import com.camhub.studio.ui.theme.PrimaryLight
import com.camhub.studio.ui.theme.SpaceGroteskFamily
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary
import com.camhub.studio.ui.theme.CyanAccent
import com.camhub.studio.ui.components.CamHubScreenBackground
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.JetBrainsMonoFamily
import com.camhub.studio.ui.theme.NeonGreen

@Composable
fun RoleSelectScreen(
    onRoleSelected: (String) -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    CamHubScreenBackground {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 900.dp)
                .padding(horizontal = 24.dp, vertical = 28.dp)
        ) {
            BrandHeader()
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "LIVE PRODUCTION STUDIO",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                color = TextTertiary,
                letterSpacing = 3.sp
            )

            Spacer(modifier = Modifier.height(if (isLandscape) 18.dp else 26.dp))

            MainCapabilityStrip()

            Spacer(modifier = Modifier.height(if (isLandscape) 18.dp else 26.dp))

            // Section label
            Text(
                text = "CHOOSE THIS DEVICE'S ROLE",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                color = TextTertiary,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Role cards
            if (isLandscape) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(0.82f)
                ) {
                    RoleCard(
                        icon = Icons.AutoMirrored.Filled.Dvr,
                        title = "Director",
                        eyebrow = "HUB MODE",
                        description = "Multi-camera switching, monitoring, and live production control",
                        accentColor = Primary,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 188.dp, max = 224.dp),
                        onClick = { onRoleSelected("director") }
                    )
                    RoleCard(
                        icon = Icons.Default.CameraAlt,
                        title = "Camera",
                        eyebrow = "SOURCE MODE",
                        description = "Camera operator with live viewfinder, exposure, and focus controls",
                        accentColor = CyanAccent,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 188.dp, max = 224.dp),
                        onClick = { onRoleSelected("camera") }
                    )
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RoleCard(
                        icon = Icons.AutoMirrored.Filled.Dvr,
                        title = "Director",
                        eyebrow = "HUB MODE",
                        description = "Multi-camera switching, monitoring, and live production control",
                        accentColor = Primary,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onRoleSelected("director") }
                    )
                    RoleCard(
                        icon = Icons.Default.CameraAlt,
                        title = "Camera",
                        eyebrow = "SOURCE MODE",
                        description = "Camera operator with live viewfinder, exposure, and focus controls",
                        accentColor = CyanAccent,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onRoleSelected("camera") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "LOCAL-FIRST  ·  ENCRYPTED CONTROL  ·  NO ACCOUNT REQUIRED",
                color = TextTertiary,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 8.sp,
                letterSpacing = 0.7.sp
            )
        }
    }
    }
}

@Composable
private fun BrandHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Primary.copy(alpha = 0.12f))
                .border(1.dp, Primary.copy(alpha = 0.32f), RoundedCornerShape(12.dp))
                .padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                BrandPixel(Primary)
                BrandPixel(CyanAccent)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                BrandPixel(NeonGreen)
                BrandPixel(Primary)
            }
        }
        Text(
            text = "CamHub",
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 38.sp,
            color = TextPrimary,
            letterSpacing = (-0.8).sp
        )
    }
}

@Composable
private fun BrandPixel(color: Color) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color)
    )
}

@Composable
private fun MainCapabilityStrip() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BackgroundDarker.copy(alpha = 0.62f))
            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        CapabilityItem("4 SOURCES", "MULTIVIEW", Modifier.weight(1f))
        CapabilityItem("WIRED + WI-FI", "FLEXIBLE LINK", Modifier.weight(1f))
        CapabilityItem("1080p PGM", "SPATIAL UPSCALE", Modifier.weight(1f))
    }
}

@Composable
private fun CapabilityItem(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            color = TextPrimary,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = TextTertiary,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 7.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun RoleCard(
    icon: ImageVector,
    title: String,
    eyebrow: String,
    description: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(shape)
            .background(SurfaceDark.copy(alpha = 0.94f))
            .border(width = 1.dp, color = accentColor.copy(alpha = 0.32f), shape = shape)
            .clickable(onClick = onClick)
            .heightIn(min = 184.dp)
            .padding(22.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(accentColor.copy(alpha = 0.12f))
                .border(1.dp, accentColor.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = accentColor,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = eyebrow,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            color = accentColor,
            letterSpacing = 1.4.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            color = TextSecondary,
            lineHeight = 18.sp
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "CONTINUE",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                color = accentColor,
                letterSpacing = 1.sp
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
