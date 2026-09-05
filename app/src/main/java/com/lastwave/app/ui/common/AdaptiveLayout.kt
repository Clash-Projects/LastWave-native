package com.lastwave.app.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Standard Material 3 window size classes based on viewport width breakpoints.
 * - COMPACT: Standard phones in portrait (< 600dp)
 * - MEDIUM: Foldables unfolded, 7-8" tablets, portrait 10" tablets (600dp..839dp)
 * - EXPANDED: Large tablets (10"+), landscape tablets, desktop/Chromebook (>= 840dp)
 */
enum class WindowSizeClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

@Composable
fun rememberWindowSizeClass(): WindowSizeClass {
    val screenWidth = LocalConfiguration.current.screenWidthDp
    return when {
        screenWidth < 600 -> WindowSizeClass.COMPACT
        screenWidth < 840 -> WindowSizeClass.MEDIUM
        else -> WindowSizeClass.EXPANDED
    }
}

/** Returns true if the current display is a tablet, foldable, or wide viewport (>= 600dp). */
@Composable
fun isTabletOrWideScreen(): Boolean =
    LocalConfiguration.current.screenWidthDp >= 600

/** Returns true if the current display is an expanded display (>= 840dp). */
@Composable
fun isExpandedScreen(): Boolean =
    LocalConfiguration.current.screenWidthDp >= 840

@Composable
fun Modifier.adaptiveContentWidth(maxWidth: Dp = 840.dp): Modifier {
    return if (isTabletOrWideScreen()) {
        this.widthIn(max = maxWidth).fillMaxWidth()
    } else {
        this
    }
}
