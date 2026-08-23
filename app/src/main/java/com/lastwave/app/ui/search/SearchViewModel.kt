package com.lastwave.app.ui.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.search.SearchHistoryRepository
import com.lastwave.app.data.search.SearchRepository
import com.lastwave.app.data.search.SearchResultItem
import com.lastwave.app.data.search.SearchTab
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SearchStatus { IDLE, LOADING, EMPTY, RESULTS }

@Immutable
data class SearchUiState(
    val query: String = "",
    val tab: SearchTab = SearchTab.TRACKS,
    val status: SearchStatus = SearchStatus.IDLE,
    val results: List<SearchResultItem> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val isShowingSuggestions: Boolean = false,
)

/**
 * YouTube Music & Last.fm search with live auto-complete suggestions,
 * persistent search history, debounced search, and multi-tab results.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
    private val historyRepository: SearchHistoryRepository,
    private val musicPlayer: MusicPlayer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var debounceJob: Job? = null
    private var suggestionsJob: Job? = null
    private var lastIssuedQuery: String = ""

    init {
        viewModelScope.launch {
            historyRepository.history.collect { history ->
                _uiState.update { it.copy(recentSearches = history) }
            }
        }
    }

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query, isShowingSuggestions = query.isNotBlank()) }
        debounceJob?.cancel()
        suggestionsJob?.cancel()

        if (query.isBlank()) {
            _uiState.update {
                it.copy(
                    status = SearchStatus.IDLE,
                    results = emptyList(),
                    suggestions = emptyList(),
                    isShowingSuggestions = false,
                )
            }
            return
        }

        // Fast suggestions debounce (120ms)
        suggestionsJob = viewModelScope.launch {
            delay(120)
            val suggestions = repository.getSuggestions(query)
            if (_uiState.value.query == query) {
                _uiState.update { it.copy(suggestions = suggestions) }
            }
        }

        // Full search results debounce (400ms)
        debounceJob = viewModelScope.launch {
            delay(400)
            runSearch(query, saveToHistory = false)
        }
    }

    fun setTab(tab: SearchTab) {
        _uiState.update { it.copy(tab = tab, isShowingSuggestions = false) }
        val q = _uiState.value.query
        if (q.isNotBlank()) {
            debounceJob?.cancel()
            suggestionsJob?.cancel()
            viewModelScope.launch { runSearch(q, saveToHistory = false) }
        }
    }

    fun searchNow() {
        val q = _uiState.value.query
        if (q.isBlank()) return
        executeSearch(q)
    }

    fun executeSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        debounceJob?.cancel()
        suggestionsJob?.cancel()
        historyRepository.add(trimmed)
        _uiState.update {
            it.copy(
                query = trimmed,
                isShowingSuggestions = false,
            )
        }
        viewModelScope.launch { runSearch(trimmed, saveToHistory = true) }
    }

    fun removeRecentSearch(query: String) {
        historyRepository.remove(query)
    }

    fun clearRecentSearches() {
        historyRepository.clear()
    }

    fun dismissSuggestions() {
        _uiState.update { it.copy(isShowingSuggestions = false) }
    }

    fun playResult(item: SearchResultItem) {
        val tab = _uiState.value.tab
        when (tab) {
            SearchTab.TRACKS -> {
                val results = _uiState.value.results
                val queue = results.map { result ->
                    PlayableTrack(
                        title = result.name,
                        artist = result.artist.orEmpty(),
                        album = result.subtitle,
                        artworkUrl = result.artworkUrl,
                        videoId = result.videoId,
                    )
                }
                musicPlayer.playQueue(queue, results.indexOf(item).coerceAtLeast(0), sourceLabel = "Search")
            }
            SearchTab.ARTISTS, SearchTab.ALBUMS -> viewModelScope.launch {
                val tracks = runCatching { repository.songsFor(item) }.getOrDefault(emptyList())
                if (tracks.isNotEmpty()) {
                    musicPlayer.playQueue(tracks.map { track ->
                        PlayableTrack(
                            title = track.title,
                            artist = track.artist.takeUnless { it == "Unknown artist" } ?: item.artist ?: item.name,
                            album = track.album ?: if (tab == SearchTab.ALBUMS) item.name else null,
                            artworkUrl = track.artworkUrl ?: item.artworkUrl,
                            videoId = track.videoId,
                        )
                    }, sourceLabel = "Search")
                }
            }
            SearchTab.USERS -> Unit
        }
    }

    private suspend fun runSearch(query: String, saveToHistory: Boolean) {
        lastIssuedQuery = query
        _uiState.update { it.copy(status = SearchStatus.LOADING) }
        if (saveToHistory) {
            historyRepository.add(query)
        }
        try {
            val results = repository.search(_uiState.value.tab, query)
            // Stale-response guard: discard if the user has typed something
            // new since this call was issued.
            if (lastIssuedQuery != query) return
            _uiState.update {
                it.copy(
                    status = if (results.isEmpty()) SearchStatus.EMPTY else SearchStatus.RESULTS,
                    results = results,
                )
            }
        } catch (e: Exception) {
            if (lastIssuedQuery != query) return
            _uiState.update { it.copy(status = SearchStatus.EMPTY, results = emptyList()) }
        }
    }

    fun clearQuery() {
        debounceJob?.cancel()
        suggestionsJob?.cancel()
        _uiState.update {
            it.copy(
                query = "",
                status = SearchStatus.IDLE,
                results = emptyList(),
                suggestions = emptyList(),
                isShowingSuggestions = false,
            )
        }
    }
}
