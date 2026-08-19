package com.lastwave.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import java.io.File
import java.io.FileOutputStream

private const val TAG = "WidgetUpdater"
private const val ART_FILE_NAME = "widget_now_playing_art.png"

/** Writes and refreshes the shared state of the now-playing widget. */
object WidgetUpdater {
    suspend fun publish(
        context: Context,
        title: String,
        artist: String,
        album: String?,
        sourceApp: String,
        sourcePackage: String,
        art: Bitmap?,
        isPlaying: Boolean,
    ) {
        val artPath = art?.let { bitmap -> writeArt(context, bitmap) }
        NowPlayingWidgetSnapshot.write(
            context,
            NowPlayingWidgetSnapshot(
                title = title,
                artist = artist,
                album = album.orEmpty(),
                sourceApp = sourceApp,
                sourcePackage = sourcePackage,
                artPath = artPath,
                isPlaying = isPlaying,
                hasSession = true,
            ),
        )
        updateAll(context)
    }

    suspend fun clear(context: Context) {
        val current = NowPlayingWidgetSnapshot.read(context)
        NowPlayingWidgetSnapshot.write(
            context,
            current.copy(artPath = null, isPlaying = false, hasSession = false),
        )
        updateAll(context)
    }

    /** Recompose all placed widgets after the app's live color scheme changes. */
    suspend fun refreshTheme(context: Context) {
        updateAll(context)
    }

    private suspend fun updateAll(context: Context) {
        runCatching {
            val manager = GlanceAppWidgetManager(context)
            updateWidget(context, manager, NowPlayingWidget::class.java, NowPlayingWidget())
        }.onFailure { Log.w(TAG, "widget update failed", it) }
    }

    private suspend fun <T : GlanceAppWidget> updateWidget(
        context: Context,
        manager: GlanceAppWidgetManager,
        widgetClass: Class<T>,
        widget: T,
    ) {
        val ids = manager.getGlanceIds(widgetClass)
        if (ids.isNotEmpty()) widget.updateAll(context)
    }

    @Synchronized
    private fun writeArt(context: Context, bitmap: Bitmap): String? = runCatching {
        val file = File(context.filesDir, ART_FILE_NAME)
        val pending = File(context.filesDir, "$ART_FILE_NAME.pending")
        val largest = maxOf(bitmap.width, bitmap.height)
        val cached = if (largest <= 384) bitmap else {
            val scale = 384f / largest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }
        FileOutputStream(pending).use { out -> cached.compress(Bitmap.CompressFormat.PNG, 90, out) }
        if (cached !== bitmap) cached.recycle()
        if (!pending.renameTo(file)) {
            pending.copyTo(file, overwrite = true)
            pending.delete()
        }
        file.absolutePath
    }.onFailure { Log.w(TAG, "failed to cache widget art", it) }.getOrNull()
}
