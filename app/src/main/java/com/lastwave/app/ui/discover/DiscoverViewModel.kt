package com.lastwave.app.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.artwork.ArtworkRepository
import com.lastwave.app.data.discover.DiscoverRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.naming.PlaylistNamer
import com.lastwave.app.data.playlist.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

import androidx.compose.runtime.Immutable

@Immutable
data class DiscoverUiState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val tracks: List<GeneratedTrack> = emptyList(),
    val error: String? = null,
    val isRefreshing: Boolean = false,
    val saveResultMessage: String? = null,
)

/** Faithful port of discover.js (§7): infinite-scroll feed, pull-to-
 *  refresh, Surprise Me, and Save As Playlist (order-preserving snapshot
 *  of exactly what's currently rendered, with duplicate-signature
 *  detection). */
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val repository: DiscoverRepository,
    private val playlistRepository: PlaylistRepository,
    private val artworkRepository: ArtworkRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.feed.collect { feed ->
                if (feed.isNotEmpty() || _uiState.value.tracks.isNotEmpty()) {
                    _uiState.update {
                        it.copy(isLoading = if (feed.isNotEmpty()) false else it.isLoading, tracks = feed)
                    }
                }
            }
        }
        loadInitial()
    }

    fun loadInitial() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val cached = repository.allowedCachedFeed()
                val needed = (INITIAL_BATCH_SIZE - cached.size).coerceAtLeast(0)
                val batch = if (needed > 0) repository.nextBatch(needed) else emptyList()
                val feed = repository.getCachedFeed()
                _uiState.update { it.copy(isLoading = false, tracks = feed) }
                artworkRepository.enrichBatch((cached + batch).take(INITIAL_BATCH_SIZE).map { it.name to it.artist })
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Couldn't load recommendations") }
            }
        }
    }

    fun loadMore() {
        if (_uiState.value.isLoadingMore) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            try {
                val more = repository.nextBatch(PAGE_SIZE)
                _uiState.update { it.copy(isLoadingMore = false, tracks = repository.getCachedFeed()) }
                artworkRepository.enrichBatch(more.map { it.name to it.artist })
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            repository.reset()
            try {
                repository.nextBatch(INITIAL_BATCH_SIZE)
                _uiState.update { it.copy(isRefreshing = false, tracks = repository.getCachedFeed()) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isRefreshing = false, error = e.message) }
            }
        }
    }

    fun surpriseMe() {
        viewModelScope.launch {
            repository.reset()
            _uiState.update { it.copy(isLoading = true) }
            try {
                repository.nextBatch(INITIAL_BATCH_SIZE)
                _uiState.update { it.copy(isLoading = false, tracks = repository.getCachedFeed()) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /** §7.3 Save As Playlist: snapshots exactly what's currently rendered,
     *  no re-fetch. Order-preserving duplicate-signature guard. */
    fun saveAsPlaylist() {
        val tracks = _uiState.value.tracks
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            val signature = playlistRepository.discoverSignature(tracks)
            val existing = playlistRepository.findByDiscoverSignature(signature)
            if (existing != null) {
                _uiState.update { it.copy(saveResultMessage = "Already saved as \"${existing.title}\"") }
                return@launch
            }
            val title = PlaylistNamer.generateUniqueName(playlistRepository.titles())
            val subtitle = PlaylistNamer.subtitleFor("discover")
            playlistRepository.save(title, subtitle, "discover", tracks, discoverSignature = signature)
            _uiState.update { it.copy(saveResultMessage = "Saved as \"$title\"") }
        }
    }

    fun dismissSaveResult() = _uiState.update { it.copy(saveResultMessage = null) }
    fun dismissError() = _uiState.update { it.copy(error = null) }

    private companion object {
        const val INITIAL_BATCH_SIZE = 16
        const val PAGE_SIZE = 12
    }
}
