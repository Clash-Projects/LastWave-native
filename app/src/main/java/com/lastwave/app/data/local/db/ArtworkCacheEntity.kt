package com.lastwave.app.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Port of the original's localStorage disk cache (_artDiskGet/_artDiskSet):
 *  one row per "t:name|artist" key. [url] is "" for a confirmed no-art
 *  result — a deliberate, cached negative, not an unresolved state. */
@Entity(tableName = "artwork_cache")
data class ArtworkCacheEntity(
    @PrimaryKey val cacheKey: String,
    val url: String,
    val provider: String,
    val timestampMillis: Long,
)
