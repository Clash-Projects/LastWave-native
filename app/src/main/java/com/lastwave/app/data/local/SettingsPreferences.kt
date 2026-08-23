package com.lastwave.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class MiscSettings(
    /** When on, the app's accent color follows the dominant color of the
     *  currently-scrobbling track's artwork (Home's "now playing" track),
     *  updating live as that track changes. Falls back to the user's
     *  regular selected accent whenever nothing is playing or artwork
     *  colors can't be extracted — see ThemeRepository.updateNowPlayingArtwork. */
    val dynamicNowPlayingEnabled: Boolean = false,
    /** "Use Application Font" — on: the bundled Google Sans Flex variable
     *  font (see ui/theme/Type.kt); off: the device's own system font.
     *  Defaults on so the app ships with its own identity out of the box. */
    val useCustomFont: Boolean = true,
    /** Last.fm usernames pinned to the top of Home's friend-switcher sheet
     *  (long-press a friend row to toggle). Order among pinned friends
     *  follows whatever order user.getfriends itself returns them in —
     *  just filtered to the front, not independently reorderable. */
    val pinnedFriends: Set<String> = emptySet(),
    /** When true, the player attempts to resolve and stream lossless / Hi-Res audio
     *  directly from Qobuz CDN when a high-confidence match exists. Falls back to YouTube Music. */
    val preferQobuzStreaming: Boolean = true,
    /** Preferred quality preset for Qobuz streaming (27: 24/192, 7: 24/96, 6: 16/44.1, 5: 320k).
     *  If a track does not support the requested quality, the worker automatically selects the highest available. */
    val qobuzQuality: Int = 27,
)

/** Small dedicated prefs object for settings that don't fit ThemePreferences
 *  or SessionPreferences semantically — shares the app's single DataStore. */
@Singleton
class SettingsPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val DYNAMIC_NOW_PLAYING = booleanPreferencesKey("lw_dynamic_now_playing")
        val USE_CUSTOM_FONT = booleanPreferencesKey("lw_use_custom_font")
        val PINNED_FRIENDS = stringSetPreferencesKey("lw_pinned_friends")
        val PREFER_QOBUZ_STREAMING = booleanPreferencesKey("lw_prefer_qobuz_streaming")
        val QOBUZ_QUALITY = androidx.datastore.preferences.core.intPreferencesKey("lw_qobuz_quality")
    }

    val settings: Flow<MiscSettings> = dataStore.data.map { p ->
        MiscSettings(
            dynamicNowPlayingEnabled = p[Keys.DYNAMIC_NOW_PLAYING] ?: false,
            useCustomFont = p[Keys.USE_CUSTOM_FONT] ?: true,
            pinnedFriends = p[Keys.PINNED_FRIENDS] ?: emptySet(),
            preferQobuzStreaming = p[Keys.PREFER_QOBUZ_STREAMING] ?: true,
            qobuzQuality = p[Keys.QOBUZ_QUALITY] ?: 27,
        )
    }

    suspend fun setDynamicNowPlaying(enabled: Boolean) {
        dataStore.edit { it[Keys.DYNAMIC_NOW_PLAYING] = enabled }
    }

    suspend fun setUseCustomFont(enabled: Boolean) {
        dataStore.edit { it[Keys.USE_CUSTOM_FONT] = enabled }
    }

    suspend fun setPreferQobuzStreaming(enabled: Boolean) {
        dataStore.edit { it[Keys.PREFER_QOBUZ_STREAMING] = enabled }
    }

    suspend fun setQobuzQuality(quality: Int) {
        dataStore.edit { it[Keys.QOBUZ_QUALITY] = quality }
    }

    suspend fun toggleFriendPinned(username: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.PINNED_FRIENDS] ?: emptySet()
            prefs[Keys.PINNED_FRIENDS] = if (username in current) current - username else current + username
        }
    }
}
