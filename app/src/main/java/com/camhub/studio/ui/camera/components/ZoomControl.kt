package com.camhub.studio.ui.camera.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.components.DrumDial
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.JetBrainsMonoFamily
import com.camhub.studio.ui.theme.TextTertiary

@Composable
fun ZoomControl(
    zoomSteps: List<String>,
    selectedZoomIndex: Int,
    onZoomIndexChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "ZOOM",
            color = TextTertiary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = JetBrainsMonoFamily,
            letterSpacing = 1.sp
        )
        DrumDial(
            values = zoomSteps,
            selectedIndex = selectedZoomIndex,
            onIndexChanged = onZoomIndexChanged,
            visibleItems = 5,
            modifier = Modifier
                .width(80.dp)
                .height(120.dp)
                .background(BackgroundDarker.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
        )
    }
}
