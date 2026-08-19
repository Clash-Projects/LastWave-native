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
import javax.inject.Inject
import javax.inject.Singleton

data class PlayableTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val videoId: String? = null,
    val playbackUrl: String? = null,
    val playbackMimeType: String? = null,
)

data class MusicPlayerState(
    val connected: Boolean = true,
    val current: PlayableTrack? = null,
    val queue: List<PlayableTrack> = emptyList(),
    val currentIndex: Int = -1,
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
    private val applicationScope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private var ticker: Job? = null
    private var playRequest: Job? = null
    private var queueEnrichmentJob: Job? = null
    private var unavailableSkipJob: Job? = null
    private var sleepTimerDeadlineMs: Long? = null
    private var sleepTimerStep = 0
    private val _state = MutableStateFlow(MusicPlayerState())
    val state: StateFlow<MusicPlayerState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh(player)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem != null) enrichUpcomingQueue(player.currentMediaItemIndex)
        }
        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(error = error.message ?: error.errorCodeName, isBuffering = false) }
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
                }
                delay(500)
            }
        }
    }

    fun play(track: PlayableTrack) {
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
                    isBuffering = true,
                )
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

    fun playQueue(tracks: List<PlayableTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
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
                    isBuffering = true,
                )
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    _state.update { it.copy(isBuffering = false, error = error.message ?: "Unable to play this playlist") }
                    scheduleUnavailableQueueSkip(tracks, selectedIndex)
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
        val current = player.currentMediaItemIndex
        if (current >= 0 && current + 1 < player.mediaItemCount) {
            player.removeMediaItems(current + 1, player.mediaItemCount)
        }
    }
    fun stopAndClear() = onMain {
        playRequest?.cancel()
        queueEnrichmentJob?.cancel()
        unavailableSkipJob?.cancel()
        sleepTimerDeadlineMs = null
        sleepTimerStep = 0
        player.stop()
        player.clearMediaItems()
        _state.value = MusicPlayerState()
        applicationScope.launch(Dispatchers.IO) { WidgetUpdater.clear(appContext) }
        appContext.stopService(Intent(appContext, MusicPlaybackService::class.java))
    }
    fun removeQueueItem(index: Int) = onMain {
        if (index in 0 until player.mediaItemCount) player.removeMediaItem(index)
    }
    fun clearError() = _state.update { it.copy(error = null) }

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

    /** A generated Last.fm track can have no playable YouTube Music match.
     * Keep the queue moving instead of leaving the player stopped on it. */
    @MainThread
    private fun scheduleUnavailableQueueSkip(tracks: List<PlayableTrack>, failedIndex: Int) {
        val nextIndex = failedIndex + 1
        if (nextIndex !in tracks.indices) return
        unavailableSkipJob?.cancel()
        unavailableSkipJob = applicationScope.launch(Dispatchers.Main.immediate) {
            delay(UNAVAILABLE_SKIP_DELAY_MS)
            if (_state.value.currentIndex == failedIndex && !player.isPlaying) {
                unavailableSkipJob = null
                playQueue(tracks, nextIndex)
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
    }

    private companion object {
        const val UNAVAILABLE_SKIP_DELAY_MS = 2_500L
        val SLEEP_TIMER_MINUTES = intArrayOf(0, 15, 30, 60)
    }
}

private fun PlayableTrack.toMediaItem(): MediaItem {
    val playbackUri = playbackUrl?.takeIf(String::isNotBlank)?.let(Uri::parse) ?: if (!videoId.isNullOrBlank()) {
        Uri.Builder().scheme("lastwave").authority("youtube").appendPath(videoId).build()
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
