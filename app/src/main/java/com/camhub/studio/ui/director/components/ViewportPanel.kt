package com.camhub.studio.ui.director.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import com.camhub.studio.data.gl.SpatialUpscaleSurfaceView
import com.camhub.studio.data.ptz.HubPtzTransform
import com.camhub.studio.data.ptz.HybridPtzController
import com.camhub.studio.data.ptz.HybridPtzState
import com.camhub.studio.data.ptz.PtzMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import com.camhub.studio.ui.director.model.TransitionType
import com.camhub.studio.ui.theme.BackgroundDarker
import com.camhub.studio.ui.theme.AmberYellow
import com.camhub.studio.ui.theme.CyanAccent
import com.camhub.studio.ui.theme.ElectricRed
import com.camhub.studio.ui.theme.JetBrainsMonoFamily
import com.camhub.studio.ui.theme.NeonGreen
import com.camhub.studio.ui.theme.SurfaceDark
import com.camhub.studio.ui.theme.TallyGreen
import com.camhub.studio.ui.theme.TallyRed
import com.camhub.studio.ui.theme.TextSecondary
import com.camhub.studio.ui.theme.TextTertiary
import com.camhub.studio.ui.theme.TextPrimary

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
 * Supports transition overlay rendering for MIX, DIP, and WIPE effects.
 */
