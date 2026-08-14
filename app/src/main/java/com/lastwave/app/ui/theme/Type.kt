@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.lastwave.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lastwave.app.R

/**
 * Google Sans Flex, bundled as a real variable font (res/font/google_sans_flex.ttf)
 * rather than looked up by device font name — every device gets the same
 * look now, not just ones that happen to ship it as a system font.
 *
 * The file exposes 6 axes (checked via fonttools): wght 1-1000 (default
 * 400), wdth 25-151 (default 100), opsz 6-144 (default 18), ROND 0-100,
 * GRAD 0-100, slnt -10-0. Each text style below dials in its own
 * wght/wdth/ROND/opsz combination instead of reusing one instance for the
 * whole type scale - headlines lean heavier, a touch wider, and rounder
 * (a confident, friendly "premium app" mark), body text stays close to the
 * font's own default (readable, unobtrusive), and labels/buttons pick up
 * extra roundness so pill-shaped chips and the text inside them read as
 * one continuous shape language instead of a sharp glyph inside a soft
 * container. Optical size is set per style to that style's own
 * approximate font size, exactly what opsz exists for.
 */
private fun gsFlex(
    weight: Float,
    width: Float = 100f,
    round: Float = 0f,
    opticalSize: Float,
    grade: Float = 0f,
): FontFamily = FontFamily(
    Font(
        R.font.google_sans_flex,
        weight = when {
            weight >= 700f -> FontWeight.Bold
            weight >= 600f -> FontWeight.SemiBold
            weight >= 500f -> FontWeight.Medium
            else -> FontWeight.Normal
        },
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight.toInt()),
            FontVariation.width(width),
            FontVariation.Setting("ROND", round),
            FontVariation.Setting("opsz", opticalSize),
            FontVariation.Setting("GRAD", grade),
        ),
    ),
)

val LastWaveTypography = Typography(
    displayLarge = TextStyle(fontFamily = gsFlex(weight = 760f, width = 122f, round = 42f, opticalSize = 57f), fontWeight = FontWeight.Bold, fontSize = 57.sp, lineHeight = 64.sp),
    displayMedium = TextStyle(fontFamily = gsFlex(weight = 740f, width = 120f, round = 40f, opticalSize = 45f), fontWeight = FontWeight.Bold, fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = gsFlex(weight = 720f, width = 118f, round = 38f, opticalSize = 36f), fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = gsFlex(weight = 800f, width = 122f, round = 40f, opticalSize = 32f), fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = gsFlex(weight = 780f, width = 120f, round = 38f, opticalSize = 28f), fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = gsFlex(weight = 760f, width = 116f, round = 36f, opticalSize = 24f), fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = gsFlex(weight = 640f, width = 110f, round = 30f, opticalSize = 22f), fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = gsFlex(weight = 600f, width = 106f, round = 28f, opticalSize = 16f), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = gsFlex(weight = 580f, width = 105f, round = 26f, opticalSize = 14f), fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = gsFlex(weight = 440f, width = 102f, round = 18f, opticalSize = 16f), fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = gsFlex(weight = 430f, width = 102f, round = 18f, opticalSize = 15f), fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = gsFlex(weight = 420f, width = 100f, round = 16f, opticalSize = 13f), fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = gsFlex(weight = 680f, width = 108f, round = 46f, opticalSize = 14f), fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = gsFlex(weight = 660f, width = 108f, round = 44f, opticalSize = 12f), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = gsFlex(weight = 660f, width = 108f, round = 44f, opticalSize = 11f), fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp),
)

/**
 * The "Use Application Font" toggle's off-state: plain platform default
 * (FontFamily.Default), same sizes/weights as [LastWaveTypography] so
 * turning the toggle off changes ONLY the typeface, not the whole scale.
 */
val SystemTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 57.sp, lineHeight = 64.sp),
    displayMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)
