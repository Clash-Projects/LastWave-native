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
import com.lastwave.app.data.playlist.LIKED_SONGS_MODE
import com.lastwave.app.data.playlist.isYouTubeOnly
import com.lastwave.app.data.repository.AuthRepository
import com.lastwave.app.util.FileExportHelper
import com.lastwave.app.util.PlaylistExportFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    private val ytMusicLibraryManager: com.lastwave.app.data.ytmusic.YtMusicLibraryManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaylistUiState())
    val uiState: StateFlow<PlaylistUiState> = _uiState.asStateFlow()

    val syncedPlaylistIds: StateFlow<Set<Long>?> = ytMusicPreferences.syncedPlaylistIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
        viewModelScope.launch {
            ytMusicLibraryManager.libraryReady.first { it }
            ytMusicLibraryManager.playlists.collect { remote ->
                val openRemoteId = _uiState.value.detailPlaylist
                    ?.takeIf { it.isYouTubeOnly }
                    ?.id
                _uiState.update { current ->
                    val local = current.playlists.filterNot { it.isYouTubeOnly }
                    current.copy(playlists = sortPlaylists(local + remote, current.sortMode))
                }
                if (openRemoteId != null && remote.any { it.id == openRemoteId }) {
                    ytMusicLibraryManager.loadDetail(openRemoteId)?.let { refreshed ->
                        _uiState.update { current ->
                            current.copy(
                                detailPlaylist = refreshed,
                                playlists = current.playlists.map { playlist ->
                                    if (playlist.id == refreshed.id) refreshed else playlist
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    /** Re-reads from Room. Called on first composition and again whenever
     *  the Playlist tab regains visibility (e.g. right after Generate
     *  saves a new playlist) — see PlaylistScreen's LaunchedEffect. */
    fun load(justGeneratedId: Long? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            ytMusicLibraryManager.libraryReady.first { it }
            val local = playlistRepository.getAll()
            val all = sortPlaylists(local + ytMusicLibraryManager.playlists.value, _uiState.value.sortMode)
            val newest = justGeneratedId ?: all.maxByOrNull { it.createdAtMillis }?.id
            _uiState.update { current ->
                val currentDetailId = current.detailPlaylist?.id
                val updatedDetail = if (currentDetailId != null && currentDetailId >= 0L) {
                    all.firstOrNull { pl -> pl.id == currentDetailId }
                } else {
                    current.detailPlaylist
                }
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
            ytMusicLibraryManager.refresh()
            runCatching { ytMusicSyncManager.syncNow("playlist_open") }
        }
    }

    /** Loads a specific playlist by ID directly from Room for the detail screen. */
    fun loadDetail(playlistId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDetailLoading = true) }
            val pl = if (playlistId < 0L) {
                ytMusicLibraryManager.loadDetail(playlistId) { updated ->
                    _uiState.update { current ->
                        current.copy(
                            isDetailLoading = updated.tracks.isEmpty(),
                            detailPlaylist = updated,
                            playlists = current.playlists.map { existing ->
                                if (existing.id == updated.id) updated else existing
                            },
                        )
                    }
                    if (updated.tracks.isNotEmpty()) {
                        viewModelScope.launch {
                            artworkRepository.enrichBatch(updated.tracks.take(10).map { t -> t.name to t.artist })
                        }
                    }
                }
            } else {
                playlistRepository.getById(playlistId)
            }
            _uiState.update {
                it.copy(
                    isDetailLoading = false,
                    detailPlaylist = pl ?: it.detailPlaylist,
                    playlists = if (pl != null) {
                        if (it.playlists.any { existing -> existing.id == pl.id }) {
                            it.playlists.map { existing -> if (existing.id == pl.id) pl else existing }
                        } else it.playlists + pl
                    } else it.playlists,
                )
            }
            if (pl != null && pl.tracks.isNotEmpty()) {
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
                PlaylistSortMode.DATE_DESC -> compareByDescending<SavedPlaylist> { it.mode == LIKED_SONGS_MODE && it.isPinned }
                    .thenByDescending { it.isPinned }
                    .thenByDescending { it.createdAtMillis }
                PlaylistSortMode.DATE_ASC -> compareByDescending<SavedPlaylist> { it.mode == LIKED_SONGS_MODE && it.isPinned }
                    .thenByDescending { it.isPinned }
                    .thenBy { it.createdAtMillis }
                PlaylistSortMode.NAME -> compareByDescending<SavedPlaylist> { it.mode == LIKED_SONGS_MODE && it.isPinned }
                    .thenByDescending { it.isPinned }
                    .thenBy { it.title.lowercase() }
                PlaylistSortMode.TRACK_COUNT -> compareByDescending<SavedPlaylist> { it.mode == LIKED_SONGS_MODE && it.isPinned }
                    .thenByDescending { it.isPinned }
                    .thenByDescending { it.remoteTrackCount ?: it.tracks.size }
            },
        )

    fun regenerateLatest() {
        val newest = _uiState.value.playlists.maxByOrNull { it.createdAtMillis } ?: return
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

    fun makeLocal(id: Long) {
        viewModelScope.launch {
            val saved = ytMusicLibraryManager.makeLocal(id)
            _uiState.update {
                it.copy(
                    toastMessage = if (saved != null) "${saved.title} is now available locally" else "Couldn't make playlist local",
                    detailPlaylist = saved ?: it.detailPlaylist,
                )
            }
            if (saved != null) load(justGeneratedId = saved.id)
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
        val playlist = _uiState.value.playlists.firstOrNull { it.id == id }
            ?: _uiState.value.detailPlaylist?.takeIf { it.id == id }
            ?: return
        viewModelScope.launch {
            val willBePinned = !playlist.isPinned
            if (id < 0L) {
                ytMusicLibraryManager.togglePinned(id, willBePinned)
            } else {
                playlistRepository.setPinned(id, willBePinned)
            }
            _uiState.update { current ->
                val updatedPlaylists = current.playlists.map { if (it.id == id) it.copy(isPinned = willBePinned) else it }
                val sorted = sortPlaylists(updatedPlaylists, current.sortMode)
                current.copy(
                    playlists = sorted,
                    detailPlaylist = if (current.detailPlaylist?.id == id) current.detailPlaylist?.copy(isPinned = willBePinned) else current.detailPlaylist,
                    toastMessage = if (willBePinned) "Playlist pinned to top" else "Playlist unpinned",
                )
            }
            if (id >= 0L) load()
        }
    }

    fun removeTrack(playlistId: Long, index: Int) {
        val before = _uiState.value.detailPlaylist?.takeIf { it.id == playlistId }
        if (playlistId < 0L && before != null && index in before.tracks.indices) {
            _uiState.update { current ->
                current.copy(
                    detailPlaylist = before.copy(
                        tracks = before.tracks.filterIndexed { trackIndex, _ -> trackIndex != index },
                        remoteTrackCount = (before.remoteTrackCount ?: before.tracks.size).let { maxOf(0, it - 1) },
                    ),
                )
            }
        }
        viewModelScope.launch {
            val updated = if (playlistId < 0L) {
                ytMusicLibraryManager.removeTrack(playlistId, index)
            } else {
                playlistRepository.removeTrack(playlistId, index)
            }
            _uiState.update { current ->
                val removed = before != null && updated != null && updated.tracks.size < before.tracks.size
                current.copy(
                    detailPlaylist = if (current.detailPlaylist?.id == playlistId) updated ?: before else current.detailPlaylist,
                    toastMessage = if (removed || playlistId >= 0L) "Song removed from playlist" else "Couldn't remove song",
                )
            }
            if (playlistId >= 0L) load()
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
        if (_uiState.value.regeneratingId != null) return
        _uiState.update {
            it.copy(regeneratingId = id, toastMessage = "Regenerating inspired mix\u2026")
        }
        viewModelScope.launch {
            val playlist = _uiState.value.playlists.firstOrNull { it.id == id }
                ?: _uiState.value.detailPlaylist?.takeIf { it.id == id }
                ?: try { playlistRepository.getById(id) } catch (_: Exception) { null }
            if (playlist == null) {
                _uiState.update {
                    it.copy(regeneratingId = null, toastMessage = "Playlist could not be loaded")
                }
                return@launch
            }

            try {
                val targetCount = (30..35).random()
                val candidateCount = (targetCount + 15).coerceAtMost(50)
                val candidates = if (playlist.tracks.isNotEmpty()) {
                    // fetchTasteMixForPlaylist already dedupes, filters and
                    // caps artists itself (relaxing the cap to protect the
                    // 20+ floor) — re-prechecking here would re-shrink the
                    // result, so the mix is taken as-is.
                    generateRepository.fetchTasteMixForPlaylist(playlist.tracks, candidateCount)
                } else {
                    val raw = when (playlist.mode) {
                        "top", "library" -> generateRepository.fetchTopTracks(candidateCount, "overall")
                        "recent" -> generateRepository.fetchRecentTracks(candidateCount)
                        "recommendations" -> generateRepository.fetchRecommendations(targetCount)
                        else -> generateRepository.fetchMix(candidateCount)
                    }
                    generateRepository.precheck(raw).ifEmpty {
                        generateRepository.deduplicate(raw)
                    }
                }
                val finalTracks = generateRepository.preferPlaylistFreshness(
                    tracks = candidates,
                    limit = targetCount,
                    savedKeys = generateRepository.savedPlaylistTrackKeys(),
                )

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
                _uiState.update { it.copy(toastMessage = "Created \"$title\" (${finalTracks.size} tracks)") }
                load(justGeneratedId = saved.id)
                onRegenerated?.invoke(saved.id)
            } catch (error: CancellationException) {
                throw error
            } catch (e: Exception) {
                _uiState.update { it.copy(toastMessage = e.message ?: "Couldn't regenerate playlist") }
            } finally {
                _uiState.update { current ->
                    if (current.regeneratingId == id) current.copy(regeneratingId = null) else current
                }
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
