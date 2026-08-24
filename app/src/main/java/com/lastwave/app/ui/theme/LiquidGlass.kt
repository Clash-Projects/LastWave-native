package com.lastwave.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp

/** Shared opt-in flag for Settings > Experimental > Liquid Glass. */
val LocalLiquidGlass = staticCompositionLocalOf { false }

@Composable
fun isLiquidGlassEnabled(): Boolean = LocalLiquidGlass.current

/**
 * Cached liquid-glass optical stack with a protected foreground plane.
 * Substrate and reflection render behind content; text/icons render once at
 * native sharpness; only the one-pixel Fresnel edge renders afterward.
 */
fun Modifier.liquidGlassChrome(shape: Shape, enabled: Boolean): Modifier =
    if (!enabled) this else drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = when (outline) {
            is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
            is Outline.Generic -> outline.path
            is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        }
        val substrate = Color(0xFF090A0D).copy(alpha = 0.62f)
        val reflection = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.115f),
            0.22f to Color.White.copy(alpha = 0.045f),
            0.58f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.10f),
            startY = 0f,
            endY = size.height,
        )
        val refraction = Brush.linearGradient(
            0f to Color(0xFFB8D8FF).copy(alpha = 0.035f),
            0.48f to Color.Transparent,
            1f to Color(0xFFFFD8F0).copy(alpha = 0.025f),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        val fresnelEdge = Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.48f),
            0.20f to Color.White.copy(alpha = 0.18f),
            0.58f to Color.White.copy(alpha = 0.045f),
            1f to Color.White.copy(alpha = 0.22f),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        val edgeWidth = 1.dp.toPx()

        onDrawWithContent {
            clipPath(path) {
                drawRect(substrate)
                drawRect(reflection)
                drawRect(refraction)
            }
            drawContent()
            drawPath(path, fresnelEdge, style = Stroke(width = edgeWidth))
        }
    }

/** Static, cached accent depth behind the whole app in liquid-glass mode. */
@Composable
fun Modifier.liquidGlassAmbient(primary: Color, tertiary: Color): Modifier =
    drawWithCache {
        val maxDim = maxOf(size.width, size.height).coerceAtLeast(1f)
        val topGlow = Brush.radialGradient(
            colors = listOf(
                primary.copy(alpha = 0.11f),
                primary.copy(alpha = 0.03f),
                primary.copy(alpha = 0f),
            ),
            center = Offset(size.width * 0.18f, size.height * 0.14f),
            radius = maxDim * 0.85f,
        )
        val sideGlow = Brush.radialGradient(
            colors = listOf(
                tertiary.copy(alpha = 0.09f),
                tertiary.copy(alpha = 0.025f),
                tertiary.copy(alpha = 0f),
            ),
            center = Offset(size.width * 0.87f, size.height * 0.52f),
            radius = maxDim * 0.75f,
        )
        val bottomGlow = Brush.radialGradient(
            colors = listOf(
                primary.copy(alpha = 0.07f),
                primary.copy(alpha = 0f),
            ),
            center = Offset(size.width * 0.52f, size.height * 1.02f),
            radius = maxDim * 0.90f,
        )

        onDrawBehind {
            drawRect(topGlow)
            drawRect(sideGlow)
            drawRect(bottomGlow)
        }
    }
