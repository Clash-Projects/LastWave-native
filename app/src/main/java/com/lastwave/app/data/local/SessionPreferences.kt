package com.lastwave.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.lastwave.app.data.network.LastFmAppCredentials
import javax.inject.Inject
import javax.inject.Singleton

data class SessionData(
    val apiKey: String = LastFmAppCredentials.API_KEY,
    val apiSecret: String = LastFmAppCredentials.API_SECRET,
    /** Left blank under the current sign-in method (API key + secret +
     *  username, verified with an unsigned read call — no browser/WebView
     *  OAuth step). Nothing else in the app needs a session key except
     *  track.scrobble.delete, which already degrades gracefully
     *  (AuthRepository.DeleteScrobbleResult.AuthorizationRequired) when
     *  this is blank — every read feature (recent tracks, top tracks,
     *  Discover, Generate, stats) only needs api_key + username. */
    val sessionKey: String = "",
    val username: String = "",
) {
    val isAuthenticated: Boolean get() = username.isNotBlank()
}

@Singleton
class SessionPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val API_KEY = stringPreferencesKey("lw_apikey")
        val API_SECRET = stringPreferencesKey("lw_apisecret")
        val SESSION_KEY = stringPreferencesKey("lw_sessionkey")
        val USERNAME = stringPreferencesKey("lw_username")
        val GUEST_MODE = booleanPreferencesKey("lw_guest_mode")
    }

    val session: Flow<SessionData> = dataStore.data.map { p ->
        val storedKey = p[Keys.API_KEY]
        val storedSecret = p[Keys.API_SECRET]
        SessionData(
            apiKey = if (!storedKey.isNullOrBlank()) storedKey else LastFmAppCredentials.API_KEY,
            apiSecret = if (!storedSecret.isNullOrBlank()) storedSecret else LastFmAppCredentials.API_SECRET,
            sessionKey = p[Keys.SESSION_KEY] ?: "",
            username = p[Keys.USERNAME] ?: "",
        )
    }

    suspend fun setApiCredentials(apiKey: String, apiSecret: String) {
        dataStore.edit {
            it[Keys.API_KEY] = apiKey
            it[Keys.API_SECRET] = apiSecret
        }
    }

    /** Direct sign-in — no token, no session exchange: the verified
     *  username is stored straight away as the signed-in identity. */
    suspend fun setSignedIn(username: String) {
        dataStore.edit {
            it[Keys.USERNAME] = username
            it[Keys.GUEST_MODE] = false
        }
    }

    /** Stores a real Last.fm session key (`sk`) obtained via
     *  auth.getMobileSession — the one signed call that needs a plaintext
     *  password, kept as a separate opt-in step from normal sign-in (see
     *  AuthRepository.obtainSessionKey) rather than required for everyone,
     *  since it's only needed to unlock scrobbling and track.scrobble.delete. */
    suspend fun setSessionKey(key: String) {
        dataStore.edit { it[Keys.SESSION_KEY] = key }
    }

    suspend fun signOut() {
        dataStore.edit {
            it.remove(Keys.SESSION_KEY)
            it.remove(Keys.USERNAME)
            it[Keys.GUEST_MODE] = false
            // API key/secret are intentionally kept — matches the web app's
            // signOut(), which only clears the session, not the developer credentials.
        }
    }

    /** Settings' "Log Out" — matches settings.js's logoutApiCredentials():
     *  clears username + API key/secret. Playlists and cached data are kept. */
    suspend fun logOutApiCredentials() {
        dataStore.edit {
            it.remove(Keys.USERNAME)
            it.remove(Keys.API_KEY)
            it.remove(Keys.API_SECRET)
            it[Keys.GUEST_MODE] = false
        }
    }

    /** Settings' "Clear Session" — matches settings.js's clearAllData():
     *  a full wipe. Since ThemePreferences shares this same DataStore
     *  instance, this also resets theme/accent settings back to defaults,
     *  exactly like the original's localStorage.clear(). */
    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }
}
