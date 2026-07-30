package com.lastwave.app.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ArtworkCacheDao {
    @Query("SELECT * FROM artwork_cache WHERE cacheKey = :key LIMIT 1")
    suspend fun get(key: String): ArtworkCacheEntity?

    @Query("SELECT * FROM artwork_cache")
    suspend fun getAll(): List<ArtworkCacheEntity>

    @Upsert
    suspend fun upsert(entity: ArtworkCacheEntity)
}
