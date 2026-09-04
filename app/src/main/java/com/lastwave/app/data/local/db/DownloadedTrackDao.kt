package com.lastwave.app.data.local.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedTrackDao {

    @Query("SELECT * FROM downloaded_tracks ORDER BY downloadedAtMillis DESC")
    fun getAll(): Flow<List<DownloadedTrackEntity>>

    @Query("SELECT * FROM downloaded_tracks ORDER BY downloadedAtMillis DESC")
    suspend fun getAllList(): List<DownloadedTrackEntity>

    @Query("SELECT * FROM downloaded_tracks WHERE LOWER(title) = LOWER(:title) AND LOWER(artist) = LOWER(:artist) LIMIT 1")
    suspend fun findByTitleAndArtist(title: String, artist: String): DownloadedTrackEntity?

    @Query("SELECT * FROM downloaded_tracks WHERE trackKey = :trackKey LIMIT 1")
    suspend fun findByTrackKey(trackKey: String): DownloadedTrackEntity?

    @Query("SELECT * FROM downloaded_tracks WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): DownloadedTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: DownloadedTrackEntity): Long

    @Query("DELETE FROM downloaded_tracks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Delete
    suspend fun delete(track: DownloadedTrackEntity)

    @Query("DELETE FROM downloaded_tracks")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM downloaded_tracks")
    fun count(): Flow<Int>

    @Query("SELECT SUM(fileSizeBytes) FROM downloaded_tracks")
    fun totalBytes(): Flow<Long?>
}
