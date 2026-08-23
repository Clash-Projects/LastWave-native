package com.lastwave.app.data.artwork

import android.util.Log
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.model.TrackInfoEnvelope
import com.lastwave.app.data.network.LastFmApiService
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ArtworkPipeline"
private const val CRASH_TAG = "ArtworkCrash"

/** Port of _resolveTrackArt()'s Step 1 — a dedicated track.getInfo call,
 *  preferring album art (best quality) and falling back to the track's own
 *  image, both filtered through [ArtworkNormalizer.isRealImage]. */
@Singleton
class LastFmTrackInfoProvider @Inject constructor(
    private val api: LastFmApiService,
    private val sessionPreferences: SessionPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchArtworkUrl(name: String, artist: String): String? {
        val session = sessionPreferences.session.first()
        val apiKey = session.apiKey.ifBlank { com.lastwave.app.data.network.LastFmAppCredentials.API_KEY }
        val params = mapOf(
            "method" to "track.getInfo",
            "track" to name,
            "artist" to artist,
            "autocorrect" to "1",
            "api_key" to apiKey,
            "format" to "json",
        )
        val requestUrl = "https://ws.audioscrobbler.com/2.0/?" + params.entries.joinToString("&") { "${it.key}=${it.value}" }
        return try {
            val response = api.get(params)
            Log.d(TAG, "Provider: lastfm | Track: $name | Artist: $artist | Request URL: $requestUrl | Response code: ${response.code()}")
            if (!response.isSuccessful) {
                Log.d(TAG, "Provider: lastfm | Track: $name | Artist: $artist | Result: miss | Reason: HTTP ${response.code()}")
                return null
            }
            val body = response.body()?.string()
            if (body.isNullOrBlank()) {
                Log.d(TAG, "Provider: lastfm | Track: $name | Artist: $artist | Result: miss | Reason: empty response body")
                return null
            }
            val parsed = json.decodeFromString<TrackInfoEnvelope>(body)
            val albumArt = parsed.track?.album?.image?.let { ArtworkNormalizer.bestImageUrl(it) }
            val trackArt = parsed.track?.image?.let { ArtworkNormalizer.bestImageUrl(it) }
            val result = albumArt ?: trackArt
            Log.d(TAG, "Provider: lastfm | Track: $name | Artist: $artist | Result: ${if (result != null) "hit" else "miss"}")
            result
        } catch (e: Exception) {
            Log.e(CRASH_TAG, "Provider: lastfm | Track: $name | Artist: $artist | Request URL: $requestUrl | Exception during fetch/parse", e)
            null
        }
    }
}
