package com.lastwave.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.liquidGlassChrome

/** Only the bottom corners are rounded, and a modest 24dp at that (not
 *  36dp) — a short header (just a title row, no back button, minimal
 *  padding) can be barely taller than 2x a large radius, and when the two
 *  bottom-corner arcs get close enough to touch they don't blend, they
 *  clip into a visible seam right where they meet. 24dp stays safely
 *  smaller than the shortest real header's content height on every screen
 *  that uses this. */
private val HeaderShape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)

/**
 * The one container every major screen's header sits inside. Full-bleed
 * width with no side margins and no gap above it — it IS the top of the
 * screen, edge to edge, with only its bottom edge rounded.
 *
 * The depth behind it is a pair of broad radial gradients (accent colors
 * fading to fully transparent) painted directly as a background, not a
 * blurred solid shape — Modifier.blur() clamps/repeats pixels at its own
 * layer bounds, which reads as a hard smear or a visible line right at the
 * glow's edge instead of a smooth fade to nothing. A gradient that's
 * already transparent at its own edge has no edge to see.
 *
 * Window-inset aware: its CONTENT pads for the status bar, while the
 * colored surface itself extends up behind the status bar — callers never
 * add their own top safe-drawing padding above this.
 *
 * For a screen with only a title (a tab root, no back target) pass
 * `onBack = null`; for a pushed screen pass a pop callback and it renders a
 * leading back button in the same tonal family as the trailing [actions].
 */
@Composable
fun ExpressiveHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val glow = MaterialTheme.colorScheme.primary
    val secondaryGlow = MaterialTheme.colorScheme.tertiary
    // Liquid Glass: the header surface turns translucent via the scheme and
    // gets a specular sheen + hairline border. No-op when setting is off.
    val liquidGlass = LocalLiquidGlass.current
    Box(modifier.fillMaxWidth().zIndex(1f)) {
        Surface(
            shape = HeaderShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp,
            // No shadowElevation: a drop shadow under a shape with two
            // sharp top corners and two large rounded bottom ones reads as
            // an odd, hard-edged band right under the header rather than a
            // soft shadow — the gradient glow above already gives the
            // header depth without it.
            modifier = Modifier.fillMaxWidth().liquidGlassChrome(HeaderShape, liquidGlass),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .drawGlowBackground(glow, secondaryGlow)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    )
                    .padding(horizontal = 18.dp)
                    .padding(top = 0.dp, bottom = 4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        FilledTonalIconButton(
                            onClick = onBack,
                            modifier = Modifier.size(34.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                            ),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                    }

                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = if (onBack != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        subtitle?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        actions()
                    }
                }
            }
        }
    }
}

/** Cached accent glows for [ExpressiveHeader]. Keeping these static avoids
 * repainting two full-width gradients every frame on every screen. */
@Composable
private fun Modifier.drawGlowBackground(color: Color, secondaryColor: Color): Modifier {
    return this.drawWithCache {
        val cx = size.width * 0.58f
        val cy = size.height * 0.46f
        val radius = (size.width.coerceAtLeast(size.height) * 1.08f).coerceAtLeast(1f)
        val secondaryCx = size.width * 0.38f
        val secondaryRadius = (size.width.coerceAtLeast(size.height) * 0.72f).coerceAtLeast(1f)
        val secondaryBrush = Brush.radialGradient(
            colors = listOf(
                secondaryColor.copy(alpha = 0.06f),
                secondaryColor.copy(alpha = 0.02f),
                secondaryColor.copy(alpha = 0f),
            ),
            center = Offset(secondaryCx, size.height * 0.78f),
            radius = secondaryRadius,
        )
        val primaryBrush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = 0.09f),
                color.copy(alpha = 0.03f),
                color.copy(alpha = 0f),
            ),
            center = Offset(cx, cy),
            radius = radius,
        )
        onDrawBehind {
            drawRect(brush = secondaryBrush)
            drawRect(brush = primaryBrush)
        }
    }
}

/** A trailing action icon for [ExpressiveHeader] (or any other header-style
 *  surface) — a small filled-tonal circular button matching the leading
 *  back button's tone, so every icon in a header reads as one family
 *  instead of each screen picking its own IconButton style. */
@Composable
fun HeaderActionIcon(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(34.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
        ),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
    }
}
