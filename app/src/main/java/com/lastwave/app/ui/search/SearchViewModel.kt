package com.lastwave.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.search.SearchRepository
import com.lastwave.app.data.search.SearchResultItem
import com.lastwave.app.data.search.SearchTab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

import androidx.compose.runtime.Immutable

enum class SearchStatus { IDLE, LOADING, EMPTY, RESULTS }

@Immutable
data class SearchUiState(
    val query: String = "",
    val tab: SearchTab = SearchTab.TRACKS,
    val status: SearchStatus = SearchStatus.IDLE,
    val results: List<SearchResultItem> = emptyList(),
)

/** Port of search.js (§6): 350ms debounce, immediate on explicit search,
 *  stale-response guard, tab-switch re-search. */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var debounceJob: Job? = null
    private var lastIssuedQuery: String = ""

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
        debounceJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(status = SearchStatus.IDLE, results = emptyList()) }
            return
        }
        debounceJob = viewModelScope.launch {
            delay(350)
            runSearch(query)
        }
    }

    fun setTab(tab: SearchTab) {
        _uiState.update { it.copy(tab = tab) }
        val q = _uiState.value.query
        if (q.isNotBlank()) {
            debounceJob?.cancel()
            viewModelScope.launch { runSearch(q) }
        }
    }

    fun searchNow() {
        debounceJob?.cancel()
        val q = _uiState.value.query
        if (q.isBlank()) return
        viewModelScope.launch { runSearch(q) }
    }

    private suspend fun runSearch(query: String) {
        lastIssuedQuery = query
        _uiState.update { it.copy(status = SearchStatus.LOADING) }
        try {
            val results = repository.search(_uiState.value.tab, query)
            // Stale-response guard: discard if the user has typed something
            // new since this call was issued.
            if (lastIssuedQuery != query) return
            _uiState.update { it.copy(status = if (results.isEmpty()) SearchStatus.EMPTY else SearchStatus.RESULTS, results = results) }
        } catch (e: Exception) {
            if (lastIssuedQuery != query) return
            _uiState.update { it.copy(status = SearchStatus.EMPTY, results = emptyList()) }
        }
    }

    fun clearQuery() {
        debounceJob?.cancel()
        _uiState.update { it.copy(query = "", status = SearchStatus.IDLE, results = emptyList()) }
    }
}
