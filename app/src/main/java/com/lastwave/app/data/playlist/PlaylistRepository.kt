package com.lastwave.app.data.playlist

import android.util.Log
import androidx.compose.runtime.Immutable
import com.lastwave.app.data.local.db.SavedPlaylistDao
import com.lastwave.app.data.local.db.SavedPlaylistEntity
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.StoredTrack
import com.lastwave.app.data.generate.toGenerated
import com.lastwave.app.data.generate.toStored
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.util.FileExportHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Port of playlist.js's `lw_playlists` model: id, title, subtitle, mode,
 *  tracks, date. [id] doubles as the creation timestamp (matches the
 *  original's `Date.now()`-based id). */
@Immutable
data class SavedPlaylist(
    val id: Long,
    val title: String,
    val subtitle: String,
    val mode: String,
    val tracks: List<GeneratedTrack>,
    val createdAtMillis: Long,
    val discoverSignature: String? = null,
    val customCoverUri: String? = null,
    val isCompleted: Boolean = false,
    val isPinned: Boolean = false,
)

private const val MAX_SAVED_PLAYLISTS = 20
private const val TAG = "PlaylistRepository"

@Singleton
class PlaylistRepository @Inject constructor(
    private val dao: SavedPlaylistDao,
    private val fileExportHelper: FileExportHelper,
    private val exportEvents: PlaylistExportEvents,
    private val publicMirror: PlaylistPublicMirror,
    private val innerTube: InnerTubeMusicApi,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes = _changes.asSharedFlow()

    // Fire-and-forget scope for the public Downloads export copy — outlives
    // any single screen's viewModelScope (it's a Singleton), and its own
    // failure must never fail or delay save() itself.
    private val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startupSync = exportScope.async {
        runCatching { publicMirror.restoreIfDatabaseEmpty() }
            .onSuccess { restored ->
                if (restored > 0) _changes.tryEmit(Unit)
            }
            .onFailure { error ->
                Log.e(TAG, "Playlist JSON startup sync failed; continuing with Room", error)
            }
    }

    private suspend fun awaitStartupSync() {
        startupSync.await()
    }

    private suspend fun filterPlayable(tracks: List<GeneratedTrack>): List<GeneratedTrack> = tracks


    /** Newest first — matches _plRenderSaved()'s display order (the
     *  original reverses its append-ordered array before rendering). */
    suspend fun getAll(): List<SavedPlaylist> {
        awaitStartupSync()
        return dao.getAll().map { it.toDomain() }.sortedByDescending { it.createdAtMillis }
    }

    suspend fun getById(id: Long): SavedPlaylist? {
        awaitStartupSync()
        return dao.getById(id)?.toDomain()
    }

    /**
     * Saves a new playlist. Guards against accidental double-saves the same
     * way the original's savePlaylist() does: skips saving if an existing
     * playlist has the same title AND the same first track (name+artist).
     * Returns the saved playlist, or the pre-existing duplicate if skipped.
     */
    suspend fun save(title: String, subtitle: String, mode: String, tracks: List<GeneratedTrack>, discoverSignature: String? = null): SavedPlaylist {
        val existing = getAll()
        val playableTracks = if (mode == "custom" && tracks.isEmpty()) emptyList() else filterPlayable(tracks)
        val firstKey = playableTracks.firstOrNull()?.key
        existing.firstOrNull {
            !it.isCompleted &&
                it.mode == mode &&
                it.title.equals(title, ignoreCase = true) &&
                it.tracks.firstOrNull()?.key == firstKey
        }
            ?.let { return it }

        val entity = SavedPlaylistEntity(
            id = System.currentTimeMillis(),
            title = title,
            subtitle = subtitle,
            mode = mode,
            tracksJson = json.encodeToString(playableTracks.map { it.toStored() }),
            createdAtMillis = System.currentTimeMillis(),
            discoverSignature = discoverSignature,
        )
        dao.upsert(entity)
        dao.trimGeneratedToNewest(MAX_SAVED_PLAYLISTS)
        val saved = entity.toDomain()
        syncPublicMirror()
        _changes.tryEmit(Unit)

        // Best-effort copy to the public Downloads folder. Room is already
        // the source of truth the app reads from, so this never blocks the
        // caller — and a failure here doesn't mean the playlist was lost.
        if (mode != "custom" && tracks.isNotEmpty()) {
            exportScope.launch {
                fileExportHelper.savePlaylistToPublicDownloads(saved.title, saved.tracks)
                    .onFailure { e ->
                        Log.e(TAG, "Public Downloads export failed for \"${saved.title}\"", e)
                    }
            }
        }

        return saved
    }

    suspend fun createCustom(title: String): SavedPlaylist {
        val cleanTitle = title.trim()
        getAll().firstOrNull {
            !it.isCompleted && it.mode == "custom" && it.title.equals(cleanTitle, ignoreCase = true)
        }
            ?.let { return it }
        return save(
            title = cleanTitle,
            subtitle = "Custom playlist",
            mode = "custom",
            tracks = emptyList(),
        )
    }

    suspend fun rename(id: Long, title: String): SavedPlaylist? {
        awaitStartupSync()
        val entity = dao.getById(id) ?: return null
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return entity.toDomain()
        val updated = entity.copy(title = cleanTitle)
        dao.upsert(updated)
        syncPublicMirror()
        _changes.tryEmit(Unit)
        return updated.toDomain()
    }

    suspend fun setCustomCover(id: Long, uri: String?): SavedPlaylist? {
        awaitStartupSync()
        val entity = dao.getById(id) ?: return null
        val cleanUri = uri?.trim()?.takeIf { it.isNotBlank() }
        val updated = entity.copy(customCoverUri = cleanUri)
        dao.upsert(updated)
        syncPublicMirror()
        _changes.tryEmit(Unit)
        return updated.toDomain()
    }

    /** Archives a finished playlist. It remains in backups/public storage,
     *  but playlist-facing UI can omit it from the active list. */
    suspend fun setCompleted(id: Long, completed: Boolean = true): SavedPlaylist? {
        awaitStartupSync()
        val entity = dao.getById(id) ?: return null
        if (entity.isCompleted == completed) return entity.toDomain()
        val updated = entity.copy(isCompleted = completed)
        dao.upsert(updated)
        syncPublicMirror()
        _changes.tryEmit(Unit)
        return updated.toDomain()
    }

    suspend fun setPinned(id: Long, pinned: Boolean): SavedPlaylist? {
        awaitStartupSync()
        val entity = dao.getById(id) ?: return null
        if (entity.isPinned == pinned) return entity.toDomain()
        val updated = entity.copy(isPinned = pinned)
        dao.upsert(updated)
        syncPublicMirror()
        _changes.tryEmit(Unit)
        return updated.toDomain()
    }

    suspend fun addTrack(
        id: Long,
        track: GeneratedTrack,
        allowDuplicate: Boolean = false,
    ): SavedPlaylist? {
        awaitStartupSync()
        val entity = dao.getById(id) ?: return null
        val playlist = entity.toDomain()
        if (playlist.mode != "custom") return playlist
        if (!allowDuplicate && playlist.tracks.any { it.key == track.key }) return playlist
        if (!innerTube.isPlayable(track.name, track.artist)) return playlist
        val updatedTracksJson = json.encodeToString((playlist.tracks + track).map { it.toStored() })
        val updated = entity.copy(tracksJson = updatedTracksJson)
        dao.upsert(updated)
        syncPublicMirror()
        _changes.tryEmit(Unit)
        return updated.toDomain()
    }

    suspend fun removeTrack(id: Long, index: Int): SavedPlaylist? {
        awaitStartupSync()
        val entity = dao.getById(id) ?: return null
        val playlist = entity.toDomain()
        if (playlist.mode != "custom" || index !in playlist.tracks.indices) return playlist
        val updatedTracks = playlist.tracks.toMutableList().apply { removeAt(index) }
        val updated = entity.copy(tracksJson = json.encodeToString(updatedTracks.map { it.toStored() }))
        dao.upsert(updated)
        syncPublicMirror()
        _changes.tryEmit(Unit)
        return updated.toDomain()
    }

    suspend fun delete(id: Long) {
        awaitStartupSync()
        dao.deleteById(id)
        syncPublicMirror()
        _changes.tryEmit(Unit)
    }

    suspend fun clearAll() {
        awaitStartupSync()
        dao.clear()
        syncPublicMirror()
        _changes.tryEmit(Unit)
    }

    fun publicMirrorPlaylistCount(content: String): Int? = publicMirror.playlistCount(content)

    suspend fun importPublicMirror(content: String): Result<Int> {
        awaitStartupSync()
        return publicMirror.importAndMerge(content).onSuccess { _changes.tryEmit(Unit) }
    }

    suspend fun titles(): List<String> = getAll().map { it.title }

    /** Order-preserving signature of a Discover feed's visible tracks — port
     *  of _discTrackSignature(): used to detect "this exact feed is already
     *  saved" before creating a duplicate. */
    fun discoverSignature(tracks: List<GeneratedTrack>): String =
        tracks.joinToString("|") { it.key }

    suspend fun findByDiscoverSignature(signature: String): SavedPlaylist? =
        getAll().firstOrNull { !it.isCompleted && it.discoverSignature == signature }

    private suspend fun syncPublicMirror() {
        publicMirror.writeFromDatabase().onFailure { e ->
            Log.e(TAG, "Public playlist JSON sync failed", e)
        }
    }

    private fun SavedPlaylistEntity.toDomain(): SavedPlaylist {
        val tracks = try {
            json.decodeFromString<List<StoredTrack>>(tracksJson).map { it.toGenerated() }
        } catch (e: Exception) {
            emptyList()
        }
        return SavedPlaylist(
            id = id,
            title = title,
            subtitle = subtitle,
            mode = mode,
            tracks = tracks,
            createdAtMillis = createdAtMillis,
            discoverSignature = discoverSignature,
            customCoverUri = customCoverUri,
            isCompleted = isCompleted,
            isPinned = isPinned,
        )
    }
}