@Composable
fun ViewportCard(
    label: String,
    labelColor: Color,
    cameraName: String,
    fps: Int,
    previewBitmap: ImageBitmap?,
    previewSourceBitmap: Bitmap? = null,
    isPgm: Boolean,
    isPvw: Boolean,
    modifier: Modifier = Modifier,
    transitionBitmap: ImageBitmap? = null,
    transitionProgress: Float = 0f,
    transitionType: TransitionType = TransitionType.CUT,
    frameSequence: Long = 0L,
    transitionCameraName: String = "",
    transitionFrameSequence: Long = 0L,
    onFrameDrawn: (String, Long) -> Unit = { _, _ -> },
    enableSpatialUpscaling: Boolean = false,
    spatialUpscaleOutputHeight: Int = 1080,
    ptzState: HybridPtzState = HybridPtzState(),
    transitionPtzState: HybridPtzState = HybridPtzState(),
    maxPtzZoom: Float = 4f,
    isLivePtzUnlocked: Boolean = false,
    onToggleLivePtzLock: () -> Unit = {},
    onPtzGesture: (String, Float, Float, Float) -> Unit = { _, _, _, _ -> },
    onPtzDoubleTap: (String, Float, Float) -> Unit = { _, _, _ -> }
) {
    val useSpatialUpscaling = enableSpatialUpscaling &&
        isPgm &&
        transitionProgress <= 0f &&
        previewSourceBitmap != null
    val ptzTransform = HybridPtzController.hubTransform(ptzState)
    val transitionPtzTransform = HybridPtzController.hubTransform(transitionPtzState)
    val ptzEnabled = cameraName != "---" &&
        (isPvw || (isPgm && isLivePtzUnlocked)) &&
        transitionProgress <= 0f
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

            // Preview area with fixed 16:9 aspect ratio
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(BackgroundDarker)
                    .clipToBounds()
                    .pointerInput(cameraName, ptzEnabled, maxPtzZoom) {
                        if (ptzEnabled) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                if (size.width > 0 && size.height > 0) {
                                    onPtzGesture(
                                        cameraName,
                                        zoom,
                                        pan.x / size.width,
                                        pan.y / size.height
                                    )
                                }
                            }
                        }
                    }
                    .pointerInput(cameraName, ptzEnabled) {
                        if (ptzEnabled) {
                            detectTapGestures(
                                onDoubleTap = { offset ->
                                    if (size.width > 0 && size.height > 0) {
                                        onPtzDoubleTap(
                                            cameraName,
                                            (offset.x / size.width).coerceIn(0f, 1f),
                                            (offset.y / size.height).coerceIn(0f, 1f)
                                        )
                                    }
                                }
                            )
                        }
                    }
                    .drawWithContent {
                        drawContent()
                        if (!useSpatialUpscaling && frameSequence > 0L) {
                            onFrameDrawn(cameraName, frameSequence)
                        }
                        if (transitionProgress > 0f && transitionFrameSequence > 0L) {
                            onFrameDrawn(transitionCameraName, transitionFrameSequence)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val isTransitioning = transitionProgress > 0f && transitionBitmap != null

                when {
                    useSpatialUpscaling -> {
                        AndroidView(
                            factory = { context -> SpatialUpscaleSurfaceView(context) },
                            update = { view ->
                                view.setOutputHeight(spatialUpscaleOutputHeight)
                                previewSourceBitmap?.let { bitmap ->
                                    view.displayFrame(
                                        bitmap = bitmap,
                                        cameraName = cameraName,
                                        frameSequence = frameSequence,
                                        ptzTransform = ptzTransform,
                                        onFrameSubmitted = { name, sequence, _ ->
                                            onFrameDrawn(name, sequence)
                                        }
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    isTransitioning && transitionType == TransitionType.MIX -> {
                        // MIX: Crossfade - old PGM fades out, PVW fades in
                        if (previewBitmap != null) {
                            Image(
                                bitmap = previewBitmap,
                                contentDescription = "$label preview",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .applyHubPtz(ptzTransform)
                                    .alpha(1f - transitionProgress),
                                contentScale = ContentScale.Fit
                            )
                        }
                        Image(
                            bitmap = transitionBitmap!!,
                            contentDescription = "$label transition",
                            modifier = Modifier
                                .fillMaxSize()
                                .applyHubPtz(transitionPtzTransform)
                                .alpha(transitionProgress),
                            contentScale = ContentScale.Fit
                        )
                    }
                    isTransitioning && transitionType == TransitionType.DIP -> {
                        // DIP: Fade to black (0-0.5) then fade in new source (0.5-1.0)
                        if (transitionProgress <= 0.5f) {
                            val fadeOut = 1f - (transitionProgress * 2f)
                            if (previewBitmap != null) {
                                Image(
                                    bitmap = previewBitmap,
                                    contentDescription = "$label preview",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .applyHubPtz(ptzTransform)
                                        .alpha(fadeOut),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        } else {
                            val fadeIn = (transitionProgress - 0.5f) * 2f
                            Image(
                                bitmap = transitionBitmap!!,
                                contentDescription = "$label transition",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .applyHubPtz(transitionPtzTransform)
                                    .alpha(fadeIn),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                    isTransitioning && transitionType == TransitionType.WIPE -> {
                        // WIPE: PVW slides in from left over PGM
                        if (previewBitmap != null) {
                            Image(
                                bitmap = previewBitmap,
                                contentDescription = "$label preview",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .applyHubPtz(ptzTransform),
                                contentScale = ContentScale.Fit
                            )
                        }
                        Image(
                            bitmap = transitionBitmap!!,
                            contentDescription = "$label transition",
                            modifier = Modifier
                                .fillMaxSize()
                                .applyHubPtz(transitionPtzTransform)
                                .clipToBounds()
                                .alpha(1f),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart
                        )
                        // Black cover for the un-wiped portion
                        val coverFraction = 1f - transitionProgress
                        if (coverFraction > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = ((1f - coverFraction) * 1000).dp.coerceAtMost(1000.dp))
                            ) {
                                // Overlay PGM on right side
                                if (previewBitmap != null) {
                                    Image(
                                        bitmap = previewBitmap,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .applyHubPtz(ptzTransform),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                        }
                    }
                    previewBitmap != null -> {
                        Image(
                            bitmap = previewBitmap,
                            contentDescription = "$label preview",
                            modifier = Modifier
                                .fillMaxSize()
                                .applyHubPtz(ptzTransform),
                            contentScale = ContentScale.Fit
                        )
                    }
                    else -> {
                        Text(
                            text = "NO SIGNAL",
                            color = TextTertiary,
                            fontSize = 10.sp,
                            fontFamily = JetBrainsMonoFamily,
                            letterSpacing = 2.sp
                        )
                    }
                }

                if (
                    transitionProgress <= 0f &&
                    (previewBitmap != null || previewSourceBitmap != null) &&
                    (
                        isPgm ||
                            ptzState.zoom > 1.01f ||
                            ptzState.ptzMode == PtzMode.REMOTE_PENDING
                    )
                ) {
                    PtzStatusOverlay(
                        state = ptzState,
                        maxZoom = maxPtzZoom,
                        isPgm = isPgm,
                        isLivePtzUnlocked = isLivePtzUnlocked,
                        onToggleLivePtzLock = onToggleLivePtzLock,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(6.dp)
                    )
                }
            }
        }
    }
}

/**
 * PGM and PVW viewport cards displayed side by side (horizontal) or stacked (vertical).
 * During transitions, the PGM card renders the transition effect.
 */
@Composable
fun ViewportPanel(
    pgmCameraName: String,
    pvwCameraName: String,
    pgmFps: Int,
    pvwFps: Int,
    pgmBitmap: ImageBitmap?,
    pgmSourceBitmap: Bitmap? = null,
    pvwBitmap: ImageBitmap?,
    enablePgmSpatialUpscaling: Boolean = false,
    pgmSpatialUpscaleOutputHeight: Int = 1080,
    pgmFrameSequence: Long = 0L,
    pvwFrameSequence: Long = 0L,
    onFrameDrawn: (String, Long) -> Unit = { _, _ -> },
    isVertical: Boolean = false,
    transitionProgress: Float = 0f,
    isTransitioning: Boolean = false,
    selectedTransition: TransitionType = TransitionType.CUT,
    pgmPtzState: HybridPtzState = HybridPtzState(),
    pvwPtzState: HybridPtzState = HybridPtzState(),
    pgmMaxPtzZoom: Float = 4f,
    pvwMaxPtzZoom: Float = 4f,
    isLivePtzUnlocked: Boolean = false,
    onToggleLivePtzLock: () -> Unit = {},
    onPtzGesture: (String, Float, Float, Float) -> Unit = { _, _, _, _ -> },
    onPtzDoubleTap: (String, Float, Float) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    if (isVertical) {
        Column(
            modifier = modifier.padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ViewportCard(
                label = "PGM",
                labelColor = ElectricRed,
                cameraName = pgmCameraName,
                fps = pgmFps,
                previewBitmap = pgmBitmap,
                previewSourceBitmap = pgmSourceBitmap,
                isPgm = true,
                isPvw = false,
                modifier = Modifier.fillMaxWidth(),
                transitionBitmap = if (isTransitioning) pvwBitmap else null,
                transitionProgress = if (isTransitioning) transitionProgress else 0f,
                transitionType = selectedTransition,
                frameSequence = pgmFrameSequence,
                transitionCameraName = pvwCameraName,
                transitionFrameSequence = pvwFrameSequence,
                onFrameDrawn = onFrameDrawn,
                enableSpatialUpscaling = enablePgmSpatialUpscaling,
                spatialUpscaleOutputHeight = pgmSpatialUpscaleOutputHeight,
                ptzState = pgmPtzState,
                transitionPtzState = pvwPtzState,
                maxPtzZoom = pgmMaxPtzZoom,
                isLivePtzUnlocked = isLivePtzUnlocked,
                onToggleLivePtzLock = onToggleLivePtzLock,
                onPtzGesture = onPtzGesture,
                onPtzDoubleTap = onPtzDoubleTap
            )
            ViewportCard(
                label = "PVW",
                labelColor = NeonGreen,
                cameraName = pvwCameraName,
                fps = pvwFps,
                previewBitmap = pvwBitmap,
                isPgm = false,
                isPvw = true,
                modifier = Modifier.fillMaxWidth(),
                frameSequence = pvwFrameSequence,
                onFrameDrawn = onFrameDrawn,
                ptzState = pvwPtzState,
                maxPtzZoom = pvwMaxPtzZoom,
                onPtzGesture = onPtzGesture,
                onPtzDoubleTap = onPtzDoubleTap
            )
        }
    } else {
        Row(
            modifier = modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ViewportCard(
                label = "PGM",
                labelColor = ElectricRed,
                cameraName = pgmCameraName,
                fps = pgmFps,
                previewBitmap = pgmBitmap,
                previewSourceBitmap = pgmSourceBitmap,
                isPgm = true,
                isPvw = false,
                modifier = Modifier.weight(1f),
                transitionBitmap = if (isTransitioning) pvwBitmap else null,
                transitionProgress = if (isTransitioning) transitionProgress else 0f,
                transitionType = selectedTransition,
                frameSequence = pgmFrameSequence,
                transitionCameraName = pvwCameraName,
                transitionFrameSequence = pvwFrameSequence,
                onFrameDrawn = onFrameDrawn,
                enableSpatialUpscaling = enablePgmSpatialUpscaling,
                spatialUpscaleOutputHeight = pgmSpatialUpscaleOutputHeight,
                ptzState = pgmPtzState,
                transitionPtzState = pvwPtzState,
                maxPtzZoom = pgmMaxPtzZoom,
                isLivePtzUnlocked = isLivePtzUnlocked,
                onToggleLivePtzLock = onToggleLivePtzLock,
                onPtzGesture = onPtzGesture,
                onPtzDoubleTap = onPtzDoubleTap
            )
            ViewportCard(
                label = "PVW",
                labelColor = NeonGreen,
                cameraName = pvwCameraName,
                fps = pvwFps,
                previewBitmap = pvwBitmap,
                isPgm = false,
                isPvw = true,
                modifier = Modifier.weight(1f),
                frameSequence = pvwFrameSequence,
                onFrameDrawn = onFrameDrawn,
                ptzState = pvwPtzState,
                maxPtzZoom = pvwMaxPtzZoom,
                onPtzGesture = onPtzGesture,
                onPtzDoubleTap = onPtzDoubleTap
            )
        }
    }
}

private fun Modifier.applyHubPtz(transform: HubPtzTransform): Modifier = graphicsLayer {
    scaleX = transform.scale
    scaleY = transform.scale
    translationX = (0.5f - transform.centerX) * size.width * transform.scale
    translationY = (0.5f - transform.centerY) * size.height * transform.scale
    clip = true
}

@Composable
private fun PtzStatusOverlay(
    state: HybridPtzState,
    maxZoom: Float,
    isPgm: Boolean,
    isLivePtzUnlocked: Boolean,
    onToggleLivePtzLock: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (modeLabel, modeColor) = when (state.ptzMode) {
        PtzMode.HUB -> "HUB" to CyanAccent
        PtzMode.REMOTE_PENDING -> "SYNC" to AmberYellow
        PtzMode.REMOTE -> "CAM" to NeonGreen
    }
    val showMode = state.zoom > 1.01f || state.ptzMode == PtzMode.REMOTE_PENDING
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showMode) Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(BackgroundDarker.copy(alpha = 0.82f))
                .border(1.dp, modeColor.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(1.dp))
                    .background(modeColor)
                    .padding(2.dp)
            )
            Text(
                text = modeLabel,
                color = modeColor,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = JetBrainsMonoFamily
            )
            Text(
                text = "${String.format(Locale.US, "%.1f", state.zoom)}x",
                color = TextPrimary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = JetBrainsMonoFamily
            )
            Text(
                text = "/ ${String.format(Locale.US, "%.1f", maxZoom)}",
                color = TextTertiary,
                fontSize = 7.sp,
                fontFamily = JetBrainsMonoFamily
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        if (isPgm) {
            val lockColor = if (isLivePtzUnlocked) ElectricRed else TextSecondary
            Text(
                text = if (isLivePtzUnlocked) "LIVE PTZ" else "PTZ LOCKED",
                color = lockColor,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = JetBrainsMonoFamily,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(BackgroundDarker.copy(alpha = 0.86f))
                    .border(1.dp, lockColor.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .clickable(onClick = onToggleLivePtzLock)
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            )
        }
    }
}
