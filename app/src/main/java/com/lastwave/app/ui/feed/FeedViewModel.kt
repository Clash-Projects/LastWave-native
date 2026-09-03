package com.lastwave.app.ui.feed

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.feed.FeedData
import com.lastwave.app.data.feed.FeedQuickTile
import com.lastwave.app.data.feed.FeedRepository
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.model.RecentTrack
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val error: String? = null,
    val selectedMood: String = "All",
    val moods: List<String> = listOf("All", "Chill", "Focus", "Energy", "Workout"),
    val feedData: FeedData = FeedData(),
    val moodPlaylists: List<YouTubePlaylistSummary> = emptyList(),
    val isLoadingMood: Boolean = false,
)

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val repository: FeedRepository,
    private val sessionPreferences: SessionPreferences,
    private val musicPlayer: MusicPlayer,
    private val innerTube: InnerTubeMusicApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    init {
        loadFeed()
    }

    fun loadFeed() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val username = sessionPreferences.session.value.username.takeIf(String::isNotBlank)
                val data = repository.loadFeed(username)
                _uiState.update { it.copy(isLoading = false, feedData = data) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Failed to load feed") }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                val username = sessionPreferences.session.value.username.takeIf(String::isNotBlank)
                val data = repository.loadFeed(username)
                _uiState.update { it.copy(isRefreshing = false, feedData = data, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun selectMood(mood: String) {
        if (_uiState.value.selectedMood == mood) return
        _uiState.update { it.copy(selectedMood = mood, isLoadingMood = true) }
        viewModelScope.launch {
            try {
                val playlists = repository.fetchMoodPlaylists(mood)
                _uiState.update { it.copy(moodPlaylists = playlists, isLoadingMood = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingMood = false) }
            }
        }
    }

    fun playTrack(track: YouTubeMusicTrack, sourceLabel: String = "Feed") {
        musicPlayer.play(track.toPlayableTrack(), sourceLabel = sourceLabel)
    }

    fun playTracksQueue(tracks: List<YouTubeMusicTrack>, startIndex: Int = 0, sourceLabel: String = "Feed") {
        val playable = tracks.map { it.toPlayableTrack() }
        musicPlayer.playQueue(playable, startIndex = startIndex.coerceIn(0, playable.lastIndex.coerceAtLeast(0)), sourceLabel = sourceLabel)
    }

    fun playRecentQueue(tracks: List<RecentTrack>, startIndex: Int = 0) {
        val playable = tracks.map {
            PlayableTrack(
                title = it.name,
                artist = it.artist.displayName,
                album = it.album.displayName,
                artworkUrl = it.artworkUrl,
            )
        }
        musicPlayer.playQueue(playable, startIndex = startIndex.coerceIn(0, playable.lastIndex.coerceAtLeast(0)), sourceLabel = "Jump Back In")
    }

    fun playPlaylistSummary(summary: YouTubePlaylistSummary) {
        viewModelScope.launch {
            val result = innerTube.fetchPlaylist(summary.id)
            val tracks = result?.tracks.orEmpty()
            if (tracks.isNotEmpty()) {
                val playable = tracks.map { it.toPlayableTrack() }
                musicPlayer.playQueue(playable, startIndex = 0, sourceLabel = summary.title)
            }
        }
    }

    fun handleQuickTileClick(tile: FeedQuickTile) {
        when {
            tile.actionVideoId != null -> {
                musicPlayer.play(
                    PlayableTrack(
                        title = tile.title,
                        artist = tile.subtitle ?: "",
                        artworkUrl = tile.artworkUrl,
                        videoId = tile.actionVideoId,
                    ),
                    sourceLabel = "Quick Picks",
                )
            }
            tile.playlistId != null -> {
                viewModelScope.launch {
                    val result = innerTube.fetchPlaylist(tile.playlistId)
                    val tracks = result?.tracks.orEmpty()
                    if (tracks.isNotEmpty()) {
                        musicPlayer.playQueue(tracks.map { it.toPlayableTrack() }, startIndex = 0, sourceLabel = tile.title)
                    }
                }
            }
        }
    }

    private fun YouTubeMusicTrack.toPlayableTrack(): PlayableTrack = PlayableTrack(
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        videoId = videoId,
    )
}
