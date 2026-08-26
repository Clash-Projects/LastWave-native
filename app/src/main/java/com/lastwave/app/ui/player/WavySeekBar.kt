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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lastwave.app.playback.MusicPlayerState
import kotlin.math.PI
import kotlin.math.sin

/**
 * Ultra-smooth Bidirectional Multi-Layer Waveform Seekbar.
 * Features 3 glowing translucent wave ribbons that undulate both above and below
 * the horizontal center line with quintic smootherstep boundary convergence.
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

    val activeColor = if (isTranslucent) Color.White else MaterialTheme.colorScheme.primary
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

    // Continuous real-time wave animation
    val infiniteTransition = rememberInfiniteTransition(label = "SilkWaveAnimation")

    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "Phase1",
    )

    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "Phase2",
    )

    val phase3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "Phase3",
    )

    val isPlaying = state.isPlaying && !dragging
    val currPhase1 = if (isPlaying) phase1 + 2.0f else 2.0f
    val currPhase2 = if (isPlaying) phase2 + 1.0f else 1.0f
    val currPhase3 = if (isPlaying) phase3 else 0f

    // Softly dampened amplitudes for smooth seeking
    val density = LocalDensity.current
    val baseAmp1Px = with(density) { 9.5.dp.toPx() }  // Ambient swell
    val baseAmp2Px = with(density) { 7.5.dp.toPx() }  // Mid-harmonic wave
    val baseAmp3Px = with(density) { 5.5.dp.toPx() }  // Foreground ribbon
    val draggingAmpPx = with(density) { 1.0.dp.toPx() }

    val amp1 by animateFloatAsState(
        targetValue = if (dragging) draggingAmpPx else baseAmp1Px,
        animationSpec = tween(durationMillis = 200),
        label = "Amp1",
    )
    val amp2 by animateFloatAsState(
        targetValue = if (dragging) draggingAmpPx else baseAmp2Px,
        animationSpec = tween(durationMillis = 200),
        label = "Amp2",
    )
    val amp3 by animateFloatAsState(
        targetValue = if (dragging) draggingAmpPx else baseAmp3Px,
        animationSpec = tween(durationMillis = 200),
        label = "Amp3",
    )

    val waveLength1Px = with(density) { 120.dp.toPx() }
    val waveLength2Px = with(density) { 95.dp.toPx() }
    val waveLength3Px = with(density) { 75.dp.toPx() }

    val baseTrackThicknessPx = with(density) { 4.5.dp.toPx() }
    val thumbRadiusPx = with(density) { 7.5.dp.toPx() }
    val transitionLengthPx = with(density) { 26.dp.toPx() }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
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
            val height = size.height
            val centerY = height / 2f
            val thumbX = (shownFraction * width).coerceIn(0f, width)
            val halfThickness = baseTrackThicknessPx / 2f

            // 1. Inactive background track: Straight capsule bar from thumbX to end
            if (thumbX < width) {
                drawLine(
                    color = inactiveColor,
                    start = Offset(thumbX, centerY),
                    end = Offset(width, centerY),
                    strokeWidth = baseTrackThicknessPx,
                    cap = StrokeCap.Round,
                )
            }

            // 2. Active waves: Bidirectional flowing ribbon oscillating above and below centerY
            if (thumbX > 0f) {
                val clipBounds = Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = androidx.compose.ui.geometry.Rect(
                                left = 0f,
                                top = 0f,
                                right = thumbX + thumbRadiusPx,
                                bottom = height,
                            ),
                            topLeft = CornerRadius(halfThickness, halfThickness),
                            bottomLeft = CornerRadius(halfThickness, halfThickness),
                            topRight = CornerRadius(0f, 0f),
                            bottomRight = CornerRadius(0f, 0f),
                        )
                    )
                }

                clipPath(clipBounds) {
                    // Quintic smootherstep function for seamless C² continuity
                    fun smootherstep(t: Float): Float {
                        val c = t.coerceIn(0f, 1f)
                        return c * c * c * (c * (c * 6f - 15f) + 10f)
                    }

                    fun buildBidirectionalWave(
                        wavelength: Float,
                        amplitude: Float,
                        phase: Float,
                    ): Pair<Path, Path> {
                        val filledPath = Path()
                        val topContourPath = Path()

                        val step = 1.0f // 1px sampling resolution

                        // Forward pass: trace top edge from 0 to thumbX
                        var x = 0f
                        while (x <= thumbX) {
                            val startEnv = smootherstep(x / transitionLengthPx)
                            val endEnv = smootherstep((thumbX - x) / transitionLengthPx)
                            val envelope = startEnv * endEnv

                            val angle = ((x / wavelength) * 2 * PI).toFloat() - phase
                            // Oscillates both above (-sin) and below (+sin) centerY
                            val waveCenterY = centerY - sin(angle) * amplitude * envelope
                            val topY = waveCenterY - halfThickness

                            if (x == 0f) {
                                filledPath.moveTo(0f, topY)
                                topContourPath.moveTo(0f, topY)
                            } else {
                                filledPath.lineTo(x, topY)
                                topContourPath.lineTo(x, topY)
                            }
                            x += step
                        }

                        // Backward pass: trace bottom edge from thumbX back to 0
                        x = thumbX
                        while (x >= 0f) {
                            val startEnv = smootherstep(x / transitionLengthPx)
                            val endEnv = smootherstep((thumbX - x) / transitionLengthPx)
                            val envelope = startEnv * endEnv

                            val angle = ((x / wavelength) * 2 * PI).toFloat() - phase
                            val waveCenterY = centerY - sin(angle) * amplitude * envelope
                            val bottomY = waveCenterY + halfThickness

                            filledPath.lineTo(x, bottomY)
                            x -= step
                        }
                        filledPath.close()

                        return Pair(filledPath, topContourPath)
                    }

                    val (filled1, contour1) = buildBidirectionalWave(waveLength1Px, amp1, currPhase1)
                    val (filled2, contour2) = buildBidirectionalWave(waveLength2Px, amp2, currPhase2)
                    val (filled3, contour3) = buildBidirectionalWave(waveLength3Px, amp3, currPhase3)

                    // Layer 1: Ambient Background Layer
                    drawPath(
                        path = filled1,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                activeColor.copy(alpha = if (isTranslucent) 0.35f else 0.30f),
                                activeColor.copy(alpha = if (isTranslucent) 0.15f else 0.12f),
                            ),
                            startY = centerY - 14.dp.toPx(),
                            endY = centerY + 14.dp.toPx(),
                        ),
                    )
                    drawPath(
                        path = contour1,
                        color = activeColor.copy(alpha = if (isTranslucent) 0.45f else 0.40f),
                        style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )

                    // Layer 2: Middle Harmonic Layer
                    drawPath(
                        path = filled2,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                activeColor.copy(alpha = if (isTranslucent) 0.65f else 0.60f),
                                activeColor.copy(alpha = if (isTranslucent) 0.30f else 0.26f),
                            ),
                            startY = centerY - 10.dp.toPx(),
                            endY = centerY + 10.dp.toPx(),
                        ),
                    )
                    drawPath(
                        path = contour2,
                        color = activeColor.copy(alpha = if (isTranslucent) 0.75f else 0.70f),
                        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )

                    // Layer 3: Foreground Ribbon
                    drawPath(
                        path = filled3,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                activeColor.copy(alpha = if (isTranslucent) 0.95f else 0.92f),
                                activeColor.copy(alpha = if (isTranslucent) 0.60f else 0.55f),
                            ),
                            startY = centerY - 6.dp.toPx(),
                            endY = centerY + 6.dp.toPx(),
                        ),
                    )
                    drawPath(
                        path = contour3,
                        color = activeColor,
                        style = Stroke(width = 2.0.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }

            // 3. Leading Thumb Indicator
            if (state.durationMs > 0) {
                // Outer glow halo
                drawCircle(
                    color = activeColor.copy(alpha = 0.28f),
                    radius = thumbRadiusPx + 3.dp.toPx(),
                    center = Offset(thumbX, centerY),
                )
                // Solid center circle
                drawCircle(
                    color = activeColor,
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
