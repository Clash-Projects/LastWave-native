package com.lastwave.app.data.playlist

import android.content.Context
import android.os.Build
import com.lastwave.app.data.generate.GeneratedTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns the built-in local Liked Songs playlist and the player's heart state. */
@Singleton
class LikedSongsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistRepository: PlaylistRepository,
    private val applicationScope: CoroutineScope,
) {
    private val mutationMutex = Mutex()
    private val _likedTrackKeys = MutableStateFlow<Set<String>>(emptySet())
    val likedTrackKeys: StateFlow<Set<String>> = _likedTrackKeys.asStateFlow()
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        applicationScope.launch(Dispatchers.IO) {
            try {
                bootstrapForInstalledVersion()
                refresh()
                playlistRepository.changes.collect { refresh() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // The built-in playlist is optional launch data. A corrupt
                // legacy database or a vendor filesystem failure must not
                // terminate the process; a later start() may retry it.
                started = false
                android.util.Log.e("LikedSongsManager", "Liked Songs startup disabled", error)
            } catch (error: LinkageError) {
                started = false
                android.util.Log.e("LikedSongsManager", "Liked Songs unsupported on this ROM", error)
            }
        }
    }

    suspend fun toggle(track: GeneratedTrack): Boolean = mutationMutex.withLock {
        val existing = playlistRepository.getLikedSongs()
        val currentlyLiked = existing?.tracks?.any { it.key == track.key } == true
        if (currentlyLiked) {
            playlistRepository.replaceTracksForSync(
                existing!!.id,
                existing.tracks.filterNot { it.key == track.key },
            )
            refresh()
            false
        } else {
            // Deletion is respected until the next actual Like action.
            val playlist = existing ?: playlistRepository.ensureLikedSongs()
            playlistRepository.replaceTracksForSync(playlist.id, playlist.tracks + track)
            refresh()
            true
        }
    }

    /** Artwork double-tap is intentionally idempotent: it never unlikes. */
    suspend fun like(track: GeneratedTrack): Boolean = mutationMutex.withLock {
        val existing = playlistRepository.getLikedSongs()
        if (existing?.tracks?.any { it.key == track.key } == true) return@withLock true
        val playlist = existing ?: playlistRepository.ensureLikedSongs()
        playlistRepository.replaceTracksForSync(playlist.id, playlist.tracks + track)
        refresh()
        true
    }

    private suspend fun refresh() {
        _likedTrackKeys.value = playlistRepository.getLikedSongs()
            ?.tracks
            ?.mapTo(mutableSetOf()) { it.key }
            .orEmpty()
    }

    private suspend fun bootstrapForInstalledVersion() {
        val prefs = context.getSharedPreferences(BOOTSTRAP_PREFS, Context.MODE_PRIVATE)
        val versionCode = installedVersionCode()
        if (prefs.getLong(BOOTSTRAPPED_VERSION_KEY, Long.MIN_VALUE) == versionCode) return
        try {
            playlistRepository.ensureLikedSongs()
            prefs.edit().putLong(BOOTSTRAPPED_VERSION_KEY, versionCode).apply()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Do not mark the version: the next process launch retries safely.
        }
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()
    }

    private companion object {
        const val BOOTSTRAP_PREFS = "liked_songs_bootstrap"
        const val BOOTSTRAPPED_VERSION_KEY = "bootstrapped_version_code"
    }
}
