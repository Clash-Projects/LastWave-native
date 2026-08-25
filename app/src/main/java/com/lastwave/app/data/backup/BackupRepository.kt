package com.lastwave.app.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lastwave.app.data.local.db.SavedPlaylistDao
import com.lastwave.app.data.local.db.SavedPlaylistEntity
import com.lastwave.app.data.local.db.RecommendationExclusionDao
import com.lastwave.app.data.local.db.RecommendationExclusionEntity
import com.lastwave.app.data.local.readSafely
import com.lastwave.app.data.playlist.PlaylistPublicMirror
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val SCHEMA_VERSION = 8
private const val BACKUP_TYPE = "lastwave-backup"

/**
 * Preference keys whose on-disk type is Long. Backup schema <= 7 incorrectly
 * narrowed every Long to Int and restored it with an Int key. Preferences keys
 * are name-based, so reading one of those values through a Long key then throws
 * ClassCastException. Keep the names here both to repair old backups and to
 * normalise already-damaged preferences when they are backed up again.
 */
private val LONG_PREFERENCE_NAMES = setOf(
    "ytm_connected_at",
    "ytm_last_sync_at",
)

@Serializable
data class BackupPrefsSnapshot(
    val strings: Map<String, String> = emptyMap(),
    val booleans: Map<String, Boolean> = emptyMap(),
    val integers: Map<String, Int> = emptyMap(),
    val longs: Map<String, Long> = emptyMap(),
    val stringSets: Map<String, Set<String>> = emptyMap(),
)

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
    val isPinned: Boolean = false,
)

/** Explicit recommendation exclusions were added to backup schema v7. */
@Serializable
data class BackupRecommendationExclusionSnapshot(
    val trackKey: String,
    val excludedAtMillis: Long,
    val trackName: String = "",
    val artistName: String = "",
)

@Serializable
data class BackupFile(
    val type: String = BACKUP_TYPE,
    val schemaVersion: Int = SCHEMA_VERSION,
    val createdAt: Long,
    val appVersion: String,
    val prefs: BackupPrefsSnapshot,
    val playlists: List<BackupPlaylistSnapshot>,
    val recommendationExclusions: List<BackupRecommendationExclusionSnapshot> = emptyList(),
)

sealed interface RestoreResult {
    data class Success(val playlistCount: Int, val exclusionCount: Int) : RestoreResult
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
 * entire local storage (all DataStore prefs, saved playlists, and explicit
 * recommendation exclusions) into one JSON file, and restores it with a
 * pre-restore snapshot so any failure mid-apply rolls back automatically
 * rather than leaving a half-restored state.
 *
 * Older backups remain compatible; their former automatic history is
 * intentionally ignored and never converted into explicit dislikes.
 */
@Singleton
class BackupRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val playlistDao: SavedPlaylistDao,
    private val recommendationExclusionDao: RecommendationExclusionDao,
    private val playlistPublicMirror: PlaylistPublicMirror,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun checkBackup(content: String): BackupCheck {
        val backup = runCatching { json.decodeFromString<BackupFile>(content) }.getOrNull()
            ?: return BackupCheck.Invalid
        if (backup.type != BACKUP_TYPE) return BackupCheck.Invalid
        if (backup.schemaVersion > SCHEMA_VERSION) return BackupCheck.UnsupportedSchema
        return BackupCheck.Valid(backup.playlists.size)
    }

