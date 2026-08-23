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
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import android.media.audiofx.AudioEffect
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lastwave.app.data.discover.DiscoverRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.music.InnerTubeMusicApi
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val audioEffects: AudioEffectsEngine,
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
    @Volatile private var persistenceGeneration = 0L
    private val playbackPersistenceLock = Any()
    private var ticker: Job? = null
    private var playRequest: Job? = null
    private var queueEnrichmentJob: Job? = null
    private var preloadJob: Job? = null
    private var discoverQueueLoadJob: Job? = null
    private var discoverQueueActive = false
    private var unavailableSkipJob: Job? = null
    private var sleepTimerDeadlineMs: Long? = null
    private var sleepTimerStep = 0
    private val _state = MutableStateFlow(MusicPlayerState())
    val state: StateFlow<MusicPlayerState> = _state.asStateFlow()

    private var errorRetryCount = 0

    private val mediaCache: Cache by lazy {
        val cacheDir = java.io.File(appContext.cacheDir, "media_stream_cache")
        val evictor = LeastRecentlyUsedCacheEvictor(512L * 1024 * 1024) // 512 MB LRU stream cache
        val dbProvider = StandaloneDatabaseProvider(appContext)
        SimpleCache(cacheDir, evictor, dbProvider)
    }

    private val cacheDataSourceFactory: CacheDataSource.Factory by lazy {
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent(YOUTUBE_WEB_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)
        CacheDataSource.Factory()
            .setCache(mediaCache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh(player)
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            // The Experimental equalizer / music-enhancer effects must follow
            // the session id wherever the platform audio server rebinds it.
            audioEffects.attach(audioSessionId)
            // Broadcast audio session open intent for system equalizer / spatial audio / DSP (Pixel Audio / Wavelet / Viper)
            if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                runCatching {
                    val intent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, appContext.packageName)
                        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                    }
                    appContext.sendBroadcast(intent)
                }
            }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            errorRetryCount = 0
            if (mediaItem != null) {
                val currentIndex = player.currentMediaItemIndex
                enrichUpcomingQueue(currentIndex)
                extendDiscoverQueueIfNeeded(currentIndex)
                val currentQueue = _state.value.queue
                val nextIndex = currentIndex + 1
                if (nextIndex in currentQueue.indices) {
                    preloadNextTrack(currentQueue[nextIndex])
                }
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            val currentTrack = _state.value.current
            val currentPos = player.currentPosition.coerceAtLeast(0)
            val videoId = currentTrack?.videoId

            // Invalidate stale cache on 403 or network failure
            if (!videoId.isNullOrBlank()) {
                innerTube.invalidateCache(videoId)
            }

            if (currentTrack != null && errorRetryCount < 2) {
                errorRetryCount++
                applicationScope.launch(Dispatchers.Main.immediate) {
                    _state.update { it.copy(isBuffering = true, error = null) }
                    try {
                        val stream = resolveTrackAudioStream(currentTrack, videoId)
                        publishResolvedQuality(stream)
                        val updated = currentTrack.copy(
                            playbackUrl = stream.url,
                            playbackMimeType = stream.mimeType,
                        )
                        val currentIndex = player.currentMediaItemIndex
                        if (currentIndex in 0 until player.mediaItemCount) {
                            player.replaceMediaItem(currentIndex, updated.toMediaItem())
                            player.seekTo(currentIndex, currentPos)
                            player.prepare()
                            player.play()
                            return@launch
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MusicPlayer", "Auto-retry stream failed", e)
                    }
                    _state.update { it.copy(error = error.message ?: "Playback error (${error.errorCodeName})", isBuffering = false) }
                    scheduleUnavailableMediaSkip(
                        failedIndex = player.currentMediaItemIndex,
                        failedMediaId = player.currentMediaItem?.mediaId,
                    )
                }
                return
            }

            _state.update { it.copy(error = error.message ?: "Playback error (${error.errorCodeName})", isBuffering = false) }
            scheduleUnavailableMediaSkip(
                failedIndex = player.currentMediaItemIndex,
                failedMediaId = player.currentMediaItem?.mediaId,
            )
        }
    }

    private val player: ExoPlayer = run {
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
                        resolveTrackAudioStream(track, track.videoId)
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
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 250, // Instant ultra-reactive playback trigger
                /* bufferForPlaybackAfterRebufferMs = */ 1_000,
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
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(true)
                    .setEnableAudioTrackPlaybackParams(true)
                    .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                    .build()
            }
        }.apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableAudioTrackPlaybackParams(true)
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
            }
    }

    init {
        restorePlaybackSession()
        refresh(player)
        // If a session id already exists (restored playback), effects attach
        // right away; otherwise onAudioSessionIdChanged covers it later.
        audioEffects.attach(player.audioSessionId)
        ticker = applicationScope.launch(Dispatchers.Main.immediate) {
            var lastTickerPersistMs = 0L
            while (true) {
                if (_state.value.current != null) {
                    val remaining = sleepTimerDeadlineMs?.minus(SystemClock.elapsedRealtime())
                    if (remaining != null && remaining <= 0) {
                        sleepTimerDeadlineMs = null
                        sleepTimerStep = 0
                        player.pause()
                    }
                    val pos = player.currentPosition.coerceAtLeast(0)
                    val buf = player.bufferedPosition.coerceAtLeast(0)
                    val dur = player.duration.takeIf { value -> value > 0 } ?: _state.value.durationMs
                    val sleepRemaining = remaining?.coerceAtLeast(0)
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
                delay(if (player.isPlaying) 250L else 500L)
            }
        }

        applicationScope.launch {
            discoverRepository.feed.collect { feed ->
                if (discoverQueueActive) appendMissingDiscoverTracks(feed.map(GeneratedTrack::toPlayableTrack))
            }
        }
    }

    fun play(track: PlayableTrack, sourceLabel: String = "LastWave") {
        disableDiscoverQueue()
        playRequest?.cancel()
        unavailableSkipJob?.cancel()
        if (!track.videoId.isNullOrBlank()) {
            innerTube.prefetchStream(track.videoId)
        }
        onMain {
            ensureForegroundService()
            _state.value = MusicPlayerState(
                current = track,
                queue = listOf(track),
                currentIndex = 0,
                sourceLabel = sourceLabel,
                isBuffering = true,
                isPlaying = true,
            )
            persistPlaybackSession()
            player.setMediaItem(track.toMediaItem())
            player.prepare()
            player.play()
        }
    }

    fun playQueue(tracks: List<PlayableTrack>, startIndex: Int = 0, sourceLabel: String = "LastWave") {
        playQueueInternal(tracks, startIndex, endlessDiscover = false, sourceLabel = sourceLabel)
    }

    fun playDiscoverQueue(tracks: List<PlayableTrack>, startIndex: Int = 0) {
        playQueueInternal(tracks, startIndex, endlessDiscover = true, sourceLabel = "Discover")
    }

    private fun playQueueInternal(
        tracks: List<PlayableTrack>,
        startIndex: Int,
        endlessDiscover: Boolean,
        sourceLabel: String = if (endlessDiscover) "Discover" else "LastWave",
    ) {
        if (tracks.isEmpty()) return
        discoverQueueLoadJob?.cancel()
        discoverQueueActive = endlessDiscover
        val selectedIndex = startIndex.coerceIn(tracks.indices)
        playRequest?.cancel()
        queueEnrichmentJob?.cancel()
        unavailableSkipJob?.cancel()

        val selectedTrack = tracks[selectedIndex]
        if (!selectedTrack.videoId.isNullOrBlank()) {
            innerTube.prefetchStream(selectedTrack.videoId)
        }

        onMain {
            ensureForegroundService()
            _state.value = MusicPlayerState(
                current = selectedTrack,
                queue = tracks,
                currentIndex = selectedIndex,
                sourceLabel = sourceLabel,
                isEndlessQueue = endlessDiscover,
                isBuffering = true,
                isPlaying = true,
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
            if (selectedIndex + 1 in tracks.indices) {
                preloadNextTrack(tracks[selectedIndex + 1])
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
        _state.update { it.copy(positionMs = target) }
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
    fun toggleShuffle() = onMain { player.shuffleModeEnabled = !player.shuffleModeEnabled }
    fun cycleRepeatMode() = onMain {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
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
            val endExclusive = withContext(Dispatchers.Main.immediate) {
                minOf(currentIndex + 4, player.mediaItemCount)
            }
            data class PendingEnrich(val index: Int, val original: PlayableTrack, val expectedMediaId: String)
            val pending = (currentIndex until endExclusive).mapNotNull { index ->
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
                    if (index < player.mediaItemCount && player.getMediaItemAt(index).mediaId == expectedMediaId) {
                        player.replaceMediaItem(index, enriched.toMediaItem())
                        if (index == player.currentMediaItemIndex) {
                            _state.update { state ->
                                if (state.current?.title == enriched.title && state.current?.artist == enriched.artist) {
                                    state.copy(current = enriched)
                                } else state
                            }
                        }
                    }
                }
            }
        }
    }


    /**
     * Pre-resolves and streams the first 512KB of the upcoming track directly into
     * the local disk cache while the current song is playing, guaranteeing instant 0ms track transitions.
     */
    private fun preloadNextTrack(nextTrack: PlayableTrack?) {
        if (nextTrack == null) return
        preloadJob?.cancel()
        preloadJob = applicationScope.launch(Dispatchers.IO) {
            val resolved = runCatching {
                resolveTrackAudioStream(nextTrack, nextTrack.videoId)
            }.getOrNull() ?: return@launch

            val dataSpec = DataSpec.Builder()
                .setUri(Uri.parse(resolved.url))
                .setPosition(0)
                .setLength(512 * 1024L) // 512 KB audio chunk pre-buffer
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

    /** A generated Last.fm track can have no playable YouTube Music match.
     * Keep the queue moving instead of leaving the player stopped on it. */
    @MainThread
    private fun scheduleUnavailableQueueSkip(
        tracks: List<PlayableTrack>,
        failedIndex: Int,
        endlessDiscover: Boolean,
        sourceLabel: String = if (endlessDiscover) "Discover" else "LastWave",
    ) {
        val nextIndex = failedIndex + 1
        if (nextIndex !in tracks.indices) return
        unavailableSkipJob?.cancel()
        unavailableSkipJob = applicationScope.launch(Dispatchers.Main.immediate) {
            delay(UNAVAILABLE_SKIP_DELAY_MS)
            if (_state.value.currentIndex == failedIndex && !player.isPlaying) {
                unavailableSkipJob = null
                playQueueInternal(tracks, nextIndex, endlessDiscover, sourceLabel)
            }
        }
    }

    /** Handles failures raised by ExoPlayer after the queue has been prepared,
     * including unresolved entries reached during an automatic transition. */
    @MainThread
    private fun scheduleUnavailableMediaSkip(failedIndex: Int, failedMediaId: String?) {
        val suggestedNext = player.nextMediaItemIndex
        val nextIndex = suggestedNext.takeIf { it != C.INDEX_UNSET && it != failedIndex }
            ?: (failedIndex + 1).takeIf { it < player.mediaItemCount }
            ?: 0.takeIf { player.repeatMode == Player.REPEAT_MODE_ALL && player.mediaItemCount > 1 }
            ?: C.INDEX_UNSET
        if (failedIndex == C.INDEX_UNSET || nextIndex == C.INDEX_UNSET) return
        unavailableSkipJob?.cancel()
        unavailableSkipJob = applicationScope.launch(Dispatchers.Main.immediate) {
            delay(UNAVAILABLE_SKIP_DELAY_MS)
            if (player.currentMediaItemIndex != failedIndex || player.currentMediaItem?.mediaId != failedMediaId) {
                return@launch
            }
            unavailableSkipJob = null
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
    ): ResolvedStream = withContext(Dispatchers.IO) {
        val misc = runCatching { settingsPreferences.settings.first() }.getOrDefault(MiscSettings())
        if (misc.preferQobuzStreaming) {
            // Timeout Qobuz resolution so a slow CDN doesn't block playback.
            // Falls through to YouTube Music if Qobuz takes longer than 5 seconds
            // or returns no match.
            val qobuzStream = withTimeoutOrNull(5_000L) {
                runCatching {
                    qobuzMusicApi.resolveStream(
                        title = track.title,
                        artist = track.artist,
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
        val targetVideoId = videoId
            ?: withTimeoutOrNull(3_500L) { innerTube.findBestMatch(track.title, track.artist) }?.videoId
            ?: error("No audio stream available")
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

    private fun restorePlaybackSession() {
        val raw = playbackPreferences.getString(PLAYBACK_SESSION_KEY, null) ?: return
        val session = runCatching {
            persistenceJson.decodeFromString<PersistedPlaybackSession>(raw)
        }.getOrElse {
            clearPersistedPlaybackSession()
            return
        }
        val restoredQueue = session.queue
            .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
            .map { it.copy(playbackUrl = null, playbackMimeType = null) }
        if (restoredQueue.isEmpty()) {
            clearPersistedPlaybackSession()
            return
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
        player.setMediaItems(
            restoredQueue.map(PlayableTrack::toMediaItem),
            restoredIndex,
            session.positionMs.coerceAtLeast(0),
        )
        player.shuffleModeEnabled = session.shuffleEnabled
        player.repeatMode = session.repeatMode.takeIf {
            it in Player.REPEAT_MODE_OFF..Player.REPEAT_MODE_ALL
        } ?: Player.REPEAT_MODE_OFF
        player.setPlaybackSpeed(session.speed.coerceIn(0.5f, 2f))
        player.pause()
    }

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
        _state.value = MusicPlayerState(
            current = current,
            queue = queue,
            currentIndex = player.currentMediaItemIndex.takeIf { player.mediaItemCount > 0 } ?: -1,
            sourceLabel = previous.sourceLabel,
            isEndlessQueue = previous.isEndlessQueue && discoverQueueActive,
            isPlaying = player.isPlaying,
            isBuffering = isBuffering,
            positionMs = player.currentPosition.coerceAtLeast(0),
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it > 0 } ?: 0,
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
        const val UNAVAILABLE_SKIP_DELAY_MS = 2_500L
        const val POSITION_PERSIST_INTERVAL_MS = 5_000L
        const val MAX_PERSISTED_QUEUE_SIZE = 200
        const val RESTORED_PREVIOUS_TRACKS = 50
        const val PLAYBACK_PREFERENCES_NAME = "lastwave_playback_session"
        const val PLAYBACK_SESSION_KEY = "active_session"
        /** Ticker-driven session persistence cadence (explicit state changes persist immediately). */
        const val TICKER_PERSIST_INTERVAL_MS = 2_000L
        /** Hard ceiling for the blocking data-spec stream resolution inside ExoPlayer's loader. */
        const val RESOLVE_DATA_SPEC_TIMEOUT_MS = 25_000L
        val SLEEP_TIMER_MINUTES = intArrayOf(0, 15, 30, 60)
    }
}

private fun PlayableTrack.toMediaItem(): MediaItem {
    val playbackUri = if (playbackUrl?.isNotBlank() == true) {
        Uri.parse(playbackUrl)
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
    return MediaItem.Builder()
        .setMediaId(videoId ?: "query:${artist.lowercase()}|${title.lowercase()}")
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

private fun MediaItem.toPlayableTrack(): PlayableTrack = PlayableTrack(
    title = mediaMetadata.title?.toString().orEmpty().ifBlank { "Unknown track" },
    artist = mediaMetadata.artist?.toString().orEmpty().ifBlank { "Unknown artist" },
    album = mediaMetadata.albumTitle?.toString(),
    artworkUrl = mediaMetadata.artworkUri?.toString(),
    videoId = mediaId.takeUnless { it.startsWith("query:") },
)

fun GeneratedTrack.toPlayableTrack() = PlayableTrack(
    title = name,
    artist = artist,
    album = album,
    artworkUrl = artworkUrl,
)

private fun PlayableTrack.queueKey(): String = "$title|$artist".lowercase()
