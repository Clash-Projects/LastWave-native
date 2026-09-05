package com.lastwave.app.ui.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import android.view.ViewParent
import android.view.Window
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** Keeps interactive content clear of side cutouts while its parent remains full-bleed. */
@Composable
fun Modifier.safeHorizontalContentPadding(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))

/** Bottom clearance covering gesture navigation, three-button navigation, and cutouts. */
@Composable
fun safeDrawingBottomPadding(): Dp =
    WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()

/**
 * Ensures a Dialog or ModalBottomSheet window properly renders edge-to-edge
 * with transparent navigation and status bars and disabled contrast scrims.
 */
@Composable
fun EdgeToEdgeDialogWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        var current: ViewParent? = view.parent
        var window: Window? = null
        while (current != null) {
            if (current is DialogWindowProvider) {
                window = current.window
                break
            }
            current = current.parent
        }
        if (window == null) {
            var ctx = view.context
            while (ctx is ContextWrapper) {
                if (ctx is Activity) {
                    window = ctx.window
                    break
                }
                ctx = ctx.baseContext
            }
        }
        window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            w.navigationBarColor = android.graphics.Color.TRANSPARENT
            w.statusBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                w.isNavigationBarContrastEnforced = false
                w.isStatusBarContrastEnforced = false
            }
        }
        onDispose {}
    }
}
