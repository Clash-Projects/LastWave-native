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

private const val SCHEMA_VERSION = 5
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
    val customCoverUri: String? = null,
    val isCompleted: Boolean = false,
    val isPinned: Boolean = false,
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

sealed interface BackupCheck {
    data class Valid(val playlistCount: Int) : BackupCheck
    data object UnsupportedSchema : BackupCheck
    data object Invalid : BackupCheck
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

    fun checkBackup(content: String): BackupCheck {
        val backup = runCatching { json.decodeFromString<BackupFile>(content) }.getOrNull()
            ?: return BackupCheck.Invalid
        if (backup.type != BACKUP_TYPE) return BackupCheck.Invalid
        if (backup.schemaVersion > SCHEMA_VERSION) return BackupCheck.UnsupportedSchema
        return BackupCheck.Valid(backup.playlists.size)
    }

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
            BackupPlaylistSnapshot(
                id = it.id,
                title = it.title,
                subtitle = it.subtitle,
                mode = it.mode,
                tracksJson = it.tracksJson,
                createdAtMillis = it.createdAtMillis,
                discoverSignature = it.discoverSignature,
                customCoverUri = it.customCoverUri,
                isCompleted = it.isCompleted,
                isPinned = it.isPinned,
            )
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

    suspend fun restore(
        content: String,
        preserveSignedInSession: Boolean = false,
    ): RestoreResult {
        val backup = try {
            json.decodeFromString<BackupFile>(content)
        } catch (e: Exception) {
            return RestoreResult.InvalidFile
        }
        if (backup.type != BACKUP_TYPE) return RestoreResult.InvalidFile
        if (backup.schemaVersion > SCHEMA_VERSION) return RestoreResult.UnsupportedSchema

        val currentPrefs = dataStore.data.first()
        val preservedAuthStrings = if (preserveSignedInSession) {
            AUTH_PREFERENCE_NAMES.mapNotNull { name ->
                currentPrefs[stringPreferencesKey(name)]?.let { value -> name to value }
            }.toMap()
        } else {
            emptyMap()
        }

        val previousPrefsSnapshot = try { buildBackup("rollback") } catch (e: Exception) { null }
        val previousPlaylists = try { playlistDao.getAll() } catch (e: Exception) { emptyList() }
        val previousSeenTracks = try { seenTrackDao.getAll() } catch (e: Exception) { emptyList() }

        return try {
            dataStore.edit { mutablePrefs ->
                mutablePrefs.clear()
                backup.prefs.strings.forEach { (k, v) -> mutablePrefs[stringPreferencesKey(k)] = v }
                backup.prefs.booleans.forEach { (k, v) -> mutablePrefs[booleanPreferencesKey(k)] = v }
                if (preserveSignedInSession) {
                    preservedAuthStrings.forEach { (name, value) ->
                        mutablePrefs[stringPreferencesKey(name)] = value
                    }
                    mutablePrefs[booleanPreferencesKey(GUEST_MODE_PREFERENCE)] = false
                }
            }
            playlistDao.replaceAll(backup.playlists.map { p ->
                SavedPlaylistEntity(
                    id = p.id,
                    title = p.title,
                    subtitle = p.subtitle,
                    mode = p.mode,
                    tracksJson = p.tracksJson,
                    createdAtMillis = p.createdAtMillis,
                    discoverSignature = p.discoverSignature,
                    customCoverUri = p.customCoverUri,
                    isCompleted = p.isCompleted,
                    isPinned = p.isPinned,
                )
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

    private companion object {
        const val GUEST_MODE_PREFERENCE = "lw_guest_mode"
        val AUTH_PREFERENCE_NAMES = listOf(
            "lw_apikey",
            "lw_apisecret",
            "lw_sessionkey",
            "lw_username",
        )
    }
}
