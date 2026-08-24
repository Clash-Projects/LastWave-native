package com.lastwave.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Rebuilds the app's dark Material You scheme from a single source hue,
 * mirroring `_applyMaterialYouScheme()` / `_applyMonochromeScheme()` in the
 * legacy app.js (which itself hand-rolled dynamicDarkColorScheme()-style
 * tonal roles in CSS HSL space). This is intentionally NOT the same
 * algorithm as Android's real HCT-based dynamic color — using that here
 * would shift every color relationship in the app and count as a redesign.
 * This is a literal, faithful port so nothing has to be re-approved visually.
 */
object Md3SchemeBuilder {

    /** Chroma levels (CSS HSL %), match app.js constants exactly. */
    private const val CHROMA_PRIMARY = 30
    private const val CHROMA_SECONDARY = 14
    private const val CHROMA_TERTIARY = 22
    private const val CHROMA_NEUTRAL = 5
    private const val CHROMA_NEUTRAL_VARIANT = 8

    /** Extracts just the hue (0-360) from a hex color — the only component
     *  app.js's _hexToHsl() result that _applyMaterialYouScheme() actually uses. */
    fun hueOf(hex: String): Int {
        val clean = hex.removePrefix("#")
        if (clean.length < 6) return 0
        val r = clean.substring(0, 2).toInt(16) / 255f
        val g = clean.substring(2, 4).toInt(16) / 255f
        val b = clean.substring(4, 6).toInt(16) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max == min) return 0
        val d = max - min
        val h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } / 6f
        return (h * 360f).roundToInt().let { ((it % 360) + 360) % 360 }
    }

    /** Full dynamic scheme, seeded from [hex]'s hue. Matches _applyMaterialYouScheme(). */
    fun buildScheme(hex: String, amoled: Boolean, liquidGlass: Boolean = false): ColorScheme {
        val h = hueOf(hex)
        val hT = (h + 60) % 360
        return build(
            primaryHue = h,
            secondaryHue = h,
            tertiaryHue = hT,
            neutralHue = h,
            amoled = amoled,
            liquidGlass = liquidGlass,
        )
    }

    /** Pure grayscale scheme (all chroma = 0). Matches _applyMonochromeScheme(). */
    fun buildMonochromeScheme(amoled: Boolean, liquidGlass: Boolean = false): ColorScheme = build(
        primaryHue = 0,
        secondaryHue = 0,
        tertiaryHue = 0,
        neutralHue = 0,
        amoled = amoled,
        monochrome = true,
        liquidGlass = liquidGlass,
    )

    private fun build(
        primaryHue: Int,
        secondaryHue: Int,
        tertiaryHue: Int,
        neutralHue: Int,
        amoled: Boolean,
        monochrome: Boolean = false,
        liquidGlass: Boolean = false,
    ): ColorScheme {
        val cP = if (monochrome) 0 else CHROMA_PRIMARY
        val cS = if (monochrome) 0 else CHROMA_SECONDARY
        val cT = if (monochrome) 0 else CHROMA_TERTIARY
        val cN = if (monochrome) 0 else CHROMA_NEUTRAL
        val cNV = if (monochrome) 0 else CHROMA_NEUTRAL_VARIANT

        val bgL = if (amoled) 0 else 6
        val sc1L = if (amoled) 4 else 11
        val sc2L = if (amoled) 8 else 16
        val sc3L = if (amoled) 12 else 20

        val backgroundCol = if (amoled) Color.Black else hsl(neutralHue, cN, bgL)
        val surfaceCol = if (amoled) Color.Black else hsl(neutralHue, cN, bgL)
        val surfaceLowCol = if (amoled) Color.Black else hsl(neutralHue, cN, bgL)
        val surfaceLowestCol = if (amoled) Color.Black else hsl(neutralHue, cN, (bgL - 2).coerceAtLeast(0))

        val scheme = darkColorScheme(
            primary = hsl(primaryHue, cP, 82),
            onPrimary = hsl(primaryHue, cP, 16),
            primaryContainer = hsl(primaryHue, cP, if (amoled) 24 else 30),
            onPrimaryContainer = hsl(primaryHue, (cP - 5).coerceAtLeast(0), 90),

            secondary = hsl(secondaryHue, cS, 80),
            onSecondary = hsl(secondaryHue, cS, 16),
            secondaryContainer = hsl(secondaryHue, cS, if (amoled) 22 else 28),
            onSecondaryContainer = hsl(secondaryHue, (cS - 4).coerceAtLeast(0), 90),

            tertiary = hsl(tertiaryHue, cT, 80),
            onTertiary = hsl(tertiaryHue, cT, 16),
            tertiaryContainer = hsl(tertiaryHue, cT, if (amoled) 22 else 28),
            onTertiaryContainer = hsl(tertiaryHue, (cT - 6).coerceAtLeast(0), 90),

            error = hsl(0, if (monochrome) 45 else 45, 80),
            errorContainer = hsl(0, if (monochrome) 30 else 30, 25),
            onErrorContainer = hsl(0, if (monochrome) 25 else 25, 88),
            onError = hsl(0, 45, 16),

            background = backgroundCol,
            onBackground = hsl(neutralHue, cNV, 90),
            surface = surfaceCol,
            onSurface = hsl(neutralHue, cNV, 90),
            surfaceContainer = hsl(neutralHue, cN, sc1L),
            surfaceContainerHigh = hsl(neutralHue, cN, sc2L),
            surfaceContainerHighest = hsl(neutralHue, cN, sc3L),
            surfaceContainerLow = surfaceLowCol,
            surfaceContainerLowest = surfaceLowestCol,
            surfaceVariant = hsl(neutralHue, cNV, sc2L + 4),
            onSurfaceVariant = hsl(neutralHue, cNV, 80),

            outline = hsl(neutralHue, cNV, 60),
            outlineVariant = hsl(neutralHue, cNV, 28),

            inverseSurface = hsl(neutralHue, cNV, 90),
            inverseOnSurface = hsl(neutralHue, cNV, 20),
            inversePrimary = hsl(primaryHue, cP, 40),
            scrim = Color.Black,
        )

        // Experimental "liquid glass": only the CONTAINER roles go
        // translucent — background/surface (the page itself), on* text roles,
        // outline and scrim stay fully opaque so contrast, scrims and the
        // window backdrop are untouched. Elevation hierarchy is preserved by
        // making higher containers slightly MORE opaque, mirroring how iOS's
        if (!liquidGlass) return scheme

        return scheme.copy(
            surfaceContainer = scheme.surfaceContainer.copy(alpha = GLASS_CONTAINER_ALPHA),
            surfaceContainerHigh = scheme.surfaceContainerHigh.copy(alpha = GLASS_CONTAINER_ALPHA + 0.04f),
            surfaceContainerHighest = scheme.surfaceContainerHighest.copy(alpha = GLASS_CONTAINER_ALPHA + 0.08f),
            surfaceVariant = scheme.surfaceVariant.copy(alpha = 0.86f),
            primaryContainer = scheme.primaryContainer,
            secondaryContainer = scheme.secondaryContainer,
            tertiaryContainer = scheme.tertiaryContainer,
        )
    }

    /** Real translucency while retaining enough density for white text. */
    private const val GLASS_CONTAINER_ALPHA = 0.74f

    /**
     * HSL(h in 0-360, s in 0-100, l in 0-100) -> Compose Color.
     * Standard conversion — must match browsers' CSS hsl() exactly so every
     * token lines up with the values app.css previously rendered.
     */
    private fun hsl(h: Int, s: Int, l: Int): Color {
        val hf = ((h % 360) + 360) % 360 / 360f
        val sf = (s.coerceIn(0, 100)) / 100f
        val lf = (l.coerceIn(0, 100)) / 100f

        if (sf == 0f) return Color(lf, lf, lf)

        val q = if (lf < 0.5f) lf * (1 + sf) else lf + sf - lf * sf
        val p = 2 * lf - q

        fun hueToRgb(t: Float): Float {
            var tt = t
            if (tt < 0f) tt += 1f
            if (tt > 1f) tt -= 1f
            return when {
                tt < 1f / 6f -> p + (q - p) * 6f * tt
                tt < 1f / 2f -> q
                tt < 2f / 3f -> p + (q - p) * (2f / 3f - tt) * 6f
                else -> p
            }
        }

        val r = hueToRgb(hf + 1f / 3f)
        val g = hueToRgb(hf)
        val b = hueToRgb(hf - 1f / 3f)
        return Color(r, g, b)
    }
}

/** Kept for readability at call sites that only care about hue distance. */
internal fun hueDelta(a: Int, b: Int): Int = abs(a - b).let { minOf(it, 360 - it) }
