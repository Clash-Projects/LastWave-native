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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

private val TOPIC_SUFFIX_REGEX = Regex("""(?i)\s*-\s*topic$""")
private val VEVO_SUFFIX_REGEX = Regex("""(?i)\s*vevo$""")
private val TITLE_SUFFIX_REGEX = Regex(
    """(?i)\s*[\(\[](official\s*(music\s*)?video|official\s*audio|visualizer|audio|lyric\s*video|lyrics|hd|4k|remastered|hq)[\)\]]""",
)

private data class CleanTrackMetadata(val title: String, val artist: String)

private fun sameServiceState(previous: MusicPlayerState, current: MusicPlayerState): Boolean =
    previous.current == current.current &&
        previous.isPlaying == current.isPlaying &&
        previous.isBuffering == current.isBuffering &&
        previous.durationMs == current.durationMs &&
        previous.speed == current.speed &&
        (previous.isPlaying || current.isPlaying || previous.positionMs == current.positionMs)

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
    @Inject lateinit var artworkRepository: com.lastwave.app.data.artwork.ArtworkRepository

    // SupervisorJob stops sibling failure propagation; the handler below
    // additionally stops an unexpected exception in any fire-and-forget
    // launch (scrobble, artwork, notification publish) from reaching the
    // default handler and killing the whole process mid-playback.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, error ->
                android.util.Log.e("MusicPlaybackService", "Suppressed playback service coroutine failure", error)
            },
    )
    // A handful of ROMs ship broken or deliberately crippled media-session
    // stacks where constructing a MediaSession or MediaController throws, or
    // the binder calls fail at runtime. Audio playback itself never depends
    // on either handle, so they stay nullable and every use degrades to
    // notification-only transport controls instead of crashing the service.
    private var mediaSession: MediaSession? = null
    private var ownController: MediaController? = null
    private var settings = ScrobblerSettings()
    private var detectorJob: Job? = null
    private var detectedKey = ""
    private var nowPlayingAnnouncedKey = ""
    @Volatile private var nowPlayingInFlight = false
    private var accumulatedMs = 0L
    private var lastPositionMs = 0L
    private var startedAtEpochSec = 0L
    private var submissionAttempted = false
    private var retryCount = 0
    private var pendingPreviousTrack: PlayableTrack? = null
    private var pendingPreviousStartedAt: Long = 0L
    private var cleanedSourceTitle = ""
    private var cleanedSourceArtist = ""
    private var cleanedMetadata = CleanTrackMetadata("", "")
    private var artworkJob: Job? = null
    private var artworkUrl: String? = null
    private var artworkBitmap: Bitmap? = null
    private var notificationSignature = ""
    private var widgetSignature = ""
    private var systemStateSignature = ""
    private var artworkRequestKey = ""
    private var notificationPalette = NotificationPalette.default()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        runCatching {
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
            ownController = mediaSession?.sessionToken?.let { token -> MediaController(this, token) }
            ActiveMediaSessionHolder.ownToken = mediaSession?.sessionToken
        }.onFailure { error ->
            android.util.Log.e("MusicPlaybackService", "System media integration unavailable; continuing audio-only", error)
            runCatching { mediaSession?.release() }
            mediaSession = null
            ownController = null
            ActiveMediaSessionHolder.ownToken = null
        }
        notificationPalette = NotificationPalette.from(themeRepository.uiState.value.colorScheme)
        // Android 12+ can reject foreground promotion for background starts
        // (widget, tile or MediaButton paths on aggressive ROM builds), and a
        // foreground service that never promotes is killed by the system
        // timeout. Fall back to a minimal notification first (custom
        // RemoteViews layouts are another OEM failure point), then stop the
        // service cleanly if even that is refused.
        val promotedToForeground = runCatching {
            startForeground(NOTIFICATION_ID, buildNotification(musicPlayer.state.value, null))
        }.onFailure { firstError ->
            android.util.Log.w("MusicPlaybackService", "Rich startup notification rejected; retrying minimal", firstError)
        }.recoverCatching {
            startForeground(
                NOTIFICATION_ID,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(this, CHANNEL_ID)
                } else {
                    @Suppress("DEPRECATION") Notification.Builder(this)
                }.build(),
            )
        }.isSuccess
        if (!promotedToForeground) {
            android.util.Log.w("MusicPlaybackService", "Foreground promotion refused by system; stopping service")
            stopSelf()
            return
        }
        scope.launch { scrobblerPreferences.settings.collect { settings = it } }
        scope.launch {
            themeRepository.uiState.collectLatest { theme ->
                val newPalette = NotificationPalette.from(theme.colorScheme)
                // Only force a notification rebuild when the palette actually
                // changed — unrelated DataStore settings also flow through this
                // state and used to trigger pointless RemoteViews rebuilds.
                if (newPalette != notificationPalette) {
                    notificationPalette = newPalette
                    notificationSignature = ""
                    publishNotification(musicPlayer.state.value, force = true)
                }
            }
        }
        scope.launch {
            musicPlayer.state
                // Position/buffer ticks arrive about 16 times per second for
                // the seek bar. Notification, widget and MediaSession chrome
                // do not need that cadence; Android extrapolates a playing
                // position from the published speed. Filter those ticks before
                // doing string work, artwork lookup and binder-state checks.
                .distinctUntilChanged(::sameServiceState)
                .collect { state ->
                    requestArtwork(state.current)
                    publishSystemState(state)
                    publishNotification(state)
                    publishWidget(state)
                    detectTransition(state)
                }
        }
        scope.launch {
            artworkRepository.resolved.collect { map ->
                val current = musicPlayer.state.value.current ?: return@collect
                val key = com.lastwave.app.data.artwork.ArtworkNormalizer.cacheKey(current.title, current.artist)
                val resolvedUrl = map[key]
                if (!resolvedUrl.isNullOrBlank() && resolvedUrl != artworkUrl) {
                    fetchArtwork(resolvedUrl)
                }
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
        val releasedToken = mediaSession?.sessionToken
        runCatching { mediaSession?.isActive = false }
        runCatching { mediaSession?.release() }
        ownController?.let { controller -> ActiveMediaSessionHolder.clear(controller) }
        ActiveMediaSessionHolder.clearToken(releasedToken)
        scope.cancel()
        super.onDestroy()
    }

    private fun cleanTrackMetadata(title: String, artist: String): CleanTrackMetadata {
        if (title == cleanedSourceTitle && artist == cleanedSourceArtist) return cleanedMetadata
        val cleanArtist = artist.trim()
            .replace(TOPIC_SUFFIX_REGEX, "")
            .replace(VEVO_SUFFIX_REGEX, "")
            .trim()
        val cleanTitle = title.trim()
            .replace(TITLE_SUFFIX_REGEX, "")
            .trim()
        cleanedSourceTitle = title
        cleanedSourceArtist = artist
        return CleanTrackMetadata(
            cleanTitle.ifBlank { title },
            cleanArtist.ifBlank { artist },
        ).also { cleanedMetadata = it }
    }

    private fun detectTransition(state: MusicPlayerState) {
        val track = state.current ?: return
        val (cleanTitle, cleanArtist) = cleanTrackMetadata(track.title, track.artist)
        val key = "${cleanArtist.lowercase()}|${cleanTitle.lowercase()}"
        if (key == detectedKey) {
            if (state.isPlaying && key != nowPlayingAnnouncedKey) {
                announceNowPlaying(state)
            }
            return
        }

        // If previous track reached threshold before transition but wasn't submitted yet, submit now
        if (pendingPreviousTrack != null && !submissionAttempted && accumulatedMs >= 30_000L) {
            val prev = pendingPreviousTrack!!
            val (prevTitle, prevArtist) = cleanTrackMetadata(prev.title, prev.artist)
            val prevStartedAt = pendingPreviousStartedAt
            scope.launch(Dispatchers.IO) {
                runCatching {
                    scrobbleRepository.scrobble(
                        artist = prevArtist,
                        track = prevTitle,
                        album = prev.album,
                        timestampSec = prevStartedAt,
                    )
                }.onFailure { error ->
                    android.util.Log.e("MusicPlaybackService", "Deferred scrobble failed", error)
                }
            }
        }

        detectedKey = key
        pendingPreviousTrack = track
        pendingPreviousStartedAt = System.currentTimeMillis() / 1000
        nowPlayingAnnouncedKey = ""
        accumulatedMs = 0
        lastPositionMs = 0
        retryCount = 0
        startedAtEpochSec = System.currentTimeMillis() / 1000
        submissionAttempted = false
        debugLog.log("Own player detected: \"$cleanTitle\" — $cleanArtist")
        if (state.isPlaying) {
            announceNowPlaying(state)
        }
    }

    private fun announceNowPlaying(state: MusicPlayerState) {
        val track = state.current ?: return
        if (!state.isPlaying || !settings.submitNowPlaying) return
        // One announcement in flight at a time: during a rate-limit cooldown
        // each write can wait seconds on the shared write mutex — without this
        // guard the 1s detector loop would stack duplicate announcements that
        // all fire once the cooldown clears.
        if (nowPlayingInFlight) return
        val (cleanTitle, cleanArtist) = cleanTrackMetadata(track.title, track.artist)
        val key = "${cleanArtist.lowercase()}|${cleanTitle.lowercase()}"
        nowPlayingAnnouncedKey = key
        nowPlayingInFlight = true
        scope.launch(Dispatchers.IO) {
            try {
                val result = scrobbleRepository.updateNowPlaying(cleanArtist, cleanTitle, track.album)
                when {
                    result is ScrobbleRepository.Result.Success ->
                        debugLog.log("Now playing updated: \"$cleanTitle\" — $cleanArtist")
                    result is ScrobbleRepository.Result.Failed && result.retryable -> {
                        // Transient (rate limit / network): clear the announced key so
                        // the 1s detector loop re-attempts once the rate shield
                        // clears, instead of leaving Now Playing silently dead for
                        // the rest of the track.
                        if (nowPlayingAnnouncedKey == key) nowPlayingAnnouncedKey = ""
                    }
                    else -> Unit
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A throw here used to escape via try/finally with no catch
                // and (before the scope handler existed) kill the process.
                debugLog.log("Now playing update crashed: ${e.message}")
                android.util.Log.e("MusicPlaybackService", "updateNowPlaying failed", e)
            } finally {
                nowPlayingInFlight = false
            }
        }
    }

    private fun startDetector() {
        detectorJob?.cancel()
        detectorJob = scope.launch {
            var wasPlaying = false
            while (true) {
                delay(1_000)
                val state = musicPlayer.state.value
                val track = state.current

                if (track == null) {
                    wasPlaying = false
                    continue
                }

                val (cleanTitle, cleanArtist) = cleanTrackMetadata(track.title, track.artist)
                val key = "${cleanArtist.lowercase()}|${cleanTitle.lowercase()}"

                // Announce Now Playing when transitioning to playing or if not yet announced
                if (state.isPlaying && (!wasPlaying || key != nowPlayingAnnouncedKey)) {
                    announceNowPlaying(state)
                }
                wasPlaying = state.isPlaying

                if (!state.isPlaying) continue

                // Check for track replay / restart
                if (state.positionMs in 1..4_000 && lastPositionMs > 20_000L && accumulatedMs > 15_000L) {
                    accumulatedMs = 0L
                    startedAtEpochSec = System.currentTimeMillis() / 1000
                    submissionAttempted = false
                    retryCount = 0
                    announceNowPlaying(state)
                }
                lastPositionMs = state.positionMs

                accumulatedMs += 1_000

                if (submissionAttempted) continue

                // Calculate threshold with fallback for streaming tracks where duration is not yet reported
                val effectiveDuration = if (state.durationMs > 0) state.durationMs else 180_000L
                val percent = if (settings.scrobblePercent in 25..90) settings.scrobblePercent else 50
                val threshold = minOf(effectiveDuration * percent / 100, 4 * 60_000L).coerceAtLeast(30_000L)

                if (accumulatedMs < threshold) continue

                submissionAttempted = true
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        when (val result = scrobbleRepository.scrobble(
                            artist = cleanArtist,
                            track = cleanTitle,
                            album = track.album,
                            timestampSec = startedAtEpochSec,
                        )) {
                            ScrobbleRepository.Result.Success -> {
                                debugLog.log("Own player scrobbled \"$cleanTitle\" — $cleanArtist")
                                // Re-announce now playing so Last.fm keeps active status during ongoing playback
                                if (musicPlayer.state.value.isPlaying) {
                                    scrobbleRepository.updateNowPlaying(cleanArtist, cleanTitle, track.album)
                                }
                            }
                            ScrobbleRepository.Result.NoSessionKey -> {
                                debugLog.log("Own play detected for \"$cleanTitle\"; connect Last.fm to submit it")
                            }
                            is ScrobbleRepository.Result.Failed -> {
                                debugLog.log("Own player scrobble failed for \"$cleanTitle\": ${result.message}")
                                // Allow up to 3 retries on network failure
                                if (retryCount < 3) {
                                    retryCount++
                                    submissionAttempted = false
                                }
                            }
                        }
                    }.onFailure { error ->
                        android.util.Log.e("MusicPlaybackService", "Scrobble submission crashed", error)
                    }
                }
            }
        }
    }

    private fun publishSystemState(state: MusicPlayerState) {
        val track = state.current
        // Transport-control target is cheap and must stay fresh every emission.
        val active = ActiveMediaSessionHolder.controller
        val otherAppIsPlaying = active?.packageName != packageName &&
            active?.playbackState?.state == PlaybackState.STATE_PLAYING
        if (track != null && (state.isPlaying || active == null || !otherAppIsPlaying)) {
            ActiveMediaSessionHolder.controller = ownController
        }

        // setMetadata/setPlaybackState are binder IPC into system_server. The
        // player state emits ~4x/second while playing; republishing on every
        // emission meant ~240 binder transactions/minute for identical data —
        // a constant main-thread tax that compounds into visible jank and
        // "app stopped responding" spells. PlaybackState carries position +
        // speed, so the system extrapolates between publishes; only actual
        // changes (track, duration, play/buffer state, artwork, or a seek
        // while paused) need to cross the binder.
        val playbackState = when {
            state.isBuffering -> PlaybackState.STATE_BUFFERING
            state.isPlaying -> PlaybackState.STATE_PLAYING
            track != null -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_NONE
        }
        val signature = buildString {
            append(track?.title).append('|')
            append(track?.artist).append('|')
            append(track?.album).append('|')
            append(state.durationMs).append('|')
            append(playbackState).append('|')
            append(artworkUrl).append('|')
            append(artworkBitmap != null)
            if (!state.isPlaying) append("|pos=").append(state.positionMs)
        }
        if (signature == systemStateSignature) return
        systemStateSignature = signature

        val artUri = artworkUrl ?: track?.artworkUrl.orEmpty()
        val session = mediaSession ?: return
        runCatching {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, track?.title.orEmpty())
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, track?.artist.orEmpty())
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, track?.album.orEmpty())
                    .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, artUri)
                    .putString(MediaMetadata.METADATA_KEY_ART_URI, artUri)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, artUri)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
                    .apply {
                        val art = artworkBitmap
                        if (art != null) {
                            putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                            putBitmap(MediaMetadata.METADATA_KEY_ART, art)
                            putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, art)
                        }
                    }
                    .build(),
            )
            session.setPlaybackState(
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
        }.onFailure { error ->
            // Binder IPC into a broken OEM media stack must not kill the
            // state collector that also drives notifications and widget.
            android.util.Log.w("MusicPlaybackService", "Media session publish failed", error)
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
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(state, artworkBitmap))
        }.onFailure { error ->
            // Some OEMs throw from notify() for transient binder failures;
            // the next state emission rebuilds and retries anyway.
            android.util.Log.w("MusicPlaybackService", "Notification publish failed", error)
        }
    }

    private fun requestArtwork(track: PlayableTrack?) {
        if (track == null) {
            artworkRequestKey = ""
            artworkUrl = null
            artworkBitmap = null
            artworkJob?.cancel()
            return
        }

        // The player state emits ~4x/second; without this guard every emission
        // re-launched an artwork resolution job for the same track.
        val requestKey = "${track.title}|${track.artist}|${track.artworkUrl.orEmpty()}"
        if (requestKey == artworkRequestKey) return
        artworkRequestKey = requestKey

        // Track has changed: immediately clear previous track's artwork
        // so the notification bar never displays the old song's cover art
        artworkBitmap = null
        artworkUrl = null
        artworkJob?.cancel()

        val directUrl = track.artworkUrl?.takeIf(String::isNotBlank)
        if (directUrl != null) {
            fetchArtwork(directUrl)
            return
        }

        // If track artwork is missing (e.g. from lists/generator), resolve via ArtworkRepository
        val key = com.lastwave.app.data.artwork.ArtworkNormalizer.cacheKey(track.title, track.artist)
        val cached = artworkRepository.resolved.value[key]?.takeIf(String::isNotBlank)
        if (cached != null) {
            fetchArtwork(cached)
        } else {
            scope.launch(Dispatchers.IO) {
                artworkRepository.resolve(track.title, track.artist)
            }
        }
    }

    private fun fetchArtwork(url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        if (cleanUrl == artworkUrl && artworkBitmap != null) return
        artworkUrl = cleanUrl
        artworkJob?.cancel()

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
            .setShowActionsInCompactView(0, 1, 2)
        mediaSession?.sessionToken?.let(style::setMediaSession)

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
        runCatching {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Native LastWave playback controls"
                    setShowBadge(false)
                },
            )
        }.onFailure { error ->
            android.util.Log.w("MusicPlaybackService", "Notification channel unavailable", error)
        }
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
