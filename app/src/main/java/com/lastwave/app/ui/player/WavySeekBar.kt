package com.lastwave.app.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lastwave.app.playback.MusicPlayerState
import kotlin.math.PI
import kotlin.math.sin

/**
 * Samsung One UI styled multi-layered filled wave seekbar with differential opacity waves.
 */
@Composable
fun WavySeekBar(
    state: MusicPlayerState,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isTranslucent: Boolean = false,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val durationMs = state.durationMs.coerceAtLeast(1)
    val currentFraction = (state.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    val shownFraction = if (dragging) dragFraction else currentFraction
    val shownMs = (shownFraction * durationMs).toLong()

    val activeBaseColor = if (isTranslucent) Color.White else MaterialTheme.colorScheme.primary
    val primaryWaveColor = activeBaseColor.copy(alpha = if (isTranslucent) 0.95f else 0.90f)
    val secondaryWaveColor = activeBaseColor.copy(alpha = if (isTranslucent) 0.38f else 0.40f)
    val inactiveColor = if (isTranslucent) {
        Color.White.copy(alpha = 0.25f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    }
    val textColor = if (isTranslucent) {
        Color.White.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Continuously animate dual wave phases for organic depth
    val infiniteTransition = rememberInfiniteTransition(label = "SamsungWavyTransitions")
    val primaryPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "PrimaryPhase",
    )

    val secondaryPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "SecondaryPhase",
    )

    val isPlaying = state.isPlaying && !dragging
    val currPrimaryPhase = if (isPlaying) primaryPhase else 0f
    val currSecondaryPhase = if (isPlaying) secondaryPhase + 1.2f else 1.2f

    // Smoothly dampen amplitude when dragging
    val density = LocalDensity.current
    val basePrimaryAmpPx = with(density) { 5.5.dp.toPx() }
    val draggingAmpPx = with(density) { 1.5.dp.toPx() }
    val targetPrimaryAmp = if (dragging) draggingAmpPx else basePrimaryAmpPx

    val primaryAmp by animateFloatAsState(
        targetValue = targetPrimaryAmp,
        animationSpec = tween(durationMillis = 200),
        label = "PrimaryAmp",
    )

    val secondaryAmpPx = with(density) { 7.0.dp.toPx() }
    val secondaryAmp by animateFloatAsState(
        targetValue = if (dragging) draggingAmpPx else secondaryAmpPx,
        animationSpec = tween(durationMillis = 200),
        label = "SecondaryAmp",
    )

    val primaryWaveLengthPx = with(density) { 32.dp.toPx() }
    val secondaryWaveLengthPx = with(density) { 40.dp.toPx() }
    val baseTrackThicknessPx = with(density) { 5.5.dp.toPx() }
    val thumbRadiusPx = with(density) { 7.5.dp.toPx() }
    val transitionLengthPx = with(density) { 18.dp.toPx() }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .pointerInput(state.durationMs) {
                    if (state.durationMs <= 0) return@pointerInput
                    detectTapGestures(
                        onPress = { offset ->
                            val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                            dragging = true
                            dragFraction = fraction
                            val released = tryAwaitRelease()
                            if (released) {
                                onSeek((dragFraction * durationMs).toLong())
                            }
                            dragging = false
                        },
                    )
                }
                .pointerInput(state.durationMs) {
                    if (state.durationMs <= 0) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            onSeek((dragFraction * durationMs).toLong())
                            dragging = false
                        },
                        onDragCancel = {
                            dragging = false
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                    )
                },
        ) {
            val width = size.width
            val centerY = size.height / 2f
            val thumbX = (shownFraction * width).coerceIn(0f, width)
            val halfThickness = baseTrackThicknessPx / 2f
            val bottomY = centerY + halfThickness

            // 1. Inactive background track (capsule line from thumbX to end)
            if (thumbX < width) {
                drawLine(
                    color = inactiveColor,
                    start = Offset(thumbX, centerY),
                    end = Offset(width, centerY),
                    strokeWidth = baseTrackThicknessPx,
                    cap = StrokeCap.Round,
                )
            }

            if (thumbX > 0f) {
                // Rounded container clip for clean start cap
                val clipBounds = Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = androidx.compose.ui.geometry.Rect(
                                left = 0f,
                                top = 0f,
                                right = thumbX + thumbRadiusPx,
                                bottom = size.height,
                            ),
                            topLeft = CornerRadius(halfThickness, halfThickness),
                            bottomLeft = CornerRadius(halfThickness, halfThickness),
                            topRight = CornerRadius(0f, 0f),
                            bottomRight = CornerRadius(0f, 0f),
                        )
                    )
                }

                clipPath(clipBounds) {
                    // 2. Layer 1: Secondary / Background Wave (lower opacity, taller wave)
                    val secondaryPath = Path()
                    secondaryPath.moveTo(0f, bottomY)
                    secondaryPath.lineTo(0f, centerY - halfThickness)

                    var x = 0f
                    val step = 3f
                    while (x <= thumbX) {
                        val startEnvelope = (x / transitionLengthPx).coerceIn(0f, 1f)
                        val endEnvelope = ((thumbX - x) / transitionLengthPx).coerceIn(0f, 1f)
                        val envelope = startEnvelope * endEnvelope

                        val angle = ((x / secondaryWaveLengthPx) * 2 * PI).toFloat() - currSecondaryPhase
                        val waveY = centerY - halfThickness - (sin(angle) * secondaryAmp * envelope)
                        secondaryPath.lineTo(x, waveY)
                        x += step
                    }
                    secondaryPath.lineTo(thumbX, bottomY)
                    secondaryPath.close()

                    drawPath(path = secondaryPath, color = secondaryWaveColor)

                    // 3. Layer 2: Primary / Foreground Wave (higher opacity filled wave)
                    val primaryPath = Path()
                    primaryPath.moveTo(0f, bottomY)
                    primaryPath.lineTo(0f, centerY - halfThickness)

                    x = 0f
                    while (x <= thumbX) {
                        val startEnvelope = (x / transitionLengthPx).coerceIn(0f, 1f)
                        val endEnvelope = ((thumbX - x) / transitionLengthPx).coerceIn(0f, 1f)
                        val envelope = startEnvelope * endEnvelope

                        val angle = ((x / primaryWaveLengthPx) * 2 * PI).toFloat() - currPrimaryPhase
                        val waveY = centerY - halfThickness - (sin(angle) * primaryAmp * envelope)
                        primaryPath.lineTo(x, waveY)
                        x += step
                    }
                    primaryPath.lineTo(thumbX, bottomY)
                    primaryPath.close()

                    drawPath(path = primaryPath, color = primaryWaveColor)
                }
            }

            // 4. Leading Thumb Circle
            if (state.durationMs > 0) {
                drawCircle(
                    color = activeBaseColor,
                    radius = thumbRadiusPx,
                    center = Offset(thumbX, centerY),
                )
            }
        }

        Spacer(Modifier.height(2.dp))

        // Time labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(shownMs),
                style = MaterialTheme.typography.labelMedium,
                color = textColor,
            )
            Text(
                text = formatTime(state.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = textColor,
            )
        }
    }
}
