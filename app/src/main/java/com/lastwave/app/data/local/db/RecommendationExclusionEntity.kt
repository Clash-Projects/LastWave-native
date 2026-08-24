package com.lastwave.app.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** A song the user explicitly marked "Don't recommend again". */
@Entity(tableName = "recommendation_exclusions")
data class RecommendationExclusionEntity(
    @PrimaryKey val trackKey: String,
    val excludedAtMillis: Long,
)

@Dao
interface RecommendationExclusionDao {
    @Upsert
    suspend fun upsert(entity: RecommendationExclusionEntity)

    @Upsert
    suspend fun upsertAll(entities: List<RecommendationExclusionEntity>)

    @Query("SELECT * FROM recommendation_exclusions")
    suspend fun getAll(): List<RecommendationExclusionEntity>

    @Query("SELECT COUNT(*) FROM recommendation_exclusions")
    suspend fun count(): Int

    @Query("DELETE FROM recommendation_exclusions")
    suspend fun clear()
}
