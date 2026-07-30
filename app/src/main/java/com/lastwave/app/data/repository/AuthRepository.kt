package com.lastwave.app.data.repository

import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.model.AuthState
import com.lastwave.app.data.model.SessionEnvelope
import com.lastwave.app.data.model.TokenResponse
import com.lastwave.app.data.network.LastFmApiService
import com.lastwave.app.data.network.LastFmErrors
import com.lastwave.app.data.network.LastFmException
import com.lastwave.app.data.network.LastFmSigner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_EXCHANGE_ATTEMPTS = 3

@Singleton
class AuthRepository @Inject constructor(
    private val api: LastFmApiService,
    private val sessionPreferences: SessionPreferences,
    externalScope: kotlinx.coroutines.CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Transient states not worth persisting (pending/exchanging/error);
     *  null means "defer to the persisted session". */
    private val transientState = MutableStateFlow<AuthState?>(null)

    val authState: StateFlow<AuthState> = combine(
        sessionPreferences.session,
        transientState,
    ) { session, transient ->
        transient ?: if (session.isAuthenticated) {
            AuthState.SignedIn(session.username)
        } else {
            AuthState.SignedOut
        }
    }.stateIn(externalScope, SharingStarted.WhileSubscribed(5_000), AuthState.Unknown)

    suspend fun saveApiCredentials(apiKey: String, apiSecret: String) {
        sessionPreferences.setApiCredentials(
            LastFmSigner.normalizeKey(apiKey),
            LastFmSigner.normalizeKey(apiSecret),
        )
    }

    /** Step 1+2 — request an unsigned token, return the Last.fm authorize URL
     *  to open in Chrome Custom Tabs / the system browser. */
    suspend fun startAuth(apiKey: String): Result<String> {
        transientState.value = AuthState.RequestingToken
        return try {
            val keyNorm = LastFmSigner.normalizeKey(apiKey)
            if (keyNorm.length < 16) {
                throw LastFmException("API key is too short after normalisation — please check it in Settings.")
            }
            val response = api.get(
                mapOf(
                    "method" to "auth.getToken",
                    "api_key" to keyNorm,
                    "format" to "json",
                )
            )
            val body = response.body()?.string() ?: throw LastFmException("Empty response from Last.fm")
            val parsed = json.decodeFromString<TokenResponse>(body)
            if (parsed.error != null || parsed.token == null) {
                throw LastFmException(LastFmErrors.friendlyMessage(parsed.error, parsed.message), parsed.error)
            }
            sessionPreferences.setPendingToken(parsed.token)
            transientState.value = AuthState.AwaitingAuthorization
            Result.success("https://www.last.fm/api/auth/?api_key=${keyNorm}&token=${parsed.token}")
        } catch (e: Exception) {
            val message = (e as? LastFmException)?.message ?: (e.message ?: "Could not request a token")
            transientState.value = AuthState.Error(message)
            Result.failure(e)
        }
    }

    /** Step 5+6 — exchange the approved token for a session key (signed POST),
     *  retrying up to [MAX_EXCHANGE_ATTEMPTS] times with back-off, since the
     *  user may tap through before Last.fm has fully registered the approval. */
    suspend fun exchangeToken(apiKey: String, apiSecret: String, token: String): Result<String> {
        transientState.value = AuthState.ExchangingToken
        val keyNorm = LastFmSigner.normalizeKey(apiKey)
        val secretNorm = LastFmSigner.normalizeKey(apiSecret)

        if (keyNorm.length < 16 || secretNorm.length < 16) {
            val message = "API key/secret is too short after normalisation — please check it in Settings."
            transientState.value = AuthState.Error(message)
            return Result.failure(LastFmException(message))
        }

        var lastError: Exception? = null
        for (attempt in 1..MAX_EXCHANGE_ATTEMPTS) {
            try {
                val signParams = mapOf(
                    "method" to "auth.getSession",
                    "token" to token,
                    "api_key" to keyNorm,
                )
                val sig = LastFmSigner.sign(signParams, secretNorm)
                val body = signParams + mapOf("api_sig" to sig, "format" to "json")

                val response = api.post(body)
                val text = response.body()?.string() ?: throw LastFmException("Empty response from Last.fm")
                val parsed = json.decodeFromString<SessionEnvelope>(text)

                if (parsed.error != null || parsed.session == null) {
                    throw LastFmException(LastFmErrors.friendlyMessage(parsed.error, parsed.message), parsed.error)
                }

                sessionPreferences.setAuthenticatedSession(parsed.session.key, parsed.session.name)
                transientState.value = null // defer to persisted SignedIn state
                return Result.success(parsed.session.name)
            } catch (e: Exception) {
                lastError = e
                if (attempt < MAX_EXCHANGE_ATTEMPTS) {
                    delay(1500L * attempt)
                }
            }
        }

        val message = (lastError as? LastFmException)?.message ?: (lastError?.message ?: "Authorization failed")
        transientState.value = AuthState.Error(message)
        return Result.failure(lastError ?: LastFmException(message))
    }

    /** Manual fallback — user taps "I've authorized, continue" instead of
     *  relying on the deep link firing; reads back the token stashed in
     *  startAuth() so the exchange can proceed without a fresh deep link. */
    suspend fun pendingToken(): String? =
        sessionPreferences.session.first().pendingAuthToken.takeIf { it.isNotBlank() }

    suspend fun signOut() {
        transientState.value = null
        sessionPreferences.signOut()
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
            val parsed = json.parseToJsonElement(text).let { it as? kotlinx.serialization.json.JsonObject }
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
