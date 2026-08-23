package com.lastwave.app.data.ytmusic

import android.util.Log
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the connected YouTube Music account's session cookies and derives the
 * per-request SAPISIDHASH Authorization header that InnerTube write endpoints
 * require (same scheme music.youtube.com itself uses in the browser).
 *
 * Cookies are captured by [com.lastwave.app.ui.settings.YouTubeLoginScreen]'s
 * WebView sign-in flow and persisted via [YtMusicPreferences].
 */
@Singleton
class YtMusicAuthManager @Inject constructor(
    private val preferences: YtMusicPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connection = MutableStateFlow(YtConnection.DISCONNECTED)
    val connection: StateFlow<YtConnection> = _connection.asStateFlow()

    init {
        scope.launch {
            runCatching { preferences.connection.collect { _connection.value = it } }
                .onFailure { Log.w(TAG, "Failed to observe YT connection", it) }
        }
    }

    /** SAPISID token, extracted in priority order exactly like music.youtube.com. */
    fun sapisid(): String? =
        connection.value.cookies[COOKIE_SAPISID_PRIMARY]
            ?: connection.value.cookies["SAPISID"]
            ?: connection.value.cookies["APISID"]

    fun cookieHeaderValue(): String? {
        val cookies = connection.value.cookies
        if (cookies.isEmpty()) return null
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * Builds `SAPISIDHASH <unix_ts>_<sha1(ts SP sapisid SP origin)>` — the
     * reverse-engineered scheme documented at stackoverflow.com/a/32065323
     * and used by every InnerTube client with credentials.
     */
    fun authorizationHeaderValue(origin: String = AUTH_ORIGIN): String? {
        val sapisid = sapisid() ?: return null
        val timestamp = System.currentTimeMillis() / 1000
        val payload = "$timestamp $sapisid $origin"
        val digest = MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timestamp}_$hex"
    }

    suspend fun connect(rawCookieHeader: String, accountName: String, channelHandle: String?, photoUrl: String?) {
        val cookies = parseCookieHeader(rawCookieHeader)
        if (cookies.isEmpty()) return
        preferences.saveConnection(cookies, accountName.ifBlank { "Google account" }, channelHandle, photoUrl)
    }

    suspend fun updateAccountIdentity(accountName: String, channelHandle: String?, photoUrl: String?) {
        val current = connection.value
        if (!current.isConnected) return
        preferences.saveConnection(current.cookies, accountName, channelHandle, photoUrl)
    }

    /** Clears cookies + identity AND the local→remote mapping table, so a
     *  later reconnect starts clean instead of writing into stale playlists
     *  owned by whoever signed in previously. */
    suspend fun signOut() = preferences.clearConnection()

    private fun parseCookieHeader(raw: String): Map<String, String> =
        raw.split(';')
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null else {
                    val name = pair.substring(0, idx).trim()
                    val value = pair.substring(idx + 1).trim()
                    if (name.isBlank() || value.isBlank()) null else name to value
                }
            }
            .toMap()

    private companion object {
        const val TAG = "YtMusicAuthManager"
        const val COOKIE_SAPISID_PRIMARY = "__Secure-3PAPISID"
        const val AUTH_ORIGIN = "https://music.youtube.com"
    }
}
