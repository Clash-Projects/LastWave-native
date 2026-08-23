package com.lastwave.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp

/**
 * The one "refresh this" trigger with an actual in-flight loading state
 * (Playlist's Regenerate button) — a filled tonal container with the
 * live accent color, spinning smoothly while refreshing and settling back
 * with a spring rather than snapping to rest. Dynamic color support is
 * inherent, not a separate branch: reading colorScheme.primaryContainer
 * already reflects whichever accent source is currently active.
 */
@Composable
fun ExpressiveRefreshButton(
    isRefreshing: Boolean,
    onClick: () -> Unit,
    contentDescription: String = "Refresh",
    modifier: Modifier = Modifier,
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            rotation.animateTo(
                targetValue = rotation.value + 360f,
                animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing)),
            )
        } else {
            // Settle forward to the next full turn, not back to 0 — from a
            // mid-spin angle like 750 degrees, animating to a literal 0
            // would spin backward almost a full turn instead of easing to
            // a natural stop.
            val nextFullTurn = (kotlin.math.ceil(rotation.value / 360f).toInt().coerceAtLeast(1)) * 360f
            rotation.animateTo(
                targetValue = nextFullTurn,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
            )
            rotation.snapTo(0f)
        }
    }

    FilledTonalIconButton(
        onClick = onClick,
        enabled = !isRefreshing,
        shape = RoundedCornerShape(14.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = modifier,
    ) {
        Icon(
            Icons.Filled.Refresh,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(28.dp).rotate(rotation.value),
        )
    }
}
