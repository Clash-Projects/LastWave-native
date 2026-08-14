package com.lastwave.app.data.repository

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts a representative accent color from a track's artwork for the
 * "Dynamic Now Playing Theme" setting (Settings → Appearance). Reuses the
 * app's single Coil ImageLoader (LastWaveApplication.newImageLoader()) via
 * context.imageLoader — the now-playing row on Home has almost always
 * already decoded this exact URL into that same memory/disk cache, so this
 * is normally a cache hit rather than a fresh network fetch.
 *
 * Runs entirely off the main thread. Any failure — network, decode, no
 * usable swatch — returns null so ThemeRepository can fall back to the
 * user's regular selected accent, per the feature's own fallback
 * requirement, rather than leaving a stale or broken color applied.
 */
@Singleton
class NowPlayingPaletteExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun extractAccentHex(artworkUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = ImageRequest.Builder(context)
                .data(artworkUrl)
                .allowHardware(false) // Palette needs to read pixels from a software bitmap
                .build()
            val result = context.imageLoader.execute(request)
            val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                ?: return@withContext null

            val palette = Palette.from(bitmap).generate()
            val swatch = palette.vibrantSwatch
                ?: palette.dominantSwatch
                ?: palette.mutedSwatch
                ?: return@withContext null

            val rgb = swatch.rgb
            "#%02X%02X%02X".format((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
        } catch (e: Exception) {
            null
        }
    }
}
