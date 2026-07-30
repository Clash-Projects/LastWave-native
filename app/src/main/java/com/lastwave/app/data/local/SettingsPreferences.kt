package com.lastwave.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class MiscSettings(
    val iTunesArtworkEnabled: Boolean = true,
    /** UI-only stub, matching the original's `lw_use_lbz` flag — see
     *  checklist §0.3: no ListenBrainz API call exists anywhere in the app,
     *  this toggle is stored for parity but has no consuming logic. */
    val listenBrainzArtworkEnabled: Boolean = false,
)

/** Small dedicated prefs object for settings that don't fit ThemePreferences
 *  or SessionPreferences semantically — shares the app's single DataStore. */
@Singleton
class SettingsPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val ITUNES_ARTWORK = booleanPreferencesKey("lw_itunes_artwork")
        val LBZ_ARTWORK = booleanPreferencesKey("lw_use_lbz")
    }

    val settings: Flow<MiscSettings> = dataStore.data.map { p ->
        MiscSettings(
            iTunesArtworkEnabled = p[Keys.ITUNES_ARTWORK] ?: true,
            listenBrainzArtworkEnabled = p[Keys.LBZ_ARTWORK] ?: false,
        )
    }

    suspend fun setItunesArtwork(enabled: Boolean) {
        dataStore.edit { it[Keys.ITUNES_ARTWORK] = enabled }
    }

    suspend fun setListenBrainzArtwork(enabled: Boolean) {
        dataStore.edit { it[Keys.LBZ_ARTWORK] = enabled }
    }
}
