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
 * Shifts the Hue and boosts Saturation/Value of a Compose Color to produce
 * a harmonious, vibrant neon companion hue.
 */
private fun Color.shiftNeonHue(hueOffset: Float, saturationScale: Float = 1.2f, valueScale: Float = 1.15f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (red * 255).toInt().coerceIn(0, 255),
        (green * 255).toInt().coerceIn(0, 255),
        (blue * 255).toInt().coerceIn(0, 255),
        hsv,
    )
    if (hsv[1] < 0.15f) {
        hsv[0] = 265f // Electric Indigo default
        hsv[1] = 0.85f
    }
    var h = (hsv[0] + hueOffset) % 360f
    if (h < 0f) h += 360f
    hsv[0] = h
    hsv[1] = (hsv[1] * saturationScale).coerceIn(0.70f, 1.0f)
    hsv[2] = (hsv[2] * valueScale).coerceIn(0.85f, 1.0f)
    val rgb = android.graphics.Color.HSVToColor(hsv)
    return Color(rgb)
}

/**
 * Premium Odyssey Glowing Neon Waveform Seekbar.
 * Features 3 fluid, multi-chromatic neon wave hills (electric blue, indigo, and glowing purple)
 * filled down to a crisp glowing baseline with razor-sharp rim highlights,
 * quintic smootherstep boundary convergence, and a clean straight unplayed baseline.
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

    val rawBaseColor = MaterialTheme.colorScheme.primary

    // 3 Distinct Glowing Neon Hues (Electric Blue, Deep Indigo, Vivid Purple)
    val color1 = remember(rawBaseColor, isTranslucent) {
        if (isTranslucent) Color(0xFF448AFF) else rawBaseColor.shiftNeonHue(-32f) // Electric Blue / Cyan Neon
    }
    val color2 = remember(rawBaseColor, isTranslucent) {
        if (isTranslucent) Color(0xFF7C4DFF) else rawBaseColor.shiftNeonHue(+25f) // Rich Glowing Purple
    }
    val color3 = remember(rawBaseColor, isTranslucent) {
        if (isTranslucent) Color(0xFFB388FF) else rawBaseColor.shiftNeonHue(0f)   // Luminous Violet / Primary
    }

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

    // Faster, highly fluid wave animations
    val infiniteTransition = rememberInfiniteTransition(label = "OdysseyNeonWaveAnimation")

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
            animation = tween(durationMillis = 1650, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "Phase2",
    )

    val phase3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "Phase3",
    )

    val isPlaying = state.isPlaying && !dragging
    val currPhase1 = if (isPlaying) phase1 + 2.2f else 2.2f
    val currPhase2 = if (isPlaying) phase2 + 1.2f else 1.2f
    val currPhase3 = if (isPlaying) phase3 else 0f

    // 3-Tier Amplitudes with smooth dampening on seek
    val density = LocalDensity.current
    val baseAmp1Px = with(density) { 14.0.dp.toPx() } // Deepest blue hill
    val baseAmp2Px = with(density) { 11.0.dp.toPx() } // Glowing purple hill
    val baseAmp3Px = with(density) { 8.2.dp.toPx() }  // Foreground violet hill
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

    // Broad wavelengths for smooth rolling hills
    val waveLength1Px = with(density) { 160.dp.toPx() }
    val waveLength2Px = with(density) { 125.dp.toPx() }
    val waveLength3Px = with(density) { 95.dp.toPx() }

    val baseTrackThicknessPx = with(density) { 4.5.dp.toPx() }
    val thumbRadiusPx = with(density) { 7.5.dp.toPx() }
    val transitionLengthPx = with(density) { 28.dp.toPx() }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
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
            val centerY = height / 2f + 8.dp.toPx()
            val thumbX = (shownFraction * width).coerceIn(0f, width)
            val halfThickness = baseTrackThicknessPx / 2f
            val bottomY = centerY + halfThickness
            val topBaselineY = centerY - halfThickness

            // 1. Inactive background track: Straight horizontal capsule bar from thumbX to end
            if (thumbX < width) {
                drawLine(
                    color = inactiveColor,
                    start = Offset(thumbX, centerY),
                    end = Offset(width, centerY),
                    strokeWidth = baseTrackThicknessPx,
                    cap = StrokeCap.Round,
                )
            }

            // 2. Active 3-Layer Multi-Chromatic Neon Waves: Confined strictly to [0, thumbX]
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

                    // Builds a smooth filled wave body down to the bottom baseline
                    fun buildFilledWave(
                        wavelength: Float,
                        amplitude: Float,
                        phase: Float,
                    ): Pair<Path, Path> {
                        val filledPath = Path()
                        val contourPath = Path()
                        filledPath.moveTo(0f, bottomY)
                        filledPath.lineTo(0f, topBaselineY)
                        contourPath.moveTo(0f, topBaselineY)

                        val step = 0.9f // Sub-pixel resolution
                        var x = 0f
                        while (x <= thumbX) {
                            val startEnv = smootherstep(x / transitionLengthPx)
                            val endEnv = smootherstep((thumbX - x) / transitionLengthPx)
                            val envelope = startEnv * endEnv

                            val angle = ((x / wavelength) * 2 * PI).toFloat() - phase
                            val waveHeight = (0.5f + 0.5f * sin(angle)) * amplitude * envelope
                            val y = topBaselineY - waveHeight

                            filledPath.lineTo(x, y)
                            contourPath.lineTo(x, y)
                            x += step
                        }
                        filledPath.lineTo(thumbX, bottomY)
                        filledPath.close()

                        return Pair(filledPath, contourPath)
                    }

                    val (filled1, contour1) = buildFilledWave(waveLength1Px, amp1, currPhase1)
                    val (filled2, contour2) = buildFilledWave(waveLength2Px, amp2, currPhase2)
                    val (filled3, contour3) = buildFilledWave(waveLength3Px, amp3, currPhase3)

                    // Layer 1: Electric Blue / Cyan Neon Hill (~35% opacity)
                    drawPath(
                        path = filled1,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                color1.copy(alpha = 0.42f),
                                color1.copy(alpha = 0.12f),
                            ),
                            startY = centerY - 22.dp.toPx(),
                            endY = bottomY,
                        ),
                    )
                    drawPath(
                        path = contour1,
                        color = color1.copy(alpha = 0.55f),
                        style = Stroke(width = 1.0.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )

                    // Layer 2: Glowing Purple / Indigo Neon Hill (~68% opacity)
                    drawPath(
                        path = filled2,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                color2.copy(alpha = 0.72f),
                                color2.copy(alpha = 0.25f),
                            ),
                            startY = centerY - 16.dp.toPx(),
                            endY = bottomY,
                        ),
                    )
                    drawPath(
                        path = contour2,
                        color = color2.copy(alpha = 0.82f),
                        style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )

                    // Layer 3: Vibrant Violet / Primary Neon Hill (~96% opacity)
                    drawPath(
                        path = filled3,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                color3.copy(alpha = 0.98f),
                                color3.copy(alpha = 0.55f),
                            ),
                            startY = centerY - 11.dp.toPx(),
                            endY = bottomY,
                        ),
                    )
                    drawPath(
                        path = contour3,
                        color = color3,
                        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )

                    // 3. Crisp Illuminated Baseline Highlight
                    drawLine(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                color1.copy(alpha = 0.85f),
                                color2.copy(alpha = 0.90f),
                                color3.copy(alpha = 0.95f),
                            ),
                            startX = 0f,
                            endX = thumbX,
                        ),
                        start = Offset(0f, centerY),
                        end = Offset(thumbX, centerY),
                        strokeWidth = baseTrackThicknessPx,
                        cap = StrokeCap.Round,
                    )
                }
            }

            // 4. Leading Thumb Indicator
            if (state.durationMs > 0) {
                // Soft glowing halo
                drawCircle(
                    color = color3.copy(alpha = 0.35f),
                    radius = thumbRadiusPx + 3.dp.toPx(),
                    center = Offset(thumbX, centerY),
                )
                // Solid center circle
                drawCircle(
                    color = color3,
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
