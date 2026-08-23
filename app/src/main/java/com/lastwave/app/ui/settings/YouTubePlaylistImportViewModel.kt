package com.lastwave.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubePlaylistResult
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.data.playlist.PlaylistImportManager
import com.lastwave.app.data.playlist.SavedPlaylist
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class YouTubeImportUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<YouTubePlaylistSummary> = emptyList(),
    val previewPlaylist: YouTubePlaylistResult? = null,
    val isPreviewLoading: Boolean = false,
    val selectedPlaylistIds: Set<String> = emptySet(),
    val isImporting: Boolean = false,
    val importProgress: String? = null,
    val errorMessage: String? = null,
    val importedCount: Int = 0,
)

@HiltViewModel
class YouTubePlaylistImportViewModel @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
    private val importManager: PlaylistImportManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(YouTubeImportUiState())
    val uiState: StateFlow<YouTubeImportUiState> = _uiState.asStateFlow()

    init {
        // Initial popular playlists search for instant inspiration
        search("Top Hits 2024")
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query, errorMessage = null) }
    }

    fun search(query: String = _uiState.value.query) {
        if (query.isBlank()) return
        val clean = query.trim()

        // Check if query is a direct YouTube link or playlist ID
        val extractedId = innerTube.extractPlaylistId(clean)
        if (extractedId.startsWith("PL") || extractedId.startsWith("VL") || extractedId.startsWith("RDCLAK") || clean.contains("list=")) {
            loadPreview(extractedId)
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, errorMessage = null) }
            try {
                val results = innerTube.searchPlaylists(clean)
                _uiState.update {
                    it.copy(
                        searchResults = results,
                        isSearching = false,
                        errorMessage = if (results.isEmpty()) "No playlists found for \"$clean\"" else null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        errorMessage = e.localizedMessage ?: "Failed to search playlists",
                    )
                }
            }
        }
    }

    fun loadPreview(playlistIdOrUrl: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isPreviewLoading = true, errorMessage = null) }
            try {
                val result = innerTube.fetchPlaylist(playlistIdOrUrl)
                if (result != null && result.tracks.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            previewPlaylist = result,
                            isPreviewLoading = false,
                            selectedPlaylistIds = it.selectedPlaylistIds + result.id,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isPreviewLoading = false,
                            errorMessage = "Couldn't load playlist songs. Ensure the link is public or unlisted.",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isPreviewLoading = false,
                        errorMessage = e.localizedMessage ?: "Failed to load playlist preview",
                    )
                }
            }
        }
    }

    fun dismissPreview() {
        _uiState.update { it.copy(previewPlaylist = null) }
    }

    fun togglePlaylistSelection(playlistId: String) {
        _uiState.update {
            val current = it.selectedPlaylistIds
            val updated = if (playlistId in current) current - playlistId else current + playlistId
            it.copy(selectedPlaylistIds = updated)
        }
    }

    fun selectAll(select: Boolean) {
        _uiState.update {
            val updated = if (select) it.searchResults.map { pl -> pl.id }.toSet() else emptySet()
            it.copy(selectedPlaylistIds = updated)
        }
    }

    fun importSelected(onSuccess: (List<SavedPlaylist>) -> Unit) {
        val selectedIds = _uiState.value.selectedPlaylistIds
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, importProgress = "Starting import...") }
            val savedList = mutableListOf<SavedPlaylist>()

            try {
                // If preview is active and is the only one selected
                val preview = _uiState.value.previewPlaylist
                if (preview != null && selectedIds.contains(preview.id) && selectedIds.size == 1) {
                    val saved = importManager.importYouTubePlaylist(preview)
                    savedList.add(saved)
                } else {
                    var count = 0
                    for (id in selectedIds) {
                        count++
                        _uiState.update { it.copy(importProgress = "Importing playlist $count of ${selectedIds.size}...") }
                        val playlistResult = innerTube.fetchPlaylist(id)
                        if (playlistResult != null && playlistResult.tracks.isNotEmpty()) {
                            val saved = importManager.importYouTubePlaylist(playlistResult)
                            savedList.add(saved)
                        }
                    }
                }

                _uiState.update {
                    it.copy(
                        isImporting = false,
                        importProgress = null,
                        importedCount = savedList.size,
                    )
                }
                onSuccess(savedList)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        importProgress = null,
                        errorMessage = "Import failed: ${e.localizedMessage ?: e.message}",
                    )
                }
            }
        }
    }
}
