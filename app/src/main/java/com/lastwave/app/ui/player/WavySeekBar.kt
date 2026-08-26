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
 * Ultra-smooth Samsung One UI Media Player Seekbar with 3 distinct flowing waves
 * of calibrated wavelengths, speeds, and progressive translucency.
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

    // 3 distinct layers with clear progressive opacity differences
    val layer1Color = activeBaseColor.copy(alpha = if (isTranslucent) 0.25f else 0.22f) // Deepest ambient swell
    val layer2Color = activeBaseColor.copy(alpha = if (isTranslucent) 0.58f else 0.55f) // Intermediate wave
    val layer3Color = activeBaseColor.copy(alpha = if (isTranslucent) 0.95f else 0.92f) // Primary vibrant ribbon

    val inactiveColor = if (isTranslucent) {
        Color.White.copy(alpha = 0.24f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    }
    val textColor = if (isTranslucent) {
        Color.White.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Faster, lively, and fluid animation speeds across all 3 layers
    val infiniteTransition = rememberInfiniteTransition(label = "SmoothWaveTransitions")

    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "Phase1",
    )

    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "Phase2",
    )

    val phase3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "Phase3",
    )

    val isPlaying = state.isPlaying && !dragging
    val currPhase1 = if (isPlaying) phase1 + 2.2f else 2.2f
    val currPhase2 = if (isPlaying) phase2 + 1.1f else 1.1f
    val currPhase3 = if (isPlaying) phase3 else 0f

    // Smooth amplitude dampening on seek
    val density = LocalDensity.current
    val baseAmp1Px = with(density) { 6.5.dp.toPx() } // Tallest background swell
    val baseAmp2Px = with(density) { 4.8.dp.toPx() } // Mid-height wave
    val baseAmp3Px = with(density) { 3.6.dp.toPx() } // Focused foreground crest
    val draggingAmpPx = with(density) { 0.8.dp.toPx() }

    val amp1 by animateFloatAsState(
        targetValue = if (dragging) draggingAmpPx else baseAmp1Px,
        animationSpec = tween(durationMillis = 180),
        label = "Amp1",
    )
    val amp2 by animateFloatAsState(
        targetValue = if (dragging) draggingAmpPx else baseAmp2Px,
        animationSpec = tween(durationMillis = 180),
        label = "Amp2",
    )
    val amp3 by animateFloatAsState(
        targetValue = if (dragging) draggingAmpPx else baseAmp3Px,
        animationSpec = tween(durationMillis = 180),
        label = "Amp3",
    )

    // Distinct wavelengths for multi-harmonic wave separation
    val waveLength1Px = with(density) { 96.dp.toPx() } // Broad wide swell
    val waveLength2Px = with(density) { 74.dp.toPx() } // Medium harmonic
    val waveLength3Px = with(density) { 58.dp.toPx() } // Lively compact wave

    val baseTrackThicknessPx = with(density) { 4.5.dp.toPx() }
    val thumbRadiusPx = with(density) { 7.5.dp.toPx() }
    val transitionLengthPx = with(density) { 18.dp.toPx() }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
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
            val topBaselineY = centerY - halfThickness

            // 1. Inactive background track (smooth capsule bar from thumbX to end)
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
                // Rounded start cap clip
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
                    val step = 1.8f // Ultra-high precision sampling for 120Hz displays

                    // Layer 1: Deep Background Wave (broadest, softest swell)
                    val path1 = Path()
                    path1.moveTo(0f, bottomY)
                    path1.lineTo(0f, topBaselineY)
                    var x = 0f
                    while (x <= thumbX) {
                        val tStart = (x / transitionLengthPx).coerceIn(0f, 1f)
                        val tEnd = ((thumbX - x) / transitionLengthPx).coerceIn(0f, 1f)
                        // Smoothstep curve for seamless non-pointy envelope
                        val startEnv = tStart * tStart * (3f - 2f * tStart)
                        val endEnv = tEnd * tEnd * (3f - 2f * tEnd)
                        val envelope = startEnv * endEnv

                        val angle = ((x / waveLength1Px) * 2 * PI).toFloat() - currPhase1
                        val waveHeight = (0.5f + 0.5f * sin(angle)) * amp1 * envelope
                        path1.lineTo(x, topBaselineY - waveHeight)
                        x += step
                    }
                    path1.lineTo(thumbX, bottomY)
                    path1.close()
                    drawPath(path = path1, color = layer1Color)

                    // Layer 2: Intermediate Wave (medium harmonic swell)
                    val path2 = Path()
                    path2.moveTo(0f, bottomY)
                    path2.lineTo(0f, topBaselineY)
                    x = 0f
                    while (x <= thumbX) {
                        val tStart = (x / transitionLengthPx).coerceIn(0f, 1f)
                        val tEnd = ((thumbX - x) / transitionLengthPx).coerceIn(0f, 1f)
                        val startEnv = tStart * tStart * (3f - 2f * tStart)
                        val endEnv = tEnd * tEnd * (3f - 2f * tEnd)
                        val envelope = startEnv * endEnv

                        val angle = ((x / waveLength2Px) * 2 * PI).toFloat() - currPhase2
                        val waveHeight = (0.5f + 0.5f * sin(angle)) * amp2 * envelope
                        path2.lineTo(x, topBaselineY - waveHeight)
                        x += step
                    }
                    path2.lineTo(thumbX, bottomY)
                    path2.close()
                    drawPath(path = path2, color = layer2Color)

                    // Layer 3: Foreground Wave (vibrant primary ribbon)
                    val path3 = Path()
                    path3.moveTo(0f, bottomY)
                    path3.lineTo(0f, topBaselineY)
                    x = 0f
                    while (x <= thumbX) {
                        val tStart = (x / transitionLengthPx).coerceIn(0f, 1f)
                        val tEnd = ((thumbX - x) / transitionLengthPx).coerceIn(0f, 1f)
                        val startEnv = tStart * tStart * (3f - 2f * tStart)
                        val endEnv = tEnd * tEnd * (3f - 2f * tEnd)
                        val envelope = startEnv * endEnv

                        val angle = ((x / waveLength3Px) * 2 * PI).toFloat() - currPhase3
                        val waveHeight = (0.5f + 0.5f * sin(angle)) * amp3 * envelope
                        path3.lineTo(x, topBaselineY - waveHeight)
                        x += step
                    }
                    path3.lineTo(thumbX, bottomY)
                    path3.close()
                    drawPath(path = path3, color = layer3Color)
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
