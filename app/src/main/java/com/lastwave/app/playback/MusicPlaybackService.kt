package com.lastwave.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState as PlatformPlaybackState
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.media.MediaBrowserServiceCompat
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
import kotlinx.coroutines.withContext
import javax.inject.Inject

private val TOPIC_SUFFIX_REGEX = Regex("""(?i)\s*-\s*topic$""")
private val VEVO_SUFFIX_REGEX = Regex("""(?i)\s*vevo$""")
private val TITLE_SUFFIX_REGEX = Regex(
    """(?i)\s*[\(\[](official\s*(music\s*)?video|official\s*audio|visualizer|audio|lyric\s*video|lyrics|hd|4k|remastered|hq)[\)\]]""",
)

private data class CleanTrackMetadata(val title: String, val artist: String)

private fun sameServiceState(previous: MusicPlayerState, current: MusicPlayerState): Boolean =
    previous.current == current.current &&
        previous.queue == current.queue &&
        previous.currentIndex == current.currentIndex &&
        previous.isPlaying == current.isPlaying &&
        previous.isBuffering == current.isBuffering &&
        previous.durationMs == current.durationMs &&
        previous.speed == current.speed &&
        previous.shuffleEnabled == current.shuffleEnabled &&
        previous.repeatMode == current.repeatMode &&
        (previous.isPlaying || current.isPlaying || previous.positionMs == current.positionMs)

/**
 * Foreground playback host and standard Android MediaSession bridge. It
 * gives the singleton player lock-screen, notification, headset, Bluetooth
 * and external hardware controls without requiring notification-listener
 * access.
 */
