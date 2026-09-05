package com.lastwave.app.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * Port of the original's `lw_playlists` localStorage array (playlist.js's
 * _plSave/_plLoad): one row per saved playlist. [tracksJson] holds the
 * track list serialized as JSON (name/artist/url/image/listeners/playcount/
 * match/album per entry) rather than a normalized child table — this
 * mirrors the original's `{ id, title, subtitle, mode, tracks, date }` shape
 * exactly and keeps read/write a single round trip, same as the original's
 * single localStorage key.
 *
 * [discoverSignature] is only set for mode == "discover" (Save As Playlist's
 * order-preserving track-set signature, used to detect "this exact Discover
 * feed is already saved" — see _discTrackSignature in the original).
 */
@Entity(tableName = "saved_playlists")
data class SavedPlaylistEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val subtitle: String,
    val mode: String,
    val tracksJson: String,
    val createdAtMillis: Long,
    val discoverSignature: String? = null,
    val customCoverUri: String? = null,
    val isPinned: Boolean = false,
)

@Dao
interface SavedPlaylistDao {
    /** Newest-last, matching the original's storage order (it reverses for
     *  display in _plRenderSaved — done in the repository/UI layer instead). */
    @Query("SELECT * FROM saved_playlists ORDER BY createdAtMillis ASC")
    suspend fun getAll(): List<SavedPlaylistEntity>

    @Query("SELECT * FROM saved_playlists WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SavedPlaylistEntity?

    @Upsert
    suspend fun upsert(entity: SavedPlaylistEntity)

    @Upsert
    suspend fun upsertAll(entities: List<SavedPlaylistEntity>)

    @Query("DELETE FROM saved_playlists WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM saved_playlists")
    suspend fun count(): Int

    @Query("SELECT id FROM saved_playlists WHERE mode NOT IN ('custom', 'liked') AND isPinned = 0 ORDER BY createdAtMillis DESC")
    suspend fun getUnpinnedGeneratedIds(): List<Long>

    /** Keeps pinned/generated playlists plus the newest [max] unpinned ones. */
    @Transaction
    suspend fun trimGeneratedToNewest(max: Int) {
        val ids = getUnpinnedGeneratedIds()
        if (ids.size > max) {
            ids.drop(max).forEach { deleteById(it) }
        }
    }

    @Query("DELETE FROM saved_playlists")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(entities: List<SavedPlaylistEntity>) {
        clear()
        upsertAll(entities)
    }
}
