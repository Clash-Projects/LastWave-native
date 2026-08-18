package com.lastwave.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import java.io.File
import java.io.FileOutputStream

private const val TAG = "WidgetUpdater"
private const val ART_FILE_NAME = "widget_now_playing_art.png"

/**
 * The only place that writes to [NowPlayingWidget]'s persisted state.
 * Called from [com.lastwave.app.service.MediaScrobbleListenerService]
 * whenever the tracked session's metadata or playback state changes.
 *
 * Album art can't be stored directly in Glance's Preferences-backed
 * widget state (it only holds primitives), so it's written once to a
 * small cache file in app-private storage and just the path is stored —
 * this also means the widget still has last-known art immediately after
 * a process restart, before the scrobbler service reconnects.
 */
object WidgetUpdater {

    suspend fun publish(
        context: Context,
        title: String,
        artist: String,
        art: Bitmap?,
        isPlaying: Boolean,
    ) {
        val artPath = art?.let { bitmap -> writeArt(context, bitmap) }
        updateAll(context) { prefs ->
            prefs[NowPlayingWidget.Keys.title] = title
            prefs[NowPlayingWidget.Keys.artist] = artist
            prefs[NowPlayingWidget.Keys.isPlaying] = isPlaying
            prefs[NowPlayingWidget.Keys.hasSession] = true
            if (artPath != null) prefs[NowPlayingWidget.Keys.artPath] = artPath
            else prefs.remove(NowPlayingWidget.Keys.artPath)
        }
    }

    /** Called when the last watched session goes away — widget falls back
     *  to its empty "nothing playing" state rather than showing stale info. */
    suspend fun clear(context: Context) {
        updateAll(context) { prefs ->
            prefs[NowPlayingWidget.Keys.hasSession] = false
            prefs[NowPlayingWidget.Keys.isPlaying] = false
        }
    }

    private suspend fun updateAll(
        context: Context,
        edit: (androidx.datastore.preferences.core.MutablePreferences) -> Unit,
    ) {
        runCatching {
            val manager = GlanceAppWidgetManager(context)
            val ids = manager.getGlanceIds(NowPlayingWidget::class.java)
            if (ids.isEmpty()) return // no instance of the widget on any home screen
            ids.forEach { id ->
                updateAppWidgetState(context, id) { prefs -> edit(prefs) }
            }
            NowPlayingWidget().updateAll(context)
        }.onFailure { Log.w(TAG, "widget update failed", it) }
    }

    private fun writeArt(context: Context, bitmap: Bitmap): String? = runCatching {
        val file = File(context.filesDir, ART_FILE_NAME)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 90, out) }
        file.absolutePath
    }.onFailure { Log.w(TAG, "failed to cache widget art", it) }.getOrNull()
}
