package com.lastwave.app.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lastwave.app.data.local.db.SavedPlaylistDao
import com.lastwave.app.data.local.db.SavedPlaylistEntity
import com.lastwave.app.data.local.db.SeenTrackDao
import com.lastwave.app.data.local.db.SeenTrackEntity
import com.lastwave.app.data.playlist.PlaylistPublicMirror
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val SCHEMA_VERSION = 2
private const val BACKUP_TYPE = "lastwave-backup"

@Serializable
data class BackupPrefsSnapshot(val strings: Map<String, String> = emptyMap(), val booleans: Map<String, Boolean> = emptyMap())

@Serializable
data class BackupPlaylistSnapshot(
    val id: Long,
    val title: String,
    val subtitle: String,
    val mode: String,
    val tracksJson: String,
    val createdAtMillis: Long,
    val discoverSignature: String? = null,
)

/** Added in schema v2. Absent/empty on older backup files — restoring one
 *  of those just leaves discovery history untouched rather than failing. */
@Serializable
data class BackupSeenTrackSnapshot(val trackKey: String, val lastSeenMillis: Long)

@Serializable
data class BackupFile(
    val type: String = BACKUP_TYPE,
    val schemaVersion: Int = SCHEMA_VERSION,
    val createdAt: Long,
    val appVersion: String,
    val prefs: BackupPrefsSnapshot,
    val playlists: List<BackupPlaylistSnapshot>,
    val seenTracks: List<BackupSeenTrackSnapshot> = emptyList(),
)

sealed interface RestoreResult {
    data class Success(val playlistCount: Int, val seenTrackCount: Int) : RestoreResult
    data object UnsupportedSchema : RestoreResult
    data object InvalidFile : RestoreResult
    data class Failed(val message: String) : RestoreResult
}

/**
 * Faithful port of settings.js's Backup & Restore (§8.6): serializes the
 * entire local storage (all DataStore prefs, all saved playlists, and
 * discovery history) into one JSON file, and restores it with a
 * pre-restore snapshot so any failure mid-apply rolls back automatically
 * rather than leaving a half-restored state.
 *
 * v2 fix: v1 only captured DataStore prefs + playlists. Discovery history
 * (seen_tracks, the same data Settings' "Clear Discovery History" row
 * operates on) was silently left out of every backup — restoring a v1
 * backup still works today, it just won't have discovery history to bring
 * back, which is expected for a file that never contained it.
 */
@Singleton
class BackupRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val playlistDao: SavedPlaylistDao,
    private val seenTrackDao: SeenTrackDao,
    private val playlistPublicMirror: PlaylistPublicMirror,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    suspend fun buildBackup(appVersionName: String): String {
        val prefs = dataStore.data.first()
        val strings = mutableMapOf<String, String>()
        val booleans = mutableMapOf<String, Boolean>()
        for (entry in prefs.asMap()) {
            val key = entry.key.name
            when (val value = entry.value) {
                is String -> strings[key] = value
                is Boolean -> booleans[key] = value
                else -> Unit // Other pref types aren't used anywhere in this app currently.
            }
        }
        val playlists = playlistDao.getAll().map {
            BackupPlaylistSnapshot(it.id, it.title, it.subtitle, it.mode, it.tracksJson, it.createdAtMillis, it.discoverSignature)
        }
        val seenTracks = seenTrackDao.getAll().map { BackupSeenTrackSnapshot(it.trackKey, it.lastSeenMillis) }
        val backup = BackupFile(
            createdAt = System.currentTimeMillis(),
            appVersion = appVersionName,
            prefs = BackupPrefsSnapshot(strings, booleans),
            playlists = playlists,
            seenTracks = seenTracks,
        )
        return json.encodeToString(backup)
    }

    suspend fun restore(content: String): RestoreResult {
        val backup = try {
            json.decodeFromString<BackupFile>(content)
        } catch (e: Exception) {
            return RestoreResult.InvalidFile
        }
        if (backup.type != BACKUP_TYPE) return RestoreResult.InvalidFile
        if (backup.schemaVersion > SCHEMA_VERSION) return RestoreResult.UnsupportedSchema

        val previousPrefsSnapshot = try { buildBackup("rollback") } catch (e: Exception) { null }
        val previousPlaylists = try { playlistDao.getAll() } catch (e: Exception) { emptyList() }
        val previousSeenTracks = try { seenTrackDao.getAll() } catch (e: Exception) { emptyList() }

        return try {
            dataStore.edit { mutablePrefs ->
                mutablePrefs.clear()
                backup.prefs.strings.forEach { (k, v) -> mutablePrefs[stringPreferencesKey(k)] = v }
                backup.prefs.booleans.forEach { (k, v) -> mutablePrefs[booleanPreferencesKey(k)] = v }
            }
            playlistDao.replaceAll(backup.playlists.map { p ->
                SavedPlaylistEntity(p.id, p.title, p.subtitle, p.mode, p.tracksJson, p.createdAtMillis, p.discoverSignature)
            })
            if (backup.seenTracks.isNotEmpty()) {
                seenTrackDao.clear()
                seenTrackDao.upsertAll(backup.seenTracks.map { SeenTrackEntity(it.trackKey, it.lastSeenMillis) })
            }
            playlistPublicMirror.writeFromDatabase()
            RestoreResult.Success(backup.playlists.size, backup.seenTracks.size)
        } catch (e: Exception) {
            try {
                previousPrefsSnapshot?.let { rollback(it) }
                playlistDao.replaceAll(previousPlaylists)
                seenTrackDao.clear()
                seenTrackDao.upsertAll(previousSeenTracks)
            } catch (rollbackError: Exception) {
                // Nothing more we can safely do — surface the original failure.
            }
            RestoreResult.Failed(e.message ?: "Restore failed")
        }
    }

    private suspend fun rollback(snapshotJson: String) {
        val snapshot = json.decodeFromString<BackupFile>(snapshotJson)
        dataStore.edit { mutablePrefs ->
            mutablePrefs.clear()
            snapshot.prefs.strings.forEach { (k, v) -> mutablePrefs[stringPreferencesKey(k)] = v }
            snapshot.prefs.booleans.forEach { (k, v) -> mutablePrefs[booleanPreferencesKey(k)] = v }
        }
    }
}
