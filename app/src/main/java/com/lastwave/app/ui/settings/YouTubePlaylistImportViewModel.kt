package com.lastwave.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubePlaylistResult
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.data.playlist.PlaylistImportManager
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class ImportTab(val title: String) {
    LIBRARY("Your Library"),
    SEARCH("YouTube Music"),
    LINK("Direct Link"),
    CSV("CSV File"),
}

data class YouTubeImportUiState(
    val selectedTab: ImportTab = ImportTab.SEARCH,
    val query: String = "",
    val directLink: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<YouTubePlaylistSummary> = emptyList(),
    /** Playlists from the connected YouTube Music account's own library. */
    val libraryPlaylists: List<YouTubePlaylistSummary> = emptyList(),
    val isLoadingLibrary: Boolean = false,
    val ytConnected: Boolean = false,
    val ytAccountName: String = "",
    val previewPlaylist: YouTubePlaylistResult? = null,
    val isPreviewLoading: Boolean = false,
    val selectedPlaylistIds: Set<String> = emptySet(),
    val isImporting: Boolean = false,
    val importProgress: String? = null,
    val errorMessage: String? = null,
    val importedCount: Int = 0,
    val csvFilename: String? = null,
    val isCsvImporting: Boolean = false,
)

@HiltViewModel
class YouTubePlaylistImportViewModel @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
    private val importManager: PlaylistImportManager,
    private val ytAuthManager: YtMusicAuthManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(YouTubeImportUiState())
    val uiState: StateFlow<YouTubeImportUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            ytAuthManager.connection.collect { connection ->
                val wasConnected = _uiState.value.ytConnected
                val nowConnected = connection.isConnected
                _uiState.update {
                    it.copy(ytConnected = nowConnected, ytAccountName = connection.accountName)
                }
                if (nowConnected && !wasConnected) {
                    // Freshly connected (or reconnected): land on the account's
                    // own library so the "select & import" flow is front and center.
                    if (_uiState.value.selectedTab == ImportTab.SEARCH && _uiState.value.searchResults.isEmpty()) {
                        selectTab(ImportTab.LIBRARY)
                    }
                    loadLibrary()
                }
            }
        }
        // Initial popular playlists search for instant inspiration
        search("Top Hits 2024")
    }

    fun selectTab(tab: ImportTab) {
        _uiState.update { it.copy(selectedTab = tab, errorMessage = null) }
        if (tab == ImportTab.LIBRARY && _uiState.value.libraryPlaylists.isEmpty() &&
            !_uiState.value.isLoadingLibrary
        ) {
            loadLibrary()
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query, errorMessage = null) }
    }

    fun onDirectLinkChange(link: String) {
        _uiState.update { it.copy(directLink = link, errorMessage = null) }
    }

    fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun loadLibrary(force: Boolean = false) {
        if (!_uiState.value.ytConnected) return
        if (_uiState.value.isLoadingLibrary) return
        if (!force && _uiState.value.libraryPlaylists.isNotEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingLibrary = true, errorMessage = null) }
            try {
                val playlists = withContext(Dispatchers.IO) { innerTube.fetchLibraryPlaylists() }
                _uiState.update { current ->
                    current.copy(isLoadingLibrary = false, libraryPlaylists = playlists)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingLibrary = false,
                        errorMessage = "Couldn't load your YouTube Music playlists: ${e.localizedMessage ?: e.message}",
                    )
                }
            }
        }
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

    fun resolveDirectLink(link: String = _uiState.value.directLink) {
        if (link.isBlank()) return
        val clean = link.trim()
        val extractedId = innerTube.extractPlaylistId(clean)
        loadPreview(extractedId)
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
                            errorMessage = if (_uiState.value.ytConnected) {
                                "Couldn't load this playlist. If it's not yours, check that sharing is enabled."
                            } else {
                                "Couldn't load playlist songs. Public/unlisted links work anonymously — connect a YouTube Music account in Settings to import your own."
                            },
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

    /** Select/deselect all across the ACTIVE tab's list (library or search). */
    fun selectAll(select: Boolean) {
        _uiState.update { current ->
            val activeList = when (current.selectedTab) {
                ImportTab.LIBRARY -> current.libraryPlaylists
                else -> current.searchResults
            }
            val activeIds = activeList.map { it.id }.toSet()
            val updated =
                if (select) current.selectedPlaylistIds + activeIds
                else current.selectedPlaylistIds - activeIds
            current.copy(selectedPlaylistIds = updated)
        }
    }

    fun importSelected(onSuccess: (List<SavedPlaylist>) -> Unit) {
        val selectedIds = _uiState.value.selectedPlaylistIds
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, importProgress = "Starting import...") }
            val savedList = mutableListOf<SavedPlaylist>()

            try {
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
            } catch (e: CancellationException) {
                throw e
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

    fun importCsv(
        inputStream: java.io.InputStream,
        filename: String,
        onSuccess: (SavedPlaylist) -> Unit,
    ) {
        val isM3u = filename.endsWith(".m3u", ignoreCase = true) || filename.endsWith(".m3u8", ignoreCase = true)
        val fileType = if (isM3u) "M3U" else "CSV"
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isCsvImporting = true,
                    csvFilename = filename,
                    importProgress = "Parsing & matching $fileType tracks...",
                    errorMessage = null,
                )
            }
            try {
                val (savedPlaylist, result) = inputStream.use {
                    importManager.importCsvStream(it, filename)
                }
                _uiState.update {
                    it.copy(
                        isCsvImporting = false,
                        importProgress = null,
                        importedCount = 1,
                    )
                }
                onSuccess(savedPlaylist)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isCsvImporting = false,
                        importProgress = null,
                        errorMessage = "$fileType import failed: ${e.localizedMessage ?: e.message}",
                    )
                }
            } catch (error: LinkageError) {
                _uiState.update {
                    it.copy(
                        isCsvImporting = false,
                        importProgress = null,
                        errorMessage = "$fileType import isn't supported by this ROM",
                    )
                }
            }
        }
    }
}
