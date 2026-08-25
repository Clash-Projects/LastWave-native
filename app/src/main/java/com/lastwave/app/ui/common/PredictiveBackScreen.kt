package com.lastwave.app.ui.common

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlinx.coroutines.CancellationException

/**
 * Wraps a pushed screen (Settings / Search / Discover / Genres — any
 * NavHost destination reached by navigating on top of MainShell) with
 * Android's Predictive Back visuals: as the user drags the system back
 * gesture in from the edge, this screen scales down and its corners round
 * off — the same "lifting off the stack" treatment modern Google apps use.
 * Releasing past the gesture's commit point finishes the animation and
 * calls [onBack] (pop the stack); releasing early cancels and the screen
 * springs back to full size.
 *
 * Anchored to the edge the gesture actually started from, not the screen's
 * center: BackEventCompat reports which edge (LEFT/RIGHT) and how far down
 * the screen (touchY) the drag began, and the shrink's pivot is set to
 * that exact point via TransformOrigin. That's what makes it read as
 * "shrinking away from the corner/edge you dragged from" instead of
 * uniformly shrinking toward the middle of the screen — the same
 * touch-anchored feel the system's own predictive back uses, and it also
 * nudges the screen a few dp further toward that edge as it shrinks, on
 * top of the scale, for the same reason.
 *
 * This animates the CURRENT (exiting) screen only — since NavHost here
 * only keeps the active back-stack entry composed, there's no destination
 * screen underneath to reveal/blur during the gesture. A blurred preview
 * of the previous screen would need it kept composed alongside this one
 * (e.g. via a two-pane Crossfade or navigation-compose 2.8+'s built-in
 * predictive-back transitions) — noted as a follow-up if that fuller
 * effect is wanted; this still gives real, system-driven progress-based
 * animation rather than a canned pop transition.
 *
 * Falls back gracefully pre-Android 13 / wherever predictive back isn't
 * available: PredictiveBackHandler's Flow simply completes without
 * meaningful per-frame progress, so [onBack] still fires correctly — just
 * without the scale/round animation playing first.
 */
@Composable
fun PredictiveBackScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }

    // Which edge the gesture started from (BackEventCompat.EDGE_LEFT/RIGHT)
    // and how far down the screen, as a 0..1 fraction of height — together
    // these place the shrink's pivot at the actual corner the finger is
    // dragging from, instead of a fixed corner or the screen center.
    var edge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    var touchYFraction by remember { mutableFloatStateOf(0.5f) }
    var containerHeightPx by remember { mutableFloatStateOf(1f) }

    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { backEvent: BackEventCompat ->
                edge = backEvent.swipeEdge
                if (containerHeightPx > 0f) {
                    touchYFraction = (backEvent.touchY / containerHeightPx).coerceIn(0f, 1f)
                }
                progress.snapTo(backEvent.progress)
            }
            // The flow above completes on EVERY back invocation the system
            // hands this handler — not just a held/dragged predictive-back
            // gesture. A quick tap of the on-screen back button, the
            // hardware back key, or a fast gesture-nav swipe all complete
            // it too. Checking progress.value > 0f alone wasn't enough:
            // even a fast, un-held swipe still emits a few real progress
            // events before completing (system sends them per-frame while
            // the finger moves, regardless of whether it paused), so
            // progress.value often lands above 0 for a fast flick too —
            // and this extra forced tween up to 1f then played a visible
            // "flourish" on release even for that fast swipe, which is
            // exactly what shouldn't happen. Real predictive back should
            // only visibly shrink while a gesture is actually being held
            // and dragged (the live snapTo calls above already do that
            // correctly); once released — committed or not — there's
            // nothing left to animate here, so just finish immediately
            // with whatever progress was last observed, instead of
            // forcing one final animated step first.
            onBack()
            // Reset immediately once we've actually left this screen —
            // without this, `progress` was left sitting at 1f forever
            // (nothing else ever set it back to 0), so this same
            // composable instance (kept alive by MainShell's pager /
            // NavHost's saved state) would render permanently shrunk the
            // NEXT time it's shown again, with no gesture in progress and
            // no way to un-shrink it. snapTo instead of animateTo since
            // the screen is already gone by this point — nothing visible
            // to animate.
            progress.snapTo(0f)
        } catch (e: CancellationException) {
            // Released early / gesture cancelled — spring back to rest.
            progress.animateTo(
                0f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            )
        }
    }

    val p = progress.value
    val pivotX = if (edge == BackEventCompat.EDGE_RIGHT) 1f else 0f
    // A small extra push toward the edge/corner being dragged from, on top
    // of the scale — this is what sells "shrinking away from a corner"
    // rather than just "shrinking in place".
    val edgeSign = if (edge == BackEventCompat.EDGE_RIGHT) 1f else -1f

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { coords -> containerHeightPx = coords.size.height.toFloat() }
            .graphicsLayer {
                val scale = 1f - 0.12f * p
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(pivotX, touchYFraction)
                translationX = edgeSign * 28.dp.toPx() * p
                alpha = 1f - 0.25f * p
                shape = RoundedCornerShape(lerp(0.dp, 32.dp, p))
                // Clipping a full-screen LazyColumn at rest forces extra GPU
                // work on every frame. It is only visually needed while a
                // predictive-back gesture has actually rounded the corners.
                clip = p > 0f
                shadowElevation = if (p > 0f) 24f else 0f
            },
    ) {
        content()
    }
}
