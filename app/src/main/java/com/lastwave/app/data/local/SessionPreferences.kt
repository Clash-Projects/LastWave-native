package com.lastwave.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class SessionData(
    val apiKey: String = "",
    val apiSecret: String = "",
    val sessionKey: String = "",
    val username: String = "",
    val pendingAuthToken: String = "",
) {
    val isAuthenticated: Boolean get() = sessionKey.isNotBlank() && username.isNotBlank()
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
        val PENDING_TOKEN = stringPreferencesKey("lw_pending_token")
    }

    val session: Flow<SessionData> = dataStore.data.map { p ->
        SessionData(
            apiKey = p[Keys.API_KEY] ?: "",
            apiSecret = p[Keys.API_SECRET] ?: "",
            sessionKey = p[Keys.SESSION_KEY] ?: "",
            username = p[Keys.USERNAME] ?: "",
            pendingAuthToken = p[Keys.PENDING_TOKEN] ?: "",
        )
    }

    suspend fun setApiCredentials(apiKey: String, apiSecret: String) {
        dataStore.edit {
            it[Keys.API_KEY] = apiKey
            it[Keys.API_SECRET] = apiSecret
        }
    }

    suspend fun setPendingToken(token: String) {
        dataStore.edit { it[Keys.PENDING_TOKEN] = token }
    }

    suspend fun clearPendingToken() {
        dataStore.edit { it.remove(Keys.PENDING_TOKEN) }
    }

    suspend fun setAuthenticatedSession(sessionKey: String, username: String) {
        dataStore.edit {
            it[Keys.SESSION_KEY] = sessionKey
            it[Keys.USERNAME] = username
            it.remove(Keys.PENDING_TOKEN)
        }
    }

    suspend fun signOut() {
        dataStore.edit {
            it.remove(Keys.SESSION_KEY)
            it.remove(Keys.USERNAME)
            it.remove(Keys.PENDING_TOKEN)
            // API key/secret are intentionally kept — matches the web app's
            // signOut(), which only clears the session, not the developer credentials.
        }
    }

    /** Settings' "Log Out" — matches settings.js's logoutApiCredentials():
     *  clears username + API key/secret. Playlists and cached data are kept.
     *  (Faithful to the original: it does not separately clear the session
     *  key either — same as the web app.) */
    suspend fun logOutApiCredentials() {
        dataStore.edit {
            it.remove(Keys.USERNAME)
            it.remove(Keys.API_KEY)
            it.remove(Keys.API_SECRET)
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
