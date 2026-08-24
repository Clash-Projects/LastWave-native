package com.lastwave.app.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A song the user explicitly marked "Don't recommend again". */
@Entity(tableName = "recommendation_exclusions")
data class RecommendationExclusionEntity(
    @PrimaryKey val trackKey: String,
    val excludedAtMillis: Long,
    val trackName: String = "",
    val artistName: String = "",
)

@Dao
interface RecommendationExclusionDao {
    @Upsert
    suspend fun upsert(entity: RecommendationExclusionEntity)

    @Upsert
    suspend fun upsertAll(entities: List<RecommendationExclusionEntity>)

    @Query("SELECT * FROM recommendation_exclusions ORDER BY excludedAtMillis DESC")
    suspend fun getAll(): List<RecommendationExclusionEntity>

    @Query("SELECT * FROM recommendation_exclusions ORDER BY excludedAtMillis DESC")
    fun observeAll(): Flow<List<RecommendationExclusionEntity>>

    @Query("SELECT COUNT(*) FROM recommendation_exclusions")
    suspend fun count(): Int

    @Query("DELETE FROM recommendation_exclusions")
    suspend fun clear()

    @Query("DELETE FROM recommendation_exclusions WHERE trackKey = :trackKey")
    suspend fun delete(trackKey: String)
}
