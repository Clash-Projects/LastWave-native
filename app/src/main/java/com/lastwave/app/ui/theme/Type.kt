package com.lastwave.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * GS Flex-Airy, used everywhere via a device-font-name lookup — the same
 * graceful-degradation strategy the original web app used with
 * `font-family: 'Google Sans Flex', 'Roboto', system-ui`. There's no
 * bundlable, redistributable file for this font (it isn't an open-source
 * Google Fonts release; it ships as a system font on some Android
 * devices), so this asks Android's font resolver for it *by name* — if the
 * device has it installed, every screen gets it; if not, this falls back
 * to the platform's default system font, exactly like the CSS fallback
 * chain did. Every TextStyle below references this one family — no mixed
 * fonts anywhere in the app.
 */
val LastWaveFontFamily = FontFamily(
    Font(DeviceFontFamilyName("GS Flex-Airy"), weight = FontWeight.Normal),
    Font(DeviceFontFamilyName("GS Flex-Airy"), weight = FontWeight.Medium),
    Font(DeviceFontFamilyName("GS Flex-Airy"), weight = FontWeight.SemiBold),
    Font(DeviceFontFamilyName("GS Flex-Airy"), weight = FontWeight.Bold),
)

val LastWaveTypography = Typography(
    displayLarge = TextStyle(fontFamily = LastWaveFontFamily, fontWeight = FontWeight.Normal, fontSize = 57.sp, lineHeight = 64.sp),
    displayMedium = TextStyle(fontFamily = LastWaveFontFamily, fontWeight = FontWeight.Normal, fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = LastWaveFontFamily, fontWeight = FontWeight.Normal, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = LastWaveFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = LastWaveFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = LastWaveFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = LastWaveFontFamily, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = LastWaveFontFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = LastWaveFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = LastWaveFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = LastWaveFontFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = LastWaveFontFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = LastWaveFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = LastWaveFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = LastWaveFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)
