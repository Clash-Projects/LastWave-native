package com.lastwave.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * A small rubber-band "squeeze" for scrollable content dragged past its
 * bounds — or that has nothing to scroll at all, like Generate's list when
 * it fits on screen without overflowing.
 *
 * v3 — the two previous versions (both since removed after real-device
 * reports of a phantom "extra movement" after a normal scroll settled)
 * shared a suspect neither actually addressed: Android's OWN default
 * stretch/glow overscroll (on by default for every scrollable Compose
 * component on API 31+) was never disabled, so it kept running
 * SIMULTANEOUSLY with this custom one. Two independent overscroll
 * systems reacting to the same boundary — the platform's own spring-back
 * timing and this one's — landing at slightly different moments reads
 * exactly like "it already stopped, then moved again". This version's
 * actual fix is disabling the platform effect for the wrapped content via
 * [withoutPlatformOverscroll] (LocalOverscrollConfiguration = null) and
 * only ever running ONE overscroll system at a time — this one.
 *
 * The drag-tracking itself is unchanged from v2: a plain synchronous
 * float updated directly in onPostScroll (no coroutine, nothing to race),
 * switching to an Animatable only for the spring-back once the drag
 * actually ends (onPostFling, itself already sequential).
 */
@Composable
fun Modifier.wobbleOverscroll(): Modifier {
    var raw by remember { mutableFloatStateOf(0f) }
    val settleAnim = remember { Animatable(0f) }
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y != 0f) {
                    raw = (raw + available.y * 0.30f).coerceIn(-32f, 32f)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (raw != 0f) {
                    settleAnim.snapTo(raw)
                    raw = 0f
                    settleAnim.animateTo(
                        0f,
                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                    )
                }
                return Velocity.Zero
            }
        }
    }

    return this
        .nestedScroll(connection)
        .graphicsLayer {
            // While actively dragging: follow `raw` directly (synchronous,
            // no animation lag). Once released: `raw` is zeroed and
            // `settleAnim` takes over the spring-back, so there's always
            // exactly one source of truth for translationY, never both at
            // once fighting each other.
            translationY = if (raw != 0f) raw else settleAnim.value
            clip = true
        }
}

/** Disables Android's own default stretch/glow overscroll for whatever
 *  scrollable content is composed inside [content] — see
 *  [wobbleOverscroll]'s doc comment for why this matters: without it, the
 *  platform's own overscroll effect keeps running underneath a custom one
 *  and the two visibly conflict at the scroll boundary. */
@Suppress("DEPRECATION")
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun WithoutPlatformOverscroll(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.foundation.LocalOverscrollConfiguration provides null,
        content = content,
    )
}
