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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lastwave.app.playback.MusicPlayerState
import kotlin.math.PI
import kotlin.math.sin

/**
 * Premium Continuous Waveform Seekbar.
 * Features 3 continuous translucent undulating wave layers spanning the entire width,
 * with luminous vertical gradients and contour highlights on the active side, and subtle
 * transparent waves on the inactive side.
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
    val inactiveBaseColor = if (isTranslucent) Color.White else MaterialTheme.colorScheme.onSurface
    val textColor = if (isTranslucent) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant

    // Continuous real-time wave animation
    val infiniteTransition = rememberInfiniteTransition(label = "OdysseyWaveTransition")

    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "WavePhase1",
    )

    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "WavePhase2",
    )

    val phase3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "WavePhase3",
    )

    val isPlaying = state.isPlaying && !dragging
    val currPhase1 = if (isPlaying) phase1 + 1.8f else 1.8f
    val currPhase2 = if (isPlaying) phase2 + 0.9f else 0.9f
    val currPhase3 = if (isPlaying) phase3 else 0f

    // Amplitude dampening on drag
    val density = LocalDensity.current
    val baseAmp1Px = with(density) { 9.0.dp.toPx() } // Tallest ambient layer
    val baseAmp2Px = with(density) { 7.0.dp.toPx() } // Mid-height wave
    val baseAmp3Px = with(density) { 5.2.dp.toPx() } // Foreground ribbon
    val draggingAmpPx = with(density) { 1.2.dp.toPx() }

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

    val waveLength1Px = with(density) { 120.dp.toPx() }
    val waveLength2Px = with(density) { 95.dp.toPx() }
    val waveLength3Px = with(density) { 78.dp.toPx() }

    val baseTrackThicknessPx = with(density) { 4.0.dp.toPx() }
    val thumbRadiusPx = with(density) { 7.0.dp.toPx() }
    val edgeTransitionPx = with(density) { 20.dp.toPx() }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
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
            val centerY = height / 2f + 4.dp.toPx()
            val thumbX = (shownFraction * width).coerceIn(0f, width)
            val halfThickness = baseTrackThicknessPx / 2f
            val bottomY = centerY + halfThickness
            val topBaselineY = centerY - halfThickness

            // Build full continuous wave paths across [0, width]
            fun buildWavePath(
                wavelength: Float,
                amplitude: Float,
                phase: Float,
            ): Pair<Path, Path> {
                val filledPath = Path()
                val contourPath = Path()
                filledPath.moveTo(0f, bottomY)
                filledPath.lineTo(0f, topBaselineY)
                contourPath.moveTo(0f, topBaselineY)

                val step = 2.0f
                var x = 0f
                while (x <= width) {
                    val tStart = (x / edgeTransitionPx).coerceIn(0f, 1f)
                    val tEnd = ((width - x) / edgeTransitionPx).coerceIn(0f, 1f)
                    val startEnv = tStart * tStart * (3f - 2f * tStart)
                    val endEnv = tEnd * tEnd * (3f - 2f * tEnd)
                    val envelope = startEnv * endEnv

                    val angle = ((x / wavelength) * 2 * PI).toFloat() - phase
                    val waveHeight = (0.5f + 0.5f * sin(angle)) * amplitude * envelope
                    val y = topBaselineY - waveHeight

                    filledPath.lineTo(x, y)
                    contourPath.lineTo(x, y)
                    x += step
                }
                filledPath.lineTo(width, bottomY)
                filledPath.close()

                return Pair(filledPath, contourPath)
            }

            val (filled1, contour1) = buildWavePath(waveLength1Px, amp1, currPhase1)
            val (filled2, contour2) = buildWavePath(waveLength2Px, amp2, currPhase2)
            val (filled3, contour3) = buildWavePath(waveLength3Px, amp3, currPhase3)

            val fullTrackClip = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = androidx.compose.ui.geometry.Rect(0f, 0f, width, height),
                        topLeft = CornerRadius(halfThickness, halfThickness),
                        bottomLeft = CornerRadius(halfThickness, halfThickness),
                        topRight = CornerRadius(halfThickness, halfThickness),
                        bottomRight = CornerRadius(halfThickness, halfThickness),
                    )
                )
            }

            clipPath(fullTrackClip) {
                // --- ZONE 1: ACTIVE SIDE (Vibrant Illuminated Gradients & Highlights) ---
                if (thumbX > 0f) {
                    clipRect(left = 0f, top = 0f, right = thumbX, bottom = height) {
                        // Layer 1 (Deepest Background Wave)
                        drawPath(
                            path = filled1,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    activeColor.copy(alpha = if (isTranslucent) 0.35f else 0.30f),
                                    activeColor.copy(alpha = if (isTranslucent) 0.15f else 0.10f),
                                ),
                                startY = centerY - 14.dp.toPx(),
                                endY = bottomY,
                            ),
                        )
                        drawPath(
                            path = contour1,
                            color = activeColor.copy(alpha = if (isTranslucent) 0.50f else 0.45f),
                            style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )

                        // Layer 2 (Middle Harmonic Wave)
                        drawPath(
                            path = filled2,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    activeColor.copy(alpha = if (isTranslucent) 0.65f else 0.60f),
                                    activeColor.copy(alpha = if (isTranslucent) 0.30f else 0.25f),
                                ),
                                startY = centerY - 10.dp.toPx(),
                                endY = bottomY,
                            ),
                        )
                        drawPath(
                            path = contour2,
                            color = activeColor.copy(alpha = if (isTranslucent) 0.80f else 0.75f),
                            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )

                        // Layer 3 (Foreground Vibrant Wave)
                        drawPath(
                            path = filled3,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    activeColor.copy(alpha = if (isTranslucent) 0.95f else 0.92f),
                                    activeColor.copy(alpha = if (isTranslucent) 0.60f else 0.55f),
                                ),
                                startY = centerY - 6.dp.toPx(),
                                endY = bottomY,
                            ),
                        )
                        drawPath(
                            path = contour3,
                            color = activeColor,
                            style = Stroke(width = 2.0.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }

                // --- ZONE 2: INACTIVE SIDE (Subtle Translucent Ambient Waves) ---
                if (thumbX < width) {
                    clipRect(left = thumbX, top = 0f, right = width, bottom = height) {
                        // Layer 1
                        drawPath(
                            path = filled1,
                            color = inactiveBaseColor.copy(alpha = 0.06f),
                        )
                        drawPath(
                            path = contour1,
                            color = inactiveBaseColor.copy(alpha = 0.12f),
                            style = Stroke(width = 1.0.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )

                        // Layer 2
                        drawPath(
                            path = filled2,
                            color = inactiveBaseColor.copy(alpha = 0.12f),
                        )
                        drawPath(
                            path = contour2,
                            color = inactiveBaseColor.copy(alpha = 0.22f),
                            style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )

                        // Layer 3
                        drawPath(
                            path = filled3,
                            color = inactiveBaseColor.copy(alpha = 0.20f),
                        )
                        drawPath(
                            path = contour3,
                            color = inactiveBaseColor.copy(alpha = 0.35f),
                            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
            }

            // --- THUMB INDICATOR ---
            if (state.durationMs > 0) {
                // Soft glow halo
                drawCircle(
                    color = activeColor.copy(alpha = 0.30f),
                    radius = thumbRadiusPx + 3.dp.toPx(),
                    center = Offset(thumbX, centerY),
                )
                // Solid thumb circle
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
