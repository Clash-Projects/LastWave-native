package com.lastwave.app.data.generate

import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.network.LastFmApiService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** 1 hour — exact TTL from app.js's _TASTE_PROFILE_TTL. */
private const val TASTE_PROFILE_TTL_MILLIS = 60L * 60 * 1000

/**
 * Port of _buildUserTasteProfile()'s caching wrapper: rebuilds the 4-call
 * profile snapshot at most once per hour per username, since My Mix,
 * Recommendations, and Explore-This-Genre would otherwise each pay for it
 * separately on every single playlist generation.
 */
@Singleton
class TasteProfileProvider @Inject constructor(
    private val api: LastFmApiService,
    private val sessionPreferences: SessionPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var cached: TasteProfile? = null
    private var cachedForUsername: String? = null

    private suspend fun call(params: Map<String, String>): JsonObject? {
        val session = sessionPreferences.session.first()
        if (session.apiKey.isBlank()) return null
        return try {
            val response = api.get(params + ("api_key" to session.apiKey) + ("format" to "json"))
            val body = response.body()?.string() ?: return null
            val parsed = json.parseToJsonElement(body).jsonObject
            if (parsed["error"] != null) null else parsed
        } catch (e: Exception) {
            null
        }
    }

    suspend fun get(forceRefresh: Boolean = false): TasteProfile = mutex.withLock {
        val session = sessionPreferences.session.first()
        val username = session.username

        cached?.let {
            if (!forceRefresh && cachedForUsername == username && System.currentTimeMillis() - it.builtAtMillis < TASTE_PROFILE_TTL_MILLIS) {
                return@withLock it
            }
        }

        val topTracksResult = call(mapOf("method" to "user.gettoptracks", "user" to username, "period" to "overall", "limit" to "50"))
        val recentResult = call(mapOf("method" to "user.getrecenttracks", "user" to username, "limit" to "50"))
        val topArtistsResult = call(mapOf("method" to "user.gettopartists", "user" to username, "period" to "overall", "limit" to "30"))
        val topTagsResult = call(mapOf("method" to "user.gettoptags", "user" to username, "limit" to "15"))

        val topTracksRaw = topTracksResult?.let { GenerateJson.normalise(it["toptracks"]?.jsonObject?.get("track")) } ?: emptyList()

        val recentRaw = recentResult?.let { r ->
            val raw = r["recenttracks"]?.jsonObject?.get("track")
            val withoutNowPlaying = GenerateJson.asObjectList(raw)
                .filterNot { it["@attr"]?.jsonObject?.get("nowplaying") != null }
            GenerateJson.normalise(kotlinx.serialization.json.JsonArray(withoutNowPlaying))
        } ?: emptyList()

        val topArtistNames = topArtistsResult
            ?.let { GenerateJson.namesOf(it["topartists"]?.jsonObject?.get("artist")) }
            ?.map { it.lowercase() }
            ?.toSet() ?: emptySet()

        val topTags = topTagsResult
            ?.let { GenerateJson.namesOf(it["toptags"]?.jsonObject?.get("tag")) }
            ?.map { it.lowercase() }
            ?.toSet() ?: emptySet()

        val recentArtists = recentRaw.map { it.artist.lowercase() }.toSet()
        val topTrackKeys = topTracksRaw.map { it.key }.toSet()
        val recentTrackKeys = recentRaw.map { it.key }.toSet()

        val profile = TasteProfile(
            topArtistNames = topArtistNames,
            recentArtists = recentArtists,
            topTags = topTags,
            topTrackKeys = topTrackKeys,
            recentTrackKeys = recentTrackKeys,
            topTracksRaw = topTracksRaw,
            recentTracksRaw = recentRaw,
            topArtistsRaw = topArtistNames.toList(),
            builtAtMillis = System.currentTimeMillis(),
        )
        cached = profile
        cachedForUsername = username
        profile
    }
}
