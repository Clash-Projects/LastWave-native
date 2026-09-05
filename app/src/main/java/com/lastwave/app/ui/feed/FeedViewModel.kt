package com.lastwave.app.ui.feed

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.feed.FeedArtist
import com.lastwave.app.data.feed.FeedData
import com.lastwave.app.data.feed.FeedQuickTile
import com.lastwave.app.data.feed.FeedRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.model.RecentTrack
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class FeedUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val launchingRadio: String? = null,
    val error: String? = null,
    val feedData: FeedData = FeedData(),
)

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val repository: FeedRepository,
    private val sessionPreferences: SessionPreferences,
    private val musicPlayer: MusicPlayer,
    private val innerTube: InnerTubeMusicApi,
    private val ytAuth: YtMusicAuthManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()
    private var feedJob: Job? = null

    init {
        viewModelScope.launch {
            ytAuth.awaitLoadedConnection()
            combine(ytAuth.connection, sessionPreferences.session) { connection, session ->
                connection to session.username
            }.distinctUntilChanged().collect { (connection, _) ->
                feedJob?.cancel()
                _uiState.value = FeedUiState(
                    feedData = FeedData(
                        isYtConnected = connection.isConnected,
                        ytAccountName = connection.accountName.takeIf { connection.isConnected },
                    ),
                )
                loadFeed()
            }
        }
    }

    fun loadFeed() = fetchFeed(refreshing = false)

    fun refresh() = fetchFeed(refreshing = true)

    private fun fetchFeed(refreshing: Boolean) {
        feedJob?.cancel()
        feedJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = !refreshing || it.isLoading, isRefreshing = refreshing, error = null) }
            try {
                val connection = ytAuth.awaitLoadedConnection()
                val username = sessionPreferences.session.value.username.takeIf(String::isNotBlank)
                val data = repository.loadFeed(username)
                ensureActive()
                if (ytAuth.connection.value != connection ||
                    sessionPreferences.session.value.username.takeIf(String::isNotBlank) != username
                ) return@launch
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        feedData = data,
                        error = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                ensureActive()
                _uiState.update {
                    it.copy(isLoading = false, isRefreshing = false, error = "Couldn't update your feed. Please try again.")
                }
            }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun playTrack(track: YouTubeMusicTrack, sourceLabel: String = "Feed") {
        musicPlayer.play(track.toPlayableTrack(), sourceLabel = sourceLabel, startRadio = true)
    }

    fun playTracksQueue(tracks: List<YouTubeMusicTrack>, startIndex: Int = 0, sourceLabel: String = "Feed") {
        if (tracks.isEmpty()) return
        val playable = tracks.map { it.toPlayableTrack() }
        musicPlayer.playQueue(playable, startIndex = startIndex.coerceIn(0, playable.lastIndex.coerceAtLeast(0)), sourceLabel = sourceLabel)
    }

    fun playRecentQueue(tracks: List<RecentTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val playable = tracks.map {
            PlayableTrack(
                title = it.name,
                artist = it.artist.displayName,
                album = it.album.displayName,
                artworkUrl = it.artworkUrl,
                videoId = GeneratedTrack(it.name, it.artist.displayName, it.artworkUrl, it.url).youtubeVideoIdOrNull(),
            )
        }
        musicPlayer.playQueue(playable, startIndex = startIndex.coerceIn(0, playable.lastIndex.coerceAtLeast(0)), sourceLabel = "Jump Back In")
    }

    fun playGeneratedQueue(tracks: List<GeneratedTrack>, startIndex: Int = 0, sourceLabel: String = "Heavy Rotation") {
        if (tracks.isEmpty()) return
        val playable = tracks.map {
            PlayableTrack(
                title = it.name,
                artist = it.artist,
                album = it.album,
                artworkUrl = it.artworkUrl,
                videoId = it.youtubeVideoIdOrNull(),
            )
        }
        musicPlayer.playQueue(playable, startIndex = startIndex.coerceIn(0, playable.lastIndex.coerceAtLeast(0)), sourceLabel = sourceLabel)
    }

    fun playArtistRadio(artist: FeedArtist) {
        viewModelScope.launch {
            val seeds = try {
                innerTube.searchSongs("${artist.name} songs", limit = 5, prefetchStreams = false)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
            val seed = seeds.firstOrNull {
                it.artist.contains(artist.name, ignoreCase = true) || artist.name.contains(it.artist, ignoreCase = true)
            } ?: seeds.firstOrNull()
            val related = seed?.let {
                try {
                    innerTube.fetchRelatedSongs(it.videoId, limit = 25, prefetchStreams = false)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    emptyList()
                }
            }.orEmpty()
            val songs = (listOfNotNull(seed) + related).distinctBy(YouTubeMusicTrack::videoId)
            if (songs.isNotEmpty()) {
                playTracksQueue(songs, startIndex = 0, sourceLabel = "${artist.name} Radio")
            }
        }
    }

    fun playDiscoveryQuery(title: String, query: String) {
        if (_uiState.value.launchingRadio != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(launchingRadio = title) }
            try {
                val seeds = try {
                    innerTube.searchSongs(query, limit = 8, prefetchStreams = false)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    emptyList()
                }
                val seed = seeds.take(5).randomOrNull()
                val related = seed?.let {
                    try {
                        innerTube.fetchRelatedSongs(it.videoId, limit = 24, prefetchStreams = false)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        emptyList()
                    }
                }.orEmpty()
                val songs = (listOfNotNull(seed) + related).distinctBy(YouTubeMusicTrack::videoId)
                if (songs.isNotEmpty()) playTracksQueue(songs, sourceLabel = title)
            } finally {
                _uiState.update { it.copy(launchingRadio = null) }
            }
        }
    }

    fun playPlaylistSummary(summary: YouTubePlaylistSummary) {
        viewModelScope.launch {
            val result = runCatching { innerTube.fetchPlaylist(summary.id, maxTracks = 100) }
                .getOrNull()
            val tracks = result?.tracks.orEmpty()
            if (tracks.isNotEmpty()) {
                val playable = tracks.map { it.toPlayableTrack() }
                musicPlayer.playQueue(playable, startIndex = 0, sourceLabel = summary.title)
            }
        }
    }

    fun handleQuickTileClick(tile: FeedQuickTile) {
        val videoId = tile.actionVideoId ?: return
        musicPlayer.play(
            PlayableTrack(
                title = tile.title,
                artist = tile.subtitle ?: "",
                artworkUrl = tile.artworkUrl,
                videoId = videoId.takeIf(String::isNotBlank),
            ),
            sourceLabel = "Quick Picks",
        )
    }

    private fun YouTubeMusicTrack.toPlayableTrack(): PlayableTrack = PlayableTrack(
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        videoId = videoId.takeIf(String::isNotBlank),
    )
}
