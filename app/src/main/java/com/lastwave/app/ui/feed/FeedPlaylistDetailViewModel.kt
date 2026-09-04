package com.lastwave.app.ui.feed

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistResult
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface FeedPlaylistDetailUiState {
    data object Loading : FeedPlaylistDetailUiState

    @Immutable
    data class Success(val playlist: YouTubePlaylistResult) : FeedPlaylistDetailUiState

    data class Error(val message: String) : FeedPlaylistDetailUiState
}

@HiltViewModel
class FeedPlaylistDetailViewModel @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
    private val musicPlayer: MusicPlayer,
) : ViewModel() {
    private val _uiState = MutableStateFlow<FeedPlaylistDetailUiState>(FeedPlaylistDetailUiState.Loading)
    val uiState: StateFlow<FeedPlaylistDetailUiState> = _uiState.asStateFlow()

    private var currentPlaylistId: String? = null

    fun load(playlistId: String) {
        if (playlistId.isBlank()) {
            _uiState.value = FeedPlaylistDetailUiState.Error("This mix is unavailable.")
            return
        }
        if (playlistId == currentPlaylistId && _uiState.value !is FeedPlaylistDetailUiState.Error) return

        currentPlaylistId = playlistId
        viewModelScope.launch {
            _uiState.value = FeedPlaylistDetailUiState.Loading
            val result = runCatching {
                innerTube.fetchPlaylist(playlistId, maxTracks = FEED_PLAYLIST_TRACK_LIMIT)
            }.getOrNull()

            if (currentPlaylistId != playlistId) return@launch
            _uiState.value = when {
                result == null -> FeedPlaylistDetailUiState.Error("Couldn't open this mix. Check your connection and try again.")
                result.tracks.isEmpty() -> FeedPlaylistDetailUiState.Error("This mix doesn't have any playable tracks right now.")
                else -> FeedPlaylistDetailUiState.Success(result)
            }
        }
    }

    fun playFrom(index: Int) {
        val playlist = (_uiState.value as? FeedPlaylistDetailUiState.Success)?.playlist ?: return
        if (playlist.tracks.isEmpty()) return
        musicPlayer.playQueue(
            tracks = playlist.tracks.map { it.toPlayableTrack() },
            startIndex = index.coerceIn(0, playlist.tracks.lastIndex),
            sourceLabel = playlist.title.ifBlank { "Feed mix" },
        )
    }

    fun shuffle() {
        val playlist = (_uiState.value as? FeedPlaylistDetailUiState.Success)?.playlist ?: return
        if (playlist.tracks.isEmpty()) return
        musicPlayer.playQueue(
            tracks = playlist.tracks.shuffled().map { it.toPlayableTrack() },
            startIndex = 0,
            sourceLabel = playlist.title.ifBlank { "Feed mix" },
        )
    }

    private fun YouTubeMusicTrack.toPlayableTrack() = PlayableTrack(
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        videoId = videoId,
    )

    private companion object {
        const val FEED_PLAYLIST_TRACK_LIMIT = 100
    }
}
