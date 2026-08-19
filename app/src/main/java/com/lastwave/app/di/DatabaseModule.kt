package com.lastwave.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import com.lastwave.app.data.local.db.AppDatabase
import com.lastwave.app.data.local.db.ArtworkCacheDao
import com.lastwave.app.data.local.db.SavedPlaylistDao
import com.lastwave.app.data.local.db.SeenTrackDao
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
        override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE saved_playlists ADD COLUMN isCompleted INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "lastwave.db")
            // PlaylistRepository mirrors playlists to public JSON before
            // future schema changes can rebuild Room, then restores that
            // mirror if the database opens empty. Artwork/history are cache.
            .fallbackToDestructiveMigration()
            .addMigrations(migration4To5, migration5To6)
            .build()

    @Provides
    @Singleton
    fun provideArtworkCacheDao(database: AppDatabase): ArtworkCacheDao = database.artworkCacheDao()

    @Provides
    @Singleton
    fun provideSeenTrackDao(database: AppDatabase): SeenTrackDao = database.seenTrackDao()

    @Provides
    @Singleton
    fun provideSavedPlaylistDao(database: AppDatabase): SavedPlaylistDao = database.savedPlaylistDao()
}
