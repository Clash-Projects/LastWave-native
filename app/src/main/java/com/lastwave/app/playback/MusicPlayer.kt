package com.lastwave.app.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lastwave.app.data.discover.DiscoverRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.ConfirmedUnplayableMediaException
import com.lastwave.app.data.music.YOUTUBE_WEB_USER_AGENT
import com.lastwave.app.data.qobuz.QobuzAudioStream
import com.lastwave.app.data.qobuz.QobuzMusicApi
import com.lastwave.app.widget.WidgetUpdater
import kotlinx.coroutines.flow.first
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PlayableTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val videoId: String? = null,
    val playbackUrl: String? = null,
    val playbackMimeType: String? = null,
)

@Serializable
internal data class PersistedPlaybackSession(
    val version: Int = 2,
    val queue: List<PlayableTrack>,
    val currentIndex: Int,
    val positionMs: Long,
    val sourceLabel: String = "LastWave",
    val isEndlessQueue: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
)

data class MusicPlayerState(
    val connected: Boolean = true,
    val current: PlayableTrack? = null,
    val queue: List<PlayableTrack> = emptyList(),
    val currentIndex: Int = -1,
    val sourceLabel: String = "LastWave",
    val isEndlessQueue: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val bufferedPositionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val bitrateKbps: Int? = null,
    val audioCodec: String? = null,
    val isQobuz: Boolean = false,
    val bitDepth: Int? = null,
    val samplingRateKHz: Double? = null,
    val sleepTimerRemainingMs: Long? = null,
    val error: String? = null,
)

/**
 * Playback fields used by list rows and collapsed player chrome. Unlike
 * [MusicPlayerState], this does not contain the 60 ms position ticker, so a
 * playing track no longer invalidates every visible track list 16 times/sec.
 */
data class PlaybackChromeState(
    val current: PlayableTrack? = null,
    val sourceLabel: String = "LastWave",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val queueSize: Int = 0,
)

