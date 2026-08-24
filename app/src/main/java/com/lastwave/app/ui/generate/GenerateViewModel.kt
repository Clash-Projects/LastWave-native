package com.lastwave.app.ui.generate

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.generate.GenerateRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.GenerationStatus
import com.lastwave.app.data.generate.RECOMMENDATION_TRACK_COUNT
import com.lastwave.app.data.naming.PlaylistNamer
import com.lastwave.app.data.playlist.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Port of generator.html's exact 8-mode list (data-mode attributes, in order). */
enum class GenerateMode(val label: String, val description: String, val storageValue: String) {
    TOP("Top Tracks", "Your most played tracks of all time", "top"),
    RECENT("Recent Tracks", "What you've been listening to lately", "recent"),
    SIMILAR_TRACKS("Similar Tracks", "Tracks similar to one you love", "similar-tracks"),
    SIMILAR_ARTISTS("Similar Artists", "Discover artists like your favourites", "similar-artists"),
    TAG("By Tag / Genre", "Browse by genre like rock, lofi, jazz", "tag"),
    MIX("My Mix", "Smart blend of top, recent & similar", "mix"),
    RECOMMENDATIONS("My Recommendation", "35 fresh tracks for you", "recommendations"),
    LIBRARY("My Library", "Re-discover the sounds of your past", "library"),
}

/** Exact 6 period chips shared by Top Tracks + My Library. */
val GENERATE_PERIODS = listOf(
    "overall" to "All Time",
    "12month" to "12 Months",
    "6month" to "6 Months",
    "3month" to "3 Months",
    "1month" to "1 Month",
    "7day" to "7 Days",
)

/** Exact 12 quick-select genre chips from generator.html's tag-suggestions. */
val GENRE_QUICK_CHIPS = listOf("pop", "rock", "hip-hop", "electronic", "jazz", "lofi", "metal", "indie", "classical", "r&b", "ambient", "punk")

@Immutable
data class GenerateUiState(
    val selectedMode: GenerateMode? = null,
    val trackCount: Int = 25,
    val period: String = "overall",
    val seedTrackName: String = "",
    val seedArtistName: String = "",
    val seedArtistQuery: String = "",
    val tagInput: String = "",
    val seedTrackResults: List<GeneratedTrack> = emptyList(),
    val seedArtistResults: List<String> = emptyList(),
    val isSearchingSeed: Boolean = false,
    val isGenerating: Boolean = false,
    val loadingMessage: String = "",
    val error: String? = null,
)

/** One-shot navigation signal. */
sealed interface GenerateNavEvent {
    data object NavigateToPlaylistLoading : GenerateNavEvent
}

