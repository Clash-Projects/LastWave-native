package com.lastwave.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens derived from the HTML prototype (home_prototype.html).
 *
 * CSS tokens:
 *   --bg-base: #0a0a0c
 *   --surface-glass: rgba(255, 255, 255, 0.05)
 *   --surface-glass-border: rgba(255, 255, 255, 0.08)
 *   --text-primary: #ffffff
 *   --text-secondary: rgba(255, 255, 255, 0.6)
 *   --accent-color: #6366f1
 */
object HomeThemeTokens {
    val bgBase = Color(0xFF0A0A0C)
    // 5% opacity white
    val surfaceGlass = Color(0x0DFFFFFF)
    // 8% opacity white
    val surfaceGlassBorder = Color(0x14FFFFFF)
    val textPrimary = Color(0xFFFFFFFF)
    // 60% opacity white
    val textSecondary = Color(0x99FFFFFF)
    val accentColor = Color(0xFF6366F1)
}

private val LightHomeColors: ColorScheme = lightColorScheme(
    primary = HomeThemeTokens.accentColor,
    onPrimary = HomeThemeTokens.textPrimary,
    background = HomeThemeTokens.bgBase,
    onBackground = HomeThemeTokens.textPrimary,
    surface = HomeThemeTokens.surfaceGlass,
    onSurface = HomeThemeTokens.textPrimary,
    outline = HomeThemeTokens.surfaceGlassBorder,
    primaryContainer = HomeThemeTokens.surfaceGlass,
    onPrimaryContainer = HomeThemeTokens.textPrimary
)

@Composable
fun HomeTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) {
        // Currently using the same light scheme for dark mode; can be refined later.
        LightHomeColors
    } else {
        LightHomeColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
