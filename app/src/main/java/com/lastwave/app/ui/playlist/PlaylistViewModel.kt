package com.lastwave.app.ui.playlist

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.artwork.ArtworkRepository
import com.lastwave.app.data.generate.GenerateRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.naming.PlaylistNamer
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.PlaylistExportEvents
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.data.repository.AuthRepository
import com.lastwave.app.util.FileExportHelper
import com.lastwave.app.util.PlaylistExportFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ExportFormat { CSV, M3U }
enum class PlaylistSortMode { DATE_DESC, DATE_ASC, NAME, TRACK_COUNT }

@Immutable
data class PlaylistUiState(
    val isLoading: Boolean = true,
    val playlists: List<SavedPlaylist> = emptyList(),
    val sortMode: PlaylistSortMode = PlaylistSortMode.DATE_DESC,
    val expandedIds: Set<Long> = emptySet(),
    val newestId: Long? = null,
    val justSavedBannerVisible: Boolean = false,
    val exportSheetForPlaylistId: Long? = null,
    val deleteConfirmForPlaylistId: Long? = null,
    val isGeneratingSimilarFor: Long? = null,
    val regeneratingId: Long? = null,
    val toastMessage: String? = null,
    val deleteScrobbleAuthRequired: Boolean = false,
    val isGenerating: Boolean = false,
    val generatingMessage: String = "",
)

/**
 * Full port of playlist.js's saved-playlist screen state: list + expand/
 * collapse + the "just generated" regenerate bar (§4.2) + export (§4.6) +
 * Generate Similar (§4.7) + delete. Reads/writes through PlaylistRepository
 * (Room), so anything GenerateViewModel saves shows up here automatically
 * on next load() — this ViewModel calls load() from init and whenever the
 * screen becomes visible again (the Composable re-triggers it via a
 * lifecycle-aware LaunchedEffect key, not polling).
 */