@HiltViewModel
class GenerateViewModel @Inject constructor(
    private val repository: GenerateRepository,
    private val playlistRepository: PlaylistRepository,
    private val artworkRepository: com.lastwave.app.data.artwork.ArtworkRepository,
    private val generationStatus: GenerationStatus,
    private val mixLauncher: MixLauncher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GenerateUiState())
    val uiState: StateFlow<GenerateUiState> = _uiState.asStateFlow()

    private val _navEvents = MutableSharedFlow<GenerateNavEvent>(extraBufferCapacity = 1)
    val navEvents: SharedFlow<GenerateNavEvent> = _navEvents

    val lastSavedPlaylistId = MutableStateFlow<Long?>(null)

    init {
        // "Start Mix with this Song" (§6) landing here from any screen's
        // track menu: pre-fill Similar Tracks with the tapped song and
        // generate immediately — a real one-tap mix, not just a
        // pre-filled form waiting for another tap.
        viewModelScope.launch {
            mixLauncher.requests.collect { seed ->
                _uiState.update {
                    it.copy(
                        selectedMode = GenerateMode.SIMILAR_TRACKS,
                        seedTrackName = seed.trackName,
                        seedArtistName = seed.artistName,
                        error = null,
                    )
                }
                generate()
            }
        }
    }

    fun selectMode(mode: GenerateMode) {
        if (_uiState.value.isGenerating) return
        _uiState.update {
            if (it.selectedMode == mode) it.copy(selectedMode = null)
            else it.copy(selectedMode = mode, error = null, seedTrackResults = emptyList(), seedArtistResults = emptyList())
        }
    }

    fun setTrackCount(value: Int) { if (!_uiState.value.isGenerating) _uiState.update { it.copy(trackCount = value.coerceIn(5, 35)) } }
    fun setPeriod(value: String) = _uiState.update { it.copy(period = value) }
    fun setTagInput(value: String) = _uiState.update { it.copy(tagInput = value) }
    fun setSeedArtistQuery(value: String) = _uiState.update { it.copy(seedArtistQuery = value) }
    fun setSeedTrackName(value: String) = _uiState.update { it.copy(seedTrackName = value) }
    fun setSeedArtistName(value: String) = _uiState.update { it.copy(seedArtistName = value) }

    fun loadTopTracksForSeed() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSearchingSeed = true) }
            try {
                val results = repository.topTracksForSeed()
                _uiState.update { it.copy(isSearchingSeed = false, seedTrackResults = results) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSearchingSeed = false, error = e.message) }
            }
        }
    }

    fun loadTopArtistsForSeed() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSearchingSeed = true) }
            try {
                val results = repository.topArtistsForSeed()
                _uiState.update { it.copy(isSearchingSeed = false, seedArtistResults = results) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSearchingSeed = false, error = e.message) }
            }
        }
    }

    fun searchSeedTrack() {
        val state = _uiState.value
        if (state.seedTrackName.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSearchingSeed = true) }
            try {
                val results = repository.searchTracks(state.seedTrackName, state.seedArtistName.ifBlank { null })
                _uiState.update { it.copy(isSearchingSeed = false, seedTrackResults = results) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSearchingSeed = false, error = e.message) }
            }
        }
    }

    fun searchSeedArtist() {
        val state = _uiState.value
        if (state.seedArtistQuery.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSearchingSeed = true) }
            try {
                val results = repository.searchArtists(state.seedArtistQuery)
                _uiState.update { it.copy(isSearchingSeed = false, seedArtistResults = results) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSearchingSeed = false, error = e.message) }
            }
        }
    }

    fun pickSeedTrack(track: GeneratedTrack) = _uiState.update { it.copy(seedTrackName = track.name, seedArtistName = track.artist, seedTrackResults = emptyList()) }
    fun pickSeedArtist(name: String) = _uiState.update { it.copy(seedArtistQuery = name, seedArtistResults = emptyList()) }
    fun setGenreChip(tag: String) = _uiState.update { it.copy(tagInput = tag) }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun generate() {
        val state = _uiState.value
        if (state.isGenerating) return
        val mode = state.selectedMode ?: return

        when (mode) {
            GenerateMode.SIMILAR_TRACKS -> if (state.seedTrackName.isBlank() || state.seedArtistName.isBlank()) {
                _uiState.update { it.copy(error = "Enter a seed track and artist") }; return
            }
            GenerateMode.SIMILAR_ARTISTS -> if (state.seedArtistQuery.isBlank()) {
                _uiState.update { it.copy(error = "Enter a seed artist") }; return
            }
            GenerateMode.TAG -> if (state.tagInput.isBlank()) {
                _uiState.update { it.copy(error = "Enter a genre/tag") }; return
            }
            else -> Unit
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, error = null, loadingMessage = "Analyzing taste profile\u2026") }
            generationStatus.update(isGenerating = true, message = "Analyzing taste profile\u2026")
            try {
                val onProgress: (String) -> Unit = { msg ->
                    _uiState.update { s -> s.copy(loadingMessage = msg) }
                    generationStatus.update(isGenerating = true, message = msg)
                }

                onProgress("Gathering recommendations\u2026")

                val targetCount = if (mode == GenerateMode.RECOMMENDATIONS) {
                    RECOMMENDATION_TRACK_COUNT
                } else {
                    state.trackCount
                }

                val raw: List<GeneratedTrack> = when (mode) {
                    GenerateMode.TOP -> repository.fetchTopTracks(targetCount, state.period)
                    GenerateMode.LIBRARY -> repository.fetchTopTracks(targetCount, state.period)
                    GenerateMode.RECENT -> repository.fetchRecentTracks(targetCount)
                    GenerateMode.SIMILAR_TRACKS -> repository.fetchSimilarTracks(state.seedTrackName, state.seedArtistName, targetCount)
                    GenerateMode.SIMILAR_ARTISTS -> repository.fetchSimilarArtistTracks(state.seedArtistQuery, targetCount)
                    GenerateMode.TAG -> repository.fetchTagTracks(state.tagInput, targetCount)
                    GenerateMode.MIX -> repository.fetchMix(targetCount, onProgress)
                    GenerateMode.RECOMMENDATIONS -> repository.fetchRecommendations(targetCount, onProgress)
                }

                onProgress("Pre-checking availability\u2026")
                val finalTracks = if (mode == GenerateMode.RECOMMENDATIONS) {
                    // RecommendationEngine already applies its own diversity
                    // stages. Do not discard fresh tracks afterward via the
                    // generic per-artist cap and accidentally save under 35.
                    repository.deduplicate(raw).take(targetCount)
                } else {
                    repository.precheck(raw).take(targetCount)
                }
                if (finalTracks.isEmpty()) {
                    val message = if (mode == GenerateMode.SIMILAR_TRACKS && state.seedTrackName.isNotBlank()) {
                        "No similar songs found to mix for \"${state.seedTrackName}\"."
                    } else if (mode == GenerateMode.SIMILAR_ARTISTS && state.seedArtistQuery.isNotBlank()) {
                        "No similar artists found for \"${state.seedArtistQuery}\"."
                    } else if (mode == GenerateMode.TAG && state.tagInput.isNotBlank()) {
                        "No songs found for tag \"${state.tagInput}\"."
                    } else {
                        "No songs found to create this mix."
                    }
                    throw IllegalStateException(message)
                }

                onProgress("Saving playlist\u2026")
                val existingTitles = playlistRepository.titles()
                val title = PlaylistNamer.generateUniqueName(existingTitles)
                val subtitle = PlaylistNamer.subtitleFor(
                    mode = mode.storageValue,
                    tagInput = state.tagInput,
                    seedTrackName = state.seedTrackName,
                    seedArtistInput = state.seedArtistQuery,
                )
                val saved = playlistRepository.save(title, subtitle, mode.storageValue, finalTracks)
                lastSavedPlaylistId.value = saved.id

                // Playlist is persisted — done from the user's point of view.
                // Cover-art enrichment keeps running in the background and
                // updates rows reactively via ArtworkRepository's own flow,
                // so we don't make the user wait on it before showing the result.
                _uiState.update { it.copy(isGenerating = false) }
                generationStatus.update(isGenerating = false)
                viewModelScope.launch {
                    try {
                        artworkRepository.enrichBatch(finalTracks.take(8).map { it.name to it.artist })
                    } catch (e: Exception) { }
                }

                _navEvents.tryEmit(GenerateNavEvent.NavigateToPlaylistLoading)
            } catch (e: Exception) {
                _uiState.update { it.copy(isGenerating = false, error = e.message ?: "Couldn't generate a playlist") }
                generationStatus.update(isGenerating = false)
            }
        }
    }
}
