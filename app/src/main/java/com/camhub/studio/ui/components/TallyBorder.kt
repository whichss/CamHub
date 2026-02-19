package com.camhub.studio.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.camhub.studio.ui.theme.ElectricRed
import com.camhub.studio.ui.theme.NeonGreen

enum class TallyState { PGM, PVW, NONE }

@Composable
fun TallyBorder(
    tallyState: TallyState,
    cornerRadius: Dp = 12.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "tally_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tally_pulse_alpha"
    )

    val shape = RoundedCornerShape(cornerRadius)

    when (tallyState) {
        TallyState.PGM -> {
            Box(
                modifier = modifier
                    .drawBehind {
                        // Pulsing glow
                        drawRoundRect(
                            color = ElectricRed.copy(alpha = pulseAlpha * 0.3f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                cornerRadius.toPx() + 4.dp.toPx()
                            ),
                            style = Stroke(width = 6.dp.toPx())
                        )
                    }
                    .border(2.dp, ElectricRed.copy(alpha = pulseAlpha), shape)
            ) {
                content()
            }
        }
        TallyState.PVW -> {
            Box(
                modifier = modifier
                    .border(2.dp, NeonGreen.copy(alpha = 0.6f), shape)
            ) {
                content()
            }
        }
        TallyState.NONE -> {
            Box(modifier = modifier) {
                content()
            }
        }
    }
}
