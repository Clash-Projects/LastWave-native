package com.lastwave.app.ui.playlist

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.artwork.ArtworkRepository
import com.lastwave.app.data.generate.GenerateRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.RECOMMENDATION_TRACK_COUNT
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

import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

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
    val regeneratingId: Long? = null,
    val toastMessage: String? = null,
    val deleteScrobbleAuthRequired: Boolean = false,
    val isGenerating: Boolean = false,
    val generatingMessage: String = "",
    val createDialogVisible: Boolean = false,
    val renamePlaylistId: Long? = null,
    val detailPlaylist: SavedPlaylist? = null,
    val isDetailLoading: Boolean = false,
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
    private val ytMusicPreferences: com.lastwave.app.data.ytmusic.YtMusicPreferences,
    private val ytMusicSyncManager: com.lastwave.app.data.ytmusic.YtMusicSyncManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaylistUiState())
    val uiState: StateFlow<PlaylistUiState> = _uiState.asStateFlow()

    val syncedPlaylistIds: StateFlow<Set<Long>?> = ytMusicPreferences.syncedPlaylistIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun toggleYtSync(playlistId: Long) {
        viewModelScope.launch {
            val allIds = playlistRepository.getAll().map { it.id }
            val current = ytMusicPreferences.syncedPlaylistIds.first() ?: allIds.toSet()
            val isSynced = playlistId in current
            ytMusicPreferences.togglePlaylistSync(allIds, playlistId, !isSynced)
            if (ytMusicPreferences.syncEnabled.first()) {
                runCatching { ytMusicSyncManager.syncNow("selection_change") }
            }
            _uiState.update {
                it.copy(toastMessage = if (!isSynced) "Playlist added to YouTube Music sync" else "Playlist removed from YouTube Music sync")
            }
        }
    }

    init {
        load()
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
        viewModelScope.launch {
            playlistRepository.changes.collect { load() }
        }
    }

    /** Re-reads from Room. Called on first composition and again whenever
     *  the Playlist tab regains visibility (e.g. right after Generate
     *  saves a new playlist) — see PlaylistScreen's LaunchedEffect. */
    fun load(justGeneratedId: Long? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val all = sortPlaylists(playlistRepository.getAll(), _uiState.value.sortMode)
            val newest = justGeneratedId ?: all.maxByOrNull { it.createdAtMillis }?.id
            _uiState.update { current ->
                val currentDetailId = current.detailPlaylist?.id
                val updatedDetail = if (currentDetailId != null) all.firstOrNull { pl -> pl.id == currentDetailId } else current.detailPlaylist
                current.copy(
                    isLoading = false,
                    playlists = all,
                    detailPlaylist = updatedDetail ?: current.detailPlaylist,
                    newestId = newest,
                    expandedIds = if (justGeneratedId != null) setOf(justGeneratedId) else current.expandedIds,
                    justSavedBannerVisible = justGeneratedId != null,
                )
            }
            if (justGeneratedId != null) {
                all.firstOrNull { it.id == justGeneratedId }?.let { pl ->
                    artworkRepository.enrichBatch(pl.tracks.take(6).map { it.name to it.artist })
                }
            }
        }
    }

    /** Loads a specific playlist by ID directly from Room for the detail screen. */
    fun loadDetail(playlistId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDetailLoading = true) }
            val pl = playlistRepository.getById(playlistId)
            _uiState.update {
                it.copy(
                    isDetailLoading = false,
                    detailPlaylist = pl,
                    playlists = if (pl != null && it.playlists.none { existing -> existing.id == pl.id }) {
                        it.playlists + pl
                    } else it.playlists,
                )
            }
            if (pl != null) {
                artworkRepository.enrichBatch(pl.tracks.take(10).map { t -> t.name to t.artist })
            }
        }
    }

    fun setSortMode(mode: PlaylistSortMode) {
        _uiState.update { s ->
            val sorted = sortPlaylists(s.playlists, mode)
            s.copy(sortMode = mode, playlists = sorted)
        }
    }

    private fun sortPlaylists(playlists: List<SavedPlaylist>, mode: PlaylistSortMode): List<SavedPlaylist> =
        playlists.sortedWith(
            when (mode) {
                PlaylistSortMode.DATE_DESC -> compareByDescending<SavedPlaylist> { it.isPinned }
                    .thenByDescending { it.createdAtMillis }
                PlaylistSortMode.DATE_ASC -> compareByDescending<SavedPlaylist> { it.isPinned }
                    .thenBy { it.createdAtMillis }
                PlaylistSortMode.NAME -> compareByDescending<SavedPlaylist> { it.isPinned }
                    .thenBy { it.title.lowercase() }
                PlaylistSortMode.TRACK_COUNT -> compareByDescending<SavedPlaylist> { it.isPinned }
                    .thenByDescending { it.tracks.size }
            },
        )

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

    fun openCreateDialog() = _uiState.update { it.copy(createDialogVisible = true) }
    fun dismissCreateDialog() = _uiState.update { it.copy(createDialogVisible = false) }

    fun createCustomPlaylist(title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val playlist = playlistRepository.createCustom(title)
            _uiState.update {
                it.copy(
                    createDialogVisible = false,
                    expandedIds = it.expandedIds + playlist.id,
                    toastMessage = "Created ${playlist.title}",
                )
            }
            load()
        }
    }

    fun requestRename(id: Long) = _uiState.update { it.copy(renamePlaylistId = id) }
    fun dismissRename() = _uiState.update { it.copy(renamePlaylistId = null) }

    fun renamePlaylist(title: String) {
        val id = _uiState.value.renamePlaylistId ?: return
        if (title.isBlank()) return
        viewModelScope.launch {
            playlistRepository.rename(id, title)
            _uiState.update { it.copy(renamePlaylistId = null, toastMessage = "Playlist renamed") }
            load()
        }
    }

    fun setCustomCover(id: Long, uri: String?) {
        viewModelScope.launch {
            playlistRepository.setCustomCover(id, uri)
            _uiState.update {
                it.copy(toastMessage = if (uri.isNullOrBlank()) "Using automatic playlist cover" else "Playlist cover updated")
            }
            load()
        }
    }

    fun togglePinned(id: Long) {
        val playlist = _uiState.value.playlists.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            playlistRepository.setPinned(id, !playlist.isPinned)
            _uiState.update {
                it.copy(toastMessage = if (playlist.isPinned) "Playlist unpinned" else "Playlist pinned")
            }
            load()
        }
    }

    fun removeTrack(playlistId: Long, index: Int) {
        viewModelScope.launch {
            val updated = playlistRepository.removeTrack(playlistId, index)
            _uiState.update { current ->
                current.copy(
                    detailPlaylist = if (current.detailPlaylist?.id == playlistId) updated else current.detailPlaylist,
                    toastMessage = "Song removed from playlist",
                )
            }
            load()
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
     *  same inputs and saves a brand-new playlist inspired from the original. */
    fun regenerate(id: Long, onRegenerated: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            val playlist = _uiState.value.playlists.firstOrNull { it.id == id }
                ?: _uiState.value.detailPlaylist?.takeIf { it.id == id }
                ?: try { playlistRepository.getById(id) } catch (_: Exception) { null }
                ?: return@launch

            _uiState.update { it.copy(regeneratingId = id, toastMessage = "Regenerating inspired mix\u2026") }
            try {
                val targetCount = (30..35).random()
                val finalTracks = if (playlist.tracks.isNotEmpty()) {
                    // fetchTasteMixForPlaylist already dedupes, filters and
                    // caps artists itself (relaxing the cap to protect the
                    // 20+ floor) — re-prechecking here would re-shrink the
                    // result, so the mix is taken as-is.
                    generateRepository.fetchTasteMixForPlaylist(playlist.tracks, targetCount)
                } else {
                    val raw = when (playlist.mode) {
                        "top", "library" -> generateRepository.fetchTopTracks(targetCount, "overall")
                        "recent" -> generateRepository.fetchRecentTracks(targetCount)
                        "recommendations" -> generateRepository.fetchRecommendations(targetCount)
                        else -> generateRepository.fetchMix(targetCount)
                    }
                    generateRepository.precheck(raw).take(targetCount).ifEmpty {
                        generateRepository.deduplicate(raw).take(targetCount)
                    }
                }

                if (finalTracks.isEmpty()) {
                    throw IllegalStateException("No tracks found to mix for this playlist.")
                }

                val existingTitles = playlistRepository.titles().toSet()
                val baseTitle = if (playlist.title.endsWith(" Mix", ignoreCase = true) || playlist.title.contains("Inspired", ignoreCase = true)) {
                    playlist.title
                } else {
                    "${playlist.title} Mix"
                }
                var title = baseTitle
                var counter = 2
                while (title in existingTitles) {
                    title = "$baseTitle $counter"
                    counter++
                }

                val subtitle = "Inspired by ${playlist.title}"
                val saved = playlistRepository.save(title, subtitle, playlist.mode, finalTracks)
                _uiState.update { it.copy(regeneratingId = null, toastMessage = "Created \"$title\" (${finalTracks.size} tracks)") }
                load(justGeneratedId = saved.id)
                onRegenerated?.invoke(saved.id)
            } catch (e: Exception) {
                _uiState.update { it.copy(regeneratingId = null, toastMessage = e.message ?: "Couldn't regenerate playlist") }
            }
        }
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
