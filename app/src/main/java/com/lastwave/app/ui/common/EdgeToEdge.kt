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

/** Keeps interactive content clear of side cutouts while its parent remains full-bleed. */
@Composable
fun Modifier.safeHorizontalContentPadding(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))

/** Bottom clearance covering gesture navigation, three-button navigation, and cutouts. */
@Composable
fun safeDrawingBottomPadding(): Dp =
    WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
