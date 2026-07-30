package com.lastwave.app.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

@Entity(tableName = "seen_tracks")
data class SeenTrackEntity(
    @PrimaryKey val trackKey: String,
    val lastSeenMillis: Long,
)

@Dao
interface SeenTrackDao {
    @Upsert
    suspend fun upsertAll(entities: List<SeenTrackEntity>)

    @Query("SELECT * FROM seen_tracks")
    suspend fun getAll(): List<SeenTrackEntity>

    @Query("SELECT COUNT(*) FROM seen_tracks")
    suspend fun count(): Int

    /** Keeps only the [max] most-recently-seen rows — port of _SEEN_MAX's
     *  "trim to the newest 3000 entries" behavior. */
    @Query(
        """DELETE FROM seen_tracks WHERE trackKey NOT IN
           (SELECT trackKey FROM seen_tracks ORDER BY lastSeenMillis DESC LIMIT :max)""",
    )
    suspend fun trimToNewest(max: Int)

    @Query("DELETE FROM seen_tracks")
    suspend fun clear()
}
