package com.lastwave.app.ui.genres

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.generate.GenerateRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.GenreStat
import com.lastwave.app.data.generate.GenresRepository
import com.lastwave.app.data.naming.PlaylistNamer
import com.lastwave.app.data.playlist.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Exact period options from genres.html's dropdown. */
val GENRE_PERIODS = listOf("7day" to "Past 7 Days", "1month" to "This Month", "12month" to "Last 12 Months", "overall" to "Overall")

enum class GenreDetailSort { POPULAR, NEWEST, AZ }

@Immutable
data class GenresUiState(
    val isLoading: Boolean = true,
    val period: String = "overall",
    val stats: List<GenreStat> = emptyList(),
    val error: String? = null,
    // Detail sheet
    val detailGenre: String? = null,
    val detailTracks: List<GeneratedTrack> = emptyList(),
    val detailLoading: Boolean = false,
    val detailPage: Int = 1,
    val detailSort: GenreDetailSort = GenreDetailSort.POPULAR,
    val detailHasMore: Boolean = true,
    val navigateToPlaylist: Boolean = false,
)

@HiltViewModel
class GenresViewModel @Inject constructor(
    private val genresRepository: GenresRepository,
    private val generateRepository: GenerateRepository,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GenresUiState())
    val uiState: StateFlow<GenresUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun setPeriod(period: String) {
        _uiState.update { it.copy(period = period) }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val stats = genresRepository.fetchGenreStats(_uiState.value.period)
                _uiState.update { it.copy(isLoading = false, stats = stats) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Couldn't load genres") }
            }
        }
    }

    fun openDetail(genre: String) {
        _uiState.update { it.copy(detailGenre = genre, detailTracks = emptyList(), detailPage = 1, detailHasMore = true) }
        loadDetailPage(reset = true)
    }

    fun closeDetail() = _uiState.update { it.copy(detailGenre = null, detailTracks = emptyList()) }

    fun setDetailSort(sort: GenreDetailSort) {
        _uiState.update { it.copy(detailSort = sort) }
        if (sort == GenreDetailSort.AZ) {
            _uiState.update { it.copy(detailTracks = it.detailTracks.sortedBy { t -> t.name.lowercase() }) }
        }
    }

    fun loadDetailPage(reset: Boolean = false) {
        val genre = _uiState.value.detailGenre ?: return
        if (_uiState.value.detailLoading) return
        if (!reset && !_uiState.value.detailHasMore) return
        viewModelScope.launch {
            _uiState.update { it.copy(detailLoading = true) }
            try {
                val page = if (reset) 1 else _uiState.value.detailPage
                val fetched = genresRepository.fetchGenreTracks(genre, page)
                _uiState.update { s ->
                    val merged = if (reset) fetched else s.detailTracks + fetched
                    val ordered = if (s.detailSort == GenreDetailSort.AZ) merged.sortedBy { it.name.lowercase() } else merged
                    s.copy(detailTracks = ordered, detailLoading = false, detailPage = page + 1, detailHasMore = fetched.isNotEmpty())
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(detailLoading = false, detailHasMore = false) }
            }
        }
    }

    /** §5.3's "Start Mix" — reuses Generator's exact tag fetch path. */
    fun startMix(genre: String) {
        viewModelScope.launch {
            try {
                val tracks = generateRepository.fetchTagTracks(genre, 25)
                val finalTracks = generateRepository.precheck(tracks).take(25)
                generateRepository.markAsSeen(finalTracks)
                val title = PlaylistNamer.generateUniqueName(playlistRepository.titles())
                val subtitle = PlaylistNamer.subtitleFor("tag", tagInput = genre)
                playlistRepository.save(title, subtitle, "tag", finalTracks)
                _uiState.update { it.copy(navigateToPlaylist = true, detailGenre = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    /** §5.5 "Discover More". */
    fun discoverMore(genre: String) {
        viewModelScope.launch {
            try {
                val tracks = genresRepository.discoverMore(genre)
                val title = PlaylistNamer.generateUniqueName(playlistRepository.titles())
                val subtitle = PlaylistNamer.subtitleFor("tag", tagInput = genre)
                playlistRepository.save(title, subtitle, "tag", tracks)
                _uiState.update { it.copy(navigateToPlaylist = true, detailGenre = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    /** §5.4 "Explore This Genre" — reachable from any track's context menu
     *  app-wide, not just from within this screen. */
    fun exploreGenre(genre: String) {
        viewModelScope.launch {
            try {
                val tracks = genresRepository.explorePersonalizedGenre(genre)
                val title = PlaylistNamer.generateUniqueName(playlistRepository.titles())
                val subtitle = PlaylistNamer.subtitleFor("tag", tagInput = genre)
                playlistRepository.save(title, subtitle, "tag", tracks)
                _uiState.update { it.copy(navigateToPlaylist = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun consumeNavigateToPlaylist() = _uiState.update { it.copy(navigateToPlaylist = false) }
    fun dismissError() = _uiState.update { it.copy(error = null) }
}
