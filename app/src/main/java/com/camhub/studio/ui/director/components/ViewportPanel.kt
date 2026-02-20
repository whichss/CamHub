package com.camhub.studio.ui.director.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.ElectricRed
import com.camhub.studio.ui.theme.JetBrainsMonoFamily
import com.camhub.studio.ui.theme.NeonGreen
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.TallyGreen
import com.camhub.studio.ui.theme.TallyRed
import com.camhub.studio.ui.theme.TextPrimary
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary

/**
 * Tally border composable that wraps content with a colored border indicating PGM/PVW status.
 */
@Composable
fun TallyBorder(
    isPgm: Boolean,
    isPvw: Boolean,
    modifier: Modifier = Modifier,
    borderWidth: Dp = 2.dp,
    content: @Composable () -> Unit
) {
    val borderColor = when {
        isPgm -> TallyRed
        isPvw -> TallyGreen
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = borderWidth,
                color = borderColor,
                shape = RoundedCornerShape(6.dp)
            )
    ) {
        content()
    }
}

/**
 * A single viewport card showing a camera preview with tally border, label, camera name, and FPS.
 */
@Composable
fun ViewportCard(
    label: String,
    labelColor: Color,
    cameraName: String,
    fps: Int,
    previewBitmap: ImageBitmap?,
    isPgm: Boolean,
    isPvw: Boolean,
    modifier: Modifier = Modifier
) {
    TallyBorder(
        isPgm = isPgm,
        isPvw = isPvw,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .background(SurfaceDark, RoundedCornerShape(6.dp))
        ) {
            // Label bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(labelColor.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = label,
                    color = labelColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = JetBrainsMonoFamily,
                    letterSpacing = 1.sp,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = cameraName,
                        color = TextSecondary,
                        fontSize = 9.sp,
                        fontFamily = JetBrainsMonoFamily
                    )
                    Text(
                        text = "${fps}fps",
                        color = TextTertiary,
                        fontSize = 9.sp,
                        fontFamily = JetBrainsMonoFamily
                    )
                }
            }

            // Preview area — adapts aspect ratio to stream content
            val bitmapAspect = if (previewBitmap != null) {
                previewBitmap.width.toFloat() / previewBitmap.height.toFloat()
            } else {
                16f / 9f
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(bitmapAspect)
                    .background(BackgroundDarker),
                contentAlignment = Alignment.Center
            ) {
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap,
                        contentDescription = "$label preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = "NO SIGNAL",
                        color = TextTertiary,
                        fontSize = 10.sp,
                        fontFamily = JetBrainsMonoFamily,
                        letterSpacing = 2.sp
                    )
                }
            }
        }
    }
}

/**
 * PGM and PVW viewport cards displayed side by side (horizontal) or stacked (vertical).
 *
 * @param pgmCameraName Name of the camera on PGM.
 * @param pvwCameraName Name of the camera on PVW.
 * @param pgmFps Frames per second for PGM camera.
 * @param pvwFps Frames per second for PVW camera.
 * @param pgmBitmap Preview bitmap for PGM camera.
 * @param pvwBitmap Preview bitmap for PVW camera.
 * @param isVertical When true, cards are stacked vertically (landscape layout).
 */
@Composable
fun ViewportPanel(
    pgmCameraName: String,
    pvwCameraName: String,
    pgmFps: Int,
    pvwFps: Int,
    pgmBitmap: ImageBitmap?,
    pvwBitmap: ImageBitmap?,
    isVertical: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (isVertical) {
        Column(
            modifier = modifier.padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ViewportCard(
                label = "PVW",
                labelColor = NeonGreen,
                cameraName = pvwCameraName,
                fps = pvwFps,
                previewBitmap = pvwBitmap,
                isPgm = false,
                isPvw = true,
                modifier = Modifier.fillMaxWidth()
            )
            ViewportCard(
                label = "PGM",
                labelColor = ElectricRed,
                cameraName = pgmCameraName,
                fps = pgmFps,
                previewBitmap = pgmBitmap,
                isPgm = true,
                isPvw = false,
                modifier = Modifier.fillMaxWidth()
            )
        }
    } else {
        Row(
            modifier = modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ViewportCard(
                label = "PVW",
                labelColor = NeonGreen,
                cameraName = pvwCameraName,
                fps = pvwFps,
                previewBitmap = pvwBitmap,
                isPgm = false,
                isPvw = true,
                modifier = Modifier.weight(1f)
            )
            ViewportCard(
                label = "PGM",
                labelColor = ElectricRed,
                cameraName = pgmCameraName,
                fps = pgmFps,
                previewBitmap = pgmBitmap,
                isPgm = true,
                isPvw = false,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