/** The small, frequently changing state consumed only by progress UI. */
data class PlaybackProgressState(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/**
 * Process-wide native ExoPlayer engine. A foreground service publishes its
 * platform MediaSession/notification while this object owns the actual
 * queue, ensuring the app UI and system controls always operate on the same
 * player instance.
 */
@OptIn(UnstableApi::class)
@Singleton
class MusicPlayer @Inject constructor(
    @ApplicationContext context: Context,
    private val innerTube: InnerTubeMusicApi,
    private val qobuzMusicApi: QobuzMusicApi,
    private val settingsPreferences: SettingsPreferences,
    private val discoverRepository: DiscoverRepository,
    private val nativeAudioEngine: dagger.Lazy<NativeAudioEngine>,
    private val audioEffectsEngine: AudioEffectsEngine,
    private val applicationScope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val playbackPreferences = appContext.getSharedPreferences(
        PLAYBACK_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val persistenceJson = Json { ignoreUnknownKeys = true }
    private var lastPersistedSignature = ""
    private var playbackPersistenceJob: Job? = null
    private var pendingRestoredSession: PersistedPlaybackSession? = null
    @Volatile private var persistenceGeneration = 0L
    private val playbackPersistenceLock = Any()
    private var ticker: Job? = null
    private var playRequest: Job? = null
    private var queueEnrichmentJob: Job? = null
    private var preloadJob: Job? = null
    private var discoverQueueLoadJob: Job? = null
    private var discoverQueueActive = false
    private var unavailableSkipJob: Job? = null
    private val unavailableMediaIds = mutableSetOf<String>()
    private var sleepTimerDeadlineMs: Long? = null
    private var sleepTimerStep = 0
    @Volatile
    private var crossfadeEnabled = false
    @Volatile
    private var crossfadeDurationMs = 5_000L
    private var incomingCrossfadeMediaId: String? = null
    private var incomingCrossfadeStartVolume = 0f
    private val _state = MutableStateFlow(MusicPlayerState())
    val state: StateFlow<MusicPlayerState> = _state.asStateFlow()
    val chromeState: StateFlow<PlaybackChromeState> = state
        .map {
            PlaybackChromeState(
                current = it.current,
                sourceLabel = it.sourceLabel,
                isPlaying = it.isPlaying,
                isBuffering = it.isBuffering,
                queueSize = it.queue.size,
            )
        }
        .distinctUntilChanged()
        .stateIn(applicationScope, SharingStarted.Eagerly, PlaybackChromeState())
    val progressState: StateFlow<PlaybackProgressState> = state
        .map { PlaybackProgressState(positionMs = it.positionMs, durationMs = it.durationMs) }
        .distinctUntilChanged()
        .stateIn(applicationScope, SharingStarted.Eagerly, PlaybackProgressState())

    private var errorRetryCount = 0
    private var retryMediaId: String? = null
    private var bufferingWatchMediaId: String? = null
    private var bufferingWatchStartedMs = 0L
    private var bufferingWatchPositionMs = -1L
    private var bufferingWatchBufferedMs = -1L
    private var bufferingRecoveryCount = 0
    private var bufferingRecoveryJob: Job? = null
    private val qobuzBypassMediaIds = ConcurrentHashMap.newKeySet<String>()

    private val mediaCache: Cache by lazy {
        val cacheDir = java.io.File(appContext.cacheDir, "media_stream_cache")
        // Bounded playback buffer, not an offline library. LRU eviction keeps
        // recent rewind/next-track data while preventing multi-GB growth.
        val evictor = LeastRecentlyUsedCacheEvictor(MEDIA_STREAM_CACHE_BYTES)
        val dbProvider = StandaloneDatabaseProvider(appContext)
        SimpleCache(cacheDir, evictor, dbProvider)
    }

    private val cacheDataSourceFactory: CacheDataSource.Factory by lazy {
        val httpUpstream = DefaultHttpDataSource.Factory()
            .setUserAgent(YOUTUBE_WEB_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)
        val defaultDataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(appContext, httpUpstream)
        CacheDataSource.Factory()
            .setCache(mediaCache)
            .setUpstreamDataSourceFactory(defaultDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh(player)
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                unavailableSkipJob?.cancel()
                unavailableSkipJob = null
                unavailableMediaIds.clear()
                bufferingWatchStartedMs = 0L
                bufferingWatchPositionMs = player.currentPosition.coerceAtLeast(0L)
                bufferingWatchBufferedMs = player.bufferedPosition.coerceAtLeast(0L)
                errorRetryCount = 0
                retryMediaId = player.currentMediaItem?.mediaId
            }
        }
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            audioEffectsEngine.attach(audioSessionId)
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem != null) {
                if (bufferingWatchMediaId != mediaItem.mediaId) {
                    resetBufferingWatch(mediaItem.mediaId)
                    qobuzBypassMediaIds.retainAll(setOf(mediaItem.mediaId))
                }
                if (retryMediaId != mediaItem.mediaId) {
                    retryMediaId = mediaItem.mediaId
                    errorRetryCount = 0
                }
                val currentIndex = player.currentMediaItemIndex
                val currentTrack = mediaItem.toPlayableTrack()
                val currentQueue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toPlayableTrack() }
                _state.update {
                    it.copy(
                        current = currentTrack,
                        currentIndex = currentIndex,
                        queue = if (currentQueue.isNotEmpty()) currentQueue else it.queue,
                        isBuffering = true,
                        error = null,
                    )
                }
                val isNaturalTransition = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                if (crossfadeEnabled && crossfadeDurationMs > 0L && isNaturalTransition) {
                    incomingCrossfadeMediaId = mediaItem.mediaId
                    incomingCrossfadeStartVolume = player.volume
                        .coerceIn(0f, 1f)
                        .takeIf { it < 0.99f }
                        ?: 0f
                    player.volume = incomingCrossfadeStartVolume
                } else {
                    incomingCrossfadeMediaId = null
                    player.volume = 1f
                }
                if (currentTrack.playbackUrl != null) {
                    applicationScope.launch(Dispatchers.IO) {
                        publishLocalTrackQuality(currentTrack)
                    }
                }
                enrichUpcomingQueue(currentIndex)
                extendDiscoverQueueIfNeeded(currentIndex)
                val nextIndex = if (player.shuffleModeEnabled) {
                    player.nextMediaItemIndex
                } else {
                    currentIndex + 1
                }
                if (nextIndex != C.INDEX_UNSET && nextIndex in 0 until player.mediaItemCount) {
                    preloadNextTrack(player.getMediaItemAt(nextIndex).toPlayableTrack())
                }
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            val currentTrack = _state.value.current
            val failedQobuzStream = _state.value.isQobuz
            val currentPos = player.currentPosition.coerceAtLeast(0)
            val videoId = currentTrack?.videoId
            val failedIndex = player.currentMediaItemIndex
            val failedMediaId = player.currentMediaItem?.mediaId

            if (currentTrack?.playbackUrl != null) {
                _state.update { it.copy(error = error.message ?: "Local file playback error (${error.errorCodeName})", isBuffering = false) }
                return
            }

            // Invalidate stale cache on 403 or network failure
            if (!videoId.isNullOrBlank()) {
                innerTube.invalidateCache(videoId)
            }

            val confirmedWithoutRetry = isExplicitlyUnplayableFailure(error) ||
                isUnsupportedMediaFailure(error)
            val confirmedAfterRetry = errorRetryCount > 0 && isPermanentHttpFailure(error)
            // Any Qobuz CDN/format failure gets one immediate YouTube Music
            // fallback. Permanent-error skipping applies only after that
            // alternate source has also failed.
            if ((confirmedWithoutRetry || confirmedAfterRetry) && !failedQobuzStream) {
                _state.update {
                    it.copy(error = "Track unavailable", isPlaying = false, isBuffering = false)
                }
                scheduleUnavailableMediaSkip(failedIndex, failedMediaId)
                return
            }

            if (currentTrack != null && errorRetryCount < 2) {
                errorRetryCount++
                applicationScope.launch(Dispatchers.Main.immediate) {
                    _state.update { it.copy(isBuffering = true, error = null) }
                    var retryFailure: Throwable? = null
                    try {
                        val stream = resolveTrackAudioStream(
                            track = currentTrack,
                            videoId = videoId,
                            allowQobuz = !failedQobuzStream,
                        )
                        publishResolvedQuality(stream)
                        val updated = currentTrack.copy(
                            playbackUrl = stream.url,
                            playbackMimeType = stream.mimeType,
                        )
                        // The watchdog may have skipped this item while its
                        // retry was resolving. Never replace the new track
                        // with a stale result from the rejected one.
                        if (player.currentMediaItemIndex != failedIndex ||
                            player.currentMediaItem?.mediaId != failedMediaId
                        ) {
                            return@launch
                        }
                        if (failedIndex in 0 until player.mediaItemCount) {
                            player.replaceMediaItem(failedIndex, updated.toMediaItem())
                            player.seekTo(failedIndex, currentPos)
                            player.prepare()
                            player.play()
                            return@launch
                        }
                    } catch (e: Exception) {
                        retryFailure = e
                        android.util.Log.e("MusicPlayer", "Auto-retry stream failed", e)
                    }
                    if (player.currentMediaItemIndex != failedIndex ||
                        player.currentMediaItem?.mediaId != failedMediaId
                    ) {
                        return@launch
                    }
                    _state.update { it.copy(error = error.message ?: "Playback error (${error.errorCodeName})", isBuffering = false) }
                    if (retryFailure?.let(::isConfirmedUnplayableFailure) == true) {
                        scheduleUnavailableMediaSkip(
                            failedIndex = failedIndex,
                            failedMediaId = failedMediaId,
                        )
                    }
                }
                return
            }

            _state.update { it.copy(error = error.message ?: "Playback error (${error.errorCodeName})", isBuffering = false) }
        }
    }

    // Do not initialize ExoPlayer/audio/cache merely to draw the launcher.
    // Several Android 11 OEM audio stacks are fragile during cold start; the
    // engine is needed only when the user actually operates playback.
    private val playerDelegate = lazy {
        val resolving = ResolvingDataSource.Factory(cacheDataSourceFactory) { dataSpec ->
            val requested = dataSpec.uri
            if (requested.scheme != "lastwave") {
                dataSpec
            } else {
                val stream = runBlocking(Dispatchers.IO) {
                    val track = when (requested.host) {
                        "youtube" -> {
                            val videoId = requested.pathSegments.firstOrNull()
                            PlayableTrack(
                                title = requested.getQueryParameter("title").orEmpty(),
                                artist = requested.getQueryParameter("artist").orEmpty(),
                                videoId = videoId,
                            )
                        }
                        "search" -> PlayableTrack(
                            title = requested.getQueryParameter("title").orEmpty(),
                            artist = requested.getQueryParameter("artist").orEmpty(),
                        )
                        else -> error("Invalid LastWave playback item")
                    }
                    // Hard bound: an unbounded resolve here blocked the player's
                    // loader thread forever on network stalls (eternal buffering).
                    // Timing out surfaces a normal playback error that the
                    // auto-skip path already knows how to recover from.
                    val resolved = withTimeoutOrNull(RESOLVE_DATA_SPEC_TIMEOUT_MS) {
                        val mediaId = track.videoId
                            ?: "query:${track.artist.lowercase()}|${track.title.lowercase()}"
                        resolveTrackAudioStream(
                            track = track,
                            videoId = track.videoId,
                            allowQobuz = mediaId !in qobuzBypassMediaIds,
                        )
                    } ?: error("Timed out resolving audio for ${track.title}")
                    applicationScope.launch(Dispatchers.Main.immediate) {
                        publishResolvedQuality(resolved)
                    }
                    resolved
                }
                dataSpec.withUri(Uri.parse(stream.url))
            }
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 45_000,
                /* maxBufferMs = */ 120_000,
                /* bufferForPlaybackMs = */ 4_000,
                /* bufferForPlaybackAfterRebufferMs = */ 8_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(15_000, true)
            .build()
        val renderersFactory = object : DefaultRenderersFactory(appContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink {
                val fallbackSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(false)
                    .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                    .build()
                val engine = runCatching { nativeAudioEngine.get() }.getOrNull()
                if (engine?.isAvailable != true) {
                    audioEffectsEngine.setFallbackRequired(true)
                    return fallbackSink
                }
                val enhancedSink = try {
                    DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(true)
                        .setEnableAudioTrackPlaybackParams(false)
                        .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                        .build()
                } catch (error: Exception) {
                    android.util.Log.w("MusicPlayer", "Enhanced audio sink unavailable; using PCM16", error)
                    audioEffectsEngine.setFallbackRequired(true)
                    return fallbackSink
                } catch (error: LinkageError) {
                    android.util.Log.w("MusicPlayer", "Enhanced audio sink linkage failed; using PCM16", error)
                    audioEffectsEngine.setFallbackRequired(true)
                    return fallbackSink
                }
                audioEffectsEngine.setFallbackRequired(false)
                return NativeProcessingAudioSink(
                    enhancedDelegate = enhancedSink,
                    fallbackDelegate = fallbackSink,
                    processor = NativePcmAudioProcessor(engine),
                    onPlatformEffectsRequired = audioEffectsEngine::setFallbackRequired,
                )
            }
        }.apply {
            // FFmpeg-first decoding, the Poweramp/VLC model: every codec the
            // bundled GPL build supports (FLAC 24/96-192, Opus, AAC, MP3,
            // Vorbis) decodes through the same battle-tested software path on
            // every device, eliminating per-OEM platform codec bugs that
            // surface as noise/distortion. setEnableDecoderFallback keeps the
            // platform decoder for anything FFmpeg rejects.
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true)
            setEnableAudioTrackPlaybackParams(false)
            setMediaCodecSelector(accurateAudioMediaCodecSelector)
        }

        ExoPlayer.Builder(appContext, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(appContext).setDataSourceFactory(resolving))
            .setLoadControl(loadControl)
            .build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
                        .build(),
                    true,
                )
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
                addListener(listener)
            }.also { restoredPlayer ->
                // A persisted queue is UI state, not a reason to touch the
                // device's codec/audio stack during process launch. Hydrate
                // ExoPlayer only when an actual player operation first asks
                // for it. The renderer, sink, DSP and fallback graph above is
                // otherwise identical on every device.
                val restoredSession = pendingRestoredSession
                pendingRestoredSession = null
                if (restoredSession != null && restoredSession.queue.isNotEmpty()) {
                    val restoredIndex = restoredSession.currentIndex.coerceIn(restoredSession.queue.indices)
                    restoredPlayer.setMediaItems(
                        restoredSession.queue.map(PlayableTrack::toMediaItem),
                        restoredIndex,
                        restoredSession.positionMs.coerceAtLeast(0),
                    )
                    restoredPlayer.shuffleModeEnabled = restoredSession.shuffleEnabled
                    restoredPlayer.repeatMode = restoredSession.repeatMode.takeIf {
                        it in Player.REPEAT_MODE_OFF..Player.REPEAT_MODE_ALL
                    } ?: Player.REPEAT_MODE_OFF
                    restoredPlayer.setPlaybackSpeed(restoredSession.speed.coerceIn(0.5f, 2f))
                    restoredPlayer.pause()
                }
            }
    }

    private val player: ExoPlayer
        get() = playerDelegate.value

    init {
        runCatching { restorePlaybackSession() }.getOrElse { error ->
            // A corrupt session or OEM media-stack failure must not become a
            // permanent launch-crash loop. Discard only the resumable session.
            android.util.Log.e("MusicPlayer", "Playback restore disabled", error)
            _state.value = MusicPlayerState()
            clearPersistedPlaybackSession()
            false
        }
        ticker = applicationScope.launch(Dispatchers.Main.immediate) {
            var lastTickerPersistMs = 0L
            while (true) {
                if (_state.value.current != null && playerDelegate.isInitialized()) {
                    val remaining = sleepTimerDeadlineMs?.minus(SystemClock.elapsedRealtime())
                    if (remaining != null && remaining <= 0) {
                        sleepTimerDeadlineMs = null
                        sleepTimerStep = 0
                        player.pause()
                    }
                    val dur = player.duration.takeIf { value -> value > 0 } ?: _state.value.durationMs
                    val rawPos = player.currentPosition.coerceAtLeast(0)
                    val pos = if (dur > 0) rawPos.coerceIn(0L, dur) else rawPos
                    val buf = player.bufferedPosition.coerceAtLeast(0)
                    val sleepRemaining = remaining?.coerceAtLeast(0)

                    watchBufferingStall(pos, buf)

                    val targetVolume = calculateCrossfadeVolume(pos, dur)
                    if (kotlin.math.abs(player.volume - targetVolume) > 0.008f) {
                        player.volume = targetVolume
                    }

                    val previous = _state.value
                    val unchanged = !player.isPlaying &&
                        previous.positionMs == pos &&
                        previous.bufferedPositionMs == buf &&
                        previous.durationMs == dur &&
                        previous.sleepTimerRemainingMs == sleepRemaining
                    if (!unchanged) {
                        _state.update {
                            it.copy(
                                positionMs = pos,
                                bufferedPositionMs = buf,
                                durationMs = dur,
                                sleepTimerRemainingMs = sleepRemaining,
                            )
                        }
                        // Session persistence rebuilds a queue slice every call —
                        // throttling it from every tick to 2s removes constant
                        // main-thread allocation with zero UX difference (the
                        // signature already buckets positions at 5s).
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastTickerPersistMs >= TICKER_PERSIST_INTERVAL_MS) {
                            lastTickerPersistMs = now
                            persistPlaybackSession()
                        }
                    }
                }
                // Preserve lazy player startup when there is no restored or
                // active queue. The short-circuit avoids touching ExoPlayer.
                delay(
                    if (_state.value.current != null &&
                        playerDelegate.isInitialized() &&
                        player.isPlaying
                    ) 60L else 500L,
                )
            }
        }

        applicationScope.launch {
            settingsPreferences.settings.collect { settings ->
                crossfadeEnabled = settings.crossfadeEnabled
                crossfadeDurationMs = settings.crossfadeSeconds.coerceIn(1, 10) * 1000L
                if (playerDelegate.isInitialized()) {
                    onMain {
                        if (settings.crossfadeEnabled) {
                            val duration = player.duration.takeIf { it > 0L } ?: _state.value.durationMs
                            val position = player.currentPosition.coerceAtLeast(0L)
                            player.volume = calculateCrossfadeVolume(position, duration)
                            return@onMain
                        }
                        incomingCrossfadeMediaId = null
                        if (player.volume < 0.99f) player.volume = 1.0f
                    }
                }
            }
        }

        applicationScope.launch {
            discoverRepository.feed.collect { feed ->
                if (discoverQueueActive) appendMissingDiscoverTracks(feed.map(GeneratedTrack::toPlayableTrack))
            }
        }
    }

    fun play(track: PlayableTrack, sourceLabel: String = "LastWave") {
        pendingRestoredSession = null
        disableDiscoverQueue()
        playRequest?.cancel()
        unavailableSkipJob?.cancel()
        unavailableMediaIds.clear()
        if (!track.videoId.isNullOrBlank()) {
            innerTube.prefetchStream(track.videoId)
        }
        onMain {
            ensureForegroundService()
            resetBufferingWatch()
            _state.value = MusicPlayerState(
                current = track,
                queue = listOf(track),
                currentIndex = 0,
                sourceLabel = sourceLabel,
                isBuffering = true,
                isPlaying = true,
            )
            persistPlaybackSession()
            if (track.playbackUrl != null) {
                applicationScope.launch(Dispatchers.IO) {
                    publishLocalTrackQuality(track)
                }
            }
            player.setMediaItem(track.toMediaItem())
            player.prepare()
            player.play()
        }
    }

    fun playQueue(
        tracks: List<PlayableTrack>,
        startIndex: Int = 0,
        sourceLabel: String = "LastWave",
        startShuffled: Boolean = false,
    ) {
        playQueueInternal(tracks, startIndex, endlessDiscover = false, sourceLabel = sourceLabel, startShuffled = startShuffled)
    }

    fun playDiscoverQueue(tracks: List<PlayableTrack>, startIndex: Int = 0) {
        playQueueInternal(tracks, startIndex, endlessDiscover = true, sourceLabel = "Discover", startShuffled = false)
    }

    private fun playQueueInternal(
        tracks: List<PlayableTrack>,
        startIndex: Int,
        endlessDiscover: Boolean,
        sourceLabel: String = if (endlessDiscover) "Discover" else "LastWave",
        startShuffled: Boolean = false,
    ) {
        if (tracks.isEmpty()) return
        pendingRestoredSession = null
        discoverQueueLoadJob?.cancel()
        discoverQueueActive = endlessDiscover
        val selectedIndex = startIndex.coerceIn(tracks.indices)
        playRequest?.cancel()
        queueEnrichmentJob?.cancel()
        unavailableSkipJob?.cancel()
        unavailableMediaIds.clear()

        val selectedTrack = tracks[selectedIndex]
        if (!selectedTrack.videoId.isNullOrBlank()) {
            innerTube.prefetchStream(selectedTrack.videoId)
        }

        onMain {
            ensureForegroundService()
            resetBufferingWatch()
            if (startShuffled) {
                player.shuffleModeEnabled = true
            }
            _state.value = MusicPlayerState(
                current = selectedTrack,
                queue = tracks,
                currentIndex = selectedIndex,
                sourceLabel = sourceLabel,
                isEndlessQueue = endlessDiscover,
                isBuffering = true,
                isPlaying = true,
                shuffleEnabled = player.shuffleModeEnabled,
            )
            persistPlaybackSession()
            player.setMediaItems(tracks.map(PlayableTrack::toMediaItem), selectedIndex, 0L)
            player.prepare()
            player.play()

            enrichUpcomingQueue(selectedIndex)
            if (endlessDiscover) {
                appendMissingDiscoverTracks(discoverRepository.getCachedFeed().map(GeneratedTrack::toPlayableTrack))
            }
            extendDiscoverQueueIfNeeded(selectedIndex)
            val nextIndex = if (player.shuffleModeEnabled) player.nextMediaItemIndex else selectedIndex + 1
            if (nextIndex != C.INDEX_UNSET && nextIndex in tracks.indices) {
                preloadNextTrack(tracks[nextIndex])
            }
        }
    }

    fun playNext(track: PlayableTrack) {
        applicationScope.launch {
            val enriched = runCatching { matchMetadata(track) }.getOrDefault(track)
            withContext(Dispatchers.Main.immediate) {
                val index = (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
                player.addMediaItem(index, enriched.toMediaItem())
            }
        }
    }

    fun addToQueue(track: PlayableTrack) {
        applicationScope.launch {
            val enriched = runCatching { matchMetadata(track) }.getOrDefault(track)
            withContext(Dispatchers.Main.immediate) { player.addMediaItem(enriched.toMediaItem()) }
        }
    }

    /** Adds the varied continuation loaded for a search-started track.
     * A stale response can never modify a newer playback queue. */
    fun appendSearchRecommendations(seed: PlayableTrack, tracks: List<PlayableTrack>) {
        if (tracks.isEmpty()) return
        onMain {
            val current = player.currentMediaItem?.toPlayableTrack() ?: return@onMain
            val sameSeed = if (!seed.videoId.isNullOrBlank()) {
                seed.videoId == current.videoId
            } else {
                seed.title.equals(current.title, ignoreCase = true) &&
                    seed.artist.equals(current.artist, ignoreCase = true)
            }
            if (!sameSeed || _state.value.sourceLabel != "Search") return@onMain

            val seenQueueKeys = (0 until player.mediaItemCount).mapTo(mutableSetOf()) {
                player.getMediaItemAt(it).toPlayableTrack().queueKey()
            }
            val seenTitles = (0 until player.mediaItemCount).mapTo(mutableSetOf()) {
                player.getMediaItemAt(it).toPlayableTrack().searchQueueTitleKey()
            }
            val fresh = tracks.filter { track ->
                track.title.isNotBlank() && track.artist.isNotBlank() &&
                    seenQueueKeys.add(track.queueKey()) &&
                    seenTitles.add(track.searchQueueTitleKey())
            }
            if (fresh.isEmpty()) return@onMain

            player.addMediaItems(fresh.map(PlayableTrack::toMediaItem))
            refresh(player)
            val currentIndex = player.currentMediaItemIndex
            enrichUpcomingQueue(currentIndex)
            val nextIndex = if (player.shuffleModeEnabled) player.nextMediaItemIndex else currentIndex + 1
            if (nextIndex != C.INDEX_UNSET && nextIndex in 0 until player.mediaItemCount) {
                preloadNextTrack(player.getMediaItemAt(nextIndex).toPlayableTrack())
            }
        }
    }

    fun resume() = onMain {
        ensureForegroundService()
        if (player.mediaItemCount == 0 && _state.value.current != null) {
            val q = _state.value.queue.ifEmpty { listOf(_state.value.current!!) }
            val idx = _state.value.currentIndex.coerceIn(q.indices)
            player.setMediaItems(q.map(PlayableTrack::toMediaItem), idx, _state.value.positionMs)
            player.prepare()
            player.play()
            return@onMain
        }
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.prepare()
        }
        player.play()
    }

    fun pause() = onMain { player.pause() }

    fun togglePlayPause() = onMain {
        if (player.isPlaying) {
            player.pause()
        } else {
            ensureForegroundService()
            if (player.mediaItemCount == 0 && _state.value.current != null) {
                val q = _state.value.queue.ifEmpty { listOf(_state.value.current!!) }
                val idx = _state.value.currentIndex.coerceIn(q.indices)
                player.setMediaItems(q.map(PlayableTrack::toMediaItem), idx, _state.value.positionMs)
                player.prepare()
                player.play()
                return@onMain
            }
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0)
                player.prepare()
            }
            player.play()
        }
    }
    fun seekTo(positionMs: Long) = onMain {
        val target = positionMs.coerceAtLeast(0)
        player.seekTo(target)
        val dur = player.duration.takeIf { it > 0 } ?: _state.value.durationMs
        player.volume = calculateCrossfadeVolume(target, dur)
        _state.update { it.copy(positionMs = target) }
    }

    private fun calculateCrossfadeVolume(positionMs: Long, durationMs: Long): Float {
        if (!crossfadeEnabled || crossfadeDurationMs <= 0L) return 1.0f
        val isIncomingCrossfade = incomingCrossfadeMediaId == player.currentMediaItem?.mediaId
        if (isIncomingCrossfade) {
            val incomingFadeDuration = if (durationMs > 0L) {
                minOf(crossfadeDurationMs, durationMs / 3).coerceAtLeast(500L)
            } else {
                crossfadeDurationMs
            }
            if (positionMs < incomingFadeDuration) {
                val progress = (positionMs.toFloat() / incomingFadeDuration.toFloat()).coerceIn(0f, 1f)
                val fadeCurve = kotlin.math.sin(progress * (Math.PI.toFloat() / 2f))
                return (incomingCrossfadeStartVolume +
                    ((1f - incomingCrossfadeStartVolume) * fadeCurve)).coerceIn(0f, 1f)
            }
            incomingCrossfadeMediaId = null
        }
        if (durationMs <= 0L) return 1.0f

        val effectiveCrossfade = minOf(crossfadeDurationMs, durationMs / 3).coerceAtLeast(500L)
        if (durationMs < effectiveCrossfade * 2) return 1.0f

        val remainingMs = durationMs - positionMs
        if (remainingMs >= effectiveCrossfade) return 1.0f

        val progress = (remainingMs.toFloat() / effectiveCrossfade.toFloat()).coerceIn(0f, 1f)
        return kotlin.math.sin(progress * (Math.PI.toFloat() / 2f))
    }
    fun seekToQueueItem(index: Int) = onMain {
        if (index in 0 until player.mediaItemCount) {
            ensureForegroundService()
            player.seekToDefaultPosition(index)
            player.play()
        }
    }
    fun previous() = onMain {
        if (player.currentPosition > 5_000) player.seekTo(0) else player.seekToPreviousMediaItem()
    }
    fun next() = onMain { player.seekToNextMediaItem() }
    fun toggleShuffle() = setShuffleEnabled(!state.value.shuffleEnabled)

    fun setShuffleEnabled(enabled: Boolean) = onMain {
        if (player.shuffleModeEnabled == enabled) return@onMain
        player.shuffleModeEnabled = enabled
        _state.update { it.copy(shuffleEnabled = enabled) }
        persistPlaybackSession()
    }

    fun cycleRepeatMode() = setRepeatMode(
        when (state.value.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        },
    )

    fun setRepeatMode(mode: Int) = onMain {
        val supportedMode = when (mode) {
            Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL -> mode
            else -> Player.REPEAT_MODE_OFF
        }
        if (player.repeatMode == supportedMode) return@onMain
        player.repeatMode = supportedMode
        _state.update { it.copy(repeatMode = supportedMode) }
        persistPlaybackSession()
    }
    fun cycleSpeed() = onMain {
        val next = when {
            player.playbackParameters.speed < 1f -> 1f
            player.playbackParameters.speed < 1.25f -> 1.25f
            player.playbackParameters.speed < 1.5f -> 1.5f
            player.playbackParameters.speed < 2f -> 2f
            else -> 0.75f
        }
        player.setPlaybackSpeed(next)
    }
    fun cycleSleepTimer() = onMain {
        sleepTimerStep = (sleepTimerStep + 1) % SLEEP_TIMER_MINUTES.size
        val minutes = SLEEP_TIMER_MINUTES[sleepTimerStep]
        sleepTimerDeadlineMs = minutes.takeIf { it > 0 }
            ?.let { SystemClock.elapsedRealtime() + it * 60_000L }
        _state.update {
            it.copy(sleepTimerRemainingMs = sleepTimerDeadlineMs?.minus(SystemClock.elapsedRealtime()))
        }
    }
    fun clearUpcoming() = onMain {
        disableDiscoverQueue()
        val current = player.currentMediaItemIndex
        if (current >= 0 && current + 1 < player.mediaItemCount) {
            player.removeMediaItems(current + 1, player.mediaItemCount)
        }
    }
    fun stopAndClear() = onMain {
        playRequest?.cancel()
        queueEnrichmentJob?.cancel()
        disableDiscoverQueue()
        unavailableSkipJob?.cancel()
        sleepTimerDeadlineMs = null
        sleepTimerStep = 0
        player.stop()
        player.clearMediaItems()
        _state.value = MusicPlayerState()
        clearPersistedPlaybackSession()
        applicationScope.launch(Dispatchers.IO) { WidgetUpdater.clear(appContext) }
        appContext.stopService(Intent(appContext, MusicPlaybackService::class.java))
    }
    fun removeQueueItem(index: Int) = onMain {
        if (index in 0 until player.mediaItemCount) player.removeMediaItem(index)
    }
    fun clearError() = _state.update { it.copy(error = null) }
    fun retry() = onMain {
        val currentTrack = _state.value.current ?: return@onMain
        clearError()
        play(currentTrack, _state.value.sourceLabel)
    }

    /**
     * Keeps Last.fm's canonical display naming while attaching the exact
     * YouTube Music identity, album and high-resolution catalog artwork.
     */
    private suspend fun matchMetadata(track: PlayableTrack): PlayableTrack {
        if (!track.videoId.isNullOrBlank() && !track.artworkUrl.isNullOrBlank()) return track
        val match = innerTube.findBestMatch(track.title, track.artist)
        return track.copy(
            title = track.title.ifBlank { match.title },
            artist = track.artist.ifBlank { match.artist },
            album = track.album?.takeIf(String::isNotBlank) ?: match.album,
            artworkUrl = match.artworkUrl?.takeIf(String::isNotBlank)
                ?: track.artworkUrl?.takeIf(String::isNotBlank),
            videoId = track.videoId ?: match.videoId,
        )
    }

    private fun enrichUpcomingQueue(currentIndex: Int) {
        queueEnrichmentJob?.cancel()
        queueEnrichmentJob = applicationScope.launch {
            val targetIndices = withContext(Dispatchers.Main.immediate) {
                val list = mutableListOf<Int>()
                for (i in (currentIndex + 1) until minOf(currentIndex + 5, player.mediaItemCount)) {
                    list.add(i)
                }
                if (player.shuffleModeEnabled) {
                    val next = player.nextMediaItemIndex
                    if (next != C.INDEX_UNSET && next !in list && next in 0 until player.mediaItemCount) {
                        list.add(0, next)
                    }
                }
                list
            }
            data class PendingEnrich(val index: Int, val original: PlayableTrack, val expectedMediaId: String)
            val pending = targetIndices.mapNotNull { index ->
                val original = withContext(Dispatchers.Main.immediate) {
                    if (index >= player.mediaItemCount) null else player.getMediaItemAt(index).toPlayableTrack()
                } ?: return@mapNotNull null
                if (!original.videoId.isNullOrBlank() && !original.artworkUrl.isNullOrBlank()) return@mapNotNull null
                PendingEnrich(
                    index = index,
                    original = original,
                    expectedMediaId = original.videoId ?: "query:${original.artist.lowercase()}|${original.title.lowercase()}",
                )
            }
            if (pending.isEmpty()) return@launch
            // Match all pending tracks in parallel instead of one chained
            // network round-trip after the other.
            val enrichedPairs = coroutineScope {
                pending.map { item ->
                    async {
                        item to runCatching { matchMetadata(item.original) }.getOrNull()
                    }
                }.awaitAll()
            }
            for ((item, enriched) in enrichedPairs) {
                if (enriched == null) continue
                val expectedMediaId = item.expectedMediaId
                val index = item.index
                withContext(Dispatchers.Main.immediate) {
                    // NEVER replace the currently playing item, as replaceMediaItem resets the player buffer and interrupts playback midway
                    if (index != player.currentMediaItemIndex && index in 0 until player.mediaItemCount && player.getMediaItemAt(index).mediaId == expectedMediaId) {
                        player.replaceMediaItem(index, enriched.toMediaItem())
                    }
                }
            }
        }
    }


    /**
     * Pre-resolves a useful opening window of the upcoming track into the disk
     * cache. This reduces transition stalls without downloading the full track
     * or competing indefinitely with current playback.
     */
    private fun preloadNextTrack(nextTrack: PlayableTrack?) {
        if (nextTrack == null) return
        preloadJob?.cancel()
        preloadJob = applicationScope.launch(Dispatchers.IO) {
            delay(NEXT_TRACK_PREFETCH_DELAY_MS)
            if (!_state.value.isPlaying) return@launch
            val resolved = runCatching {
                resolveTrackAudioStream(nextTrack, nextTrack.videoId)
            }.getOrNull() ?: return@launch

            val dataSpec = DataSpec.Builder()
                .setUri(Uri.parse(resolved.url))
                .setPosition(0)
                .setLength(NEXT_TRACK_PREFETCH_BYTES)
                .build()

            runCatching {
                val cacheWriter = CacheWriter(
                    cacheDataSourceFactory.createDataSource(),
                    dataSpec,
                    null,
                    null,
                )
                cacheWriter.cache()
            }
        }
    }

    /** Keeps a Discover-started queue supplied before its loaded tail is reached. */
    private fun extendDiscoverQueueIfNeeded(currentIndex: Int) {
        if (!discoverQueueActive || discoverQueueLoadJob?.isActive == true) return
        discoverQueueLoadJob = applicationScope.launch {
            try {
                val shouldLoad = withContext(Dispatchers.Main.immediate) {
                    discoverQueueActive &&
                        currentIndex >= 0 &&
                        player.mediaItemCount - currentIndex - 1 <= DISCOVER_QUEUE_REFILL_THRESHOLD
                }
                if (!shouldLoad) return@launch

                val batch = runCatching {
                    discoverRepository.nextBatch(DISCOVER_QUEUE_BATCH_SIZE)
                }.onFailure { error ->
                    android.util.Log.d("MusicPlayer", "Discover queue refill failed", error)
                }.getOrDefault(emptyList())
                appendMissingDiscoverTracks(batch.map(GeneratedTrack::toPlayableTrack))
            } finally {
                discoverQueueLoadJob = null
            }
        }
    }

    private fun appendMissingDiscoverTracks(tracks: List<PlayableTrack>) {
        if (tracks.isEmpty()) return
        onMain {
            val known = (0 until player.mediaItemCount).map {
                player.getMediaItemAt(it).toPlayableTrack().queueKey()
            }.toSet()
            val fresh = tracks.filterNot { it.queueKey() in known }
            if (fresh.isNotEmpty()) {
                player.addMediaItems(fresh.map(PlayableTrack::toMediaItem))
            }
        }
    }

    private fun disableDiscoverQueue() {
        discoverQueueActive = false
        discoverQueueLoadJob?.cancel()
        discoverQueueLoadJob = null
    }

    /** Recover a loader that remains buffered at the same byte and playback
     * position instead of waiting forever for an error Media3 may never emit. */
    @MainThread
    private fun watchBufferingStall(positionMs: Long, bufferedPositionMs: Long) {
        if (!player.playWhenReady || player.playbackState != Player.STATE_BUFFERING) {
            bufferingWatchStartedMs = 0L
            return
        }

        val mediaId = player.currentMediaItem?.mediaId ?: return
        val now = SystemClock.elapsedRealtime()
        val progressed = bufferingWatchMediaId != mediaId ||
            positionMs > bufferingWatchPositionMs ||
            bufferedPositionMs > bufferingWatchBufferedMs
        if (progressed || bufferingWatchStartedMs == 0L) {
            if (bufferingWatchMediaId != mediaId) resetBufferingWatch(mediaId)
            bufferingWatchStartedMs = now
            bufferingWatchPositionMs = positionMs
            bufferingWatchBufferedMs = bufferedPositionMs
            return
        }
        if (now - bufferingWatchStartedMs < BUFFERING_STALL_TIMEOUT_MS || bufferingRecoveryJob?.isActive == true) return

        bufferingWatchStartedMs = now
        if (bufferingRecoveryCount >= MAX_BUFFERING_RECOVERY_ATTEMPTS) {
            _state.update { it.copy(isPlaying = false, isBuffering = false, error = "Playback stalled") }
            scheduleUnavailableMediaSkip(player.currentMediaItemIndex, mediaId)
            return
        }

        bufferingRecoveryCount++
        recoverStalledPlayback(mediaId)
    }

    @MainThread
    private fun recoverStalledPlayback(expectedMediaId: String) {
        val failedIndex = player.currentMediaItemIndex
        val failedTrack = player.currentMediaItem?.toPlayableTrack() ?: return
        if (failedTrack.playbackUrl != null || failedIndex == C.INDEX_UNSET) return
        val resumePositionMs = player.currentPosition.coerceAtLeast(0L)
        if (_state.value.isQobuz) qobuzBypassMediaIds += expectedMediaId

        preloadJob?.cancel()
        player.stop()
        bufferingRecoveryJob = applicationScope.launch(Dispatchers.Main.immediate) {
            withContext(Dispatchers.IO) {
                failedTrack.videoId?.takeIf(String::isNotBlank)?.let(innerTube::invalidateCache)
                runCatching {
                    mediaCache.keys.toList().forEach(mediaCache::removeResource)
                }
            }
            if (player.currentMediaItemIndex != failedIndex || player.currentMediaItem?.mediaId != expectedMediaId) return@launch

            val cleanTrack = failedTrack.copy(playbackUrl = null, playbackMimeType = null)
            player.replaceMediaItem(failedIndex, cleanTrack.toMediaItem())
            player.seekTo(failedIndex, resumePositionMs)
            player.prepare()
            player.play()
        }
    }

    @MainThread
    private fun resetBufferingWatch(mediaId: String? = null) {
        bufferingRecoveryJob?.cancel()
        bufferingRecoveryJob = null
        bufferingWatchMediaId = mediaId
        bufferingWatchStartedMs = 0L
        bufferingWatchPositionMs = -1L
        bufferingWatchBufferedMs = -1L
        bufferingRecoveryCount = 0
        if (mediaId == null) qobuzBypassMediaIds.clear()
    }

    @MainThread
    private fun scheduleUnavailableMediaSkip(failedIndex: Int, failedMediaId: String?) {
        if (failedIndex == C.INDEX_UNSET || failedMediaId == null) return
        unavailableSkipJob?.cancel()
        unavailableSkipJob = applicationScope.launch(Dispatchers.Main.immediate) {
            delay(UNAVAILABLE_SKIP_DELAY_MS)
            if (player.currentMediaItemIndex != failedIndex ||
                player.currentMediaItem?.mediaId != failedMediaId ||
                !player.playWhenReady || player.isPlaying
            ) {
                unavailableSkipJob = null
                return@launch
            }

            // Resolve the next item after the timeout so an endless queue has
            // time to append more tracks while this one is buffering.
            unavailableMediaIds += failedMediaId
            val suggestedNext = player.nextMediaItemIndex
            fun isUntried(index: Int): Boolean =
                index in 0 until player.mediaItemCount &&
                    player.getMediaItemAt(index).mediaId !in unavailableMediaIds
            val nextIndex = suggestedNext.takeIf { it != C.INDEX_UNSET && it != failedIndex && isUntried(it) }
                ?: (failedIndex + 1 until player.mediaItemCount).firstOrNull(::isUntried)
                ?: (0 until failedIndex).firstOrNull(::isUntried)
                    .takeIf { player.repeatMode == Player.REPEAT_MODE_ALL }
                ?: C.INDEX_UNSET
            unavailableSkipJob = null
            if (nextIndex == C.INDEX_UNSET) {
                player.stop()
                _state.update {
                    it.copy(isPlaying = false, isBuffering = false, error = "Track unavailable")
                }
                return@launch
            }
            ensureForegroundService()
            _state.update { it.copy(error = null, isBuffering = true) }
            player.seekToDefaultPosition(nextIndex)
            player.prepare()
            player.play()
        }
    }

    private fun onMain(action: () -> Unit) {
        applicationScope.launch(Dispatchers.Main.immediate) { action() }
    }

    data class ResolvedStream(
        val url: String,
        val mimeType: String,
        val bitrateKbps: Int?,
        val audioCodec: String?,
        val isQobuz: Boolean = false,
        val bitDepth: Int? = null,
        val samplingRateKHz: Double? = null,
    )

    private suspend fun resolveTrackAudioStream(
        track: PlayableTrack,
        videoId: String?,
        allowQobuz: Boolean = true,
    ): ResolvedStream = withContext(Dispatchers.IO) {
        val misc = runCatching { settingsPreferences.settings.first() }.getOrDefault(MiscSettings())
        if (allowQobuz) {
            // Keep Qobuz primary without making an unavailable catalog entry
            // visibly delay the YouTube Music fallback.
            val qobuzStream = withTimeoutOrNull(QOBUZ_RESOLVE_TIMEOUT_MS) {
                runCatching {
                    qobuzMusicApi.resolveStream(
                        title = track.title,
                        artist = track.artist,
                        expectedAlbum = track.album,
                        preferredQuality = misc.qobuzQuality,
                    )
                }.getOrNull()
            }

            if (qobuzStream != null) {
                val codec = when {
                    qobuzStream.bitDepth > 16 || qobuzStream.samplingRate > 48.0 -> "HI-RES FLAC"
                    qobuzStream.formatId == QobuzMusicApi.QUALITY_CD_LOSSLESS -> "LOSSLESS"
                    qobuzStream.formatId == QobuzMusicApi.QUALITY_MP3_320 -> "MP3 320k"
                    else -> "LOSSLESS"
                }
                return@withContext ResolvedStream(
                    url = qobuzStream.url,
                    mimeType = qobuzStream.mimeType,
                    bitrateKbps = qobuzStream.bitrateKbps,
                    audioCodec = codec,
                    isQobuz = true,
                    bitDepth = qobuzStream.bitDepth.takeIf { it > 0 },
                    samplingRateKHz = qobuzStream.samplingRate.takeIf { it > 0 },
                )
            }
        }

        // Fallback to YouTube Music
        val targetVideoId = videoId ?: run {
            val match = try {
                kotlinx.coroutines.withTimeout(3_500L) {
                    innerTube.findBestMatch(track.title, track.artist)
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                throw java.io.IOException("Timed out finding a playable match", timeout)
            }
            match.videoId.takeIf(String::isNotBlank)
                ?: throw ConfirmedUnplayableMediaException("No playable match found")
        }
        val ytStream = innerTube.resolveAudioStream(targetVideoId)
        val trueBitrate = ytStream.bitrate.takeIf { it > 0 }?.let { (it + 500) / 1_000 }
        val rawCodec = ytStream.mimeType?.substringAfter("audio/")?.substringBefore(';')?.uppercase()?.ifBlank { "WEBM" } ?: "WEBM"
        val codec = when {
            rawCodec.contains("OPUS") || rawCodec == "WEBM" -> "OPUS"
            rawCodec.contains("M4A") || rawCodec.contains("MP4") || rawCodec.contains("AAC") -> "AAC"
            else -> rawCodec
        }
        ResolvedStream(
            url = ytStream.url,
            mimeType = ytStream.mimeType.orEmpty(),
            bitrateKbps = trueBitrate,
            audioCodec = codec,
            isQobuz = false,
        )
    }

    private fun publishResolvedQuality(resolved: ResolvedStream) {
        _state.update {
            it.copy(
                bitrateKbps = resolved.bitrateKbps,
                audioCodec = resolved.audioCodec,
                isQobuz = resolved.isQobuz,
                bitDepth = resolved.bitDepth,
                samplingRateKHz = resolved.samplingRateKHz,
            )
        }
    }

    private fun publishStreamQuality(stream: com.lastwave.app.data.music.YouTubeAudioStream) {
        val trueBitrate = stream.bitrate.takeIf { value -> value > 0 }?.let { (it + 500) / 1_000 }
        val rawCodec = stream.mimeType?.substringAfter("audio/")?.substringBefore(';')?.uppercase()?.ifBlank { "WEBM" } ?: "WEBM"
        val codec = when {
            rawCodec.contains("OPUS") || rawCodec == "WEBM" -> "WEBM"
            rawCodec.contains("M4A") || rawCodec.contains("MP4") || rawCodec.contains("AAC") -> "M4A"
            else -> rawCodec
        }
        _state.update {
            it.copy(
                bitrateKbps = trueBitrate,
                audioCodec = codec,
                isQobuz = false,
            )
        }
    }

    private fun publishLocalTrackQuality(track: PlayableTrack) {
        val url = track.playbackUrl ?: return
        val retriever = android.media.MediaMetadataRetriever()
        try {
            if (url.startsWith("content://")) {
                retriever.setDataSource(appContext, Uri.parse(url))
            } else {
                retriever.setDataSource(url.removePrefix("file://"))
            }
            val mime = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.lowercase().orEmpty()
            val bitrateStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)
            val bitrateKbps = bitrateStr?.toIntOrNull()?.let { it / 1000 }
            val sampleRateStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
            } else null
            val sampleRateKHz = sampleRateStr?.toDoubleOrNull()?.let { it / 1000.0 }
            val bitDepthStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
            } else null
            val bitDepth = bitDepthStr?.toIntOrNull()

            val isFlac = mime.contains("flac") || url.endsWith(".flac", ignoreCase = true)
            val isM4a = mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") || url.endsWith(".m4a", ignoreCase = true)
            val isOpus = mime.contains("opus") || mime.contains("ogg") || url.endsWith(".opus", ignoreCase = true)
            val isMp3 = mime.contains("mp3") || mime.contains("mpeg") || url.endsWith(".mp3", ignoreCase = true)

            val codec = when {
                isFlac && ((bitDepth ?: 0) > 16 || (sampleRateKHz ?: 0.0) > 48.0) -> "HI-RES FLAC"
                isFlac -> "FLAC"
                isM4a -> "AAC"
                isOpus -> "OPUS"
                isMp3 -> "MP3"
                else -> "LOCAL AUDIO"
            }

            _state.update {
                it.copy(
                    audioCodec = codec,
                    bitrateKbps = bitrateKbps,
                    bitDepth = bitDepth ?: if (isFlac) 16 else null,
                    samplingRateKHz = sampleRateKHz ?: if (isFlac) 44.1 else null,
                    isQobuz = isFlac && (bitDepth ?: 0) > 16,
                )
            }
        } catch (_: Exception) {
            val isFlac = url.endsWith(".flac", ignoreCase = true)
            _state.update {
                it.copy(
                    audioCodec = if (isFlac) "FLAC" else "AUDIO",
                    bitDepth = if (isFlac) 16 else null,
                    samplingRateKHz = if (isFlac) 44.1 else null,
                )
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun ensureForegroundService() {
        val intent = Intent(appContext, MusicPlaybackService::class.java)
        // Background-start restrictions (Android 12+) can reject this when
        // playback is triggered from widget/tile paths — that must never take
        // the app down; playback simply continues without foreground priority.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.startForegroundService(intent)
            else appContext.startService(intent)
        }.onFailure {
            android.util.Log.w("MusicPlayer", "Foreground service start rejected", it)
        }
    }

    private fun restorePlaybackSession(): Boolean {
        val raw = playbackPreferences.getString(PLAYBACK_SESSION_KEY, null) ?: return false
        val session = runCatching {
            persistenceJson.decodeFromString<PersistedPlaybackSession>(raw)
        }.getOrElse {
            clearPersistedPlaybackSession()
            return false
        }
        val restoredQueue = session.queue
            .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
            .map { it.copy(playbackUrl = null, playbackMimeType = null) }
        if (restoredQueue.isEmpty()) {
            clearPersistedPlaybackSession()
            return false
        }
        val restoredIndex = session.currentIndex.coerceIn(restoredQueue.indices)
        discoverQueueActive = session.isEndlessQueue
        _state.value = MusicPlayerState(
            current = restoredQueue[restoredIndex],
            queue = restoredQueue,
            currentIndex = restoredIndex,
            sourceLabel = session.sourceLabel,
            isEndlessQueue = session.isEndlessQueue,
            positionMs = session.positionMs.coerceAtLeast(0),
            shuffleEnabled = session.shuffleEnabled,
            repeatMode = session.repeatMode,
            speed = session.speed,
        )
        pendingRestoredSession = session.copy(
            queue = restoredQueue,
            currentIndex = restoredIndex,
        )
        return true
    }

    private fun isConfirmedUnplayableFailure(error: Throwable): Boolean {
        return isExplicitlyUnplayableFailure(error) ||
            isPermanentHttpFailure(error) ||
            isUnsupportedMediaFailure(error)
    }

    private fun isExplicitlyUnplayableFailure(error: Throwable): Boolean =
        error.causeChain().any { it is ConfirmedUnplayableMediaException }

    private fun isPermanentHttpFailure(error: Throwable): Boolean =
        error.causeChain().any {
            it is HttpDataSource.InvalidResponseCodeException &&
                it.responseCode in PERMANENT_HTTP_STATUS_CODES
        }

    private fun isUnsupportedMediaFailure(error: Throwable): Boolean =
        error.causeChain().filterIsInstance<PlaybackException>().any {
            it.errorCode in PERMANENT_PLAYBACK_ERROR_CODES
        }

    private fun Throwable.causeChain(): Sequence<Throwable> =
        generateSequence(this) { it.cause }.take(12)

    private fun persistPlaybackSession() {
        val snapshot = _state.value
        val sourceQueue = snapshot.queue.ifEmpty {
            snapshot.current?.let(::listOf).orEmpty()
        }
        if (sourceQueue.isEmpty()) {
            clearPersistedPlaybackSession()
            return
        }
        val sourceIndex = snapshot.currentIndex.coerceIn(sourceQueue.indices)
        val startIndex = (sourceIndex - RESTORED_PREVIOUS_TRACKS).coerceAtLeast(0)
        val endIndex = minOf(sourceQueue.size, startIndex + MAX_PERSISTED_QUEUE_SIZE)
        val persistedQueue = sourceQueue.subList(startIndex, endIndex).map {
            it.copy(playbackUrl = null, playbackMimeType = null)
        }
        val persistedIndex = sourceIndex - startIndex
        val signature = buildString {
            append(persistedQueue.size).append('|')
            append(persistedIndex).append('|')
            append(persistedQueue[persistedIndex].queueKey()).append('|')
            append(snapshot.positionMs / POSITION_PERSIST_INTERVAL_MS).append('|')
            append(snapshot.sourceLabel).append('|')
            append(snapshot.isEndlessQueue).append('|')
            append(snapshot.shuffleEnabled).append('|')
            append(snapshot.repeatMode).append('|')
            append(snapshot.speed)
        }
        if (signature == lastPersistedSignature) return
        val session = PersistedPlaybackSession(
            queue = persistedQueue,
            currentIndex = persistedIndex,
            positionMs = snapshot.positionMs.coerceAtLeast(0),
            sourceLabel = snapshot.sourceLabel,
            isEndlessQueue = snapshot.isEndlessQueue,
            shuffleEnabled = snapshot.shuffleEnabled,
            repeatMode = snapshot.repeatMode,
            speed = snapshot.speed,
        )
        lastPersistedSignature = signature
        val generation = ++persistenceGeneration
        playbackPersistenceJob?.cancel()
        playbackPersistenceJob = applicationScope.launch(Dispatchers.IO) {
            val encoded = runCatching { persistenceJson.encodeToString(session) }.getOrNull()
                ?: return@launch
            synchronized(playbackPersistenceLock) {
                if (generation == persistenceGeneration) {
                    playbackPreferences.edit().putString(PLAYBACK_SESSION_KEY, encoded).apply()
                }
            }
        }
    }

    private fun clearPersistedPlaybackSession() {
        pendingRestoredSession = null
        persistenceGeneration++
        playbackPersistenceJob?.cancel()
        playbackPersistenceJob = null
        lastPersistedSignature = ""
        synchronized(playbackPersistenceLock) {
            playbackPreferences.edit().remove(PLAYBACK_SESSION_KEY).apply()
        }
    }


    @MainThread
    private fun refresh(player: Player) {
        val previous = _state.value
        val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toPlayableTrack() }
        val current = player.currentMediaItem?.toPlayableTrack()
        val sameTrack = current?.let { it.title == previous.current?.title && it.artist == previous.current?.artist } == true ||
            (current?.videoId != null && current.videoId == previous.current?.videoId)
        val isBuffering = player.playbackState == Player.STATE_BUFFERING ||
            (player.playWhenReady && player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0)
        val dur = player.duration.takeIf { it > 0 } ?: 0L
        val rawPos = player.currentPosition.coerceAtLeast(0)
        val pos = if (dur > 0) rawPos.coerceIn(0L, dur) else rawPos
        _state.value = MusicPlayerState(
            current = current,
            queue = queue,
            currentIndex = player.currentMediaItemIndex.takeIf { player.mediaItemCount > 0 } ?: -1,
            sourceLabel = previous.sourceLabel,
            isEndlessQueue = previous.isEndlessQueue && discoverQueueActive,
            isPlaying = player.isPlaying,
            isBuffering = isBuffering,
            positionMs = pos,
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0),
            durationMs = dur,
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            speed = player.playbackParameters.speed,
            bitrateKbps = previous.bitrateKbps.takeIf { sameTrack },
            audioCodec = previous.audioCodec.takeIf { sameTrack },
            isQobuz = previous.isQobuz && sameTrack,
            bitDepth = previous.bitDepth.takeIf { sameTrack },
            samplingRateKHz = previous.samplingRateKHz.takeIf { sameTrack },
            sleepTimerRemainingMs = sleepTimerDeadlineMs?.minus(SystemClock.elapsedRealtime())?.coerceAtLeast(0),
            error = if (player.isPlaying) null else previous.error,
        )
        persistPlaybackSession()
    }

    private companion object {
        const val DISCOVER_QUEUE_BATCH_SIZE = 16
        const val DISCOVER_QUEUE_REFILL_THRESHOLD = 8
        const val UNAVAILABLE_SKIP_DELAY_MS = 6_000L
        const val BUFFERING_STALL_TIMEOUT_MS = 25_000L
        const val MAX_BUFFERING_RECOVERY_ATTEMPTS = 1
        const val POSITION_PERSIST_INTERVAL_MS = 5_000L
        const val MAX_PERSISTED_QUEUE_SIZE = 200
        const val RESTORED_PREVIOUS_TRACKS = 50
        const val PLAYBACK_PREFERENCES_NAME = "lastwave_playback_session"
        const val PLAYBACK_SESSION_KEY = "active_session"
        /** Ticker-driven session persistence cadence (explicit state changes persist immediately). */
        const val TICKER_PERSIST_INTERVAL_MS = 2_000L
        /** Hard ceiling for the blocking data-spec stream resolution inside ExoPlayer's loader. */
        const val QOBUZ_RESOLVE_TIMEOUT_MS = 4_000L
        const val RESOLVE_DATA_SPEC_TIMEOUT_MS = 35_000L
        const val MEDIA_STREAM_CACHE_BYTES = 64L * 1024 * 1024
        const val NEXT_TRACK_PREFETCH_BYTES = 1L * 1024 * 1024
        const val NEXT_TRACK_PREFETCH_DELAY_MS = 3_000L
        val PERMANENT_HTTP_STATUS_CODES = setOf(401, 404, 410, 451)
        val PERMANENT_PLAYBACK_ERROR_CODES = setOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
        val SLEEP_TIMER_MINUTES = intArrayOf(0, 15, 30, 60)
    }
}

