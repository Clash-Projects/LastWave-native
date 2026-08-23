package com.lastwave.app.data.generate

import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.network.LastFmApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
        val apiKey = session.apiKey.ifBlank { com.lastwave.app.data.network.LastFmAppCredentials.API_KEY }
        return try {
            val response = api.get(params + ("api_key" to apiKey) + ("format" to "json"))
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
        val isGuest = username.isBlank() || username.equals("Guest User", ignoreCase = true)

        cached?.let {
            if (!forceRefresh && cachedForUsername == username && System.currentTimeMillis() - it.builtAtMillis < TASTE_PROFILE_TTL_MILLIS) {
                return@withLock it
            }
        }

        var topTracksRaw: List<GeneratedTrack> = emptyList()
        var recentRaw: List<GeneratedTrack> = emptyList()
        var topArtistNames: Set<String> = emptySet()
        var topTags: Set<String> = emptySet()

        if (!isGuest) {
            coroutineScope {
                val topTracksDeferred = async(Dispatchers.IO) { call(mapOf("method" to "user.gettoptracks", "user" to username, "period" to "overall", "limit" to "50")) }
                val recentDeferred = async(Dispatchers.IO) { call(mapOf("method" to "user.getrecenttracks", "user" to username, "limit" to "50")) }
                val topArtistsDeferred = async(Dispatchers.IO) { call(mapOf("method" to "user.gettopartists", "user" to username, "period" to "overall", "limit" to "30")) }
                val topTagsDeferred = async(Dispatchers.IO) { call(mapOf("method" to "user.gettoptags", "user" to username, "limit" to "15")) }

                val topTracksResult = topTracksDeferred.await()
                val recentResult = recentDeferred.await()
                val topArtistsResult = topArtistsDeferred.await()
                val topTagsResult = topTagsDeferred.await()

                topTracksRaw = topTracksResult?.let { GenerateJson.normalise(it["toptracks"]?.jsonObject?.get("track")) } ?: emptyList()

                recentRaw = recentResult?.let { r ->
                    val raw = r["recenttracks"]?.jsonObject?.get("track")
                    val withoutNowPlaying = GenerateJson.asObjectList(raw)
                        .filterNot { it["@attr"]?.jsonObject?.get("nowplaying") != null }
                    GenerateJson.normalise(kotlinx.serialization.json.JsonArray(withoutNowPlaying))
                } ?: emptyList()

                topArtistNames = topArtistsResult
                    ?.let { GenerateJson.namesOf(it["topartists"]?.jsonObject?.get("artist")) }
                    ?.map { it.lowercase() }
                    ?.toSet() ?: emptySet()

                topTags = topTagsResult
                    ?.let { GenerateJson.namesOf(it["toptags"]?.jsonObject?.get("tag")) }
                    ?.map { it.lowercase() }
                    ?.toSet() ?: emptySet()
            }
        }

        // Seamless chart seeding fallback if user data is missing or user is a guest
        if (topTracksRaw.isEmpty() && recentRaw.isEmpty()) {
            coroutineScope {
                val chartTracksDeferred = async(Dispatchers.IO) { call(mapOf("method" to "chart.gettoptracks", "limit" to "50")) }
                val chartArtistsDeferred = async(Dispatchers.IO) { call(mapOf("method" to "chart.gettopartists", "limit" to "30")) }
                val chartTagsDeferred = async(Dispatchers.IO) { call(mapOf("method" to "chart.gettoptags", "limit" to "15")) }

                val chartTracksResult = chartTracksDeferred.await()
                val chartArtistsResult = chartArtistsDeferred.await()
                val chartTagsResult = chartTagsDeferred.await()

                topTracksRaw = chartTracksResult?.let { GenerateJson.normalise(it["tracks"]?.jsonObject?.get("track")) }
                    ?: chartTracksResult?.let { GenerateJson.normalise(it["toptracks"]?.jsonObject?.get("track")) }
                    ?: emptyList()
                recentRaw = topTracksRaw

                topArtistNames = chartArtistsResult?.let { GenerateJson.namesOf(it["artists"]?.jsonObject?.get("artist")) }
                    ?.map { it.lowercase() }?.toSet() ?: emptySet()

                topTags = chartTagsResult?.let { GenerateJson.namesOf(it["tags"]?.jsonObject?.get("tag")) }
                    ?.map { it.lowercase() }?.toSet()
                    ?: setOf("rock", "indie", "pop", "electronic", "hip-hop", "synthwave", "alternative", "rnb", "jazz")
            }
        }

        val recentArtists = recentRaw.map { it.artist.lowercase() }.toSet()
        val topTrackKeys = topTracksRaw.map { it.key }.toSet()
        val recentTrackKeys = recentRaw.map { it.key }.toSet()

        val profile = TasteProfile(
            topArtistNames = topArtistNames,
            recentArtists = recentArtists,
            topTags = topTags.ifEmpty { setOf("rock", "indie", "pop", "electronic", "hip-hop", "synthwave", "alternative", "rnb") },
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
