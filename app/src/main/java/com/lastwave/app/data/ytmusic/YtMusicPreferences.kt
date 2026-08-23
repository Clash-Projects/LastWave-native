package com.lastwave.app.data.ytmusic

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** A YouTube Music playlist mirrored to (or imported from) the account. */
@Serializable
data class YtPlaylistMapping(
    val remotePlaylistId: String,
    val remoteTitle: String,
    val lastSyncAtMillis: Long = 0L,
)

data class YtConnection(
    val cookies: Map<String, String> = emptyMap(),
    val accountName: String = "",
    val channelHandle: String? = null,
    val photoUrl: String? = null,
    val connectedAtMillis: Long = 0L,
) {
    val isConnected: Boolean
        get() = cookies.isNotEmpty() && accountName.isNotBlank()

    companion object {
        val DISCONNECTED = YtConnection()
    }
}

/**
 * DataStore-backed persistence for the connected YouTube Music account:
 * session cookies, display identity, sync settings and the local→remote
 * playlist mapping table used by [YtMusicSyncManager].
 *
 * Stored in the app's shared DataStore so it rides along with existing
 * backup/clear flows without touching Room schema.
 */
@Singleton
class YtMusicPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val connection: Flow<YtConnection> = dataStore.data.map { prefs ->
        val cookies = prefs[COOKIES_KEY]?.let { raw ->
            runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull()
        } ?: emptyMap()
        val name = prefs[ACCOUNT_NAME_KEY] ?: ""
        if (cookies.isEmpty()) {
            YtConnection.DISCONNECTED
        } else {
            YtConnection(
                cookies = cookies,
                accountName = name,
                channelHandle = prefs[CHANNEL_HANDLE_KEY],
                photoUrl = prefs[PHOTO_URL_KEY],
                connectedAtMillis = prefs[CONNECTED_AT_KEY] ?: 0L,
            )
        }
    }

    /** True only while a connection exists AND the user left sync on — both
     *  must hold before any background write to the account happens. */
    suspend fun isSyncActive(): Boolean =
        connection.first().isConnected && dataStore.data.first()[SYNC_ENABLED_KEY] == true

    val syncEnabled: Flow<Boolean> = dataStore.data.map { it[SYNC_ENABLED_KEY] == true }

    val lastSyncAt: Flow<Long> = dataStore.data.map { it[LAST_SYNC_KEY] ?: 0L }

    suspend fun mappings(): Map<Long, YtPlaylistMapping> =
        withContext(Dispatchers.IO) {
            dataStore.data.first()[MAPPINGS_KEY]?.let { raw ->
                runCatching {
                    json.decodeFromString<Map<String, YtPlaylistMapping>>(raw)
                        .mapNotNull { (key, mapping) ->
                            key.toLongOrNull()?.let { it to mapping }
                        }.toMap()
                }.getOrNull()
            } ?: emptyMap()
        }

    suspend fun setMappings(mappings: Map<Long, YtPlaylistMapping>) {
        withContext(Dispatchers.IO) {
            runCatching {
                dataStore.edit { prefs ->
                    prefs[MAPPINGS_KEY] = json.encodeToString(
                        YtPlaylistMappingStringMapSerializer,
                        mappings.mapKeys { (k, _) -> k.toString() },
                    )
                }
            }.onFailure { Log.w(TAG, "Failed to persist YT playlist mappings", it) }
        }
    }

    suspend fun saveConnection(
        cookies: Map<String, String>,
        accountName: String,
        channelHandle: String?,
        photoUrl: String?,
    ) {
        dataStore.edit { prefs ->
            prefs[COOKIES_KEY] = json.encodeToString(cookies)
            prefs[ACCOUNT_NAME_KEY] = accountName
            if (channelHandle != null) prefs[CHANNEL_HANDLE_KEY] = channelHandle else prefs.remove(CHANNEL_HANDLE_KEY)
            if (photoUrl != null) prefs[PHOTO_URL_KEY] = photoUrl else prefs.remove(PHOTO_URL_KEY)
            prefs[CONNECTED_AT_KEY] = System.currentTimeMillis()
        }
    }

    suspend fun clearConnection() {
        dataStore.edit { prefs ->
            prefs.remove(COOKIES_KEY)
            prefs.remove(ACCOUNT_NAME_KEY)
            prefs.remove(CHANNEL_HANDLE_KEY)
            prefs.remove(PHOTO_URL_KEY)
            prefs.remove(CONNECTED_AT_KEY)
            prefs.remove(MAPPINGS_KEY)
            prefs[SYNC_ENABLED_KEY] = false
        }
    }

    val syncedPlaylistIds: Flow<Set<Long>?> = dataStore.data.map { prefs ->
        prefs[SYNCED_PLAYLIST_IDS_KEY]?.let { raw ->
            runCatching { json.decodeFromString<Set<Long>>(raw) }.getOrNull()
        }
    }

    suspend fun setSyncedPlaylistIds(ids: Set<Long>?) {
        dataStore.edit { prefs ->
            if (ids != null) {
                prefs[SYNCED_PLAYLIST_IDS_KEY] = json.encodeToString(ids)
            } else {
                prefs.remove(SYNCED_PLAYLIST_IDS_KEY)
            }
        }
    }

    suspend fun togglePlaylistSync(allPlaylistIds: List<Long>, playlistId: Long, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[SYNCED_PLAYLIST_IDS_KEY]?.let { raw ->
                runCatching { json.decodeFromString<Set<Long>>(raw) }.getOrNull()
            } ?: allPlaylistIds.toSet()
            val updated = if (enabled) current + playlistId else current - playlistId
            prefs[SYNCED_PLAYLIST_IDS_KEY] = json.encodeToString(updated)
        }
    }

    suspend fun setSyncEnabled(enabled: Boolean) {
        dataStore.edit { it[SYNC_ENABLED_KEY] = enabled }
    }

    suspend fun lastSyncAtMillis(): Long = dataStore.data.first()[LAST_SYNC_KEY] ?: 0L

    suspend fun setLastSyncAt(millis: Long) {
        dataStore.edit { it[LAST_SYNC_KEY] = millis }
    }

    private companion object {
        const val TAG = "YtMusicPreferences"
        val COOKIES_KEY = stringPreferencesKey("ytm_cookies")
        val ACCOUNT_NAME_KEY = stringPreferencesKey("ytm_account_name")
        val CHANNEL_HANDLE_KEY = stringPreferencesKey("ytm_channel_handle")
        val PHOTO_URL_KEY = stringPreferencesKey("ytm_photo_url")
        val CONNECTED_AT_KEY = longPreferencesKey("ytm_connected_at")
        val SYNC_ENABLED_KEY = booleanPreferencesKey("ytm_sync_enabled")
        val SYNCED_PLAYLIST_IDS_KEY = stringPreferencesKey("ytm_synced_playlist_ids")
        val MAPPINGS_KEY = stringPreferencesKey("ytm_playlist_mappings")
        val LAST_SYNC_KEY = longPreferencesKey("ytm_last_sync_at")
    }
}

private val YtPlaylistMappingStringMapSerializer =
    MapSerializer(String.serializer(), YtPlaylistMapping.serializer())