private fun PlayableTrack.toMediaItem(): MediaItem {
    val playbackUri = if (playbackUrl?.isNotBlank() == true) {
        if (playbackUrl.startsWith("/")) {
            Uri.fromFile(java.io.File(playbackUrl))
        } else {
            Uri.parse(playbackUrl)
        }
    } else if (!videoId.isNullOrBlank()) {
        Uri.Builder().scheme("lastwave").authority("youtube").appendPath(videoId)
            .appendQueryParameter("title", title)
            .appendQueryParameter("artist", artist)
            .build()
    } else {
        Uri.Builder().scheme("lastwave").authority("search")
            .appendQueryParameter("title", title)
            .appendQueryParameter("artist", artist)
            .build()
    }
    val mediaIdKey = when {
        !playbackUrl.isNullOrBlank() -> "local:${playbackUrl}"
        !videoId.isNullOrBlank() -> videoId
        else -> "query:${artist.lowercase()}|${title.lowercase()}"
    }
    return MediaItem.Builder()
        .setMediaId(mediaIdKey)
        .setUri(playbackUri)
        .apply { playbackMimeType?.takeIf(String::isNotBlank)?.let(::setMimeType) }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artworkUrl?.takeIf(String::isNotBlank)?.let(Uri::parse))
                .setIsPlayable(true)
                .build(),
        )
        .build()
}

