package com.lastwave.app.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One shared slider look for the whole app: a sleek circular knob riding a
 * short rounded capsule track, instead of Material 3's tall vertical thumb.
 *
 * The knob is drawn as three concentric circles — a soft accent halo that
 * grows while the finger is down, the solid accent body, and a small surface
 * "core" that keeps it legible against a matching-colored track.
 */
object ExpressiveSliderDefaults {
    val ThumbRadius: Dp = 9.dp
    val TrackHeight: Dp = 6.dp

    /** Box the thumb is measured in. The halo has to fit inside it, and the
     *  slider derives its horizontal inset from this width. */
    val ThumbBox: Dp = 26.dp
}

/** The shared circular knob. Reused by every slider in the app. */
@Composable
fun ExpressiveSliderThumb(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
    coreColor: Color = MaterialTheme.colorScheme.surface,
    radius: Dp = ExpressiveSliderDefaults.ThumbRadius,
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val dragged by interactionSource.collectIsDraggedAsState()
    val active = pressed || dragged
    val halo by animateFloatAsState(
        targetValue = if (active) 1f else 0.45f,
        animationSpec = ExpressiveMotion.smoothSpring(),
        label = "sliderThumbHalo",
    )
    val knob = if (enabled) color else color.copy(alpha = 0.38f)

    Box(
        modifier = Modifier.size(ExpressiveSliderDefaults.ThumbBox),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(ExpressiveSliderDefaults.ThumbBox)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val bodyRadius = radius.toPx()
            drawCircle(
                color = knob.copy(alpha = 0.22f),
                radius = bodyRadius + (4.dp.toPx() * halo),
                center = center,
            )
            drawCircle(color = knob, radius = bodyRadius, center = center)
            drawCircle(color = coreColor, radius = bodyRadius * 0.36f, center = center)
        }
    }
}

/** The shared capsule track, with clearance either side of the knob. */
@Composable
fun ExpressiveSliderTrack(
    fraction: Float,
    enabled: Boolean = true,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f),
    height: Dp = ExpressiveSliderDefaults.TrackHeight,
) {
    val active = if (enabled) activeColor else activeColor.copy(alpha = 0.38f)
    val inactive = if (enabled) inactiveColor else inactiveColor.copy(alpha = 0.5f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(ExpressiveSliderDefaults.ThumbBox),
    ) {
        val h = height.toPx()
        val top = (size.height - h) / 2f
        val corner = CornerRadius(h / 2f, h / 2f)
        val split = (size.width * fraction.coerceIn(0f, 1f))
        drawRoundRect(
            color = inactive,
            topLeft = Offset(0f, top),
            size = Size(size.width, h),
            cornerRadius = corner,
        )
        if (split > 0f) {
            drawRoundRect(
                color = active,
                topLeft = Offset(0f, top),
                size = Size(split, h),
                cornerRadius = corner,
            )
        }
    }
}

/**
 * Drop-in replacement for Material 3's [Slider] wearing the app's circular
 * knob. Same parameter names as the Material slider so call sites read the
 * same way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressiveSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f),
) {
    val interactionSource = remember { MutableInteractionSource() }
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        modifier = modifier,
        interactionSource = interactionSource,
        colors = SliderDefaults.colors(
            // Every visible pixel is drawn by the slots below; the Material
            // colors only still exist so ticks stay invisible.
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
            disabledActiveTickColor = Color.Transparent,
            disabledInactiveTickColor = Color.Transparent,
        ),
        thumb = {
            ExpressiveSliderThumb(
                interactionSource = interactionSource,
                enabled = enabled,
                color = activeColor,
            )
        },
        track = {
            ExpressiveSliderTrack(
                fraction = fraction,
                enabled = enabled,
                activeColor = activeColor,
                inactiveColor = inactiveColor,
            )
        },
    )
}
