package com.lastwave.app.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ArtworkCacheDao {
    @Query("SELECT * FROM artwork_cache WHERE cacheKey = :key LIMIT 1")
    suspend fun get(key: String): ArtworkCacheEntity?

    @Query("SELECT * FROM artwork_cache ORDER BY timestampMillis DESC LIMIT :maxEntries")
    suspend fun getNewest(maxEntries: Int): List<ArtworkCacheEntity>

    @Query("DELETE FROM artwork_cache WHERE timestampMillis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    @Query(
        """DELETE FROM artwork_cache
           WHERE cacheKey NOT IN
           (SELECT cacheKey FROM artwork_cache ORDER BY timestampMillis DESC LIMIT :maxEntries)""",
    )
    suspend fun trimToNewest(maxEntries: Int)

    @Upsert
    suspend fun upsert(entity: ArtworkCacheEntity)
}