@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val generateRepository: GenerateRepository,
    private val artworkRepository: ArtworkRepository,
    private val authRepository: AuthRepository,
    private val fileExportHelper: FileExportHelper,
    private val generationStatus: com.lastwave.app.data.generate.GenerationStatus,
    private val exportEvents: PlaylistExportEvents,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaylistUiState())
    val uiState: StateFlow<PlaylistUiState> = _uiState.asStateFlow()

    init {
        load()
        // Mirrors the same generation state Generate screen shows, so the
        // progress card here is never a second copy of that logic — and
        // reloads the list the instant a generation finishes, so a newly
        // saved playlist appears without waiting for the tab to be revisited.
        viewModelScope.launch {
            var wasGenerating = false
            generationStatus.state.collect { progress ->
                _uiState.update { it.copy(isGenerating = progress.isGenerating, generatingMessage = progress.message) }
                if (wasGenerating && !progress.isGenerating) {
                    load()
                }
                wasGenerating = progress.isGenerating
            }
        }
        viewModelScope.launch {
            exportEvents.failures.collect { message ->
                _uiState.update { it.copy(toastMessage = message) }
            }
        }
    }

    /** Re-reads from Room. Called on first composition and again whenever
     *  the Playlist tab regains visibility (e.g. right after Generate
     *  saves a new playlist) — see PlaylistScreen's LaunchedEffect. */
    fun load(justGeneratedId: Long? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val all = playlistRepository.getAll()
            val newest = justGeneratedId ?: all.maxByOrNull { it.createdAtMillis }?.id
            _uiState.update {
                it.copy(
                    isLoading = false,
                    playlists = all,
                    newestId = newest,
                    expandedIds = if (justGeneratedId != null) setOf(justGeneratedId) else it.expandedIds,
                    justSavedBannerVisible = justGeneratedId != null,
                )
            }
            if (justGeneratedId != null) {
                // Pre-enrich the first few tracks of the newest card so its
                // cover grid isn't empty on first render (§4.3).
                all.firstOrNull { it.id == justGeneratedId }?.let { pl ->
                    artworkRepository.enrichBatch(pl.tracks.take(6).map { it.name to it.artist })
                }
            }
        }
    }

    fun setSortMode(mode: PlaylistSortMode) {
        _uiState.update { s ->
            val sorted = when (mode) {
                PlaylistSortMode.DATE_DESC -> s.playlists.sortedByDescending { it.createdAtMillis }
                PlaylistSortMode.DATE_ASC -> s.playlists.sortedBy { it.createdAtMillis }
                PlaylistSortMode.NAME -> s.playlists.sortedBy { it.title.lowercase() }
                PlaylistSortMode.TRACK_COUNT -> s.playlists.sortedByDescending { it.tracks.size }
            }
            s.copy(sortMode = mode, playlists = sorted)
        }
    }

    fun regenerateLatest() {
        val newest = _uiState.value.playlists.firstOrNull() ?: return
        regenerate(newest.id)
    }

    fun dismissJustSavedBanner() = _uiState.update { it.copy(justSavedBannerVisible = false) }

    fun toggleExpanded(id: Long) {
        _uiState.update { s ->
            val next = s.expandedIds.toMutableSet()
            if (id in next) next.remove(id) else next.add(id)
            s.copy(expandedIds = next)
        }
    }

    fun requestDelete(id: Long) = _uiState.update { it.copy(deleteConfirmForPlaylistId = id) }
    fun dismissDeleteConfirm() = _uiState.update { it.copy(deleteConfirmForPlaylistId = null) }

    fun confirmDelete() {
        val id = _uiState.value.deleteConfirmForPlaylistId ?: return
        viewModelScope.launch {
            playlistRepository.delete(id)
            _uiState.update { it.copy(deleteConfirmForPlaylistId = null) }
            load()
        }
    }

    fun openExportSheet(id: Long) = _uiState.update { it.copy(exportSheetForPlaylistId = id) }
    fun dismissExportSheet() = _uiState.update { it.copy(exportSheetForPlaylistId = null) }

    fun exportSave(id: Long, format: ExportFormat) {
        val playlist = _uiState.value.playlists.firstOrNull { it.id == id } ?: return
        val filename = exportFilename(playlist, format)
        val content = exportContent(playlist, format)
        fileExportHelper.saveToDocuments(filename, content)
        _uiState.update { it.copy(exportSheetForPlaylistId = null, toastMessage = "Saved $filename") }
    }

    fun exportShare(id: Long, format: ExportFormat) {
        val playlist = _uiState.value.playlists.firstOrNull { it.id == id } ?: return
        val filename = exportFilename(playlist, format)
        val content = exportContent(playlist, format)
        val mime = if (format == ExportFormat.CSV) "text/csv" else "audio/x-mpegurl"
        fileExportHelper.shareFile(filename, content, mime)
        _uiState.update { it.copy(exportSheetForPlaylistId = null) }
    }

    private fun exportFilename(playlist: SavedPlaylist, format: ExportFormat): String {
        val safeTitle = fileExportHelper.sanitizeFilename(playlist.title)
        return when (format) {
            ExportFormat.CSV -> "$safeTitle.csv"
            ExportFormat.M3U -> "$safeTitle(${PlaylistExportFormat.templateLabelFor(playlist.mode)}).m3u"
        }
    }

    private fun exportContent(playlist: SavedPlaylist, format: ExportFormat): String = when (format) {
        ExportFormat.CSV -> PlaylistExportFormat.toCsv(playlist.tracks)
        ExportFormat.M3U -> PlaylistExportFormat.toM3u(playlist.title, playlist.tracks)
    }

    fun dismissToast() = _uiState.update { it.copy(toastMessage = null) }

    /** Port of §4.2's "Generate Fresh" — re-runs the same mode with the
     *  same inputs and saves a brand-new playlist (does not overwrite the
     *  existing one, matching the original: regenerate always creates a
     *  new saved entry, it's not an in-place update). */
    fun regenerate(id: Long) {
        val playlist = _uiState.value.playlists.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(regeneratingId = id) }
            try {
                val raw: List<GeneratedTrack> = when (playlist.mode) {
                    "top", "library" -> generateRepository.fetchTopTracks(playlist.tracks.size.coerceAtLeast(5), "overall")
                    "recent" -> generateRepository.fetchRecentTracks(playlist.tracks.size.coerceAtLeast(5))
                    "mix" -> generateRepository.fetchMix(playlist.tracks.size.coerceAtLeast(5))
                    "recommendations" -> generateRepository.fetchRecommendations(playlist.tracks.size.coerceAtLeast(5))
                    else -> generateRepository.fetchMix(playlist.tracks.size.coerceAtLeast(5))
                }
                val finalTracks = generateRepository.precheck(raw).take(playlist.tracks.size.coerceAtLeast(5))
                generateRepository.markAsSeen(finalTracks)
                val title = PlaylistNamer.generateUniqueName(playlistRepository.titles())
                val saved = playlistRepository.save(title, playlist.subtitle, playlist.mode, finalTracks)
                _uiState.update { it.copy(regeneratingId = null) }
                load(justGeneratedId = saved.id)
            } catch (e: Exception) {
                _uiState.update { it.copy(regeneratingId = null, toastMessage = e.message ?: "Couldn't regenerate") }
            }
        }
    }

    /** Port of §4.7's Generate Similar: seeds from up to 5 evenly-spread
     *  tracks in the source playlist + top tracks from up to 4 of its
     *  artists' similar artists. */
    fun generateSimilar(id: Long) {
        val playlist = _uiState.value.playlists.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingSimilarFor = id) }
            try {
                val targetCount = maxOf(playlist.tracks.size, 15)
                val seeds = evenlySpread(playlist.tracks, 5)
                val candidates = mutableListOf<GeneratedTrack>()
                for (seed in seeds) {
                    try {
                        candidates += generateRepository.fetchSimilarTracks(seed.name, seed.artist, 20)
                    } catch (e: Exception) { /* best-effort per seed */ }
                }
                val artists = playlist.tracks.map { it.artist }.distinct().take(4)
                for (artist in artists) {
                    try {
                        candidates += generateRepository.fetchSimilarArtistTracks(artist, 8)
                    } catch (e: Exception) { /* best-effort per artist */ }
                }
                val sourceKeys = playlist.tracks.map { it.key }.toSet()
                val deduped = LinkedHashMap<String, GeneratedTrack>()
                val artistCap = mutableMapOf<String, Int>()
                for (c in candidates) {
                    if (c.key in sourceKeys || c.key in deduped) continue
                    val ak = c.artist.lowercase()
                    val count = (artistCap[ak] ?: 0)
                    if (count >= 2) continue
                    artistCap[ak] = count + 1
                    deduped[c.key] = c
                }
                val finalTracks = deduped.values.take(targetCount).toList()
                artworkRepository.enrichBatch(finalTracks.take(4).map { it.name to it.artist })

                val title = PlaylistNamer.generateUniqueName(playlistRepository.titles())
                val subtitle = "Similar to \"${playlist.title}\""
                val saved = playlistRepository.save(title, subtitle, playlist.mode, finalTracks)
                _uiState.update { it.copy(isGeneratingSimilarFor = null) }
                load(justGeneratedId = saved.id)
            } catch (e: Exception) {
                _uiState.update { it.copy(isGeneratingSimilarFor = null, toastMessage = e.message ?: "Couldn't generate similar playlist") }
            }
        }
    }

    private fun evenlySpread(tracks: List<GeneratedTrack>, count: Int): List<GeneratedTrack> {
        if (tracks.size <= count) return tracks
        val step = tracks.size.toDouble() / count
        return (0 until count).map { i -> tracks[(i * step).toInt().coerceIn(0, tracks.size - 1)] }
    }

    fun deleteScrobble(trackName: String, artistName: String) {
        viewModelScope.launch {
            when (val result = authRepository.deleteScrobble(trackName, artistName, timestampMillis = null)) {
                is AuthRepository.DeleteScrobbleResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Scrobble deleted") }
                }
                is AuthRepository.DeleteScrobbleResult.AuthorizationRequired -> {
                    _uiState.update { it.copy(deleteScrobbleAuthRequired = true) }
                }
                is AuthRepository.DeleteScrobbleResult.NoTimestamp -> {
                    _uiState.update { it.copy(toastMessage = "Cannot delete \u2014 scrobble has no timestamp") }
                }
                is AuthRepository.DeleteScrobbleResult.Failed -> {
                    _uiState.update { it.copy(toastMessage = result.message) }
                }
            }
        }
    }

    fun dismissDeleteScrobbleAuthRequired() = _uiState.update { it.copy(deleteScrobbleAuthRequired = false) }

    fun refreshArtwork(name: String, artist: String) {
        viewModelScope.launch {
            artworkRepository.forceRefresh(name, artist)
            load()
        }
    }
}
