package com.lastwave.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "WidgetUpdater"
private const val ART_FILE_NAME = "widget_now_playing_art.png"
private const val WAVE_FRAME_INTERVAL_MS = 550L

/** Writes and refreshes the shared state of the now-playing widget. */
object WidgetUpdater {
    // Widgets are static RemoteViews, so the artwork's 3-frame equalizer
    // waves are driven by a light ticker that re-publishes only while a
    // session is actively playing (550ms per frame). It stops on pause,
    // clear, or when no widget is placed anymore.

    @Volatile
    internal var animationFrame: Int = 0
        private set

    private val animationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var waveAnimationJob: Job? = null

    /** Starts the equalizer frame ticker for active playback (idempotent). */
    internal fun startWaveAnimation(context: Context) {
        synchronized(this) {
            if (waveAnimationJob?.isActive == true) return
            waveAnimationJob = animationScope.launch {
                val appContext = context.applicationContext
                while (isActive) {
                    delay(WAVE_FRAME_INTERVAL_MS)
                    animationFrame = (animationFrame + 1) % 3
                    if (!updateAll(appContext)) break
                }
            }
        }
    }

    /** Stops the equalizer frame ticker. */
    internal fun stopWaveAnimation() {
        synchronized(this) {
            waveAnimationJob?.cancel()
            waveAnimationJob = null
        }
    }

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
        if (isPlaying) startWaveAnimation(context) else stopWaveAnimation()
    }

    suspend fun clear(context: Context) {
        stopWaveAnimation()
        val current = NowPlayingWidgetSnapshot.read(context)
        NowPlayingWidgetSnapshot.write(
            context,
            current.copy(artPath = null, isPlaying = false, hasSession = false),
        )
        updateAll(context)
    }

    /** Immediately reflects widget-originated playback actions while the
     * media-session callback catches up. Always writes and refreshes so a
     * stale persisted flag can never leave the play/pause glyph out of
     * sync with the real session. */
    suspend fun setPlaying(context: Context, isPlaying: Boolean) {
        val current = NowPlayingWidgetSnapshot.read(context)
        if (!current.hasSession) return
        NowPlayingWidgetSnapshot.write(context, current.copy(isPlaying = isPlaying))
        updateAll(context)
        if (isPlaying) startWaveAnimation(context) else stopWaveAnimation()
    }

    /** Refreshes a freshly placed widget from persisted state. */
    suspend fun sync(context: Context) {
        updateAll(context)
    }

    /** Recompose all placed widgets after the app's live color scheme changes. */
    suspend fun refreshTheme(context: Context) {
        updateAll(context)
    }

    private suspend fun updateAll(context: Context): Boolean = runCatching {
            val manager = GlanceAppWidgetManager(context)
            updateWidget(context, manager, NowPlayingWidget::class.java, NowPlayingWidget())
        }.onFailure { Log.w(TAG, "widget update failed", it) }.getOrDefault(false)

    private suspend fun <T : GlanceAppWidget> updateWidget(
        context: Context,
        manager: GlanceAppWidgetManager,
        widgetClass: Class<T>,
        widget: T,
    ): Boolean {
        val ids = manager.getGlanceIds(widgetClass)
        if (ids.isNotEmpty()) widget.updateAll(context)
        return ids.isNotEmpty()
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
