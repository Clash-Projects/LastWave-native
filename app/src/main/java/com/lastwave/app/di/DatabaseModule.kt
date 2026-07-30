package com.lastwave.app.di

import android.content.Context
import androidx.room.Room
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

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "lastwave.db")
            // This database currently holds only a disposable artwork cache —
            // losing it just means artwork gets re-resolved, not data loss.
            // Destructive fallback here means a schema change rebuilds the
            // cache instead of crashing the app on open, which is exactly
            // what happened last round when a column changed without this.
            .fallbackToDestructiveMigration()
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
