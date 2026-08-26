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
 * Authentic Samsung One UI Media Player Seekbar.
 * Features wide, gentle rolling liquid acoustic swells with multi-layer opacity,
 * a flat continuous baseline, and smooth interactive scrubbing.
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
    val secondaryWaveColor = activeBaseColor.copy(alpha = if (isTranslucent) 0.35f else 0.35f)
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

    // Slow, soothing continuous wave motion
    val infiniteTransition = rememberInfiniteTransition(label = "SamsungWaveAnimation")
    val primaryPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "PrimaryPhase",
    )

    val secondaryPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "SecondaryPhase",
    )

    val isPlaying = state.isPlaying && !dragging
    val currPrimaryPhase = if (isPlaying) primaryPhase else 0f
    val currSecondaryPhase = if (isPlaying) secondaryPhase + 1.4f else 1.4f

    // Smoothly dampen amplitude when dragging / scrubbing
    val density = LocalDensity.current
    val basePrimaryAmpPx = with(density) { 7.0.dp.toPx() }
    val baseSecondaryAmpPx = with(density) { 10.0.dp.toPx() }
    val draggingAmpPx = with(density) { 1.0.dp.toPx() }

    val primaryAmp by animateFloatAsState(
        targetValue = if (dragging) draggingAmpPx else basePrimaryAmpPx,
        animationSpec = tween(durationMillis = 220),
        label = "PrimaryAmp",
    )

    val secondaryAmp by animateFloatAsState(
        targetValue = if (dragging) draggingAmpPx else baseSecondaryAmpPx,
        animationSpec = tween(durationMillis = 220),
        label = "SecondaryAmp",
    )

    val baseTrackThicknessPx = with(density) { 5.0.dp.toPx() }
    val thumbRadiusPx = with(density) { 7.5.dp.toPx() }
    val transitionLengthPx = with(density) { 24.dp.toPx() }

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

            // Wide rolling wavelength proportional to width: only 1 to 1.5 gentle swells across the screen
            val primaryWaveLengthPx = (width * 0.72f).coerceAtLeast(with(density) { 200.dp.toPx() })
            val secondaryWaveLengthPx = (width * 0.88f).coerceAtLeast(with(density) { 250.dp.toPx() })

            // 1. Inactive background track (straight capsule bar from thumbX to end)
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
                    // 2. Layer 1: Background Secondary Wave (softer, taller swell)
                    val secondaryPath = Path()
                    secondaryPath.moveTo(0f, bottomY)
                    secondaryPath.lineTo(0f, topBaselineY)

                    var x = 0f
                    val step = 3f
                    while (x <= thumbX) {
                        val startEnvelope = (x / transitionLengthPx).coerceIn(0f, 1f)
                        val endEnvelope = ((thumbX - x) / transitionLengthPx).coerceIn(0f, 1f)
                        val envelope = startEnvelope * endEnvelope

                        val angle = ((x / secondaryWaveLengthPx) * 2 * PI).toFloat() - currSecondaryPhase
                        // Broad positive swell rising above baseline
                        val waveHeight = (0.5f + 0.5f * sin(angle)) * secondaryAmp * envelope
                        val waveY = topBaselineY - waveHeight
                        secondaryPath.lineTo(x, waveY)
                        x += step
                    }
                    secondaryPath.lineTo(thumbX, bottomY)
                    secondaryPath.close()

                    drawPath(path = secondaryPath, color = secondaryWaveColor)

                    // 3. Layer 2: Foreground Primary Wave (vibrant, gentle swell)
                    val primaryPath = Path()
                    primaryPath.moveTo(0f, bottomY)
                    primaryPath.lineTo(0f, topBaselineY)

                    x = 0f
                    while (x <= thumbX) {
                        val startEnvelope = (x / transitionLengthPx).coerceIn(0f, 1f)
                        val endEnvelope = ((thumbX - x) / transitionLengthPx).coerceIn(0f, 1f)
                        val envelope = startEnvelope * endEnvelope

                        val angle = ((x / primaryWaveLengthPx) * 2 * PI).toFloat() - currPrimaryPhase
                        val waveHeight = (0.5f + 0.5f * sin(angle)) * primaryAmp * envelope
                        val waveY = topBaselineY - waveHeight
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
