package com.lastwave.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ArtworkCacheEntity::class,
        SeenTrackEntity::class,
        SavedPlaylistEntity::class,
        DownloadedTrackEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun artworkCacheDao(): ArtworkCacheDao
    abstract fun seenTrackDao(): SeenTrackDao
    abstract fun savedPlaylistDao(): SavedPlaylistDao
    abstract fun downloadedTrackDao(): DownloadedTrackDao
}
