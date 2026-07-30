package com.lastwave.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.lastwave.app.data.repository.ThemeUiState

/**
 * Wraps the whole app. The color scheme itself always comes from
 * [Md3SchemeBuilder] (accent/AMOLED-aware, dark-only — the web app never had
 * a light mode, so neither does this), never from MaterialTheme's own
 * light/dark scheme resolution.
 */
@Composable
fun LastWaveTheme(
    themeState: ThemeUiState,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
            // Setting navigationBarColor to TRANSPARENT above is not enough on
            // its own: Android 10+ automatically draws its own translucent
            // black scrim over a transparent nav bar ("contrast enforcement")
            // to keep the gesture pill visible against arbitrary content —
            // THAT scrim is the visible black strip. Disabling enforcement
            // here is what actually removes it; without this line the app
            // background never reaches the true bottom of the display no
            // matter what padding or Surface backgrounds are added elsewhere.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    MaterialTheme(
        colorScheme = themeState.colorScheme,
        typography = LastWaveTypography,
        shapes = LastWaveShapes,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            content()
        }
    }
}