    suspend fun buildBackup(appVersionName: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val prefs = dataStore.data.first()
        val strings = mutableMapOf<String, String>()
        val booleans = mutableMapOf<String, Boolean>()
        val integers = mutableMapOf<String, Int>()
        val longs = mutableMapOf<String, Long>()
        val stringSets = mutableMapOf<String, Set<String>>()
        for (entry in prefs.asMap()) {
            val key = entry.key.name
            when (val value = entry.value) {
                is String -> strings[key] = value
                is Boolean -> booleans[key] = value
                // A timestamp found as Int has already been truncated by the
                // old schema and cannot be reconstructed; reset it safely.
                is Int -> if (key in LONG_PREFERENCE_NAMES) longs[key] = 0L else integers[key] = value
                is Long -> longs[key] = value
                is Set<*> -> stringSets[key] = value.mapNotNull { it as? String }.toSet()
                else -> Unit
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
                isPinned = it.isPinned,
            )
        }
        val exclusions = recommendationExclusionDao.getAll().map {
            BackupRecommendationExclusionSnapshot(
                trackKey = it.trackKey,
                excludedAtMillis = it.excludedAtMillis,
                trackName = it.trackName,
                artistName = it.artistName,
            )
        }
        val backup = BackupFile(
            createdAt = System.currentTimeMillis(),
            appVersion = appVersionName,
            prefs = BackupPrefsSnapshot(
                strings = strings,
                booleans = booleans,
                integers = integers,
                longs = longs,
                stringSets = stringSets,
            ),
            playlists = playlists,
            recommendationExclusions = exclusions,
        )
        json.encodeToString(backup)
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

        val currentPrefs = try {
            dataStore.data.first()
        } catch (error: Exception) {
            return RestoreResult.Failed(error.message ?: "Could not read current settings")
        }
        val preservedAuthStrings = if (preserveSignedInSession) {
            AUTH_PREFERENCE_NAMES.mapNotNull { name ->
                currentPrefs.readSafely(stringPreferencesKey(name))?.let { value -> name to value }
            }.toMap()
        } else {
            emptyMap()
        }

        val previousPrefsSnapshot = try { buildBackup("rollback") } catch (e: Exception) { null }
        val previousPlaylists = try { playlistDao.getAll() } catch (e: Exception) { emptyList() }
        val previousExclusions = try { recommendationExclusionDao.getAll() } catch (e: Exception) { emptyList() }

        return try {
            dataStore.edit { mutablePrefs ->
                mutablePrefs.clear()
                backup.prefs.strings.forEach { (k, v) -> mutablePrefs[stringPreferencesKey(k)] = v }
                backup.prefs.booleans.forEach { (k, v) -> mutablePrefs[booleanPreferencesKey(k)] = v }
                backup.prefs.integers.forEach { (k, v) ->
                    // Repair v7-and-older backups that wrote Long preferences
                    // into the integer bucket.
                    if (k in LONG_PREFERENCE_NAMES) {
                        mutablePrefs[longPreferencesKey(k)] = 0L
                    } else {
                        mutablePrefs[intPreferencesKey(k)] = v
                    }
                }
                backup.prefs.longs.forEach { (k, v) -> mutablePrefs[longPreferencesKey(k)] = v }
                backup.prefs.stringSets.forEach { (k, v) -> mutablePrefs[stringSetPreferencesKey(k)] = v }
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
                    isPinned = p.isPinned,
                )
            })
            if (backup.schemaVersion >= 7) {
                recommendationExclusionDao.clear()
                recommendationExclusionDao.upsertAll(
                    backup.recommendationExclusions.map {
                        RecommendationExclusionEntity(
                            trackKey = it.trackKey,
                            excludedAtMillis = it.excludedAtMillis,
                            trackName = it.trackName,
                            artistName = it.artistName,
                        )
                    },
                )
            }
            playlistPublicMirror.writeFromDatabase()
            RestoreResult.Success(backup.playlists.size, backup.recommendationExclusions.size)
        } catch (e: Exception) {
            try {
                previousPrefsSnapshot?.let { rollback(it) }
                playlistDao.replaceAll(previousPlaylists)
                recommendationExclusionDao.clear()
                recommendationExclusionDao.upsertAll(previousExclusions)
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
            snapshot.prefs.integers.forEach { (k, v) ->
                if (k in LONG_PREFERENCE_NAMES) {
                    mutablePrefs[longPreferencesKey(k)] = 0L
                } else {
                    mutablePrefs[intPreferencesKey(k)] = v
                }
            }
            snapshot.prefs.longs.forEach { (k, v) -> mutablePrefs[longPreferencesKey(k)] = v }
            snapshot.prefs.stringSets.forEach { (k, v) -> mutablePrefs[stringSetPreferencesKey(k)] = v }
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
