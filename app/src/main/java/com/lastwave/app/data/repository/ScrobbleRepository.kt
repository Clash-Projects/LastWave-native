package com.lastwave.app.data.repository

import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.network.LastFmApiService
import com.lastwave.app.data.network.LastFmSigner
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two signed calls LastWave's own scrobbler (MediaScrobbleListenerService)
 * needs — both require a real session key (`sk`), which is only obtained if
 * the user opts into AuthRepository.obtainSessionKey. Everything else about
 * signing (param sorting, MD5) reuses LastFmSigner, same as
 * AuthRepository.deleteScrobble.
 */
@Singleton
class ScrobbleRepository @Inject constructor(
    private val api: LastFmApiService,
    private val sessionPreferences: SessionPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Result {
        data object Success : Result
        data object NoSessionKey : Result
        data class Failed(val message: String) : Result
    }

    suspend fun updateNowPlaying(artist: String, track: String, album: String?): Result =
        signedCall(
            method = "track.updateNowPlaying",
            extra = buildMap {
                put("artist", artist)
                put("track", track)
                if (!album.isNullOrBlank()) put("album", album)
            },
        )

    suspend fun scrobble(artist: String, track: String, album: String?, timestampSec: Long): Result =
        signedCall(
            method = "track.scrobble",
            extra = buildMap {
                put("artist", artist)
                put("track", track)
                put("timestamp", timestampSec.toString())
                if (!album.isNullOrBlank()) put("album", album)
            },
        )

    private suspend fun signedCall(method: String, extra: Map<String, String>): Result {
        val session = sessionPreferences.session.first()
        if (session.apiKey.isBlank() || session.apiSecret.isBlank()) {
            return Result.Failed("API credentials missing")
        }
        if (session.sessionKey.isBlank()) {
            return Result.NoSessionKey
        }
        return try {
            val signParams = extra + mapOf(
                "method" to method,
                "sk" to session.sessionKey,
                "api_key" to session.apiKey,
            )
            val sig = LastFmSigner.sign(signParams, session.apiSecret)
            val body = signParams + mapOf("api_sig" to sig, "format" to "json")
            val response = api.post(body)
            val text = response.body()?.string() ?: return Result.Failed("Empty response from Last.fm")
            val parsed = json.parseToJsonElement(text).jsonObject
            val errorCode = (parsed["error"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
            if (errorCode != null) {
                Result.Failed("Last.fm error $errorCode")
            } else {
                Result.Success
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Could not reach Last.fm")
        }
    }
}
