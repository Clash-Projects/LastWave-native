package com.lastwave.app.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp

/**
 * Opt-in "liquid glass" material flag (Settings → Experimental → Liquid
 * Glass). Provided once from [LastWaveTheme]; every consumer reads the same
 * value so toggling the setting restyles the whole app in one recomposition.
 * Always `false` until provided — i.e. previews and anything outside the
 * theme get the classic opaque look.
 */
val LocalLiquidGlass = staticCompositionLocalOf { false }

/** Convenience read for call sites that only need the boolean. */
@Composable
fun isLiquidGlassEnabled(): Boolean = LocalLiquidGlass.current

/**
 * Draws the specular dressing that makes a translucent surface read as
 * glass rather than plain transparency: a soft top sheen (light catching
 * the upper edge) plus a 1dp diagonal hairline border (bright at the
 * top-leading corner, fading around the shape) painted ON TOP of whatever
 * the decorated node already rendered.
 *
 * Applied only to the floating chrome pieces (nav dock, mini player,
 * header) whose fills come from the translucent scheme roles — regular
 * cards keep their clean flat look so the effect stays tasteful. No-op
 * when [enabled] is false, so callers can pass the flag straight through.
 */
fun Modifier.liquidGlassChrome(shape: Shape, enabled: Boolean): Modifier =
    if (!enabled) this else drawWithContent {
        drawContent()
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = when (outline) {
            is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
            is Outline.Generic -> outline.path
            is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        }

        // Top sheen: fades out well before mid-height so it reads as a
        // reflection on glass, not as a gradient wash over the content.
        clipPath(path) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.10f),
                    0.55f to Color.White.copy(alpha = 0.02f),
                    1f to Color.Transparent,
                    startY = 0f,
                    endY = size.height,
                ),
            )
        }

        // Hairline specular border, brightest where a light source would hit.
        drawPath(
            path = path,
            brush = Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.34f),
                0.45f to Color.White.copy(alpha = 0.07f),
                1f to Color.White.copy(alpha = 0.16f),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ),
            style = Stroke(width = 1.dp.toPx()),
        )
    }

/**
 * Ambient depth for liquid-glass mode: three huge, very faint accent-tinted
 * radial glows drifting slowly behind ALL content. Without something behind
 * them, translucent containers are visually indistinguishable from opaque
 * ones on a flat background — this gives the glass something to refract.
 * Same gentle infinite-drift pattern ExpressiveHeader already uses, tuned
 * slower and dimmer because it runs app-wide.
 *
 * Drawn with drawBehind at the theme root, beneath every screen, so no
 * layout, hit-testing or scrolling behavior can change.
 */
@Composable
fun Modifier.liquidGlassAmbient(primary: Color, tertiary: Color): Modifier {
    val transition = rememberInfiniteTransition(label = "liquidGlassAmbient")
    val drift by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "liquidGlassAmbientDrift",
    )
    return drawBehind {
        val maxDim = maxOf(size.width, size.height).coerceAtLeast(1f)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    primary.copy(alpha = 0.11f),
                    primary.copy(alpha = 0.03f),
                    primary.copy(alpha = 0f),
                ),
                center = Offset(size.width * (0.20f + 0.07f * drift), size.height * 0.16f),
                radius = maxDim * 0.85f,
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    tertiary.copy(alpha = 0.09f),
                    tertiary.copy(alpha = 0.025f),
                    tertiary.copy(alpha = 0f),
                ),
                center = Offset(size.width * (0.86f - 0.05f * drift), size.height * 0.52f),
                radius = maxDim * 0.75f,
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    primary.copy(alpha = 0.07f),
                    primary.copy(alpha = 0f),
                ),
                center = Offset(size.width * (0.48f + 0.09f * drift), size.height * 1.02f),
                radius = maxDim * 0.90f,
            ),
        )
    }
}
