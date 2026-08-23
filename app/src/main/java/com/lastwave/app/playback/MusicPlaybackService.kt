package com.lastwave.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.lastwave.app.MainActivity
import com.lastwave.app.R
import com.lastwave.app.data.local.ScrobblerPreferences
import com.lastwave.app.data.local.ScrobblerSettings
import com.lastwave.app.data.repository.ScrobbleRepository
import com.lastwave.app.data.repository.ThemeRepository
import com.lastwave.app.service.ScrobbleDebugLog
import com.lastwave.app.widget.ActiveMediaSessionHolder
import com.lastwave.app.widget.WidgetUpdater
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground playback host and standard Android MediaSession bridge. It
 * gives the singleton player lock-screen, notification, headset, Bluetooth
 * and external hardware controls without requiring notification-listener
 * access.
 */
@AndroidEntryPoint
class MusicPlaybackService : Service() {
    @Inject lateinit var musicPlayer: MusicPlayer
    @Inject lateinit var scrobbleRepository: ScrobbleRepository
    @Inject lateinit var scrobblerPreferences: ScrobblerPreferences
    @Inject lateinit var debugLog: ScrobbleDebugLog
    @Inject lateinit var themeRepository: ThemeRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSession: MediaSession
    private lateinit var ownController: MediaController
    private var settings = ScrobblerSettings()
    private var detectorJob: Job? = null
    private var detectedKey = ""
    private var accumulatedMs = 0L
    private var startedAtEpochSec = 0L
    private var submissionAttempted = false
    private var artworkJob: Job? = null
    private var artworkUrl: String? = null
    private var artworkBitmap: Bitmap? = null
    private var notificationSignature = ""
    private var widgetSignature = ""
    private var notificationPalette = NotificationPalette.default()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSession = MediaSession(this, "LastWavePlayer").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = musicPlayer.resume()
                override fun onPause() = musicPlayer.pause()
                override fun onSkipToNext() = musicPlayer.next()
                override fun onSkipToPrevious() = musicPlayer.previous()
                override fun onSeekTo(pos: Long) = musicPlayer.seekTo(pos)
                override fun onStop() = musicPlayer.stopAndClear()
            })
            setSessionActivity(openAppPendingIntent())
            isActive = true
        }
        ownController = MediaController(this, mediaSession.sessionToken)
        notificationPalette = NotificationPalette.from(themeRepository.uiState.value.colorScheme)
        startForeground(NOTIFICATION_ID, buildNotification(musicPlayer.state.value, null))
        scope.launch { scrobblerPreferences.settings.collect { settings = it } }
        scope.launch {
            themeRepository.uiState.collectLatest { theme ->
                notificationPalette = NotificationPalette.from(theme.colorScheme)
                notificationSignature = ""
                publishNotification(musicPlayer.state.value, force = true)
            }
        }
        scope.launch {
            musicPlayer.state.collectLatest { state ->
                requestArtwork(state.current?.artworkUrl)
                publishSystemState(state)
                publishNotification(state)
                publishWidget(state)
                detectTransition(state)
            }
        }
        startDetector()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREVIOUS -> musicPlayer.previous()
            ACTION_TOGGLE -> musicPlayer.togglePlayPause()
            ACTION_NEXT -> musicPlayer.next()
            ACTION_STOP -> musicPlayer.stopAndClear()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        detectorJob?.cancel()
        artworkJob?.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        mediaSession.isActive = false
        mediaSession.release()
        ActiveMediaSessionHolder.clear(ownController)
        scope.cancel()
        super.onDestroy()
    }

    private fun detectTransition(state: MusicPlayerState) {
        val track = state.current ?: return
        val key = "${track.artist.lowercase()}|${track.title.lowercase()}"
        if (key == detectedKey) return
        detectedKey = key
        accumulatedMs = 0
        startedAtEpochSec = System.currentTimeMillis() / 1000
        submissionAttempted = false
        debugLog.log("Own player detected: \"${track.title}\" — ${track.artist} (account not required for detection)")
        announceNowPlaying(state)
    }

    private fun announceNowPlaying(state: MusicPlayerState) {
        val track = state.current ?: return
        if (!state.isPlaying || !settings.submitNowPlaying) return
        scope.launch(Dispatchers.IO) {
            scrobbleRepository.updateNowPlaying(track.artist, track.title, track.album)
        }
    }

    private fun startDetector() {
        detectorJob?.cancel()
        detectorJob = scope.launch {
            var wasPlaying = false
            while (true) {
                delay(1_000)
                val state = musicPlayer.state.value
                if (state.isPlaying && !wasPlaying) announceNowPlaying(state)
                wasPlaying = state.isPlaying
                val track = state.current ?: continue
                if (!state.isPlaying) continue
                accumulatedMs += 1_000
                if (submissionAttempted || state.durationMs <= 0) continue
                if (state.durationMs <= 30_000) {
                    submissionAttempted = true
                    continue
                }
                val threshold = minOf(state.durationMs * settings.scrobblePercent / 100, 4 * 60_000L)
                if (accumulatedMs < threshold) continue
                submissionAttempted = true
                scope.launch(Dispatchers.IO) {
                    when (val result = scrobbleRepository.scrobble(
                        artist = track.artist,
                        track = track.title,
                        album = track.album,
                        timestampSec = startedAtEpochSec,
                    )) {
                        ScrobbleRepository.Result.Success -> debugLog.log("Own player scrobbled \"${track.title}\"")
                        ScrobbleRepository.Result.NoSessionKey -> debugLog.log("Own play detected for \"${track.title}\"; connect Last.fm to submit it")
                        is ScrobbleRepository.Result.Failed -> debugLog.log("Own player scrobble failed for \"${track.title}\": ${result.message}")
                    }
                }
            }
        }
    }

    private fun publishSystemState(state: MusicPlayerState) {
        val track = state.current
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track?.title.orEmpty())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track?.artist.orEmpty())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, track?.album.orEmpty())
                .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, track?.artworkUrl.orEmpty())
                .putString(MediaMetadata.METADATA_KEY_ART_URI, track?.artworkUrl.orEmpty())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
                .apply { artworkBitmap?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )
        val playbackState = when {
            state.isBuffering -> PlaybackState.STATE_BUFFERING
            state.isPlaying -> PlaybackState.STATE_PLAYING
            track != null -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_NONE
        }
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_STOP,
                )
                .setState(playbackState, state.positionMs, if (state.isPlaying) state.speed else 0f)
                .build(),
        )
        val active = ActiveMediaSessionHolder.controller
        val otherAppIsPlaying = active?.packageName != packageName &&
            active?.playbackState?.state == PlaybackState.STATE_PLAYING
        if (track != null && (state.isPlaying || active == null || !otherAppIsPlaying)) {
            ActiveMediaSessionHolder.controller = ownController
        }
    }

    private fun publishWidget(state: MusicPlayerState) {
        val track = state.current ?: return
        val active = ActiveMediaSessionHolder.controller
        val otherAppIsPlaying = active?.packageName != packageName &&
            active?.playbackState?.state == PlaybackState.STATE_PLAYING
        if (!state.isPlaying && otherAppIsPlaying) return
        val signature = "${track.title}|${track.artist}|${state.isPlaying}|$artworkUrl|${artworkBitmap != null}"
        if (signature == widgetSignature) return
        widgetSignature = signature
        scope.launch(Dispatchers.IO) {
            WidgetUpdater.publish(
                context = this@MusicPlaybackService,
                title = track.title,
                artist = track.artist,
                album = track.album,
                sourceApp = getString(R.string.app_name),
                sourcePackage = packageName,
                art = artworkBitmap,
                isPlaying = state.isPlaying,
            )
        }
    }

    private fun publishNotification(state: MusicPlayerState, force: Boolean = false) {
        val track = state.current
        val signature = "${track?.title}|${track?.artist}|${track?.album}|${state.isPlaying}|${state.isBuffering}|$artworkUrl|${artworkBitmap != null}"
        if (!force && signature == notificationSignature) return
        notificationSignature = signature
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state, artworkBitmap))
    }

    private fun requestArtwork(url: String?) {
        val cleanUrl = url?.takeIf(String::isNotBlank)
        if (cleanUrl == artworkUrl) return
        artworkUrl = cleanUrl
        artworkBitmap = null
        artworkJob?.cancel()
        if (cleanUrl == null) return

        artworkJob = scope.launch {
            val expectedUrl = cleanUrl
            val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                imageLoader.execute(
                    ImageRequest.Builder(this@MusicPlaybackService)
                        .data(expectedUrl)
                        .size(512)
                        .allowHardware(false)
                        .build(),
                )
            }
            if (expectedUrl != artworkUrl) return@launch
            artworkBitmap = (result as? SuccessResult)?.drawable?.toBitmap()
            val state = musicPlayer.state.value
            publishSystemState(state)
            publishNotification(state, force = true)
            widgetSignature = ""
            publishWidget(state)
        }
    }

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(state: MusicPlayerState, art: Bitmap?): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val compact = notificationRemoteViews(
            layout = R.layout.notification_player_compact,
            widthDp = 520,
            heightDp = 72,
            state = state,
            art = art,
            expanded = false,
        )
        val expanded = notificationRemoteViews(
            layout = R.layout.notification_player_expanded,
            widthDp = 520,
            heightDp = 128,
            state = state,
            art = art,
            expanded = true,
        )
        val style = Notification.DecoratedMediaCustomViewStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        return builder
            .setSmallIcon(R.drawable.ic_launcher_logo)
            .setContentTitle(state.current?.title ?: "LastWave")
            .setContentText(state.current?.artist ?: "Music player")
            .setSubText(state.current?.album)
            .setContentIntent(openAppPendingIntent())
            .setLargeIcon(scaledBitmap(art, 384))
            .setOnlyAlertOnce(true)
            .setOngoing(state.isPlaying)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setColor(notificationPalette.primary)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(style)
            .setCustomContentView(compact)
            .setCustomBigContentView(expanded)
            .setCustomHeadsUpContentView(compact)
            .addAction(Notification.Action.Builder(R.drawable.ic_widget_skip_previous, "Previous", serviceAction(ACTION_PREVIOUS, 1)).build())
            .addAction(Notification.Action.Builder(if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play, "Play or pause", serviceAction(ACTION_TOGGLE, 2)).build())
            .addAction(Notification.Action.Builder(R.drawable.ic_widget_skip_next, "Next", serviceAction(ACTION_NEXT, 3)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", serviceAction(ACTION_STOP, 4)).build())
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setColorized(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun notificationRemoteViews(
        layout: Int,
        widthDp: Int,
        heightDp: Int,
        state: MusicPlayerState,
        art: Bitmap?,
        expanded: Boolean,
    ): RemoteViews = RemoteViews(packageName, layout).apply {
        val palette = notificationPalette
        setImageViewBitmap(R.id.notification_background, glassBackground(widthDp, heightDp, palette))
        if (art != null) setImageViewBitmap(R.id.notification_artwork, scaledBitmap(art, 192))
        else setImageViewResource(R.id.notification_artwork, R.mipmap.ic_launcher)
        setTextViewText(R.id.notification_title, state.current?.title ?: "LastWave")
        setTextViewText(R.id.notification_artist, state.current?.artist ?: "Music player")
        setTextColor(R.id.notification_title, palette.onSurface)
        setTextColor(R.id.notification_artist, palette.onSurfaceVariant)
        setImageViewResource(
            R.id.notification_play_pause,
            if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )
        setInt(R.id.notification_previous, "setColorFilter", palette.onSurface)
        setInt(R.id.notification_next, "setColorFilter", palette.onSurface)
        setInt(R.id.notification_play_surface, "setColorFilter", palette.primary)
        setInt(R.id.notification_play_pause, "setColorFilter", palette.onPrimary)
        setOnClickPendingIntent(R.id.notification_root, openAppPendingIntent())
        setOnClickPendingIntent(R.id.notification_previous, serviceAction(ACTION_PREVIOUS, 1))
        setOnClickPendingIntent(R.id.notification_play_pause, serviceAction(ACTION_TOGGLE, 2))
        setOnClickPendingIntent(R.id.notification_next, serviceAction(ACTION_NEXT, 3))
        if (expanded) {
            setTextColor(R.id.notification_brand, palette.primary)
            setTextViewText(R.id.notification_album, state.current?.album.orEmpty())
            setTextColor(R.id.notification_album, palette.onSurfaceVariant)
            setViewVisibility(
                R.id.notification_album,
                if (state.current?.album.isNullOrBlank()) View.GONE else View.VISIBLE,
            )
        }
    }

    private fun glassBackground(widthDp: Int, heightDp: Int, palette: NotificationPalette): Bitmap {
        // Keep these bitmaps intentionally pixel-sized, not density-scaled:
        // RemoteViews crosses Binder and oversized bitmaps can exceed its
        // transaction limit. The ImageView stretches this small gradient.
        val width = widthDp.coerceAtLeast(1)
        val height = heightDp.coerceAtLeast(1)
        val radius = 26f
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val start = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(palette.surface, palette.primary, 0.20f),
            232,
        )
        val end = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(palette.surface, palette.tertiary, 0.13f),
            218,
        )
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), start, end, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(bounds, radius, radius, fill)
        val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(palette.onSurface, 35)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRoundRect(
            RectF(1f, 1f, width - 1f, height - 1f),
            radius,
            radius,
            highlight,
        )
        return bitmap
    }

    private fun scaledBitmap(source: Bitmap?, maxEdge: Int): Bitmap? {
        source ?: return null
        val largest = maxOf(source.width, source.height)
        if (largest <= maxEdge) return source
        val scale = maxEdge.toFloat() / largest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, MusicPlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Native LastWave playback controls"
                setShowBadge(false)
            },
        )
    }

    private companion object {
        const val CHANNEL_ID = "lastwave_playback"
        const val NOTIFICATION_ID = 4102
        const val ACTION_PREVIOUS = "com.lastwave.app.playback.PREVIOUS"
        const val ACTION_TOGGLE = "com.lastwave.app.playback.TOGGLE"
        const val ACTION_NEXT = "com.lastwave.app.playback.NEXT"
        const val ACTION_STOP = "com.lastwave.app.playback.STOP"
    }
}

private data class NotificationPalette(
    val primary: Int,
    val onPrimary: Int,
    val surface: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val tertiary: Int,
) {
    companion object {
        fun from(scheme: androidx.compose.material3.ColorScheme) = NotificationPalette(
            primary = scheme.primary.toArgb(),
            onPrimary = scheme.onPrimary.toArgb(),
            surface = scheme.surfaceContainerHigh.toArgb(),
            onSurface = scheme.onSurface.toArgb(),
            onSurfaceVariant = scheme.onSurfaceVariant.toArgb(),
            tertiary = scheme.tertiary.toArgb(),
        )

        fun default() = NotificationPalette(
            primary = 0xFFFFB4AB.toInt(),
            onPrimary = 0xFF690005.toInt(),
            surface = 0xFF211A1A.toInt(),
            onSurface = 0xFFEDE0DE.toInt(),
            onSurfaceVariant = 0xFFD8C2BF.toInt(),
            tertiary = 0xFFE7C089.toInt(),
        )
    }
}
