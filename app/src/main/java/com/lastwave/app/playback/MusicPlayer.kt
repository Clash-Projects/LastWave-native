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
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import coil.imageLoader
import coil.request.ImageRequest
import com.lastwave.app.data.discover.DiscoverRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.EqualizerPreferences
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.ConfirmedUnplayableMediaException
import com.lastwave.app.data.music.YouTubeAudioStream
import com.lastwave.app.data.music.YOUTUBE_WEB_USER_AGENT
import com.lastwave.app.data.lossless.LosslessAudioStream
import com.lastwave.app.data.lossless.LosslessMusicApi
import com.lastwave.app.widget.WidgetUpdater
import kotlinx.coroutines.flow.first
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

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
    val isLossless: Boolean = false,
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
    private val losslessMusicApi: LosslessMusicApi,
    private val settingsPreferences: SettingsPreferences,
    private val equalizerPreferences: EqualizerPreferences,
    private val discoverRepository: DiscoverRepository,
    private val nativeAudioEngine: dagger.Lazy<NativeAudioEngine>,
    private val audioEffectsEngine: AudioEffectsEngine,
    private val applicationScope: CoroutineScope,
    private val downloadedTrackDao: dagger.Lazy<com.lastwave.app.data.local.db.DownloadedTrackDao>,
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
    private val playRequestGeneration = AtomicLong()
    private var queueEnrichmentJob: Job? = null
    private var preloadJob: Job? = null
    private val resolutionRequests = ConcurrentHashMap<List<Any?>, Pair<Long, Deferred<ResolvedStream>>>()
    private var discoverQueueLoadJob: Job? = null
    private var discoverQueueActive = false
    private var radioQueueLoadJob: Job? = null
    private var radioQueueActive = false
    private val radioUsedSeeds = ConcurrentHashMap.newKeySet<String>()
    private var unavailableSkipJob: Job? = null
    private val unavailableMediaIds = mutableSetOf<String>()
    private var sleepTimerDeadlineMs: Long? = null
    private var sleepTimerStep = 0
    @Volatile
    private var crossfadeEnabled = false
    @Volatile
    private var crossfadeDurationMs = 5_000L
    private var activePlayer: ExoPlayer? = null
    private var secondaryPlayer: ExoPlayer? = null
    private var secondaryNativeEngine: NativeAudioEngine? = null
    private var secondaryEffects: AudioEffectsEngine? = null
    private var outgoingPlayer: ExoPlayer? = null
    private var overlapDurationMs = 0L
    private var standbyQueue: List<MediaItem> = emptyList()
    private var standbyIndex = C.INDEX_UNSET
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
    private val losslessBypassMediaIds = ConcurrentHashMap.newKeySet<String>()
    private var bitPerfectEnabled = false
    private val resolvingMediaIds = ConcurrentHashMap<String, Long>()
    private val preparedStreams = ConcurrentHashMap<String, ResolvedStream>()

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
            .setContentTypePredicate(HttpDataSource.REJECT_PAYWALL_TYPES)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "audio/*,*/*;q=0.8",
                    "Accept-Encoding" to "identity",
                ),
            )
        val defaultDataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(appContext, httpUpstream)
        CacheDataSource.Factory()
            .setCache(mediaCache)
            .setUpstreamDataSourceFactory(defaultDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private val listener: Player.Listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player === this@MusicPlayer.player) refresh(player)
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            outgoingPlayer?.playWhenReady = isPlaying
            if (isPlaying) {
                unavailableSkipJob?.cancel()
                unavailableSkipJob = null
                unavailableMediaIds.clear()
                errorRetryCount = 0
                retryMediaId = player.currentMediaItem?.mediaId
            }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem != null) {
                losslessBypassMediaIds.retainAll(setOf(mediaItem.mediaId))
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
                mediaItem.localConfiguration
                    ?.customCacheKey
                    ?.let(preparedStreams::get)
                    ?.let(::publishResolvedQuality)
                if (outgoingPlayer == null) cancelCrossfade()
                // Queue placeholders are intentionally non-playable until
                // their signed stream has been resolved. Resolve an item
                // before Media3 can attempt to open its lastwave:// URI.
                val prepared = mediaItem.localConfiguration
                    ?.customCacheKey
                    ?.let(preparedStreams::get)
                if (mediaItem.localConfiguration?.uri?.scheme == "lastwave" || prepared?.isExpired() == true) {
                    // During lazy-player construction a restored queue is
                    // installed before the lazy value is published. Defer
                    // resolution until the first explicit playback action.
                    if (playerDelegate.isInitialized()) {
                        resolveAndPlayQueueItem(currentIndex)
                    }
                    return
                }
                if (currentTrack.playbackUrl != null) {
                    applicationScope.launch(Dispatchers.IO) {
                        publishLocalTrackQuality(currentTrack)
                    }
                }
                updateBitPerfectState()
                enrichUpcomingQueue(currentIndex)
                extendDiscoverQueueIfNeeded(currentIndex)
                extendRadioQueueIfNeeded(currentIndex)
                val nextIndex = if (player.shuffleModeEnabled) {
                    player.nextMediaItemIndex
                } else {
                    currentIndex + 1
                }
                if (nextIndex != C.INDEX_UNSET && nextIndex in 0 until player.mediaItemCount) {
                    preloadNextTrack(nextIndex, player.getMediaItemAt(nextIndex).toPlayableTrack())
                }
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            cancelCrossfade()
            resolutionRequests.clear()
            val currentTrack = _state.value.current
            val currentPos = player.currentPosition.coerceAtLeast(0)
            val trackVideoId = currentTrack?.videoId
            val failedIndex = player.currentMediaItemIndex
            val failedMediaId = player.currentMediaItem?.mediaId
            if (failedMediaId != null && resolvingMediaIds.containsKey(failedMediaId)) {
                return
            }
            val rejectedStream = player.currentMediaItem
                ?.localConfiguration
                ?.customCacheKey
                ?.let(preparedStreams::get)
            val customCacheKey = player.currentMediaItem?.localConfiguration?.customCacheKey
            val failedLosslessStream = rejectedStream?.isLossless
                ?: customCacheKey?.startsWith("lossless:")
                ?: _state.value.isLossless
            val rejectedYouTubeCandidate = rejectedStream?.youtubeCandidate
            val videoId = trackVideoId ?: rejectedYouTubeCandidate?.videoId
            val httpStatus = error.httpStatusCodeOrNull()

            if (currentTrack?.playbackUrl != null) {
                _state.update { it.copy(error = error.message ?: "Local file playback error (${error.errorCodeName})", isBuffering = false) }
                return
            }

            rejectedStream?.let {
                logStreamEvent(
                    stage = "player-error",
                    stream = it,
                    retry = errorRetryCount,
                    httpStatus = httpStatus,
                    error = error,
                )
            } ?: currentTrack?.let { logResolutionFailure(it, "player-error", errorRetryCount, error) }

            if (failedLosslessStream && failedMediaId != null) {
                losslessBypassMediaIds += failedMediaId
            } else if (!videoId.isNullOrBlank()) {
                if (rejectedYouTubeCandidate == null) innerTube.invalidateCache(videoId)
                innerTube.reportPlaybackFailure(videoId, rejectedYouTubeCandidate)
            }

            val confirmedWithoutRetry = isExplicitlyUnplayableFailure(error)
            // Any Lossless CDN/format failure gets one immediate YouTube Music
            // fallback. Permanent-error skipping applies only after that
            // alternate source has also failed.
            if (confirmedWithoutRetry && !failedLosslessStream) {
                _state.update {
                    it.copy(error = "Track unavailable", isPlaying = false, isBuffering = false)
                }
                scheduleUnavailableMediaSkip(failedIndex, failedMediaId, failure = error)
                return
            }

            if (currentTrack != null &&
                errorRetryCount < MAX_PLAYBACK_RETRIES &&
                isRetryablePlaybackFailure(error)
            ) {
                errorRetryCount++
                val retry = errorRetryCount
                val generation = playRequestGeneration.incrementAndGet()
                playRequest?.cancel()
                playRequest = applicationScope.launch(Dispatchers.IO) {
                    var retryResolutionFailure: Throwable? = null
                    try {
                        rejectedStream?.cacheKey?.let { cacheKey ->
                            runCatching { mediaCache.removeResource(cacheKey) }
                            preparedStreams.remove(cacheKey)
                        }
                        val retryDelayMs = playbackRetryDelayMs(error, retry)
                        if (retryDelayMs > 0L) delay(retryDelayMs)
                        currentCoroutineContext().ensureActive()
                        val stream = resolveTrackAudioStream(
                            track = currentTrack,
                            videoId = videoId,
                            allowLossless = !failedLosslessStream,
                        )
                        val updated = currentTrack.copy(
                            playbackUrl = null,
                            playbackMimeType = null,
                        )
                        withContext(Dispatchers.Main.immediate) {
                            if (generation != playRequestGeneration.get() ||
                                player.currentMediaItemIndex != failedIndex ||
                                player.currentMediaItem?.mediaId != failedMediaId
                            ) {
                                return@withContext
                            }
                            if (failedIndex in 0 until player.mediaItemCount) {
                                registerPreparedStream(stream)
                                publishResolvedQuality(stream)
                                logStreamEvent("player-retry", stream, retry = retry)
                                player.replaceMediaItem(failedIndex, updated.toMediaItem(stream))
                                player.seekTo(failedIndex, currentPos)
                                player.prepare()
                                player.play()
                                preloadNextQueueItem(failedIndex)
                            }
                        }
                        return@launch
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (e: Throwable) {
                        retryResolutionFailure = e
                        logResolutionFailure(currentTrack, "player-retry-resolve", retry, e)
                    }
                    withContext(Dispatchers.Main.immediate) {
                        if (generation != playRequestGeneration.get() ||
                            player.currentMediaItemIndex != failedIndex ||
                            player.currentMediaItem?.mediaId != failedMediaId
                        ) {
                            return@withContext
                        }
                        _state.update { it.copy(error = error.message ?: "Playback error (${error.errorCodeName})", isBuffering = false) }
                        scheduleUnavailableMediaSkip(
                            failedIndex = failedIndex,
                            failedMediaId = failedMediaId,
                            expectedGeneration = generation,
                            failure = retryResolutionFailure ?: error,
                            allowAutoSkip = retryResolutionFailure != null,
                        )
                    }
                }
                _state.update { it.copy(isBuffering = true, error = null) }
                return
            }

            _state.update { it.copy(error = error.message ?: "Playback error (${error.errorCodeName})", isBuffering = false) }
            scheduleUnavailableMediaSkip(failedIndex, failedMediaId, failure = error)
        }
    }

    // Do not initialize ExoPlayer/audio/cache merely to draw the launcher.
    // Several Android 11 OEM audio stacks are fragile during cold start; the
    // engine is needed only when the user actually operates playback.
    private fun createPlayer(
        engineProvider: () -> NativeAudioEngine?,
        effects: AudioEffectsEngine,
        handleAudioFocus: Boolean,
    ): ExoPlayer {
        val resolving = ResolvingDataSource.Factory(cacheDataSourceFactory) { dataSpec ->
            val placeholder = dataSpec.uri.takeIf { it.scheme == "lastwave" }
            val resolvedPlaceholder = placeholder?.let { uri ->
                val videoId = uri.lastPathSegment.takeIf { uri.host == "youtube" }
                val title = uri.getQueryParameter("title").orEmpty()
                val artist = uri.getQueryParameter("artist").orEmpty()
                val track = _state.value.queue.firstOrNull {
                    if (videoId != null) it.videoId == videoId else it.title == title && it.artist == artist
                } ?: PlayableTrack(title = title, artist = artist, videoId = videoId)
                // Media3 can open the next item before its transition callback.
                // Resolve queue placeholders on its loader thread as well.
                runBlocking(Dispatchers.IO) {
                    resolveTrackAudioStreamWithRetry(track, track.videoId, allowLossless = true).also { resolved ->
                        applicationScope.launch(Dispatchers.Main.immediate) { registerPreparedStream(resolved) }
                    }
                }
            }
            val stream = resolvedPlaceholder ?: dataSpec.key?.let(preparedStreams::get)
                ?: preparedStreams.values.firstOrNull { it.url == dataSpec.uri.toString() }
            if (stream?.isExpired() == true) {
                throw java.io.IOException("Signed stream expired before open")
            }
            when {
                resolvedPlaceholder != null -> dataSpec.buildUpon()
                    .setUri(resolvedPlaceholder.url)
                    .setKey(resolvedPlaceholder.cacheKey)
                    .build()
                    .withRequestHeaders(resolvedPlaceholder.requestHeaders)
                stream != null -> dataSpec.withRequestHeaders(stream.requestHeaders)
                else -> dataSpec
            }
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ if (handleAudioFocus) 45_000 else 15_000,
                /* maxBufferMs = */ if (handleAudioFocus) 120_000 else 30_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
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
                val engine = runCatching(engineProvider).getOrNull()
                if (engine?.isAvailable != true) {
                    effects.setFallbackRequired(true)
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
                    effects.setFallbackRequired(true)
                    return fallbackSink
                } catch (error: LinkageError) {
                    android.util.Log.w("MusicPlayer", "Enhanced audio sink linkage failed; using PCM16", error)
                    effects.setFallbackRequired(true)
                    return fallbackSink
                }
                effects.setFallbackRequired(false)
                return NativeProcessingAudioSink(
                    enhancedDelegate = enhancedSink,
                    fallbackDelegate = fallbackSink,
                    processor = NativePcmAudioProcessor(engine),
                    onPlatformEffectsRequired = effects::setFallbackRequired,
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

        return ExoPlayer.Builder(appContext, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(appContext).setDataSourceFactory(resolving))
            .setLoadControl(loadControl)
            .build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
                        .build(),
                    handleAudioFocus,
                )
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
                addListener(object : Player.Listener {
                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        effects.attach(audioSessionId)
                    }
                })
            }
    }

    private val playerDelegate: Lazy<ExoPlayer> = lazy {
        createPlayer({ nativeAudioEngine.get() }, audioEffectsEngine, true)
            .also { restoredPlayer ->
                activePlayer = restoredPlayer
                restoredPlayer.addListener(listener)
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
        get() = activePlayer ?: playerDelegate.value

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

                    if (updateCrossfade(pos, dur)) continue

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
                crossfadeDurationMs = settings.crossfadeSeconds.coerceIn(1, 12) * 1000L
                bitPerfectEnabled = settings.isBitPerfectEnabled
                updateBitPerfectState()
                if (playerDelegate.isInitialized()) {
                    onMain {
                        if (!crossfadeEnabled || bitPerfectEnabled) cancelCrossfade()
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

    fun play(
        track: PlayableTrack,
        sourceLabel: String = "LastWave",
        startRadio: Boolean = (sourceLabel == "Search" || sourceLabel == "YouTube Music" || sourceLabel == "Spotify Link" || sourceLabel == "Shared Song"),
    ) {
        pendingRestoredSession = null
        disableDiscoverQueue()
        disableRadioQueue()
        queueEnrichmentJob?.cancel()
        unavailableSkipJob?.cancel()
        unavailableMediaIds.clear()
        radioQueueActive = startRadio
        startResolvedQueuePlayback(
            tracks = listOf(track),
            selectedIndex = 0,
            startPositionMs = 0L,
            sourceLabel = sourceLabel,
            endlessDiscover = false,
        )
        if (startRadio) {
            _state.update { it.copy(isEndlessQueue = true) }
            startRadioQueue(track)
        }
    }

    fun playQueue(
        tracks: List<PlayableTrack>,
        startIndex: Int = 0,
        sourceLabel: String = "LastWave",
        startShuffled: Boolean = false,
    ) {
        disableRadioQueue()
        playQueueInternal(tracks, startIndex, endlessDiscover = false, sourceLabel = sourceLabel, startShuffled = startShuffled)
    }

    fun playDiscoverQueue(tracks: List<PlayableTrack>, startIndex: Int = 0) {
        disableRadioQueue()
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
        disableRadioQueue()
        val selectedIndex = startIndex.coerceIn(tracks.indices)
        playRequest?.cancel()
        queueEnrichmentJob?.cancel()
        unavailableSkipJob?.cancel()
        unavailableMediaIds.clear()

        startResolvedQueuePlayback(
            tracks = tracks,
            selectedIndex = selectedIndex,
            startPositionMs = 0L,
            sourceLabel = sourceLabel,
            endlessDiscover = endlessDiscover,
            startShuffled = startShuffled,
        )
    }

    private fun startResolvedQueuePlayback(
        tracks: List<PlayableTrack>,
        selectedIndex: Int,
        startPositionMs: Long,
        sourceLabel: String,
        endlessDiscover: Boolean,
        startShuffled: Boolean = false,
    ) {
        val selectedTrack = tracks[selectedIndex].withYoutubeArtwork()
        warmArtwork(selectedTrack)
        val generation = playRequestGeneration.incrementAndGet()
        playRequest?.cancel()
        preloadJob?.cancel()
        onMain {
            ensureForegroundService()
            cancelCrossfade()
            losslessBypassMediaIds.clear()
            if (playerDelegate.isInitialized()) {
                player.stop()
                player.clearMediaItems()
            }
            _state.value = MusicPlayerState(
                current = selectedTrack,
                queue = tracks,
                currentIndex = selectedIndex,
                sourceLabel = sourceLabel,
                isEndlessQueue = endlessDiscover || radioQueueActive,
                isBuffering = true,
                isPlaying = true,
                positionMs = startPositionMs.coerceAtLeast(0L),
                shuffleEnabled = if (playerDelegate.isInitialized()) player.shuffleModeEnabled else startShuffled,
            )
            persistPlaybackSession()
        }
        playRequest = applicationScope.launch(Dispatchers.IO) {
            try {
                val resolved = if (selectedTrack.playbackUrl == null) {
                    resolveTrackAudioStreamWithRetry(
                        track = selectedTrack,
                        videoId = selectedTrack.videoId,
                        allowLossless = selectedTrack.mediaIdKey() !in losslessBypassMediaIds,
                    )
                } else {
                    null
                }
                currentCoroutineContext().ensureActive()
                if (generation != playRequestGeneration.get()) return@launch
                withContext(Dispatchers.Main.immediate) {
                    if (generation != playRequestGeneration.get()) return@withContext
                    if (startShuffled) player.shuffleModeEnabled = true
                    resolved?.let {
                        registerPreparedStream(it)
                        publishResolvedQuality(it)
                        logStreamEvent("player-prepare", it, retry = 0)
                    }
                    if (selectedTrack.playbackUrl != null) {
                        applicationScope.launch(Dispatchers.IO) { publishLocalTrackQuality(selectedTrack) }
                    }
                    val mediaItems = tracks.mapIndexed { index, track ->
                        track.toMediaItem(if (index == selectedIndex) resolved else null)
                    }
                    player.setMediaItems(mediaItems, selectedIndex, startPositionMs.coerceAtLeast(0L))
                    player.prepare()
                    player.play()
                    enrichUpcomingQueue(selectedIndex)
                    if (endlessDiscover) {
                        appendMissingDiscoverTracks(discoverRepository.getCachedFeed().map(GeneratedTrack::toPlayableTrack))
                    }
                    extendDiscoverQueueIfNeeded(selectedIndex)
                    extendRadioQueueIfNeeded(selectedIndex)
                    preloadNextQueueItem(selectedIndex)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                logResolutionFailure(selectedTrack, "resolve-before-prepare", 0, error)
                withContext(Dispatchers.Main.immediate) {
                    if (generation == playRequestGeneration.get()) {
                        unavailableMediaIds += selectedTrack.mediaIdKey()
                        val nextIndex = if (startShuffled) {
                            tracks.indices
                                .filter { tracks[it].mediaIdKey() !in unavailableMediaIds }
                                .randomOrNull()
                        } else {
                            val ordered = (selectedIndex + 1 until tracks.size) +
                                if (player.repeatMode == Player.REPEAT_MODE_ALL) {
                                    0 until selectedIndex
                                } else {
                                    emptyList()
                                }
                            ordered.firstOrNull { tracks[it].mediaIdKey() !in unavailableMediaIds }
                        }
                        if (nextIndex != null) {
                            playRequest = null
                            startResolvedQueuePlayback(
                                tracks = tracks,
                                selectedIndex = nextIndex,
                                startPositionMs = 0L,
                                sourceLabel = sourceLabel,
                                endlessDiscover = endlessDiscover,
                                startShuffled = startShuffled,
                            )
                        } else {
                            _state.update {
                                it.copy(
                                    isPlaying = false,
                                    isBuffering = false,
                                    error = error.message ?: "Unable to resolve audio",
                                )
                            }
                        }
                    }
                }
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
                preloadNextTrack(nextIndex, player.getMediaItemAt(nextIndex).toPlayableTrack())
            }
        }
    }

    fun resume() = onMain {
        ensureForegroundService()
        if (retryInterruptedPlayback()) return@onMain
        if (player.mediaItemCount == 0 && _state.value.current != null) {
            val q = _state.value.queue.ifEmpty { listOf(_state.value.current!!) }
            val idx = _state.value.currentIndex.coerceIn(q.indices)
            startResolvedQueuePlayback(
                tracks = q,
                selectedIndex = idx,
                startPositionMs = _state.value.positionMs,
                sourceLabel = _state.value.sourceLabel,
                endlessDiscover = _state.value.isEndlessQueue,
                startShuffled = _state.value.shuffleEnabled,
            )
            return@onMain
        }
        val currentItem = player.currentMediaItem
        val currentPrepared = currentItem
            ?.localConfiguration
            ?.customCacheKey
            ?.let(preparedStreams::get)
        if (currentItem?.localConfiguration?.uri?.scheme == "lastwave" || currentPrepared?.isExpired() == true) {
            resolveAndPlayQueueItem(player.currentMediaItemIndex)
            return@onMain
        }
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.prepare()
        }
        player.play()
    }

    fun pause() {
        cancelPendingPlaybackResolution()
        onMain {
            if (playerDelegate.isInitialized()) player.pause()
            _state.update { it.copy(isPlaying = false, isBuffering = false) }
        }
    }

    fun togglePlayPause() = onMain {
        if (playRequest?.isActive == true && _state.value.isBuffering) {
            pause()
            return@onMain
        }
        if (player.isPlaying) {
            player.pause()
        } else {
            ensureForegroundService()
            if (retryInterruptedPlayback()) return@onMain
            if (player.mediaItemCount == 0 && _state.value.current != null) {
                val q = _state.value.queue.ifEmpty { listOf(_state.value.current!!) }
                val idx = _state.value.currentIndex.coerceIn(q.indices)
                startResolvedQueuePlayback(
                    tracks = q,
                    selectedIndex = idx,
                    startPositionMs = _state.value.positionMs,
                    sourceLabel = _state.value.sourceLabel,
                    endlessDiscover = _state.value.isEndlessQueue,
                    startShuffled = _state.value.shuffleEnabled,
                )
                return@onMain
            }
            val currentItem = player.currentMediaItem
            val currentPrepared = currentItem
                ?.localConfiguration
                ?.customCacheKey
                ?.let(preparedStreams::get)
            if (currentItem?.localConfiguration?.uri?.scheme == "lastwave" || currentPrepared?.isExpired() == true) {
                resolveAndPlayQueueItem(player.currentMediaItemIndex)
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

    @MainThread
    private fun retryInterruptedPlayback(): Boolean {
        val snapshot = _state.value
        val track = snapshot.current ?: return false
        if (snapshot.error == null || track.playbackUrl != null) return false
        errorRetryCount = 0
        val queue = snapshot.queue.ifEmpty { listOf(track) }
        startResolvedQueuePlayback(
            tracks = queue,
            selectedIndex = snapshot.currentIndex.coerceIn(queue.indices),
            startPositionMs = snapshot.positionMs,
            sourceLabel = snapshot.sourceLabel,
            endlessDiscover = snapshot.isEndlessQueue,
            startShuffled = snapshot.shuffleEnabled,
        )
        return true
    }
    fun seekTo(positionMs: Long) = onMain {
        cancelCrossfade()
        val target = positionMs.coerceAtLeast(0)
        player.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    @MainThread
    private fun cancelCrossfade() {
        if (!playerDelegate.isInitialized()) return
        outgoingPlayer = null
        val standby = if (player === secondaryPlayer) playerDelegate.value else secondaryPlayer
        standby?.stop()
        standby?.clearMediaItems()
        standbyQueue = emptyList()
        standbyIndex = C.INDEX_UNSET
        player.volume = 1f
    }

    @MainThread
    private fun updateCrossfade(positionMs: Long, durationMs: Long): Boolean {
        if (!crossfadeEnabled || bitPerfectEnabled) return false
        outgoingPlayer?.let { outgoing ->
            val progress = (positionMs.toFloat() / overlapDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            if (progress >= 1f || outgoing.playbackState == Player.STATE_ENDED || outgoing.playerError != null) {
                cancelCrossfade()
            } else {
                val angle = progress * (Math.PI / 2.0)
                player.volume = kotlin.math.sin(angle).toFloat()
                outgoing.volume = kotlin.math.cos(angle).toFloat()
                outgoing.playWhenReady = player.isPlaying
            }
            return false
        }
        if (!player.isPlaying || durationMs <= 0L || player.repeatMode == Player.REPEAT_MODE_ONE) return false
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex == player.currentMediaItemIndex) return false
        val fadeMs = minOf((crossfadeDurationMs * player.playbackParameters.speed).toLong(), durationMs / 3)
        val remainingMs = durationMs - positionMs
        if (remainingMs <= 0L || remainingMs > fadeMs + 15_000L) return false
        val nextItem = player.getMediaItemAt(nextIndex)
        if (nextItem.localConfiguration?.uri?.scheme == "lastwave") return false
        val stream = nextItem.localConfiguration?.customCacheKey?.let(preparedStreams::get)
        if (stream?.isExpired() == true) return false

        val standby = if (player === secondaryPlayer) playerDelegate.value else {
            secondaryPlayer ?: run {
                val engine = NativeAudioEngine(settingsPreferences, equalizerPreferences, applicationScope)
                val effects = AudioEffectsEngine(equalizerPreferences, settingsPreferences, applicationScope)
                secondaryNativeEngine = engine
                secondaryEffects = effects
                createPlayer({ engine }, effects, false).also { secondaryPlayer = it }
            }
        }
        if (standbyIndex != nextIndex || standbyQueue.size != player.mediaItemCount ||
            standbyQueue.getOrNull(nextIndex) != nextItem
        ) {
            standbyQueue = (0 until player.mediaItemCount).map(player::getMediaItemAt)
            standbyIndex = nextIndex
            standby.volume = 0f
            standby.setAudioAttributes(player.audioAttributes, false)
            standby.pause()
            standby.setMediaItems(standbyQueue, nextIndex, 0L)
            standby.prepare()
        }
        if (remainingMs > fadeMs || standby.playbackState != Player.STATE_READY) return false
        // A queue edit during preparation must never start a stale next track.
        if (standbyQueue.indices.any { standbyQueue[it] != player.getMediaItemAt(it) }) {
            cancelCrossfade()
            return false
        }
        val outgoing = player
        val shuffleOrder = mutableListOf<Int>()
        val timeline = outgoing.currentTimeline
        var index = timeline.getFirstWindowIndex(outgoing.shuffleModeEnabled)
        while (index != C.INDEX_UNSET) {
            shuffleOrder.add(index)
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, outgoing.shuffleModeEnabled)
        }
        standby.setShuffleOrder(DefaultShuffleOrder(shuffleOrder.toIntArray(), Random.nextLong()))
        standby.shuffleModeEnabled = outgoing.shuffleModeEnabled
        standby.repeatMode = outgoing.repeatMode
        standby.playbackParameters = outgoing.playbackParameters
        overlapDurationMs = minOf(fadeMs, remainingMs,
            standby.duration.takeIf { it > 0L }?.div(3) ?: fadeMs).coerceAtLeast(1L)
        outgoing.removeListener(listener)
        outgoing.setAudioAttributes(outgoing.audioAttributes, false)
        outgoing.repeatMode = Player.REPEAT_MODE_OFF
        outgoing.shuffleModeEnabled = false
        outgoing.removeMediaItems(outgoing.currentMediaItemIndex + 1, outgoing.mediaItemCount)
        outgoingPlayer = outgoing
        activePlayer = standby
        standby.addListener(listener)
        standby.setAudioAttributes(standby.audioAttributes, true)
        standby.play()
        listener.onMediaItemTransition(standby.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        refresh(standby)
        return true
    }

    private fun updateBitPerfectState() {
        // Bit-Perfect applies to every stream (Lossless, YouTube Music, local downloads)
        // Completely bypassing native DSP and Android AudioFX processing.
        val effectiveBitPerfect = bitPerfectEnabled
        runCatching { nativeAudioEngine.get().setBitPerfect(effectiveBitPerfect) }
        audioEffectsEngine.setBitPerfectActive(effectiveBitPerfect)
        secondaryNativeEngine?.setBitPerfect(effectiveBitPerfect)
        secondaryEffects?.setBitPerfectActive(effectiveBitPerfect)
    }

    fun seekToQueueItem(index: Int) = onMain {
        if (index in 0 until player.mediaItemCount) {
            resolveAndPlayQueueItem(index)
        } else {
            playPendingQueueItem(index, _state.value)
        }
    }
    fun previous() = onMain {
        cancelCrossfade()
        val pendingState = _state.value
        if (player.currentPosition > 5_000) {
            player.seekTo(0)
        } else {
            val index = player.previousMediaItemIndex.takeIf { it != C.INDEX_UNSET }
                ?: previousQueueIndex(pendingState)
            index.takeIf { it != C.INDEX_UNSET }?.let {
                if (it in 0 until player.mediaItemCount) resolveAndPlayQueueItem(it)
                else playPendingQueueItem(it, pendingState)
            }
        }
    }
    fun next() = onMain {
        val pendingState = _state.value
        val index = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }
            ?: nextQueueIndex(pendingState)
        index.takeIf { it != C.INDEX_UNSET }?.let {
            if (it in 0 until player.mediaItemCount) resolveAndPlayQueueItem(it)
            else playPendingQueueItem(it, pendingState)
        }
    }

    private fun nextQueueIndex(state: MusicPlayerState): Int {
        val queue = state.queue
        if (queue.isEmpty()) return C.INDEX_UNSET
        val start = (state.currentIndex + 1).coerceAtLeast(0)
        val ordered = if (state.shuffleEnabled) queue.indices.shuffled() else {
            (start until queue.size) + if (state.repeatMode == Player.REPEAT_MODE_ALL) (0 until start) else emptyList()
        }
        return ordered.firstOrNull { it != state.currentIndex && queue[it].mediaIdKey() !in unavailableMediaIds }
            ?: C.INDEX_UNSET
    }

    private fun previousQueueIndex(state: MusicPlayerState): Int {
        val queue = state.queue
        if (queue.isEmpty()) return C.INDEX_UNSET
        val start = state.currentIndex - 1
        val ordered = (start downTo 0) + if (state.repeatMode == Player.REPEAT_MODE_ALL) (queue.lastIndex downTo 0) else emptyList()
        return ordered.firstOrNull { queue[it].mediaIdKey() !in unavailableMediaIds }
            ?: C.INDEX_UNSET
    }

    @MainThread
    private fun playPendingQueueItem(index: Int, pendingState: MusicPlayerState) {
        if (index !in pendingState.queue.indices) return
        if (index in 0 until player.mediaItemCount) {
            resolveAndPlayQueueItem(index)
        } else {
            startResolvedQueuePlayback(
                tracks = pendingState.queue,
                selectedIndex = index,
                startPositionMs = 0L,
                sourceLabel = pendingState.sourceLabel,
                endlessDiscover = pendingState.isEndlessQueue,
                startShuffled = pendingState.shuffleEnabled,
            )
        }
    }

    @MainThread
    private fun resolveAndPlayQueueItem(index: Int) {
        if (index !in 0 until player.mediaItemCount) {
            playPendingQueueItem(index, _state.value)
            return
        }
        cancelCrossfade()
        ensureForegroundService()
        val generation = playRequestGeneration.incrementAndGet()
        playRequest?.cancel()
        preloadJob?.cancel()
        unavailableSkipJob?.cancel()
        unavailableSkipJob = null
        val mediaItem = player.getMediaItemAt(index)
        val prepared = mediaItem.localConfiguration?.customCacheKey?.let(preparedStreams::get)
        if (mediaItem.localConfiguration?.uri?.scheme != "lastwave" && prepared?.isExpired() != true) {
            player.seekToDefaultPosition(index)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
            preloadNextQueueItem(index)
            return
        }

        val track = mediaItem.toPlayableTrack()
        val expectedMediaId = mediaItem.mediaId
        resolvingMediaIds[expectedMediaId] = generation
        player.pause()
        _state.update {
            it.copy(
                current = track,
                currentIndex = index,
                isPlaying = true,
                isBuffering = true,
                error = null,
            )
        }
        playRequest = applicationScope.launch(Dispatchers.IO) {
            try {
                val resolved = resolveTrackAudioStreamWithRetry(
                    track = track,
                    videoId = track.videoId,
                    allowLossless = expectedMediaId !in losslessBypassMediaIds,
                )
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.Main.immediate) {
                    if (generation != playRequestGeneration.get() ||
                        index !in 0 until player.mediaItemCount ||
                        player.getMediaItemAt(index).mediaId != expectedMediaId
                    ) {
                        return@withContext
                    }
                    registerPreparedStream(resolved)
                    publishResolvedQuality(resolved)
                    logStreamEvent("queue-prepare", resolved, retry = 0)
                    player.replaceMediaItem(index, track.toMediaItem(resolved))
                    player.seekToDefaultPosition(index)
                    player.prepare()
                    player.play()
                    enrichUpcomingQueue(index)
                    extendDiscoverQueueIfNeeded(index)
                    preloadNextQueueItem(index)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                logResolutionFailure(track, "queue-resolve", 0, error)
                withContext(Dispatchers.Main.immediate) {
                    if (generation == playRequestGeneration.get()) {
                        _state.update {
                            it.copy(
                                isPlaying = false,
                                isBuffering = false,
                                error = error.message ?: "Unable to resolve audio",
                            )
                        }
                        scheduleUnavailableMediaSkip(index, expectedMediaId, generation, error, allowAutoSkip = true)
                    }
                }
            } finally {
                resolvingMediaIds.remove(expectedMediaId, generation)
            }
        }
    }

    private fun cancelPendingPlaybackResolution() {
        playRequestGeneration.incrementAndGet()
        playRequest?.cancel()
        playRequest = null
        preloadJob?.cancel()
        preloadJob = null
        unavailableSkipJob?.cancel()
        unavailableSkipJob = null
    }

    fun toggleShuffle() = setShuffleEnabled(!state.value.shuffleEnabled)

    fun setShuffleEnabled(enabled: Boolean) = onMain {
        if (player.shuffleModeEnabled == enabled) return@onMain
        cancelCrossfade()
        player.shuffleModeEnabled = enabled
        preloadNextQueueItem(player.currentMediaItemIndex)
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
        cancelCrossfade()
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
        cancelCrossfade()
        disableDiscoverQueue()
        disableRadioQueue()
        val current = player.currentMediaItemIndex
        if (current >= 0 && current + 1 < player.mediaItemCount) {
            player.removeMediaItems(current + 1, player.mediaItemCount)
        }
    }
    fun stopAndClear() = onMain {
        cancelCrossfade()
        resolutionRequests.values.forEach { it.second.cancel() }
        resolutionRequests.clear()
        cancelPendingPlaybackResolution()
        queueEnrichmentJob?.cancel()
        disableDiscoverQueue()
        disableRadioQueue()
        unavailableSkipJob?.cancel()
        sleepTimerDeadlineMs = null
        sleepTimerStep = 0
        player.stop()
        player.clearMediaItems()
        preparedStreams.clear()
        _state.value = MusicPlayerState()
        clearPersistedPlaybackSession()
        applicationScope.launch(Dispatchers.IO) { WidgetUpdater.clear(appContext) }
        appContext.stopService(Intent(appContext, MusicPlaybackService::class.java))
    }
    fun removeQueueItem(index: Int) = onMain {
        cancelCrossfade()
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
        track.videoId?.takeIf(String::isNotBlank)?.let { videoId ->
            return track.copy(
                artworkUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            )
        }
        val match = innerTube.findBestMatch(track.title, track.artist, prefetchStreams = false)
        return track.copy(
            title = track.title.ifBlank { match.title },
            artist = track.artist.ifBlank { match.artist },
            album = track.album?.takeIf(String::isNotBlank) ?: match.album,
            artworkUrl = match.artworkUrl?.takeIf(String::isNotBlank)
                ?: track.artworkUrl?.takeIf(String::isNotBlank),
            videoId = track.videoId ?: match.videoId,
        )
    }

    private fun warmArtwork(track: PlayableTrack) {
        val url = track.withYoutubeArtwork().artworkUrl?.takeIf(String::isNotBlank) ?: return
        appContext.imageLoader.enqueue(ImageRequest.Builder(appContext).data(url).size(512).build())
    }

    private fun enrichUpcomingQueue(currentIndex: Int) {
        queueEnrichmentJob?.cancel()
        queueEnrichmentJob = applicationScope.launch {
            val targetIndices = withContext(Dispatchers.Main.immediate) {
                val list = mutableListOf<Int>()
                for (i in (currentIndex + 1) until minOf(currentIndex + 3, player.mediaItemCount)) {
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
            coroutineScope {
                pending.forEach { item ->
                    launch(Dispatchers.IO) {
                        val enriched = try {
                            matchMetadata(item.original)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            return@launch
                        }
                        val expectedMediaId = item.expectedMediaId
                        val index = item.index
                        withContext(Dispatchers.Main.immediate) {
                            val queuedItem = if (index in 0 until player.mediaItemCount) player.getMediaItemAt(index) else null
                            if (index != player.currentMediaItemIndex && queuedItem?.mediaId == expectedMediaId) {
                                val prepared = queuedItem.localConfiguration
                                    ?.customCacheKey
                                    ?.let(preparedStreams::get)
                                    ?.takeUnless { it.isExpired() }
                                    ?.takeIf { stream ->
                                        val streamVideoId = stream.youtubeCandidate?.videoId
                                        streamVideoId == null || enriched.videoId == null || streamVideoId == enriched.videoId
                                    }
                                player.replaceMediaItem(index, enriched.toMediaItem(prepared))
                            }
                        }
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
    @MainThread
    private fun preloadNextQueueItem(currentIndex: Int) {
        val nextIndex = if (player.shuffleModeEnabled) player.nextMediaItemIndex else currentIndex + 1
        if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until player.mediaItemCount) return
        val nextItem = player.getMediaItemAt(nextIndex)
        if (nextItem.localConfiguration?.uri?.scheme != "lastwave") return
        preloadNextTrack(nextIndex, nextItem.toPlayableTrack())
    }

    private fun preloadNextTrack(nextIndex: Int, nextTrack: PlayableTrack?) {
        if (nextTrack == null) return
        warmArtwork(nextTrack)
        val expectedQueueKey = nextTrack.queueKey()
        preloadJob?.cancel()
        preloadJob = applicationScope.launch(Dispatchers.IO) {
            delay(NEXT_TRACK_PREFETCH_DELAY_MS)
            if (!_state.value.isPlaying) return@launch
            val resolved = runCatching {
                resolveTrackAudioStream(nextTrack, nextTrack.videoId)
            }.onFailure { logResolutionFailure(nextTrack, "next-preload", 0, it) }
                .getOrNull() ?: return@launch

            val installed = withContext(Dispatchers.Main.immediate) {
                val queuedTrack = (if (nextIndex in 0 until player.mediaItemCount) {
                    player.getMediaItemAt(nextIndex).toPlayableTrack()
                } else null) ?: return@withContext false
                if (queuedTrack.queueKey() != expectedQueueKey || nextIndex == player.currentMediaItemIndex) {
                    return@withContext false
                }
                val resolvedVideoId = resolved.youtubeCandidate?.videoId
                if (resolvedVideoId != null && queuedTrack.videoId != null &&
                    resolvedVideoId != queuedTrack.videoId
                ) {
                    return@withContext false
                }
                registerPreparedStream(resolved)
                player.replaceMediaItem(nextIndex, queuedTrack.toMediaItem(resolved))
                logStreamEvent("next-prepared", resolved, retry = 0)
                true
            }
            if (!installed) return@launch

            val dataSpec = DataSpec.Builder()
                .setUri(Uri.parse(resolved.url))
                .setPosition(0)
                .setLength(NEXT_TRACK_PREFETCH_BYTES)
                .setKey(resolved.cacheKey)
                .build()
                .withRequestHeaders(resolved.requestHeaders)

            runCatching {
                val cacheWriter = CacheWriter(
                    cacheDataSourceFactory.createDataSource(),
                    dataSpec,
                    null,
                    null,
                )
                val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                    if (cause is CancellationException) cacheWriter.cancel()
                }
                try {
                    cacheWriter.cache()
                } finally {
                    cancellationHandle?.dispose()
                }
            }.onFailure { logResolutionFailure(nextTrack, "next-cache", 0, it) }
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

    private fun disableRadioQueue() {
        radioQueueActive = false
        radioQueueLoadJob?.cancel()
        radioQueueLoadJob = null
        radioUsedSeeds.clear()
    }

    private fun isDisallowedRadioTitle(titleLower: String): Boolean {
        val keywords = listOf(
            "mashup", "mash up", "mash-up",
            "jukebox", "juke box",
            "mega mix", "megamix",
            "non stop", "nonstop", "non-stop",
            "all songs", "top songs", "audio jukebox",
            "full album", "full songs", "compilation",
            "slowed + reverb", "slowed and reverb", "slowed reverb",
            "bass boosted", "8d audio",
        )
        return keywords.any { titleLower.contains(it) }
    }

    private fun isSameCoreSong(titleLower: String, seedTitleLower: String): Boolean {
        if (titleLower == seedTitleLower) return true
        val cleanTitle = titleLower.replace(Regex("[^a-zA-Z0-9 ]"), "").trim()
        val cleanSeed = seedTitleLower.replace(Regex("[^a-zA-Z0-9 ]"), "").trim()
        if (cleanTitle == cleanSeed) return true
        if (cleanTitle.startsWith(cleanSeed) &&
            (cleanTitle.contains("remix") || cleanTitle.contains("lofi") ||
                cleanTitle.contains("version") || cleanTitle.contains("cover") ||
                cleanTitle.contains("reprise") || cleanTitle.contains("acoustic"))
        ) {
            return true
        }
        return false
    }

    private fun startRadioQueue(seed: PlayableTrack) {
        radioQueueLoadJob?.cancel()
        radioUsedSeeds.clear()
        seed.videoId?.takeIf(String::isNotBlank)?.let { radioUsedSeeds.add(it) }

        radioQueueLoadJob = applicationScope.launch(Dispatchers.IO) {
            try {
                val seedVideoId = seed.videoId?.takeIf(String::isNotBlank)
                    ?: innerTube.findBestMatchOrNull(seed.title, seed.artist, prefetchStreams = false)?.videoId
                    ?: return@launch

                radioUsedSeeds.add(seedVideoId)
                val related = innerTube.fetchRelatedSongs(seedVideoId, limit = RADIO_QUEUE_BATCH_SIZE, prefetchStreams = false)
                if (related.isEmpty() || !radioQueueActive) return@launch

                val seedTitleLower = seed.title.trim().lowercase()
                val seedArtistLower = seed.artist.trim().lowercase()

                val fresh = related.mapNotNull { yt ->
                    val title = yt.title.trim()
                    val titleLower = title.lowercase()
                    val artist = yt.artist.trim()
                    val artistLower = artist.lowercase()

                    val isSameTrack = yt.videoId == seedVideoId ||
                        (titleLower == seedTitleLower && artistLower == seedArtistLower) ||
                        isSameCoreSong(titleLower, seedTitleLower)
                    val isJunk = isDisallowedRadioTitle(titleLower)

                    if (isSameTrack || isJunk || title.isBlank() || artist.isBlank()) {
                        null
                    } else {
                        PlayableTrack(
                            title = title,
                            artist = artist,
                            album = yt.album,
                            artworkUrl = yt.artworkUrl,
                            videoId = yt.videoId,
                        )
                    }
                }

                if (fresh.isEmpty() || !radioQueueActive) return@launch

                withContext(Dispatchers.Main.immediate) {
                    if (!radioQueueActive || !playerDelegate.isInitialized()) return@withContext
                    val current = player.currentMediaItem?.toPlayableTrack()
                    val stillCurrentSeed = current?.videoId == seedVideoId ||
                        (current?.title.equals(seed.title, ignoreCase = true) && current?.artist.equals(seed.artist, ignoreCase = true))
                    if (!stillCurrentSeed) return@withContext

                    val existingVideoIds = (0 until player.mediaItemCount).mapNotNullTo(mutableSetOf()) {
                        player.getMediaItemAt(it).toPlayableTrack().videoId
                    }
                    val existingKeys = (0 until player.mediaItemCount).mapTo(mutableSetOf()) {
                        player.getMediaItemAt(it).toPlayableTrack().queueKey()
                    }

                    val toAdd = fresh.filter {
                        (it.videoId == null || it.videoId !in existingVideoIds) && it.queueKey() !in existingKeys
                    }

                    if (toAdd.isNotEmpty()) {
                        player.addMediaItems(toAdd.map(PlayableTrack::toMediaItem))
                        refresh(player)
                        _state.update { it.copy(isEndlessQueue = true) }
                        enrichUpcomingQueue(player.currentMediaItemIndex)
                        val nextIndex = player.currentMediaItemIndex + 1
                        if (nextIndex in 0 until player.mediaItemCount) {
                            preloadNextTrack(nextIndex, player.getMediaItemAt(nextIndex).toPlayableTrack())
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                android.util.Log.d("MusicPlayer", "Failed to start radio queue", e)
            }
        }
    }

    private fun extendRadioQueueIfNeeded(currentIndex: Int) {
        if (!radioQueueActive || radioQueueLoadJob?.isActive == true) return
        val currentCount = player.mediaItemCount
        if (currentIndex < 0 || currentCount - currentIndex - 1 > RADIO_QUEUE_REFILL_THRESHOLD) return

        radioQueueLoadJob = applicationScope.launch(Dispatchers.IO) {
            try {
                val currentQueue = withContext(Dispatchers.Main.immediate) {
                    if (!playerDelegate.isInitialized()) emptyList()
                    else (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toPlayableTrack() }
                }
                if (currentQueue.isEmpty() || !radioQueueActive) return@launch

                val nextSeed = currentQueue
                    .drop(currentIndex.coerceAtLeast(0))
                    .firstOrNull { it.videoId != null && it.videoId !in radioUsedSeeds }
                    ?: currentQueue.firstOrNull { it.videoId != null && it.videoId !in radioUsedSeeds }

                val seedVideoId = nextSeed?.videoId
                    ?: currentQueue.getOrNull(currentIndex)?.let { track ->
                        innerTube.findBestMatchOrNull(track.title, track.artist, prefetchStreams = false)?.videoId
                    }
                    ?: return@launch

                radioUsedSeeds.add(seedVideoId)
                val related = innerTube.fetchRelatedSongs(seedVideoId, limit = RADIO_QUEUE_BATCH_SIZE, prefetchStreams = false)
                if (related.isEmpty() || !radioQueueActive) return@launch

                val knownVideoIds = currentQueue.mapNotNullTo(mutableSetOf()) { it.videoId }
                val knownTitleArtists = currentQueue.mapTo(mutableSetOf()) {
                    "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}"
                }
                val currentSeedTitle = nextSeed?.title?.trim()?.lowercase().orEmpty()

                val fresh = related.mapNotNull { yt ->
                    val title = yt.title.trim()
                    val titleLower = title.lowercase()
                    val artist = yt.artist.trim()
                    val artistLower = artist.lowercase()

                    val isSameTrack = yt.videoId in knownVideoIds ||
                        "$titleLower|$artistLower" in knownTitleArtists ||
                        (currentSeedTitle.isNotBlank() && isSameCoreSong(titleLower, currentSeedTitle))
                    val isJunk = isDisallowedRadioTitle(titleLower)

                    if (isSameTrack || isJunk || title.isBlank() || artist.isBlank()) {
                        null
                    } else {
                        PlayableTrack(
                            title = title,
                            artist = artist,
                            album = yt.album,
                            artworkUrl = yt.artworkUrl,
                            videoId = yt.videoId,
                        )
                    }
                }

                if (fresh.isEmpty() || !radioQueueActive) return@launch

                withContext(Dispatchers.Main.immediate) {
                    if (!radioQueueActive || !playerDelegate.isInitialized()) return@withContext
                    val existingKeys = (0 until player.mediaItemCount).mapTo(mutableSetOf()) {
                        player.getMediaItemAt(it).toPlayableTrack().queueKey()
                    }
                    val toAdd = fresh.filter { it.queueKey() !in existingKeys }
                    if (toAdd.isNotEmpty()) {
                        player.addMediaItems(toAdd.map(PlayableTrack::toMediaItem))
                        refresh(player)
                        _state.update { it.copy(isEndlessQueue = true) }
                        enrichUpcomingQueue(currentIndex)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                android.util.Log.d("MusicPlayer", "Failed to extend radio queue", e)
            }
        }
    }

    @MainThread
    private fun scheduleUnavailableMediaSkip(
        failedIndex: Int,
        failedMediaId: String?,
        expectedGeneration: Long = playRequestGeneration.get(),
        failure: Throwable,
        allowAutoSkip: Boolean = true,
    ) {
        if (expectedGeneration != playRequestGeneration.get()) return
        if (!allowAutoSkip) {
            player.pause()
            _state.update { it.copy(isPlaying = false, isBuffering = false, error = "Playback interrupted. Tap play to retry.") }
            return
        }
        if (failedIndex == C.INDEX_UNSET || failedMediaId == null) return
        unavailableSkipJob?.cancel()
        unavailableSkipJob = applicationScope.launch(Dispatchers.Main.immediate) {
            yield()
            val failedItemStillQueued = failedIndex in 0 until player.mediaItemCount &&
                player.getMediaItemAt(failedIndex).mediaId == failedMediaId
            if (expectedGeneration != playRequestGeneration.get() ||
                !failedItemStillQueued || player.currentMediaItemIndex != failedIndex || player.isPlaying
            ) {
                unavailableSkipJob = null
                return@launch
            }

            // A resolver or Media3 load has completed with a failure; advance
            // only while the same failed item is still selected.
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
            resolveAndPlayQueueItem(nextIndex)
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
        val cacheKey: String,
        val requestHeaders: Map<String, String> = emptyMap(),
        val isLossless: Boolean = false,
        val bitDepth: Int? = null,
        val samplingRateKHz: Double? = null,
        val youtubeCandidate: YouTubeAudioStream? = null,
    )

    private suspend fun resolveLocalDownloadedAudioStream(track: PlayableTrack): ResolvedStream? {
        val title = track.title.trim()
        val artist = track.artist.trim()
        if (title.isBlank() || artist.isBlank()) return null

        val trackKey = "${artist.lowercase()}_${title.lowercase()}"
        val downloaded = runCatching {
            val dao = downloadedTrackDao.get()
            dao.findByTrackKey(trackKey) ?: dao.findByTitleAndArtist(title, artist)
        }.getOrNull() ?: return null

        val uriString = downloaded.mediaStoreUri?.takeIf(String::isNotBlank)
            ?: downloaded.filePath.takeIf(String::isNotBlank)
            ?: return null

        val isAccessible = when {
            uriString.startsWith("content://") -> runCatching {
                appContext.contentResolver.openInputStream(Uri.parse(uriString))?.use { }
                true
            }.getOrDefault(false)
            else -> runCatching {
                val file = if (uriString.startsWith("file://")) {
                    File(Uri.parse(uriString).path.orEmpty())
                } else {
                    File(uriString)
                }
                file.exists() && file.length() > 0
            }.getOrDefault(false)
        }

        if (!isAccessible) {
            // Stale database record; file was deleted externally outside the app
            runCatching { downloadedTrackDao.get().delete(downloaded) }
            return null
        }

        val playbackUri = if (uriString.startsWith("/") && !uriString.startsWith("file://")) {
            Uri.fromFile(File(uriString)).toString()
        } else {
            uriString
        }

        val mime = when {
            downloaded.filePath.endsWith(".flac", ignoreCase = true) || downloaded.formatBadge.contains("FLAC") -> "audio/flac"
            downloaded.filePath.endsWith(".m4a", ignoreCase = true) || downloaded.filePath.endsWith(".mp4", ignoreCase = true) || downloaded.formatBadge.contains("M4A") -> "audio/mp4"
            downloaded.filePath.endsWith(".opus", ignoreCase = true) || downloaded.formatBadge.contains("OPUS") -> "audio/ogg"
            downloaded.filePath.endsWith(".mp3", ignoreCase = true) || downloaded.formatBadge.contains("MP3") -> "audio/mpeg"
            else -> "audio/flac"
        }

        var bitDepth: Int? = null
        var samplingRateKHz: Double? = null
        var bitrateKbps: Int? = downloaded.bitrateKbps

        runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                if (playbackUri.startsWith("content://")) {
                    retriever.setDataSource(appContext, Uri.parse(playbackUri))
                } else {
                    retriever.setDataSource(playbackUri.removePrefix("file://"))
                }
                if (bitrateKbps == null || bitrateKbps == 0) {
                    bitrateKbps = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.let { it / 1000 }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    samplingRateKHz = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toDoubleOrNull()?.let { it / 1000.0 }
                    bitDepth = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()
                }
            } finally {
                retriever.release()
            }
        }

        return ResolvedStream(
            url = playbackUri,
            mimeType = mime,
            bitrateKbps = bitrateKbps,
            audioCodec = downloaded.formatBadge,
            cacheKey = "local:$trackKey",
            isLossless = downloaded.isLossless,
            bitDepth = bitDepth,
            samplingRateKHz = samplingRateKHz,
        )
    }

    private suspend fun resolveTrackAudioStream(
        track: PlayableTrack,
        videoId: String?,
        allowLossless: Boolean = true,
    ): ResolvedStream = withContext(Dispatchers.IO) {
        // Prioritize locally downloaded file if already saved to storage — enables
        // seamless offline playback across all screens and saves mobile data (Issue #31).
        resolveLocalDownloadedAudioStream(track)?.let { localStream ->
            return@withContext localStream
        }

        val misc = runCatching { settingsPreferences.settings.first() }.getOrDefault(MiscSettings())
        val key = listOf(track.title, track.artist, track.album, videoId, allowLossless, misc.losslessQuality)
        val now = SystemClock.elapsedRealtime()
        resolutionRequests.entries.removeIf { now - it.value.first > 60_000L }
        if (resolutionRequests.size >= 64) {
            resolutionRequests.entries.removeIf { it.value.second.isCompleted }
        }
        val request = resolutionRequests.computeIfAbsent(key) {
            now to applicationScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                resolveRemoteTrackAudioStream(track, videoId, allowLossless, misc)
            }
        }
        try {
            request.second.await().also {
                if (it.isExpired()) throw java.io.IOException("Prepared stream expired")
            }
        } catch (error: Exception) {
            if (error !is CancellationException || request.second.isCancelled) {
                resolutionRequests.remove(key, request)
            }
            throw error
        }
    }

    private suspend fun resolveRemoteTrackAudioStream(
        track: PlayableTrack,
        videoId: String?,
        allowLossless: Boolean,
        misc: MiscSettings,
    ): ResolvedStream {
        if (!allowLossless || (!videoId.isNullOrBlank() &&
                (track.artist.isBlank() || track.artist.equals("Unknown artist", ignoreCase = true)))
        ) return resolveYoutubeTrackAudioStream(track, videoId)

        // Resolve both sources together, but always await lossless first. If it
        // fails, the YouTube result is already being prepared.
        val youtubeDeferred = applicationScope.async(Dispatchers.IO) {
            resolveYoutubeTrackAudioStream(track, videoId)
        }
        return try {
            resolveLosslessTrackAudioStream(track, misc) ?: youtubeDeferred.await()
        } finally {
            youtubeDeferred.cancel()
        }
    }

    private suspend fun resolveLosslessTrackAudioStream(
        track: PlayableTrack,
        misc: MiscSettings,
    ): ResolvedStream? {
        val losslessStream = try {
            losslessMusicApi.resolveStream(
                title = track.title,
                artist = track.artist,
                expectedAlbum = track.album,
                preferredQuality = misc.losslessQuality,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return null
        }
        val codec = when {
            losslessStream.bitDepth > 16 || losslessStream.samplingRate > 48.0 -> "HI-RES FLAC"
            losslessStream.formatId == LosslessMusicApi.QUALITY_CD_LOSSLESS -> "LOSSLESS"
            losslessStream.formatId == LosslessMusicApi.QUALITY_MP3_320 -> "MP3 320k"
            else -> "LOSSLESS"
        }
        return ResolvedStream(
            url = losslessStream.url,
            mimeType = losslessStream.mimeType,
            bitrateKbps = losslessStream.bitrateKbps,
            audioCodec = codec,
            cacheKey = "lossless:${track.mediaIdKey()}:${losslessStream.formatId}",
            isLossless = true,
            bitDepth = losslessStream.bitDepth.takeIf { it > 0 },
            samplingRateKHz = losslessStream.samplingRate.takeIf { it > 0 },
        )
    }

    private suspend fun resolveYoutubeTrackAudioStream(
        track: PlayableTrack,
        videoId: String?,
    ): ResolvedStream {
        val targetVideoId = videoId ?: run {
            val match = innerTube.findBestMatch(track.title, track.artist, prefetchStreams = false)
            match.videoId.takeIf(String::isNotBlank)
                ?: throw java.io.IOException("No playable match found")
        }
        val ytStream = innerTube.resolveAudioStream(targetVideoId)
        val trueBitrate = ytStream.bitrate.takeIf { it > 0 }?.let { (it + 500) / 1_000 }
        val rawCodec = ytStream.codec?.substringBefore(',')?.trim()?.uppercase()?.ifBlank {
            ytStream.mimeType?.substringAfter("audio/")?.substringBefore(';')?.uppercase()?.ifBlank { "WEBM" } ?: "WEBM"
        } ?: "WEBM"
        val codec = when {
            rawCodec.contains("OPUS") || rawCodec == "WEBM" -> "OPUS"
            rawCodec.contains("M4A") || rawCodec.contains("MP4") || rawCodec.contains("MP4A") || rawCodec.contains("AAC") -> "AAC"
            else -> rawCodec
        }
        return ResolvedStream(
            url = ytStream.url,
            mimeType = ytStream.mimeType.orEmpty(),
            bitrateKbps = trueBitrate,
            audioCodec = codec,
            cacheKey = ytStream.mediaCacheKey,
            requestHeaders = ytStream.requestHeaders,
            isLossless = false,
            samplingRateKHz = ytStream.sampleRateHz?.let { it / 1_000.0 },
            youtubeCandidate = ytStream,
        )
    }

    private fun publishResolvedQuality(resolved: ResolvedStream) {
        _state.update {
            it.copy(
                bitrateKbps = resolved.bitrateKbps,
                audioCodec = resolved.audioCodec,
                isLossless = resolved.isLossless,
                bitDepth = resolved.bitDepth,
                samplingRateKHz = resolved.samplingRateKHz,
            )
        }
        updateBitPerfectState()
    }

    private suspend fun resolveTrackAudioStreamWithRetry(
        track: PlayableTrack,
        videoId: String?,
        allowLossless: Boolean,
    ): ResolvedStream {
        var lastFailure: Throwable? = null
        repeat(2) { attempt ->
            try {
                return resolveTrackAudioStream(track, videoId, allowLossless)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                lastFailure = error
                if (attempt == 0 && error is java.io.IOException) {
                    delay(PLAYBACK_RETRY_BASE_DELAY_MS + Random.nextLong(PLAYBACK_RETRY_JITTER_MS + 1L))
                } else {
                    throw error
                }
            }
        }
        throw lastFailure ?: java.io.IOException("Unable to resolve audio")
    }

    private fun registerPreparedStream(stream: ResolvedStream) {
        preparedStreams.entries.removeIf { it.value.isExpired() }
        preparedStreams[stream.cacheKey] = stream
        if (preparedStreams.size <= MAX_PREPARED_STREAMS) return
        val activeKeys = if (playerDelegate.isInitialized()) {
            (0 until player.mediaItemCount).mapNotNullTo(mutableSetOf()) {
                player.getMediaItemAt(it).localConfiguration?.customCacheKey
            }
        } else {
            emptySet()
        }
        preparedStreams.keys
            .asSequence()
            .filterNot(activeKeys::contains)
            .take(preparedStreams.size - MAX_PREPARED_STREAMS)
            .forEach(preparedStreams::remove)
    }

    private fun logStreamEvent(
        stage: String,
        stream: ResolvedStream,
        retry: Int,
        httpStatus: Int? = null,
        error: Throwable? = null,
    ) {
        val candidate = stream.youtubeCandidate
        val expiry = when {
            candidate?.expiresAtEpochMs == null -> "unknown"
            candidate.expiresAtEpochMs <= System.currentTimeMillis() -> "expired"
            else -> "fresh"
        }
        PlaybackDiagnostics.event(
            "Stream",
            "stage=$stage videoId=${candidate?.videoId.orEmpty()} " +
                "client=${candidate?.clientProfile ?: if (stream.isLossless) "LOSSLESS" else "unknown"} " +
                "itag=${candidate?.itag ?: -1} mime=${stream.mimeType} expiry=$expiry " +
                "retry=$retry http=${httpStatus ?: 0} error=${error?.javaClass?.simpleName.orEmpty()}",
        )
    }

    private fun logResolutionFailure(
        track: PlayableTrack,
        stage: String,
        retry: Int,
        error: Throwable,
    ) {
        PlaybackDiagnostics.event(
            "Stream",
            "stage=$stage videoId=${track.videoId.orEmpty()} client=unresolved itag=-1 " +
                "mime=unknown expiry=unknown retry=$retry http=${error.httpStatusCodeOrNull() ?: 0} " +
                "error=${error.javaClass.simpleName}",
        )
        android.util.Log.e(
            "MusicPlayer",
            "Playback stream failure at $stage (${error.javaClass.simpleName})",
        )
    }

    private fun ResolvedStream.isExpired(now: Long = System.currentTimeMillis()): Boolean =
        youtubeCandidate?.expiresAtEpochMs?.let { it - now <= RESOLVED_URL_EXPIRY_MARGIN_MS } == true

    private fun Throwable.httpStatusCodeOrNull(): Int? = causeChain()
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()
        ?.responseCode

    private fun playbackRetryDelayMs(error: PlaybackException, retry: Int): Long {
        val status = error.httpStatusCodeOrNull()
        val transientHttp = status == 408 || status == 429 || (status != null && status in 500..599)
        val transientNetwork = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        if (!transientHttp && !transientNetwork) return 0L
        val exponential = PLAYBACK_RETRY_BASE_DELAY_MS * (1L shl (retry - 1).coerceAtMost(3))
        return exponential + Random.nextLong(PLAYBACK_RETRY_JITTER_MS + 1L)
    }

    private fun isRetryablePlaybackFailure(error: PlaybackException): Boolean {
        if (isUnsupportedMediaFailure(error)) return true
        val status = error.httpStatusCodeOrNull()
        if (status == 401 || status == 403 || status == 404 || status == 410 ||
            status == 408 || status == 429 || (status != null && status in 500..599)
        ) return true
        return error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
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
                isLossless = false,
            )
        }
        updateBitPerfectState()
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
                    isLossless = isFlac && (bitDepth ?: 0) > 16,
                )
            }
            updateBitPerfectState()
        } catch (_: Exception) {
            val isFlac = url.endsWith(".flac", ignoreCase = true)
            _state.update {
                it.copy(
                    audioCodec = if (isFlac) "FLAC" else "AUDIO",
                    bitDepth = if (isFlac) 16 else null,
                    samplingRateKHz = if (isFlac) 44.1 else null,
                    isLossless = isFlac,
                )
            }
            updateBitPerfectState()
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
        discoverQueueActive = session.isEndlessQueue && session.sourceLabel == "Discover"
        radioQueueActive = session.isEndlessQueue && session.sourceLabel != "Discover"
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

    private fun isExplicitlyUnplayableFailure(error: Throwable): Boolean =
        error.causeChain().filterIsInstance<java.io.IOException>().firstOrNull() is ConfirmedUnplayableMediaException

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
        if (queue.isEmpty() && current == null && previous.current != null) {
            // ExoPlayer briefly reports an empty timeline while a selected
            // track is being resolved. Keep the logical queue available for
            // transport controls until the new timeline is installed.
            return
        }
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
            isEndlessQueue = discoverQueueActive || radioQueueActive,
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
            isLossless = previous.isLossless && sameTrack,
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
        const val RADIO_QUEUE_BATCH_SIZE = 25
        const val RADIO_QUEUE_REFILL_THRESHOLD = 6
        const val POSITION_PERSIST_INTERVAL_MS = 5_000L
        const val MAX_PERSISTED_QUEUE_SIZE = 200
        const val RESTORED_PREVIOUS_TRACKS = 50
        const val PLAYBACK_PREFERENCES_NAME = "lastwave_playback_session"
        const val PLAYBACK_SESSION_KEY = "active_session"
        /** Ticker-driven session persistence cadence (explicit state changes persist immediately). */
        const val TICKER_PERSIST_INTERVAL_MS = 2_000L
        const val MAX_PLAYBACK_RETRIES = 2
        const val PLAYBACK_RETRY_BASE_DELAY_MS = 350L
        const val PLAYBACK_RETRY_JITTER_MS = 250L
        const val MEDIA_STREAM_CACHE_BYTES = 64L * 1024 * 1024
        const val NEXT_TRACK_PREFETCH_BYTES = 1L * 1024 * 1024
        const val NEXT_TRACK_PREFETCH_DELAY_MS = 500L
        const val MAX_PREPARED_STREAMS = 256
        const val RESOLVED_URL_EXPIRY_MARGIN_MS = 2 * 60 * 1000L
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

private fun PlayableTrack.withYoutubeArtwork(): PlayableTrack =
    if (artworkUrl.isNullOrBlank() && !videoId.isNullOrBlank()) {
        copy(artworkUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
    } else this

private fun PlayableTrack.toMediaItem(resolved: MusicPlayer.ResolvedStream? = null): MediaItem {
    val playbackUri = if (resolved != null) {
        Uri.parse(resolved.url)
    } else if (playbackUrl?.isNotBlank() == true) {
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
    val mediaIdKey = mediaIdKey()
    return MediaItem.Builder()
        .setMediaId(mediaIdKey)
        .setUri(playbackUri)
        .apply {
            (resolved?.mimeType ?: playbackMimeType)?.takeIf(String::isNotBlank)?.let(::setMimeType)
            resolved?.let {
                setCustomCacheKey(it.cacheKey)
            }
        }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri((artworkUrl?.takeIf(String::isNotBlank)
                    ?: (videoId ?: resolved?.youtubeCandidate?.videoId)?.let {
                        "https://i.ytimg.com/vi/$it/hqdefault.jpg"
                    })?.let(Uri::parse))
                .setIsPlayable(true)
                .build(),
        )
        .build()
}

private fun PlayableTrack.mediaIdKey(): String = when {
    !playbackUrl.isNullOrBlank() -> "local:${playbackUrl}"
    !videoId.isNullOrBlank() -> videoId
    else -> "query:${artist.lowercase()}|${title.lowercase()}"
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

fun GeneratedTrack.toPlayableTrack(): PlayableTrack {
    val videoId = youtubeVideoIdOrNull()
    return PlayableTrack(
        title = name,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl ?: videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
        videoId = videoId,
    )
}

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
