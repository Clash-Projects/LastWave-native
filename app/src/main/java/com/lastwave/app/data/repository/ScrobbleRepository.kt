package com.lastwave.app.data.repository

import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.network.LastFmApiService
import com.lastwave.app.data.network.LastFmRateGuard
import com.lastwave.app.data.network.LastFmSigner
import android.os.SystemClock
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The two signed calls LastWave's own scrobbler (MediaScrobbleListenerService)
 * needs — both require a real session key (`sk`), which is only obtained if
 * the user opts into AuthRepository.obtainSessionKey.
 *
 * Reliability contract (the "scrobbling core must never visibly break"):
 * - Signed writes are serialized and paced, but a rate-limit hit NEVER fails
 *   instantly: the call waits out the shared [LastFmRateGuard] cooldown
 *   (bounded) and proceeds — the network layer's transparent retry usually
 *   resolves it before we even get here. If the wait budget expires, the
 *   scrobble is queued in [offlineQueue] and flushed automatically on the next
 *   write; nothing is dropped.
 * - Backoff state lives in the shared guard (single source of truth for the
 *   whole process) and is adaptive with jitter — no more fixed 30s process-wide
 *   kill switch that queued retries kept re-arming for minutes.
 * - [CancellationException] is never swallowed, so cancelled scopes actually
 *   stop instead of zombie-looping through failure paths.
 */
@Singleton
class ScrobbleRepository @Inject constructor(
    private val api: LastFmApiService,
    private val sessionPreferences: SessionPreferences,
    private val rateGuard: LastFmRateGuard,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val callMutex = Mutex()
    private var lastWriteAtElapsed = 0L
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
        data class Failed(
            val message: String,
            /** True when the failure is transient (rate limit / network) and the
             *  caller should retry or queue rather than treat it as permanent. */
            val retryable: Boolean = false,
        ) : Result
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

        if (result is Result.Failed && result.retryable) {
            // Queue for automatic retry on the next write; bound the queue so a
            // long offline period can't grow it without limit.
            offlineQueue.add(PendingScrobble(artist, track, album, timestampSec))
            while (offlineQueue.size > MAX_OFFLINE_QUEUE) offlineQueue.poll()
        }
        return result
    }

    private suspend fun flushPendingScrobbles() {
        if (offlineQueue.isEmpty()) return
        // Don't burn the wait budget of the CURRENT scrobble on flushing old
        // ones during an active cooldown — they'll flush after it clears.
        if (rateGuard.cooldownRemainingMs > 0L) return
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
        val session = sessionPreferences.session.first()
        if (session.apiKey.isBlank() || session.apiSecret.isBlank()) {
            return Result.Failed("API credentials missing")
        }
        if (session.sessionKey.isBlank()) {
            return Result.NoSessionKey
        }

        // A rate-limit cooldown is active: WAIT it out (bounded) instead of
        // failing instantly — writes are background work and waiting keeps the
        // scrobble alive. The guard's cooldown is adaptive and shared with the
        // network interceptor, so this window is short and self-healing.
        if (!rateGuard.suspendAwaitClearance(WRITE_COOLDOWN_WAIT_MS)) {
            return Result.Failed(
                "Rate limit cooldown active — queued for retry",
                retryable = true,
            )
        }

        // Keep signed writes spaced out on top of the global pacer; Last.fm is
        // stricter about authenticated write endpoints than reads.
        val sinceLastWrite = SystemClock.elapsedRealtime() - lastWriteAtElapsed
        if (sinceLastWrite in 1 until WRITE_SPACING_MS) {
            delay(WRITE_SPACING_MS - sinceLastWrite)
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
            lastWriteAtElapsed = SystemClock.elapsedRealtime()

            if (response.code() == 429) {
                rateGuard.onRequestLimited()
                return Result.Failed("Last.fm rate limited (429)", retryable = true)
            }

            val text = response.body()?.string() ?: return Result.Failed("Empty response from Last.fm")
            val parsed = json.parseToJsonElement(text).jsonObject
            val errorCode = (parsed["error"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
            if (errorCode != null) {
                if (errorCode == 29) {
                    rateGuard.onRequestLimited()
                    return Result.Failed("Last.fm rate limited (error 29)", retryable = true)
                }
                Result.Failed("Last.fm error $errorCode")
            } else {
                rateGuard.onRequestSucceeded()
                Result.Success
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: IOException) {
            Result.Failed(e.message ?: "Could not reach Last.fm", retryable = true)
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Could not reach Last.fm")
        }
    }

    private companion object {
        /** Writes tolerate waiting out a cooldown far longer than reads. */
        const val WRITE_COOLDOWN_WAIT_MS = 25_000L

        /** Minimum spacing between signed write requests. */
        const val WRITE_SPACING_MS = 700L

        const val MAX_OFFLINE_QUEUE = 200
    }
}
