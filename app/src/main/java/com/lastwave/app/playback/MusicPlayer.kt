package com.lastwave.app.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lastwave.app.data.discover.DiscoverRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YOUTUBE_WEB_USER_AGENT
import com.lastwave.app.widget.WidgetUpdater
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val discoverRepository: DiscoverRepository,
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
    private var discoverQueueLoadJob: Job? = null
    private var discoverQueueActive = false
    private var unavailableSkipJob: Job? = null
    private var sleepTimerDeadlineMs: Long? = null
    private var sleepTimerStep = 0
    private val _state = MutableStateFlow(MusicPlayerState())
    val state: StateFlow<MusicPlayerState> = _state.asStateFlow()

    private var errorRetryCount = 0

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh(player)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            errorRetryCount = 0
            if (mediaItem != null) {
                enrichUpcomingQueue(player.currentMediaItemIndex)
                extendDiscoverQueueIfNeeded(player.currentMediaItemIndex)
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            val currentTrack = _state.value.current
            val currentPos = player.currentPosition.coerceAtLeast(0)
            val videoId = currentTrack?.videoId

            if (currentTrack != null && errorRetryCount < 2) {
                errorRetryCount++
                applicationScope.launch(Dispatchers.Main.immediate) {
                    _state.update { it.copy(isBuffering = true, error = null) }
                    try {
                        val resolvedId = videoId ?: innerTube.findBestMatch(currentTrack.title, currentTrack.artist).videoId
                        if (!resolvedId.isNullOrBlank()) {
                            val stream = withContext(Dispatchers.IO) { innerTube.resolveAudioStream(resolvedId) }
                            publishStreamQuality(stream)
                            val updated = currentTrack.copy(
                                videoId = resolvedId,
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
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent(YOUTUBE_WEB_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)
        val resolving = ResolvingDataSource.Factory(upstream) { dataSpec ->
            val requested = dataSpec.uri
            if (requested.scheme != "lastwave") {
                dataSpec
            } else {
                val stream = runBlocking(Dispatchers.IO) {
                    val videoId = when (requested.host) {
                        "youtube" -> requested.pathSegments.firstOrNull()
                        "search" -> innerTube.findBestMatch(
                            title = requested.getQueryParameter("title").orEmpty(),
                            artist = requested.getQueryParameter("artist").orEmpty(),
                        ).videoId
                        else -> null
                    } ?: error("Invalid LastWave playback item")
                    innerTube.resolveAudioStream(videoId).also { stream ->
                        applicationScope.launch(Dispatchers.Main.immediate) {
                            if (_state.value.current?.videoId == videoId) publishStreamQuality(stream)
                        }
                    }
                }
                dataSpec.withUri(Uri.parse(stream.url))
            }
        }
        ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(appContext).setDataSourceFactory(resolving))
            .build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
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
        ticker = applicationScope.launch(Dispatchers.Main.immediate) {
            while (true) {
                if (_state.value.current != null) {
                    val remaining = sleepTimerDeadlineMs?.minus(SystemClock.elapsedRealtime())
                    if (remaining != null && remaining <= 0) {
                        sleepTimerDeadlineMs = null
                        sleepTimerStep = 0
                        player.pause()
                    }
                    _state.update {
                        it.copy(
                            positionMs = player.currentPosition.coerceAtLeast(0),
                            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0),
                            durationMs = player.duration.takeIf { value -> value > 0 } ?: it.durationMs,
                            sleepTimerRemainingMs = remaining?.coerceAtLeast(0),
                        )
                    }
                    persistPlaybackSession()
                }
                delay(500)
            }
        }
        applicationScope.launch {
            discoverRepository.feed.collect { feed ->
                if (discoverQueueActive) appendMissingDiscoverTracks(feed)
            }
        }
    }

    fun play(track: PlayableTrack, sourceLabel: String = "LastWave") {
        disableDiscoverQueue()
        playRequest?.cancel()
        unavailableSkipJob?.cancel()
        playRequest = applicationScope.launch {
            withContext(Dispatchers.Main.immediate) {
                ensureForegroundService()
                player.stop()
                player.clearMediaItems()
                _state.value = MusicPlayerState(
                    current = track,
                    queue = listOf(track),
                    currentIndex = 0,
                    sourceLabel = sourceLabel,
                    isBuffering = true,
                )
                persistPlaybackSession()
            }

            try {
                val matched = matchMetadata(track)
                withContext(Dispatchers.Main.immediate) {
                    _state.update { it.copy(current = matched, queue = listOf(matched)) }
                }
                val stream = innerTube.resolveAudioStream(matched.videoId!!)
                withContext(Dispatchers.Main.immediate) { publishStreamQuality(stream) }
                val prepared = matched.copy(playbackUrl = stream.url, playbackMimeType = stream.mimeType)
                withContext(Dispatchers.Main.immediate) {
                    player.setMediaItem(prepared.toMediaItem())
                    player.prepare()
                    player.play()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    _state.update {
                        it.copy(
                            isBuffering = false,
                            error = error.message ?: "Unable to play this track",
                        )
                    }
                }
            }
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
        playRequest = applicationScope.launch {
            withContext(Dispatchers.Main.immediate) {
                ensureForegroundService()
                player.stop()
                player.clearMediaItems()
                _state.value = MusicPlayerState(
                    current = tracks[selectedIndex],
                    queue = tracks,
                    currentIndex = selectedIndex,
                    sourceLabel = sourceLabel,
                    isEndlessQueue = endlessDiscover,
                    isBuffering = true,
                )
                persistPlaybackSession()
            }

            try {
                val matched = matchMetadata(tracks[selectedIndex])
                withContext(Dispatchers.Main.immediate) {
                    val enrichedQueue = tracks.toMutableList().apply { this[selectedIndex] = matched }
                    _state.update { it.copy(current = matched, queue = enrichedQueue) }
                }
                val stream = innerTube.resolveAudioStream(matched.videoId!!)
                withContext(Dispatchers.Main.immediate) { publishStreamQuality(stream) }
                val prepared = matched.copy(playbackUrl = stream.url, playbackMimeType = stream.mimeType)
                val queue = tracks.toMutableList().apply { this[selectedIndex] = prepared }
                withContext(Dispatchers.Main.immediate) {
                    player.setMediaItems(queue.map(PlayableTrack::toMediaItem), selectedIndex, 0L)
                    player.prepare()
                    player.play()
                }
                enrichUpcomingQueue(selectedIndex)
                if (endlessDiscover) {
                    appendMissingDiscoverTracks(discoverRepository.getCachedFeed())
                }
                extendDiscoverQueueIfNeeded(selectedIndex)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    _state.update { it.copy(isBuffering = false, error = error.message ?: "Unable to play this playlist") }
                    scheduleUnavailableQueueSkip(tracks, selectedIndex, endlessDiscover)
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
    fun resume() = onMain {
        ensureForegroundService()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }
    fun pause() = onMain { player.pause() }
    fun togglePlayPause() = onMain {
        if (player.isPlaying) player.pause() else {
            ensureForegroundService()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }
    fun seekTo(positionMs: Long) = onMain { player.seekTo(positionMs.coerceAtLeast(0)) }
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
        if (!track.videoId.isNullOrBlank()) return track
        val match = innerTube.findBestMatch(track.title, track.artist)
        return track.copy(
            title = track.title.ifBlank { match.title },
            artist = track.artist.ifBlank { match.artist },
            album = track.album?.takeIf(String::isNotBlank) ?: match.album,
            artworkUrl = match.artworkUrl?.takeIf(String::isNotBlank)
                ?: track.artworkUrl?.takeIf(String::isNotBlank),
            videoId = match.videoId,
        )
    }

    /** Resolves only the next few queue entries, keeping startup fast while
     * making upcoming transitions use exact YouTube IDs and catalog art. */
    private fun enrichUpcomingQueue(currentIndex: Int) {
        queueEnrichmentJob?.cancel()
        queueEnrichmentJob = applicationScope.launch {
            val endExclusive = withContext(Dispatchers.Main.immediate) {
                minOf(currentIndex + 4, player.mediaItemCount)
            }
            for (index in (currentIndex + 1) until endExclusive) {
                val original = withContext(Dispatchers.Main.immediate) {
                    if (index >= player.mediaItemCount) null else player.getMediaItemAt(index).toPlayableTrack()
                } ?: continue
                if (!original.videoId.isNullOrBlank()) continue
                val expectedMediaId = "query:${original.artist.lowercase()}|${original.title.lowercase()}"
                val enriched = runCatching { matchMetadata(original) }.getOrNull() ?: continue
                withContext(Dispatchers.Main.immediate) {
                    if (index < player.mediaItemCount && player.getMediaItemAt(index).mediaId == expectedMediaId) {
                        player.replaceMediaItem(index, enriched.toMediaItem())
                    }
                }
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
                appendMissingDiscoverTracks(batch)
            } finally {
                discoverQueueLoadJob = null
            }
        }
    }

    private suspend fun appendMissingDiscoverTracks(tracks: List<GeneratedTrack>) {
        if (tracks.isEmpty()) return
        withContext(Dispatchers.Main.immediate) {
            if (!discoverQueueActive) return@withContext
            val knownKeys = (0 until player.mediaItemCount)
                .mapTo(mutableSetOf()) { player.getMediaItemAt(it).toPlayableTrack().queueKey() }
            val additions = tracks
                .map(GeneratedTrack::toPlayableTrack)
                .filter { knownKeys.add(it.queueKey()) }
            if (additions.isNotEmpty()) {
                player.addMediaItems(additions.map(PlayableTrack::toMediaItem))
                enrichUpcomingQueue(player.currentMediaItemIndex)
            }
        }
    }

    private fun disableDiscoverQueue() {
        discoverQueueActive = false
        discoverQueueLoadJob?.cancel()
        discoverQueueLoadJob = null
        _state.update { it.copy(isEndlessQueue = false) }
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

    private fun publishStreamQuality(stream: com.lastwave.app.data.music.YouTubeAudioStream) {
        _state.update {
            it.copy(
                bitrateKbps = stream.bitrate.takeIf { value -> value > 0 }?.div(1_000),
                audioCodec = stream.mimeType?.substringAfter("audio/")?.substringBefore(';')?.uppercase(),
            )
        }
    }

    private fun ensureForegroundService() {
        val intent = Intent(appContext, MusicPlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.startForegroundService(intent)
        else appContext.startService(intent)
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
                    playbackPreferences.edit().putString(PLAYBACK_SESSION_KEY, encoded).commit()
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
            playbackPreferences.edit().remove(PLAYBACK_SESSION_KEY).commit()
        }
    }

    @MainThread
    private fun refresh(player: Player) {
        val previous = _state.value
        val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toPlayableTrack() }
        val current = player.currentMediaItem?.toPlayableTrack()
        val sameTrack = current?.videoId != null && current.videoId == previous.current?.videoId
        _state.value = MusicPlayerState(
            current = current,
            queue = queue,
            currentIndex = player.currentMediaItemIndex.takeIf { player.mediaItemCount > 0 } ?: -1,
            sourceLabel = previous.sourceLabel,
            isEndlessQueue = previous.isEndlessQueue && discoverQueueActive,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0),
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it > 0 } ?: 0,
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            speed = player.playbackParameters.speed,
            bitrateKbps = previous.bitrateKbps.takeIf { sameTrack },
            audioCodec = previous.audioCodec.takeIf { sameTrack },
            sleepTimerRemainingMs = sleepTimerDeadlineMs?.minus(SystemClock.elapsedRealtime())?.coerceAtLeast(0),
            error = previous.error,
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
        val SLEEP_TIMER_MINUTES = intArrayOf(0, 15, 30, 60)
    }
}

private fun PlayableTrack.toMediaItem(): MediaItem {
    val playbackUri = if (!videoId.isNullOrBlank()) {
        Uri.Builder().scheme("lastwave").authority("youtube").appendPath(videoId).build()
    } else if (playbackUrl?.isNotBlank() == true) {
        Uri.parse(playbackUrl)
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
