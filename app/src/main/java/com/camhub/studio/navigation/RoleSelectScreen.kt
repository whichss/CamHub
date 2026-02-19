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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Dvr
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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

@Composable
fun RoleSelectScreen(
    onRoleSelected: (String) -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            // Title
            Text(
                text = "CamHub",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
                color = Primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "PRO",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 20.sp,
                color = TextTertiary,
                letterSpacing = 8.sp
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Section label
            Text(
                text = "SELECT YOUR ROLE",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                color = TextTertiary,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Role cards
            if (isLandscape) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    RoleCard(
                        icon = Icons.Default.Dvr,
                        title = "Director",
                        description = "Multi-camera switching, monitoring, and live production control",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(0.6f),
                        onClick = { onRoleSelected("director") }
                    )
                    RoleCard(
                        icon = Icons.Default.CameraAlt,
                        title = "Camera",
                        description = "Camera operator with live viewfinder, exposure, and focus controls",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(0.6f),
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
                        icon = Icons.Default.Dvr,
                        title = "Director",
                        description = "Multi-camera switching, monitoring, and live production control",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onRoleSelected("director") }
                    )
                    RoleCard(
                        icon = Icons.Default.CameraAlt,
                        title = "Camera",
                        description = "Camera operator with live viewfinder, exposure, and focus controls",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onRoleSelected("camera") }
                    )
                }
            }
        }
    }
}

@Composable
private fun RoleCard(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(shape)
            .background(SurfaceDark)
            .border(width = 1.dp, color = GlassBorder, shape = shape)
            .clickable(onClick = onClick)
            .padding(24.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = PrimaryLight,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
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
    }
}
