package com.lastwave.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import com.lastwave.app.data.local.db.AppDatabase
import com.lastwave.app.data.local.db.ArtworkCacheDao
import com.lastwave.app.data.local.db.SavedPlaylistDao
import com.lastwave.app.data.local.db.RecommendationExclusionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val migration4To5 = object : Migration(4, 5) {
        override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE saved_playlists ADD COLUMN customCoverUri TEXT")
        }
    }

    private val migration5To6 = object : Migration(5, 6) {
        // Completion no longer exists. This step stays only so databases on
        // v5 still have a continuous, data-preserving path to the latest DB.
        override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
    }

    private val migration6To7 = object : Migration(6, 7) {
        override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE saved_playlists ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    /** Removes the old automatic Discovery history and starts a clean,
     * explicit-only "Don't recommend again" exclusion list. */
    private val migration8To9 = object : Migration(8, 9) {
        override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            database.execSQL("DROP TABLE IF EXISTS seen_tracks")
            database.execSQL(
                """CREATE TABLE saved_playlists_without_completion (
                    id INTEGER NOT NULL,
                    title TEXT NOT NULL,
                    subtitle TEXT NOT NULL,
                    mode TEXT NOT NULL,
                    tracksJson TEXT NOT NULL,
                    createdAtMillis INTEGER NOT NULL,
                    discoverSignature TEXT,
                    customCoverUri TEXT,
                    isPinned INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )""".trimIndent(),
            )
            database.execSQL(
                """INSERT INTO saved_playlists_without_completion
                    (id, title, subtitle, mode, tracksJson, createdAtMillis,
                     discoverSignature, customCoverUri, isPinned)
                    SELECT id, title, subtitle, mode, tracksJson, createdAtMillis,
                           discoverSignature, customCoverUri, isPinned
                    FROM saved_playlists""".trimIndent(),
            )
            database.execSQL("DROP TABLE saved_playlists")
            database.execSQL(
                "ALTER TABLE saved_playlists_without_completion RENAME TO saved_playlists",
            )
            database.execSQL(
                """CREATE TABLE IF NOT EXISTS recommendation_exclusions (
                    trackKey TEXT NOT NULL,
                    excludedAtMillis INTEGER NOT NULL,
                    PRIMARY KEY(trackKey)
                )""".trimIndent(),
            )
        }
    }

    /** Adds display metadata so exclusions can be managed individually. */
    private val migration9To10 = object : Migration(9, 10) {
        override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE recommendation_exclusions ADD COLUMN trackName TEXT NOT NULL DEFAULT ''",
            )
            database.execSQL(
                "ALTER TABLE recommendation_exclusions ADD COLUMN artistName TEXT NOT NULL DEFAULT ''",
            )
        }
    }

    private val migration7To8 = object : Migration(7, 8) {
        override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            database.execSQL(
                """CREATE TABLE IF NOT EXISTS downloaded_tracks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    album TEXT NOT NULL,
                    artworkUrl TEXT,
                    filePath TEXT NOT NULL,
                    mediaStoreUri TEXT,
                    fileSizeBytes INTEGER NOT NULL,
                    formatBadge TEXT NOT NULL,
                    durationMs INTEGER NOT NULL,
                    bitrateKbps INTEGER,
                    isQobuz INTEGER NOT NULL,
                    hasLyrics INTEGER NOT NULL,
                    syncedLyrics TEXT,
                    plainLyrics TEXT,
                    lrcFilePath TEXT,
                    downloadedAtMillis INTEGER NOT NULL
                )""".trimIndent(),
            )
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "lastwave.db")
            // PlaylistRepository mirrors playlists to public JSON before
            // future schema changes can rebuild Room, then restores that
            // mirror if the database opens empty. Artwork is cache.
            .fallbackToDestructiveMigration()
            .addMigrations(
                migration4To5,
                migration5To6,
                migration6To7,
                migration7To8,
                migration8To9,
                migration9To10,
            )
            .build()

    @Provides
    @Singleton
    fun provideArtworkCacheDao(database: AppDatabase): ArtworkCacheDao = database.artworkCacheDao()

    @Provides
    @Singleton
    fun provideRecommendationExclusionDao(database: AppDatabase): RecommendationExclusionDao =
        database.recommendationExclusionDao()

    @Provides
    @Singleton
    fun provideSavedPlaylistDao(database: AppDatabase): SavedPlaylistDao = database.savedPlaylistDao()

    @Provides
    @Singleton
    fun provideDownloadedTrackDao(database: AppDatabase): com.lastwave.app.data.local.db.DownloadedTrackDao =
        database.downloadedTrackDao()
}
