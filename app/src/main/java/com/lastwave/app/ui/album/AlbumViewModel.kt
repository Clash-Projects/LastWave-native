package com.lastwave.app.ui.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.model.AlbumPageData
import com.lastwave.app.data.repository.AlbumRepository
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SettingsPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface AlbumUiState {
    data object Loading : AlbumUiState
    data class Success(val data: AlbumPageData) : AlbumUiState
    data class Error(val message: String) : AlbumUiState
}

@HiltViewModel
class AlbumViewModel @Inject constructor(
    private val repository: AlbumRepository,
    private val musicPlayer: MusicPlayer,
    private val settingsPreferences: SettingsPreferences,
) : ViewModel() {

    val settings: StateFlow<MiscSettings> = settingsPreferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MiscSettings())

    private val _uiState = MutableStateFlow<AlbumUiState>(AlbumUiState.Loading)
    val uiState: StateFlow<AlbumUiState> = _uiState.asStateFlow()

    private var currentAlbumTitle: String = ""
    private var currentArtistName: String = ""
    private var currentBrowseId: String? = null

    fun loadAlbum(albumTitle: String, artistName: String = "", browseId: String? = null) {
        if (albumTitle == currentAlbumTitle && artistName == currentArtistName && browseId == currentBrowseId && _uiState.value is AlbumUiState.Success) {
            return
        }
        currentAlbumTitle = albumTitle
        currentArtistName = artistName
        currentBrowseId = browseId

        viewModelScope.launch {
            _uiState.value = AlbumUiState.Loading
            try {
                val data = repository.getAlbumDetails(albumTitle, artistName, browseId)
                _uiState.value = AlbumUiState.Success(data)
            } catch (e: Exception) {
                _uiState.value = AlbumUiState.Error(e.message ?: "Failed to load album details")
            }
        }
    }

    fun playAll(startIndex: Int = 0) {
        val state = _uiState.value as? AlbumUiState.Success ?: return
        val tracks = state.data.tracks
        if (tracks.isNotEmpty()) {
            musicPlayer.playQueue(
                tracks,
                startIndex.coerceIn(0, tracks.lastIndex),
                sourceLabel = "${state.data.title} — ${state.data.artist}",
            )
        }
    }

    fun playShuffle() {
        val state = _uiState.value as? AlbumUiState.Success ?: return
        val tracks = state.data.tracks
        if (tracks.isNotEmpty()) {
            val shuffled = tracks.shuffled()
            musicPlayer.playQueue(
                shuffled,
                0,
                sourceLabel = "${state.data.title} — ${state.data.artist}",
            )
        }
    }
}
