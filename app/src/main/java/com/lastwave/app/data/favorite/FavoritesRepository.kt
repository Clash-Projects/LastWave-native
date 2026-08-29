package com.lastwave.app.data.favorite

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.ytmusic.YtMusicSyncManager
import com.lastwave.app.playback.PlayableTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

import com.lastwave.app.data.music.InnerTubeMusicApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

@Singleton
class FavoritesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val playlistRepository: PlaylistRepository,
    private val ytMusicSyncManager: YtMusicSyncManager,
    private val innerTube: InnerTubeMusicApi,
    private val settingsPreferences: SettingsPreferences,
    private val applicationScope: CoroutineScope,
) {
    private object Keys {
        val FAVORITE_KEYS = stringSetPreferencesKey("favorite_track_keys")
    }

    val favoriteKeys: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[Keys.FAVORITE_KEYS] ?: emptySet()
    }

    fun isFavorite(title: String, artist: String): Flow<Boolean> {
        val key = makeKey(title, artist)
        return favoriteKeys.map { it.contains(key) }
    }

    suspend fun toggleFavorite(track: PlayableTrack): Boolean {
        val key = makeKey(track.title, track.artist)
        var nowFavorited = false

        dataStore.edit { prefs ->
            val current = prefs[Keys.FAVORITE_KEYS] ?: emptySet()
            if (key in current) {
                prefs[Keys.FAVORITE_KEYS] = current - key
                nowFavorited = false
            } else {
                prefs[Keys.FAVORITE_KEYS] = current + key
                nowFavorited = true
            }
        }

        // Keep local "Favorites" custom playlist synchronized
        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                val favPlaylist = playlistRepository.createCustom("Favorites")
                if (nowFavorited) {
                    val genTrack = GeneratedTrack(
                        name = track.title,
                        artist = track.artist,
                        artworkUrl = track.artworkUrl,
                        album = track.album,
                    )
                    playlistRepository.addTrack(favPlaylist.id, genTrack)
                } else {
                    val index = favPlaylist.tracks.indexOfFirst {
                        it.name.equals(track.title, ignoreCase = true) &&
                            it.artist.equals(track.artist, ignoreCase = true)
                    }
                    if (index >= 0) {
                        playlistRepository.removeTrack(favPlaylist.id, index)
                    }
                }

                val settings = settingsPreferences.settings.first()
                if (settings.autoSyncLikedSongsToYouTube) {
                    val videoId = track.videoId ?: innerTube.findBestMatchOrNull(track.title, track.artist)?.videoId
                    if (videoId != null) {
                        if (nowFavorited) {
                            innerTube.likeSong(videoId)
                        } else {
                            innerTube.unlikeSong(videoId)
                        }
                    }
                    syncLikedSongsToYouTube()
                }
                Unit
            }
        }

        return nowFavorited
    }

    suspend fun syncLikedSongsToYouTube(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val favPlaylist = playlistRepository.createCustom("Favorites")
            val limiter = Semaphore(4)
            coroutineScope {
                favPlaylist.tracks.map { track ->
                    async {
                        limiter.withPermit {
                            val videoId = innerTube.findBestMatchOrNull(track.name, track.artist)?.videoId
                            if (videoId != null) {
                                innerTube.likeSong(videoId)
                            }
                        }
                    }
                }.awaitAll()
            }
            ytMusicSyncManager.syncNow("favorites_manual_sync")
        }.getOrDefault(false)
    }

    companion object {
        fun makeKey(title: String, artist: String): String =
            "${title.trim().lowercase()} • ${artist.trim().lowercase()}"
    }
}
