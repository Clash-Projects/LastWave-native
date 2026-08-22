package com.lastwave.app.data.repository

import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.network.LastFmApiService
import com.lastwave.app.data.network.LastFmSigner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two signed calls LastWave's own scrobbler (MediaScrobbleListenerService)
 * needs — both require a real session key (`sk`), which is only obtained if
 * the user opts into AuthRepository.obtainSessionKey.
 *
 * Implements rate-pacing (max 1 signed call per second), now-playing deduplication,
 * and rate-limit backoff to protect personal user scrobbles from hitting IP limits.
 */
@Singleton
class ScrobbleRepository @Inject constructor(
    private val api: LastFmApiService,
    private val sessionPreferences: SessionPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val callMutex = Mutex()
    private var lastCallTimestamp = 0L
    private var backoffUntilMillis = 0L
    @Volatile private var lastNowPlayingKey: String? = null

    private data class PendingScrobble(
        val artist: String,
        val track: String,
        val album: String?,
        val timestampSec: Long,
    )
    private val offlineQueue = ConcurrentLinkedQueue<PendingScrobble>()

    sealed interface Result {
        data object Success : Result
        data object NoSessionKey : Result
        data class Failed(val message: String) : Result
    }

    suspend fun updateNowPlaying(artist: String, track: String, album: String?): Result {
        val key = "${artist.lowercase().trim()}|${track.lowercase().trim()}"
        if (key == lastNowPlayingKey) {
            return Result.Success // Already announced on Last.fm, 0 network calls
        }

        val result = signedCall(
            method = "track.updateNowPlaying",
            extra = buildMap {
                put("artist", artist)
                put("track", track)
                if (!album.isNullOrBlank()) put("album", album)
            },
        )
        if (result is Result.Success) {
            lastNowPlayingKey = key
        }
        return result
    }

    suspend fun scrobble(artist: String, track: String, album: String?, timestampSec: Long): Result {
        // Try flushing any previously queued offline scrobbles first
        flushPendingScrobbles()

        val result = signedCall(
            method = "track.scrobble",
            extra = buildMap {
                put("artist", artist)
                put("track", track)
                put("timestamp", timestampSec.toString())
                if (!album.isNullOrBlank()) put("album", album)
            },
        )

        if (result is Result.Failed && (result.message.contains("29") || result.message.contains("timeout") || result.message.contains("reach"))) {
            // Queue for automatic retry if network or rate limit failed
            offlineQueue.add(PendingScrobble(artist, track, album, timestampSec))
        }
        return result
    }

    private suspend fun flushPendingScrobbles() {
        if (offlineQueue.isEmpty() || System.currentTimeMillis() < backoffUntilMillis) return
        while (offlineQueue.isNotEmpty()) {
            val pending = offlineQueue.peek() ?: break
            val res = signedCall(
                method = "track.scrobble",
                extra = buildMap {
                    put("artist", pending.artist)
                    put("track", pending.track)
                    put("timestamp", pending.timestampSec.toString())
                    if (!pending.album.isNullOrBlank()) put("album", pending.album)
                },
            )
            if (res is Result.Success) {
                offlineQueue.poll()
            } else {
                break
            }
        }
    }

    private suspend fun signedCall(method: String, extra: Map<String, String>): Result = callMutex.withLock {
        val now = System.currentTimeMillis()
        if (now < backoffUntilMillis) {
            return Result.Failed("Rate limit cooldown active, retrying shortly")
        }

        // Rate pacing: Ensure at least 800ms between signed POST write requests
        val elapsed = now - lastCallTimestamp
        if (elapsed < 800L) {
            delay(800L - elapsed)
        }

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
            lastCallTimestamp = System.currentTimeMillis()

            if (response.code() == 429) {
                backoffUntilMillis = System.currentTimeMillis() + 30_000L
                return Result.Failed("Last.fm rate limited (429)")
            }

            val text = response.body()?.string() ?: return Result.Failed("Empty response from Last.fm")
            val parsed = json.parseToJsonElement(text).jsonObject
            val errorCode = (parsed["error"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
            if (errorCode != null) {
                if (errorCode == 29) {
                    backoffUntilMillis = System.currentTimeMillis() + 30_000L
                }
                Result.Failed("Last.fm error $errorCode")
            } else {
                Result.Success
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Could not reach Last.fm")
        }
    }
}

