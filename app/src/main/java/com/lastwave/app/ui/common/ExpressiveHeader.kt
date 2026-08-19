package com.lastwave.app.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
 * The depth behind it is a soft two-stop radial gradient (accent color
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
    Box(modifier.fillMaxWidth()) {
        Surface(
            shape = HeaderShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp,
            // No shadowElevation: a drop shadow under a shape with two
            // sharp top corners and two large rounded bottom ones reads as
            // an odd, hard-edged band right under the header rather than a
            // soft shadow — the gradient glow above already gives the
            // header depth without it.
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .drawGlowBackground(glow)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    )
                    .padding(horizontal = 20.dp)
                    .padding(top = 2.dp, bottom = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        FilledTonalIconButton(
                            onClick = onBack,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Spacer(Modifier.width(12.dp))
                    }

                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        subtitle?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        actions()
                    }
                }
            }
        }
    }
}

/** Paints a soft radial glow (accent color fading to nothing) whose center
 *  slowly drifts side to side — a continuous, gentle infinite-transition
 *  animation, not a fixed static blob — used as [ExpressiveHeader]'s
 *  background instead of a blurred shape (see the class doc for why it's
 *  a plain gradient rather than Modifier.blur()). */
@Composable
private fun Modifier.drawGlowBackground(color: Color): Modifier {
    val transition = rememberInfiniteTransition(label = "headerGlowDrift")
    val drift by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "headerGlowDriftX",
    )
    return this.drawBehind {
        val cx = size.width / 2f + drift * size.width * 0.30f
        val cy = size.height * 0.4f
        val radius = (size.width.coerceAtLeast(size.height) * 0.9f).coerceAtLeast(1f)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.16f), color.copy(alpha = 0f)),
                center = Offset(cx, cy),
                radius = radius,
            ),
        )
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
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Icon(icon, contentDescription = contentDescription)
    }
}