private fun MediaItem.toPlayableTrack(): PlayableTrack {
    val uriStr = localConfiguration?.uri?.toString()
    val isLocal = uriStr?.startsWith("content://") == true || uriStr?.startsWith("file://") == true || mediaId.startsWith("local:")
    return PlayableTrack(
        title = mediaMetadata.title?.toString().orEmpty().ifBlank { "Unknown track" },
        artist = mediaMetadata.artist?.toString().orEmpty().ifBlank { "Unknown artist" },
        album = mediaMetadata.albumTitle?.toString(),
        artworkUrl = mediaMetadata.artworkUri?.toString(),
        videoId = mediaId.takeUnless { it.startsWith("query:") || it.startsWith("local:") },
        playbackUrl = if (isLocal) uriStr else null,
        playbackMimeType = localConfiguration?.mimeType,
    )
}

fun GeneratedTrack.toPlayableTrack() = PlayableTrack(
    title = name,
    artist = artist,
    album = album,
    artworkUrl = artworkUrl,
)

private fun PlayableTrack.queueKey(): String = "$title|$artist".lowercase()

/**
 * Samsung One UI ships vendor FLAC decoders (c2.sec.flac.decoder,
 * OMX.SEC.FLAC.Decoder, OMX.Exynos.FLAC.Decoder) that decode 24-bit hi-res
 * FLAC to packed 24-bit PCM while failing to advertise
 * KEY_PCM_ENCODING = ENCODING_PCM_24BIT_PACKED in the output MediaFormat.
 * The 3-byte samples are then consumed as 2-byte: buffers drain exactly
 * 3/2 faster — chipmunk pitch at ~1.5x speed — and broken Left/Right byte
 * boundaries surface as harsh digital noise. Only FLAC is affected;
 * Opus/AAC/MP3 play normally, which is why the fault isolated to Samsung
 * hardware playing lossless files.
 *
 * Demotes Samsung vendor decoders to the end of the FLAC codec list so the
 * reliable reference AOSP software decoder (c2.android.flac.decoder) wins.
 * Every other mime type keeps Android's default codec order untouched.
 */
