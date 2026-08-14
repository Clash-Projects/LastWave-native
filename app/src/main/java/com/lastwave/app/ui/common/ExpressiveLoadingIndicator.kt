package com.lastwave.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp

/**
 * The full-screen "this screen is loading" state, shared across Genres and
 * Search (and anywhere else a whole screen — not just one row or button —
 * is waiting on a first load). A gentle spring-driven breathing circle
 * around the spinner rather than a bare CircularProgressIndicator floating
 * on its own, matching Material 3 Expressive's spring-physics motion
 * signature. Small inline spinners (pagination footers, in-button loading
 * states) intentionally stay as plain CircularProgressIndicator — this is
 * for the primary "waiting on this screen" moment, not every spinner.
 */
@Composable
fun ExpressiveLoadingIndicator(message: String? = null, modifier: Modifier = Modifier) {
    // infiniteRepeatable() only accepts a duration-based spec (tween /
    // keyframes) — spring() isn't one (its length depends on the physics,
    // not a fixed time), so infiniteRepeatable(spring(...)) doesn't
    // compile. A manual Animatable + back-and-forth loop is the correct
    // way to get a genuinely infinite spring animation (see
    // GenerationProgressCard's identical fix).
    val breathe = remember { Animatable(0.9f) }
    LaunchedEffect(Unit) {
        while (true) {
            breathe.animateTo(1.1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessVeryLow))
            breathe.animateTo(0.9f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessVeryLow))
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(56.dp).scale(breathe.value),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        strokeWidth = 3.dp,
                    )
                }
            }
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
