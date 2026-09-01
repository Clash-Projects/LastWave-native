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
import kotlinx.coroutines.CancellationException
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
    private var searchQueueJob: Job? = null

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
        val tab = _uiState.value.tab
        debounceJob = viewModelScope.launch {
            delay(400)
            runSearch(query, tab, saveToHistory = false)
        }
    }

    fun setTab(tab: SearchTab) {
        if (tab == _uiState.value.tab) return
        _uiState.update { it.copy(tab = tab, isShowingSuggestions = false) }
        val q = _uiState.value.query
        if (q.isNotBlank()) {
            debounceJob?.cancel()
            suggestionsJob?.cancel()
            debounceJob = viewModelScope.launch { runSearch(q, tab, saveToHistory = false) }
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
        _uiState.update {
            it.copy(
                query = trimmed,
                isShowingSuggestions = false,
            )
        }
        val tab = _uiState.value.tab
        debounceJob = viewModelScope.launch {
            runSearch(trimmed, tab, saveToHistory = true)
        }
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
        searchQueueJob?.cancel()
        val tab = _uiState.value.tab
        when (tab) {
            SearchTab.TRACKS -> {
                val selected = PlayableTrack(
                    title = item.name,
                    artist = item.artist.orEmpty(),
                    album = item.subtitle,
                    artworkUrl = item.artworkUrl,
                    videoId = item.videoId,
                )
                // Start immediately. Recommendations load in the background
                // and are appended only if this is still the active track.
                musicPlayer.play(selected, sourceLabel = "Search")
                searchQueueJob = viewModelScope.launch {
                    val related = repository.similarSongsFor(item)
                    musicPlayer.appendSearchRecommendations(
                        seed = selected,
                        tracks = related.map { track ->
                            PlayableTrack(
                                title = track.name,
                                artist = track.artist,
                                album = track.album,
                                artworkUrl = track.artworkUrl,
                            )
                        },
                    )
                }
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

    private suspend fun runSearch(query: String, tab: SearchTab, saveToHistory: Boolean) {
        _uiState.update { it.copy(status = SearchStatus.LOADING) }
        if (saveToHistory) {
            historyRepository.add(query)
        }
        try {
            val results = repository.search(tab, query)
            if (_uiState.value.query != query || _uiState.value.tab != tab) return
            _uiState.update {
                it.copy(
                    status = if (results.isEmpty()) SearchStatus.EMPTY else SearchStatus.RESULTS,
                    results = results,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            if (_uiState.value.query != query || _uiState.value.tab != tab) return
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