@OptIn(UnstableApi::class)
private val accurateAudioMediaCodecSelector =
    MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        val decoderInfos = runCatching {
            MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder,
            )
        }.getOrDefault(emptyList())
        if (decoderInfos.isEmpty()) {
            emptyList()
        } else {
            // Deprioritize buggy vendor decoders (Samsung One UI / Exynos hardware decoders)
            // across audio MIME types to avoid misreported sample rates, 1.5x fast pitch shifts,
            // or digital boundary distortion. Standard AOSP/Google reference decoders take priority.
            decoderInfos.sortedBy { info -> audioDecoderPriority(info.name) }
        }
    }

/** 0 = trusted reference decoder, 1 = proprietary vendor decoder (demoted to avoid clock skew). */
private fun audioDecoderPriority(name: String): Int {
    val lower = name.lowercase()
    return if (lower.contains("sec.") || lower.contains("exynos")) 1 else 0
}

private fun PlayableTrack.searchQueueTitleKey(): String = title
    .lowercase()
    .replace(SEARCH_TITLE_VARIANT, " ")
    .replace(SEARCH_TITLE_NON_CHARACTER, "")

private val SEARCH_TITLE_VARIANT = Regex(
    """\s*[\[(][^)\]]*\b(?:official|video|audio|lyrics?|cover|karaoke|remaster(?:ed)?|live|version|edit|mix|slowed|reverb)[^)\]]*[])]""",
    RegexOption.IGNORE_CASE,
)
private val SEARCH_TITLE_NON_CHARACTER = Regex("[^\\p{L}\\p{N}]+")
