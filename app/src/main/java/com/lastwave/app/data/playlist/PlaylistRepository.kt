package com.lastwave.app.data.playlist

import android.util.Log
import com.lastwave.app.data.local.db.SavedPlaylistDao
import com.lastwave.app.data.local.db.SavedPlaylistEntity
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.StoredTrack
import com.lastwave.app.data.generate.toGenerated
import com.lastwave.app.data.generate.toStored
import com.lastwave.app.util.FileExportHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Port of playlist.js's `lw_playlists` model: id, title, subtitle, mode,
 *  tracks, date. [id] doubles as the creation timestamp (matches the
 *  original's `Date.now()`-based id). */
import androidx.compose.runtime.Immutable

@Immutable
data class SavedPlaylist(
    val id: Long,
    val title: String,
    val subtitle: String,
    val mode: String,
    val tracks: List<GeneratedTrack>,
    val createdAtMillis: Long,
    val discoverSignature: String? = null,
)

private const val MAX_SAVED_PLAYLISTS = 20
private const val TAG = "PlaylistRepository"

@Singleton
class PlaylistRepository @Inject constructor(
    private val dao: SavedPlaylistDao,
    private val fileExportHelper: FileExportHelper,
    private val exportEvents: PlaylistExportEvents,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Fire-and-forget scope for the public Downloads export copy — outlives
    // any single screen's viewModelScope (it's a Singleton), and its own
    // failure must never fail or delay save() itself.
    private val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Newest first — matches _plRenderSaved()'s display order (the
     *  original reverses its append-ordered array before rendering). */
    suspend fun getAll(): List<SavedPlaylist> =
        dao.getAll().map { it.toDomain() }.sortedByDescending { it.createdAtMillis }

    suspend fun getById(id: Long): SavedPlaylist? = dao.getById(id)?.toDomain()

    /**
     * Saves a new playlist. Guards against accidental double-saves the same
     * way the original's savePlaylist() does: skips saving if an existing
     * playlist has the same title AND the same first track (name+artist).
     * Returns the saved playlist, or the pre-existing duplicate if skipped.
     */
    suspend fun save(title: String, subtitle: String, mode: String, tracks: List<GeneratedTrack>, discoverSignature: String? = null): SavedPlaylist {
        val existing = getAll()
        val firstKey = tracks.firstOrNull()?.key
        existing.firstOrNull { it.title.equals(title, ignoreCase = true) && it.tracks.firstOrNull()?.key == firstKey }
            ?.let { return it }

        val entity = SavedPlaylistEntity(
            id = System.currentTimeMillis(),
            title = title,
            subtitle = subtitle,
            mode = mode,
            tracksJson = json.encodeToString(tracks.map { it.toStored() }),
            createdAtMillis = System.currentTimeMillis(),
            discoverSignature = discoverSignature,
        )
        dao.upsert(entity)
        dao.trimToNewest(MAX_SAVED_PLAYLISTS)
        val saved = entity.toDomain()

        // Best-effort copy to the public Downloads folder. Room is already
        // the source of truth the app reads from, so this never blocks the
        // caller — and a failure here doesn't mean the playlist was lost.
        exportScope.launch {
            fileExportHelper.savePlaylistToPublicDownloads(saved.title, saved.tracks)
                .onFailure { e ->
                    Log.e(TAG, "Public Downloads export failed for \"${saved.title}\"", e)
                    exportEvents.notifyFailure("Couldn't save \"${saved.title}\" to Downloads")
                }
        }

        return saved
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun titles(): List<String> = getAll().map { it.title }

    /** Order-preserving signature of a Discover feed's visible tracks — port
     *  of _discTrackSignature(): used to detect "this exact feed is already
     *  saved" before creating a duplicate. */
    fun discoverSignature(tracks: List<GeneratedTrack>): String =
        tracks.joinToString("|") { it.key }

    suspend fun findByDiscoverSignature(signature: String): SavedPlaylist? =
        getAll().firstOrNull { it.discoverSignature == signature }

    private fun SavedPlaylistEntity.toDomain(): SavedPlaylist {
        val tracks = try {
            json.decodeFromString<List<StoredTrack>>(tracksJson).map { it.toGenerated() }
        } catch (e: Exception) {
            emptyList()
        }
        return SavedPlaylist(id, title, subtitle, mode, tracks, createdAtMillis, discoverSignature)
    }
}
