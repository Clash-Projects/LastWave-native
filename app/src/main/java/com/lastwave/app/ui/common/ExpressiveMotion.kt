package com.lastwave.app.ui.common

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/** Shared, restrained Material 3 Expressive motion language for the app. */
object ExpressiveMotion {
    const val Quick = 160
    const val Standard = 300
    const val Emphasized = 440

    fun <T> spatialSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    fun <T> smoothSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    fun forwardEnter(): EnterTransition =
        fadeIn(tween(Standard, easing = FastOutSlowInEasing)) +
            slideInHorizontally(tween(Emphasized, easing = FastOutSlowInEasing)) { it / 10 } +
            scaleIn(tween(Standard, easing = FastOutSlowInEasing), initialScale = 0.985f)

    fun forwardExit(): ExitTransition =
        fadeOut(tween(Quick)) +
            scaleOut(tween(Standard, easing = FastOutSlowInEasing), targetScale = 0.985f)

    fun backEnter(): EnterTransition =
        fadeIn(tween(Standard, easing = FastOutSlowInEasing)) +
            slideInHorizontally(tween(Emphasized, easing = FastOutSlowInEasing)) { -it / 12 } +
            scaleIn(tween(Standard, easing = FastOutSlowInEasing), initialScale = 0.985f)

    fun backExit(): ExitTransition =
        fadeOut(tween(Quick)) +
            slideOutHorizontally(tween(Standard, easing = FastOutSlowInEasing)) { it / 12 } +
            scaleOut(tween(Standard, easing = FastOutSlowInEasing), targetScale = 0.985f)
}
