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
 * Shifts the lightness and saturation of a Compose Color to produce
 * rich, dynamic tonal variations within the theme palette.
 */
private fun Color.shiftTonal(lightnessDelta: Float, saturationScale: Float = 1.0f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (red * 255).toInt().coerceIn(0, 255),
        (green * 255).toInt().coerceIn(0, 255),
        (blue * 255).toInt().coerceIn(0, 255),
        hsv,
    )
    hsv[1] = (hsv[1] * saturationScale).coerceIn(0.15f, 1.0f)
    hsv[2] = (hsv[2] + lightnessDelta).coerceIn(0.15f, 1.0f)
    val rgb = android.graphics.Color.HSVToColor(hsv)
    return Color(rgb)
}

/**
 * Material Design 3 Dynamic Counter-Gradient Frosted Glass Waveform Seekbar.
 * Features 3 frosted glass waves that dynamically transition across playback progress:
 * - Layer 1: Light -> Dark
 * - Layer 2: Dark -> Light (Vice-versa)
 * - Layer 3: Light -> Deep Vibrant Accent
 * creating a rich, iridescent multi-depth liquid optical effect.
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

    // 100% Material Design 3 Harmonized Theme Colors
    val primaryColor = if (isTranslucent) Color.White else MaterialTheme.colorScheme.primary
    val secondaryColor = if (isTranslucent) Color.White else MaterialTheme.colorScheme.secondary
    val tertiaryColor = if (isTranslucent) Color.White else MaterialTheme.colorScheme.tertiary

    val inactiveColor = if (isTranslucent) {
        Color.White.copy(alpha = 0.22f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    }
    val textColor = if (isTranslucent) {
        Color.White.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Faster, fluid wave animations
    val infiniteTransition = rememberInfiniteTransition(label = "MaterialGlassWaveAnimation")

    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "Phase1",
    )

    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
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
    val currPhase2 = if (isPlaying) phase2 + 1.2f else 1.2f
    val currPhase3 = if (isPlaying) phase3 else 0f

    // 3-Tier Amplitudes with smooth dampening on seek
    val density = LocalDensity.current
    val baseAmp1Px = with(density) { 13.0.dp.toPx() } // Deep ambient glass layer
    val baseAmp2Px = with(density) { 10.0.dp.toPx() } // Middle frosted glass layer
    val baseAmp3Px = with(density) { 7.5.dp.toPx() }  // Foreground luminous glass layer
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

    // Broad wavelengths for smooth, elegant rolling hills
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
                .height(50.dp)
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
            val centerY = height / 2f + 7.dp.toPx()
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

            // 2. Active 3-Layer Material Frosted Glass Waves with Dynamic Counter-Gradients
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

                        val step = 0.9f // Sub-pixel sampling resolution
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

                    val activeWidth = thumbX.coerceAtLeast(1f)

                    // Layer 1: Light -> Dark (Light soft tone at 0 -> Richer/Deeper tone at thumbX)
                    val layer1Light = tertiaryColor.shiftTonal(lightnessDelta = +0.18f, saturationScale = 0.85f)
                    val layer1Dark = tertiaryColor.shiftTonal(lightnessDelta = -0.15f, saturationScale = 1.30f)
                    drawPath(
                        path = filled1,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                layer1Light.copy(alpha = if (isTranslucent) 0.28f else 0.26f),
                                layer1Dark.copy(alpha = if (isTranslucent) 0.10f else 0.08f),
                            ),
                            start = Offset(0f, centerY - 20.dp.toPx()),
                            end = Offset(activeWidth, bottomY),
                        ),
                    )
                    drawPath(
                        path = contour1,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                layer1Light.copy(alpha = if (isTranslucent) 0.42f else 0.38f),
                                layer1Dark.copy(alpha = if (isTranslucent) 0.32f else 0.28f),
                            ),
                            startX = 0f,
                            endX = activeWidth,
                        ),
                        style = Stroke(width = 0.9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )

                    // Layer 2: Dark -> Light (Vice-Versa! Deeper rich tone at 0 -> Illuminated light tone at thumbX)
                    val layer2Dark = secondaryColor.shiftTonal(lightnessDelta = -0.16f, saturationScale = 1.30f)
                    val layer2Light = secondaryColor.shiftTonal(lightnessDelta = +0.18f, saturationScale = 0.85f)
                    drawPath(
                        path = filled2,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                layer2Dark.copy(alpha = if (isTranslucent) 0.58f else 0.54f),
                                layer2Light.copy(alpha = if (isTranslucent) 0.24f else 0.20f),
                            ),
                            start = Offset(0f, centerY - 15.dp.toPx()),
                            end = Offset(activeWidth, bottomY),
                        ),
                    )
                    drawPath(
                        path = contour2,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                layer2Dark.copy(alpha = if (isTranslucent) 0.72f else 0.66f),
                                layer2Light.copy(alpha = if (isTranslucent) 0.62f else 0.56f),
                            ),
                            startX = 0f,
                            endX = activeWidth,
                        ),
                        style = Stroke(width = 1.1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )

                    // Layer 3: Light -> Deep Vibrant Primary (Illuminated tint at 0 -> Deep saturated primary at thumbX)
                    val layer3Light = primaryColor.shiftTonal(lightnessDelta = +0.20f, saturationScale = 0.90f)
                    val layer3Dark = primaryColor.shiftTonal(lightnessDelta = -0.14f, saturationScale = 1.35f)
                    drawPath(
                        path = filled3,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                layer3Light.copy(alpha = if (isTranslucent) 0.94f else 0.90f),
                                layer3Dark.copy(alpha = if (isTranslucent) 0.58f else 0.50f),
                            ),
                            start = Offset(0f, centerY - 10.dp.toPx()),
                            end = Offset(activeWidth, bottomY),
                        ),
                    )
                    drawPath(
                        path = contour3,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                layer3Light.copy(alpha = if (isTranslucent) 0.98f else 0.95f),
                                layer3Dark.copy(alpha = if (isTranslucent) 0.92f else 0.88f),
                            ),
                            startX = 0f,
                            endX = activeWidth,
                        ),
                        style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )

                    // 3. Crisp Baseline Bar with Light -> Dark dynamic gradient
                    drawLine(
                        brush = Brush.horizontalGradient(
                            colors = listOf(layer3Light, layer3Dark),
                            startX = 0f,
                            endX = activeWidth,
                        ),
                        start = Offset(0f, centerY),
                        end = Offset(thumbX, centerY),
                        strokeWidth = baseTrackThicknessPx,
                        cap = StrokeCap.Round,
                    )
                }
            }

            // 4. Leading Thumb Indicator (At current playing position)
            if (state.durationMs > 0) {
                val thumbColor = primaryColor.shiftTonal(lightnessDelta = -0.10f, saturationScale = 1.25f)
                // Soft glow halo
                drawCircle(
                    color = thumbColor.copy(alpha = 0.28f),
                    radius = thumbRadiusPx + 3.dp.toPx(),
                    center = Offset(thumbX, centerY),
                )
                // Solid center circle
                drawCircle(
                    color = thumbColor,
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
