package com.lastwave.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class ScrobblerSettings(
    /** Master switch — the listener service only actually submits anything
     *  when this is on AND Android's Notification Listener access has been
     *  granted (a separate OS-level permission the service can't request
     *  for itself, only be sent to Settings to grant). */
    val enabled: Boolean = false,
    /** Mirrors Last.fm's own scrobble.updateNowPlaying call — shows "now
     *  playing" on your profile immediately, separate from the actual
     *  scrobble which only submits once the track has played long enough. */
    val submitNowPlaying: Boolean = true,
    /** Percent of the track that must play before it's scrobbled, same
     *  idea as Pano Scrobbler's "Scrobble delay" percent slider. Last.fm's
     *  own rule (whichever comes first) is respected on top of this: a
     *  track must also be longer than 30s, and is capped at needing at
     *  most 4 minutes of playback regardless of this percent. */
    val scrobblePercent: Int = 50,
    /** Package names to actually watch for now-playing/scrobble — empty
     *  means "not configured yet" (the service treats empty as "watch
     *  nothing" rather than silently watching everything, so a track
     *  never gets scrobbled from an app the user never explicitly chose). */
    val selectedPackages: Set<String> = emptySet(),
)

@Singleton
class ScrobblerPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("lw_scrobbler_enabled")
        val SUBMIT_NOW_PLAYING = booleanPreferencesKey("lw_scrobbler_now_playing")
        val PERCENT = intPreferencesKey("lw_scrobbler_percent")
        val PACKAGES = stringSetPreferencesKey("lw_scrobbler_packages")
    }

    val settings: Flow<ScrobblerSettings> = dataStore.data.map { p ->
        ScrobblerSettings(
            enabled = p[Keys.ENABLED] ?: false,
            submitNowPlaying = p[Keys.SUBMIT_NOW_PLAYING] ?: true,
            scrobblePercent = p[Keys.PERCENT] ?: 50,
            selectedPackages = p[Keys.PACKAGES] ?: emptySet(),
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ENABLED] = enabled }
    }

    suspend fun setSubmitNowPlaying(enabled: Boolean) {
        dataStore.edit { it[Keys.SUBMIT_NOW_PLAYING] = enabled }
    }

    suspend fun setScrobblePercent(percent: Int) {
        dataStore.edit { it[Keys.PERCENT] = percent.coerceIn(25, 90) }
    }

    suspend fun setSelectedPackages(packages: Set<String>) {
        dataStore.edit { it[Keys.PACKAGES] = packages }
    }

    suspend fun togglePackage(packageName: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.PACKAGES] ?: emptySet()
            prefs[Keys.PACKAGES] = if (packageName in current) current - packageName else current + packageName
        }
    }
}