@AndroidEntryPoint
class MusicPlaybackService : MediaBrowserServiceCompat() {
    @Inject lateinit var musicPlayer: MusicPlayer
    @Inject lateinit var scrobbleRepository: ScrobbleRepository
    @Inject lateinit var scrobblerPreferences: ScrobblerPreferences
    @Inject lateinit var debugLog: ScrobbleDebugLog
    @Inject lateinit var themeRepository: ThemeRepository
    @Inject lateinit var artworkRepository: com.lastwave.app.data.artwork.ArtworkRepository
    @Inject lateinit var androidAutoLibrary: AndroidAutoMediaLibrary

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
    private var mediaSession: MediaSessionCompat? = null
    private var platformSessionToken: MediaSession.Token? = null
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
    private var sessionQueueSignature = ""
    private var carBrowseQueueSignature = ""
    @Volatile private var isPlaybackForeground = false
    private var artworkRequestKey = ""
    private var notificationPalette = NotificationPalette.default()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        runCatching {
            val session = MediaSessionCompat(this, "LastWavePlayer").apply {
                setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
                )
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() = musicPlayer.resume()
                    override fun onPause() = musicPlayer.pause()
                    override fun onSkipToNext() = musicPlayer.next()
                    override fun onSkipToPrevious() = musicPlayer.previous()
                    override fun onSeekTo(pos: Long) = musicPlayer.seekTo(pos)
                    override fun onStop() = musicPlayer.stopAndClear()
                    override fun onSkipToQueueItem(id: Long) = musicPlayer.seekToQueueItem(id.toInt())
                    override fun onSetShuffleMode(shuffleMode: Int) {
                        musicPlayer.setShuffleEnabled(shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE)
                    }
                    override fun onSetRepeatMode(repeatMode: Int) {
                        musicPlayer.setRepeatMode(
                            when (repeatMode) {
                                PlaybackStateCompat.REPEAT_MODE_ONE -> androidx.media3.common.Player.REPEAT_MODE_ONE
                                PlaybackStateCompat.REPEAT_MODE_ALL,
                                PlaybackStateCompat.REPEAT_MODE_GROUP -> androidx.media3.common.Player.REPEAT_MODE_ALL
                                else -> androidx.media3.common.Player.REPEAT_MODE_OFF
                            },
                        )
                    }
                    override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                        mediaId?.let(::playCarMediaId)
                    }
                    override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                        playCarSearch(resolveCarSearchQuery(query, extras))
                    }
                })
                setSessionActivity(openAppPendingIntent())
                isActive = true
            }
            mediaSession = session
            sessionToken = session.sessionToken
            platformSessionToken = session.sessionToken.token as? MediaSession.Token
            ownController = platformSessionToken?.let { token -> MediaController(this, token) }
            ActiveMediaSessionHolder.ownToken = platformSessionToken
        }.onFailure { error ->
            android.util.Log.e("MusicPlaybackService", "System media integration unavailable; continuing audio-only", error)
            runCatching { mediaSession?.release() }
            mediaSession = null
            platformSessionToken = null
            ownController = null
            ActiveMediaSessionHolder.ownToken = null
        }
        notificationPalette = NotificationPalette.from(themeRepository.uiState.value.colorScheme)
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
                    publishCarBrowseState(state)
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
        scope.launch {
            androidAutoLibrary.playlistChanges.collect {
                notifyCarLibraryChanged(AndroidAutoMediaLibrary.PLAYLISTS_ID)
            }
        }
        scope.launch {
            androidAutoLibrary.downloadChanges.distinctUntilChanged().collect {
                notifyCarLibraryChanged(AndroidAutoMediaLibrary.DOWNLOADS_ID)
            }
        }
        startDetector()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot? {
        if (mediaSession == null || !isAllowedMediaClient(clientPackageName, clientUid)) return null
        return BrowserRoot(
            AndroidAutoMediaLibrary.ROOT_ID,
            Bundle().apply {
                putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST)
                putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
            },
        )
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>,
    ) {
        result.detach()
        scope.launch {
            val children = runCatching {
                withContext(Dispatchers.IO) {
                    androidAutoLibrary.loadChildren(parentId, musicPlayer.state.value)
                }
            }.onFailure { error ->
                android.util.Log.w("MusicPlaybackService", "Android Auto browse failed for $parentId", error)
            }.getOrDefault(emptyList())
            result.sendResult(children)
        }
    }

    override fun onSearch(
        query: String,
        extras: Bundle?,
        result: Result<List<MediaBrowserCompat.MediaItem>>,
    ) {
        result.detach()
        scope.launch {
            val items = runCatching {
                withContext(Dispatchers.IO) { androidAutoLibrary.search(query) }
            }.onFailure { error ->
                android.util.Log.w("MusicPlaybackService", "Android Auto search failed", error)
            }.getOrDefault(emptyList())
            result.sendResult(items)
        }
    }

    private fun isAllowedMediaClient(clientPackageName: String, clientUid: Int): Boolean = runCatching {
        val ownsPackage = packageManager.getPackagesForUid(clientUid)
            ?.contains(clientPackageName) == true
        if (!ownsPackage) return@runCatching false
        if (clientUid == applicationInfo.uid || clientUid == android.os.Process.SYSTEM_UID) {
            return@runCatching true
        }
        if (clientPackageName in KNOWN_CAR_HOST_PACKAGES) return@runCatching true
        @Suppress("DEPRECATION")
        val info = packageManager.getApplicationInfo(clientPackageName, 0)
        info.flags and (
            android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            ) != 0
    }.getOrDefault(false)

    private fun playCarMediaId(mediaId: String) {
        scope.launch {
            val played = runCatching {
                withContext(Dispatchers.IO) { androidAutoLibrary.playMediaId(mediaId, musicPlayer) }
            }.onFailure { error ->
                android.util.Log.w("MusicPlaybackService", "Android Auto playback request failed", error)
            }.getOrDefault(false)
            if (!played) publishCarPlaybackError("That item is no longer available")
        }
    }

    private fun playCarSearch(query: String) {
        if (query.isBlank()) {
            musicPlayer.resume()
            return
        }
        scope.launch {
            val played = runCatching {
                withContext(Dispatchers.IO) { androidAutoLibrary.playSearch(query, musicPlayer) }
            }.onFailure { error ->
                android.util.Log.w("MusicPlaybackService", "Android Auto voice search failed", error)
            }.getOrDefault(false)
            if (!played) publishCarPlaybackError("No playable tracks found")
        }
    }

    private fun resolveCarSearchQuery(query: String?, extras: Bundle?): String {
        query?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
        return listOfNotNull(
            extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
            extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
            extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
        ).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun publishCarPlaybackError(message: String) {
        systemStateSignature = ""
        runCatching {
            mediaSession?.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_ERROR, musicPlayer.state.value.positionMs, 0f)
                    .setErrorMessage(message)
                    .build(),
            )
        }
    }

    private fun publishCarBrowseState(state: MusicPlayerState) {
        val signature = state.queue.joinToString(separator = "|") {
            "${it.videoId.orEmpty()}:${it.title}:${it.artist}"
        }
        if (signature == carBrowseQueueSignature) return
        carBrowseQueueSignature = signature
        notifyCarLibraryChanged(AndroidAutoMediaLibrary.QUEUE_ID)
    }

    private fun notifyCarLibraryChanged(parentId: String) {
        runCatching {
            notifyChildrenChanged(AndroidAutoMediaLibrary.ROOT_ID)
            notifyChildrenChanged(parentId)
        }
    }

    private fun promoteForPlayback(): Boolean {
        if (isPlaybackForeground) return true
        // Browse-only Android Auto connections stay notification-free. A
        // started playback request promotes the already-bound service here.
        val promoted = runCatching {
            startForeground(NOTIFICATION_ID, buildNotification(musicPlayer.state.value, artworkBitmap))
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
        isPlaybackForeground = promoted
        if (!promoted) {
            android.util.Log.w("MusicPlaybackService", "Foreground promotion refused by system; stopping started service")
            stopSelf()
        }
        return promoted
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!promoteForPlayback()) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_PREVIOUS -> musicPlayer.previous()
            ACTION_TOGGLE -> musicPlayer.togglePlayPause()
            ACTION_NEXT -> musicPlayer.next()
            ACTION_STOP -> musicPlayer.stopAndClear()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        detectorJob?.cancel()
        artworkJob?.cancel()
        if (isPlaybackForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
            isPlaybackForeground = false
        }
        val releasedToken = platformSessionToken
        runCatching { mediaSession?.isActive = false }
        runCatching { mediaSession?.release() }
        platformSessionToken = null
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
            active?.playbackState?.state == PlatformPlaybackState.STATE_PLAYING
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
            state.isBuffering -> PlaybackStateCompat.STATE_BUFFERING
            state.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            track != null -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_NONE
        }
        val session = mediaSession ?: return
        publishSessionQueue(session, state)
        val signature = buildString {
            append(track?.title).append('|')
            append(track?.artist).append('|')
            append(track?.album).append('|')
            append(state.durationMs).append('|')
            append(playbackState).append('|')
            append(state.shuffleEnabled).append('|')
            append(state.repeatMode).append('|')
            append(artworkUrl).append('|')
            append(artworkBitmap != null)
            if (!state.isPlaying) append("|pos=").append(state.positionMs)
        }
        if (signature == systemStateSignature) return
        systemStateSignature = signature

        val artUri = artworkUrl ?: track?.artworkUrl.orEmpty()
        runCatching {
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, state.currentIndex.toString())
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track?.title.orEmpty())
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track?.artist.orEmpty())
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track?.album.orEmpty())
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artUri)
                    .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artUri)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artUri)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs)
                    .apply {
                        val art = artworkBitmap
                        if (art != null) {
                            putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                            putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
                            putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
                        }
                    }
                    .build(),
            )
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or PlaybackStateCompat.ACTION_STOP or
                            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
                            PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or PlaybackStateCompat.ACTION_SET_REPEAT_MODE,
                    )
                    .setActiveQueueItemId(state.currentIndex.toLong())
                    .setBufferedPosition(state.bufferedPositionMs)
                    .setState(playbackState, state.positionMs, if (state.isPlaying) state.speed else 0f)
                    .build(),
            )
            session.setShuffleMode(
                if (state.shuffleEnabled) PlaybackStateCompat.SHUFFLE_MODE_ALL
                else PlaybackStateCompat.SHUFFLE_MODE_NONE,
            )
            session.setRepeatMode(
                when (state.repeatMode) {
                    androidx.media3.common.Player.REPEAT_MODE_ONE -> PlaybackStateCompat.REPEAT_MODE_ONE
                    androidx.media3.common.Player.REPEAT_MODE_ALL -> PlaybackStateCompat.REPEAT_MODE_ALL
                    else -> PlaybackStateCompat.REPEAT_MODE_NONE
                },
            )
        }.onFailure { error ->
            // Binder IPC into a broken OEM media stack must not kill the
            // state collector that also drives notifications and widget.
            android.util.Log.w("MusicPlaybackService", "Media session publish failed", error)
        }
    }

    private fun publishSessionQueue(session: MediaSessionCompat, state: MusicPlayerState) {
        val signature = state.queue.joinToString(separator = "|") {
            "${it.videoId.orEmpty()}:${it.title}:${it.artist}"
        }
        if (signature == sessionQueueSignature) return
        sessionQueueSignature = signature
        val start = (state.currentIndex - MAX_CAR_QUEUE_BEFORE_CURRENT).coerceAtLeast(0)
        val end = (start + MAX_CAR_QUEUE_ITEMS).coerceAtMost(state.queue.size)
        val queue = (start until end).map { index ->
            val track = state.queue[index]
            MediaSessionCompat.QueueItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId("queue:$index")
                    .setTitle(track.title)
                    .setSubtitle(track.artist)
                    .setDescription(track.album)
                    .build(),
                index.toLong(),
            )
        }
        runCatching {
            session.setQueue(queue)
            session.setQueueTitle(state.sourceLabel)
        }.onFailure { error ->
            android.util.Log.w("MusicPlaybackService", "Media session queue publish failed", error)
        }
    }

    private fun publishWidget(state: MusicPlayerState) {
        val track = state.current ?: return
        val active = ActiveMediaSessionHolder.controller
        val otherAppIsPlaying = active?.packageName != packageName &&
            active?.playbackState?.state == PlatformPlaybackState.STATE_PLAYING
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
        if (track == null) {
            notificationSignature = ""
            if (isPlaybackForeground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
                else @Suppress("DEPRECATION") stopForeground(true)
                isPlaybackForeground = false
            }
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            return
        }
        if (!isPlaybackForeground) return
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
        platformSessionToken?.let(style::setMediaSession)

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
        const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val CONTENT_STYLE_LIST = 1
        const val MAX_CAR_QUEUE_ITEMS = 100
        const val MAX_CAR_QUEUE_BEFORE_CURRENT = 50
        const val CHANNEL_ID = "lastwave_playback"
        const val NOTIFICATION_ID = 4102
        const val ACTION_PREVIOUS = "com.lastwave.app.playback.PREVIOUS"
        const val ACTION_TOGGLE = "com.lastwave.app.playback.TOGGLE"
        const val ACTION_NEXT = "com.lastwave.app.playback.NEXT"
        const val ACTION_STOP = "com.lastwave.app.playback.STOP"
        val KNOWN_CAR_HOST_PACKAGES = setOf(
            "com.google.android.projection.gearhead",
            "com.google.android.gms",
        )
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
