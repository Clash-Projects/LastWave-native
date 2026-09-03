package com.lastwave.app.data.repository

import android.net.Uri
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.model.AuthState
import com.lastwave.app.data.network.LastFmApiService
import com.lastwave.app.data.network.LastFmAppCredentials
import com.lastwave.app.data.network.LastFmErrors
import com.lastwave.app.data.network.LastFmException
import com.lastwave.app.data.network.LastFmSigner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** Owns Last.fm web authentication, session persistence, and legacy credential entry. */
@Singleton
class AuthRepository @Inject constructor(
    private val api: LastFmApiService,
    private val sessionPreferences: SessionPreferences,
    externalScope: kotlinx.coroutines.CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Transient states not worth persisting (signing-in/error); null means
     *  "defer to the persisted session". */
    private val transientState = MutableStateFlow<AuthState?>(null)

    val authState: StateFlow<AuthState> = combine(
        sessionPreferences.session,
        transientState,
    ) { session, transient ->
        transient ?: if (!session.isLoaded) {
            AuthState.Unknown
        } else if (session.isAuthenticated) {
            AuthState.SignedIn(session.username)
        } else {
            AuthState.SignedOut
        }
    }.stateIn(externalScope, SharingStarted.Eagerly, AuthState.Unknown)

    /** Guest mode — no Last.fm account, no network call. See
     *  SessionPreferences.continueAsGuest() for what this actually sets. */
    suspend fun continueAsGuest() {
        sessionPreferences.continueAsGuest()
        transientState.value = null // defer to persisted (now-authenticated) session
    }

    suspend fun saveApiCredentials(apiKey: String, apiSecret: String) {
        sessionPreferences.setApiCredentials(
            LastFmSigner.normalizeKey(apiKey),
            LastFmSigner.normalizeKey(apiSecret),
        )
    }

    /** Starts web auth; Last.fm returns an authorized token through the app callback. */
    fun authUrl(): String =
        Uri.parse("https://www.last.fm/api/auth/")
            .buildUpon()
            .appendQueryParameter("api_key", LastFmAppCredentials.API_KEY)
            .appendQueryParameter("cb", LAST_FM_AUTH_CALLBACK_URI)
            .build()
            .toString()

    suspend fun completeWebAuth(token: String): Result<String> {
        transientState.value = AuthState.SigningIn
        return try {
            val signParams = mapOf(
                "method" to "auth.getSession",
                "token" to token,
                "api_key" to LastFmAppCredentials.API_KEY,
            )
            val sig = LastFmSigner.sign(signParams, LastFmAppCredentials.API_SECRET)
            val body = signParams + mapOf("api_sig" to sig, "format" to "json")
            val response = api.post(body)
            val text = response.body()?.string() ?: throw LastFmException("Empty response from Last.fm")
            val parsed = json.parseToJsonElement(text).jsonObject
            val errorCode = (parsed["error"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
            if (errorCode != null) {
                val rawMessage = (parsed["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                throw LastFmException(LastFmErrors.friendlyMessage(errorCode, rawMessage), errorCode)
            }
            val sessionObj = parsed["session"]?.jsonObject
            val sessionKey = sessionObj?.get("key")?.jsonPrimitive?.content
            val username = sessionObj?.get("name")?.jsonPrimitive?.content
            if (sessionKey.isNullOrBlank() || username.isNullOrBlank()) {
                throw LastFmException("Last.fm didn't return a session")
            }
            sessionPreferences.saveSession(
                username = username,
                sessionKey = sessionKey,
                apiKey = LastFmAppCredentials.API_KEY,
                apiSecret = LastFmAppCredentials.API_SECRET,
            )
            transientState.value = null // defer to persisted SignedIn state
            Result.success(username)
        } catch (e: Exception) {
            val message = (e as? LastFmException)?.message ?: (e.message ?: "Could not complete sign-in")
            transientState.value = AuthState.Error(message)
            Result.failure(e)
        }
    }

    suspend fun signInDirect(username: String): Result<String> {
        return signIn(LastFmAppCredentials.API_KEY, LastFmAppCredentials.API_SECRET, username)
    }

    /**
     * Verifies the API key + username pair against Last.fm (user.getInfo,
     * unsigned) and, on success, saves everything and marks the session
     * signed in.
     */
    suspend fun signIn(apiKey: String, apiSecret: String, username: String): Result<String> {
        transientState.value = AuthState.SigningIn
        val keyNorm = LastFmSigner.normalizeKey(apiKey)
        val secretNorm = LastFmSigner.normalizeKey(apiSecret)
        val usernameNorm = username.trim()

        if (keyNorm.length < 16) {
            val message = "API key is too short after normalisation — please check it."
            transientState.value = AuthState.Error(message)
            return Result.failure(LastFmException(message))
        }
        if (usernameNorm.isBlank()) {
            val message = "Enter your Last.fm username."
            transientState.value = AuthState.Error(message)
            return Result.failure(LastFmException(message))
        }

        return try {
            val response = api.get(
                mapOf(
                    "method" to "user.getinfo",
                    "user" to usernameNorm,
                    "api_key" to keyNorm,
                    "format" to "json",
                )
            )
            val body = response.body()?.string() ?: throw LastFmException("Empty response from Last.fm")
            val parsed = json.parseToJsonElement(body).jsonObject
            val errorCode = (parsed["error"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
            if (errorCode != null) {
                val rawMessage = (parsed["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                throw LastFmException(LastFmErrors.friendlyMessage(errorCode, rawMessage), errorCode)
            }
            val confirmedUsername = parsed["user"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: usernameNorm

            sessionPreferences.saveSession(
                username = confirmedUsername,
                sessionKey = "",
                apiKey = keyNorm,
                apiSecret = secretNorm,
            )
            transientState.value = null // defer to persisted SignedIn state
            Result.success(confirmedUsername)
        } catch (e: Exception) {
            val message = (e as? LastFmException)?.message ?: (e.message ?: "Could not verify those credentials")
            transientState.value = AuthState.Error(message)
            Result.failure(e)
        }
    }

    suspend fun signOut() {
        transientState.value = null
        sessionPreferences.signOut()
    }

    sealed interface SessionKeyResult {
        data object Success : SessionKeyResult
        data class Failed(val message: String) : SessionKeyResult
    }

    /**
     * Obtains a real Last.fm session key (`sk`) via auth.getMobileSession —
     * the only browser-free way to get one, at the cost of needing the
     * user's actual Last.fm password (used once, over HTTPS, to sign this
     * one request; never stored). This is a deliberate OPT-IN separate from
     * normal sign-in above: everything else in the app works fine without
     * it, and this is only asked for when the user chooses to enable the
     * scrobbler (which needs signed track.scrobble / track.updateNowPlaying
     * calls) or wants track.scrobble.delete to work.
     */
    suspend fun obtainSessionKey(password: String): SessionKeyResult {
        val session = sessionPreferences.session.first()
        if (session.apiKey.isBlank() || session.apiSecret.isBlank()) {
            return SessionKeyResult.Failed("API key/secret required first \u2014 sign in above")
        }
        if (password.isBlank()) {
            return SessionKeyResult.Failed("Enter your Last.fm password")
        }
        return try {
            val signParams = mapOf(
                "method" to "auth.getMobileSession",
                "username" to session.username,
                "password" to password,
                "api_key" to session.apiKey,
            )
            val sig = LastFmSigner.sign(signParams, session.apiSecret)
            val body = signParams + mapOf("api_sig" to sig, "format" to "json")
            val response = api.post(body)
            val text = response.body()?.string() ?: return SessionKeyResult.Failed("Empty response from Last.fm")
            val parsed = json.parseToJsonElement(text).jsonObject
            val errorCode = (parsed["error"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
            if (errorCode != null) {
                val rawMessage = (parsed["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                return SessionKeyResult.Failed(LastFmErrors.friendlyMessage(errorCode, rawMessage))
            }
            val key = parsed["session"]?.jsonObject?.get("key")?.jsonPrimitive?.content
            if (key.isNullOrBlank()) return SessionKeyResult.Failed("Last.fm didn't return a session key")
            sessionPreferences.setSessionKey(key)
            SessionKeyResult.Success
        } catch (e: Exception) {
            SessionKeyResult.Failed(e.message ?: "Could not reach Last.fm")
        }
    }

    fun clearError() {
        if (transientState.value is AuthState.Error) transientState.value = null
    }

    /**
     * Faithful port of _lfmDeleteScrobble(). [timestampMillis] is the
     * original scrobble's `date.uts` (only present on tracks fetched from
     * user.getrecenttracks — Home's Recent list). Everywhere else in the
     * app (Playlist, Search) the original itself always passes null here,
     * so this always returns the "no timestamp" failure on those screens —
     * preserved exactly rather than silently invented a fix, since a
     * fabricated timestamp would delete the wrong scrobble on Last.fm.
     *
     * Requires a session key (`sk`), which this app's sign-in method
     * doesn't obtain (see the class doc) — so this always returns
     * AuthorizationRequired for now. Left in place rather than removed:
     * everything needed to make it work is already here (the signing,
     * the request shape) the moment a session key becomes available.
     */
    sealed interface DeleteScrobbleResult {
        data object Success : DeleteScrobbleResult
        data object AuthorizationRequired : DeleteScrobbleResult
        data object NoTimestamp : DeleteScrobbleResult
        data class Failed(val message: String) : DeleteScrobbleResult
    }

    suspend fun deleteScrobble(trackName: String, artistName: String, timestampMillis: Long?): DeleteScrobbleResult {
        val session = sessionPreferences.session.first()
        if (session.apiKey.isBlank() || session.apiSecret.isBlank()) {
            return DeleteScrobbleResult.Failed("API credentials required \u2014 go to Settings")
        }
        if (session.sessionKey.isBlank()) {
            return DeleteScrobbleResult.AuthorizationRequired
        }
        if (timestampMillis == null) {
            return DeleteScrobbleResult.NoTimestamp
        }
        val tsSec = timestampMillis / 1000
        return try {
            val signParams = mapOf(
                "method" to "track.scrobble.delete",
                "artist" to artistName,
                "track" to trackName,
                "timestamp" to tsSec.toString(),
                "sk" to session.sessionKey,
                "api_key" to session.apiKey,
            )
            val sig = LastFmSigner.sign(signParams, session.apiSecret)
            val body = signParams + mapOf("api_sig" to sig, "format" to "json")
            val response = api.post(body)
            val text = response.body()?.string() ?: return DeleteScrobbleResult.Failed("Empty response from Last.fm")
            val parsed = json.parseToJsonElement(text).let { it as? JsonObject }
            val error = parsed?.get("error")
            if (error != null) {
                val code = (error as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
                DeleteScrobbleResult.Failed(LastFmErrors.friendlyMessage(code, null))
            } else {
                DeleteScrobbleResult.Success
            }
        } catch (e: Exception) {
            DeleteScrobbleResult.Failed(e.message ?: "Could not delete scrobble")
        }
    }
}
