package com.lastwave.app.data.generate

import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.network.LastFmApiService
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
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
    private val innerTube: InnerTubeMusicApi,
    private val ytMusicAuth: YtMusicAuthManager,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var cached: TasteProfile? = null
    private var cachedForUsername: String? = null
    private var cachedForYtAccount: String? = null

    private fun YouTubeMusicTrack.toGeneratedTrack() = GeneratedTrack(
        name = title,
        artist = artist,
        artworkUrl = artworkUrl,
        url = "https://music.youtube.com/watch?v=$videoId",
        album = album,
    )

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
        val ytAccountKey = ytMusicAuth.connection.value
            .takeIf { it.isConnected }
            ?.let { "${it.accountName}|${it.connectedAtMillis}" }
            .orEmpty()

        cached?.let {
            if (!forceRefresh && cachedForUsername == username && cachedForYtAccount == ytAccountKey &&
                System.currentTimeMillis() - it.builtAtMillis < TASTE_PROFILE_TTL_MILLIS
            ) {
                return@withLock it
            }
        }

        var topTracksRaw: List<GeneratedTrack> = emptyList()
        var recentRaw: List<GeneratedTrack> = emptyList()
        var topArtistNames: Set<String> = emptySet()
        var topArtistsRaw: List<String> = emptyList()
        var topTags: Set<String> = emptySet()
        var ytMusicRecentRaw: List<GeneratedTrack> = emptyList()
        var ytMusicLikedRaw: List<GeneratedTrack> = emptyList()
        var ytMusicFeedRaw: List<GeneratedTrack> = emptyList()

        coroutineScope {
            val ytTasteDeferred = async(Dispatchers.IO) {
                runCatching { innerTube.fetchTasteSignals() }.getOrNull()
            }
            if (!isGuest) {
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

                topArtistsRaw = topArtistsResult
                    ?.let { GenerateJson.namesOf(it["topartists"]?.jsonObject?.get("artist")) }
                    ?: emptyList()
                topArtistNames = topArtistsRaw.map { it.trim().lowercase() }.toSet()

                topTags = topTagsResult
                    ?.let { GenerateJson.namesOf(it["toptags"]?.jsonObject?.get("tag")) }
                    ?.map { it.lowercase() }
                    ?.toSet() ?: emptySet()
            }
            val ytTaste = ytTasteDeferred.await()
            ytMusicRecentRaw = ytTaste?.recentTracks.orEmpty().map { it.toGeneratedTrack() }
            ytMusicLikedRaw = ytTaste?.likedTracks.orEmpty().map { it.toGeneratedTrack() }
            ytMusicFeedRaw = ytTaste?.feedTracks.orEmpty().map { it.toGeneratedTrack() }
        }

        val hadLastFmTaste = topTracksRaw.isNotEmpty() || recentRaw.isNotEmpty() ||
            topArtistNames.isNotEmpty() || topTags.isNotEmpty()
        val hasPersonalSignals = hadLastFmTaste || ytMusicRecentRaw.isNotEmpty() ||
            ytMusicLikedRaw.isNotEmpty() || ytMusicFeedRaw.isNotEmpty()

        // Seamless chart seeding fallback if user data is missing or user is a guest
        if (!hasPersonalSignals) {
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

                topArtistsRaw = chartArtistsResult?.let { GenerateJson.namesOf(it["artists"]?.jsonObject?.get("artist")) }
                    ?: emptyList()
                topArtistNames = topArtistsRaw.map { it.trim().lowercase() }.toSet()

                topTags = chartTagsResult?.let { GenerateJson.namesOf(it["tags"]?.jsonObject?.get("tag")) }
                    ?.map { it.lowercase() }?.toSet()
                    ?: setOf("rock", "indie", "pop", "electronic", "hip-hop", "synthwave", "alternative", "rnb", "jazz")
            }
        }

        val lastFmOrChartTop = topTracksRaw
        val lastFmOrChartRecent = recentRaw
        val lastFmOrChartTopArtists = topArtistsRaw
        val ytArtistNames = (ytMusicRecentRaw + ytMusicLikedRaw + ytMusicFeedRaw)
            .filter { it.artist.isNotBlank() }
            .groupingBy { it.artist.trim() }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
        topArtistsRaw = (topArtistsRaw + ytArtistNames.take(8))
            .distinctBy { it.lowercase() }
        topArtistNames = topArtistsRaw.map { it.trim().lowercase() }.toSet()

        // YT Music supplements the Last.fm profile with bounded samples. Its
        // Home feed is also passed through as a candidate source, while
        // Last.fm remains the strongest historical signal.
        topTracksRaw = (lastFmOrChartTop + ytMusicLikedRaw.take(8)).distinctBy { it.key }
        recentRaw = (lastFmOrChartRecent + ytMusicRecentRaw.take(10)).distinctBy { it.key }

        val affinity = mutableMapOf<String, Double>()
        fun addAffinity(artist: String, amount: Double) {
            val key = artist.trim().lowercase()
            if (key.isNotBlank()) affinity[key] = (affinity[key] ?: 0.0) + amount
        }
        lastFmOrChartTopArtists.take(30).forEachIndexed { index, artist ->
            addAffinity(artist, 1.45 / (1.0 + index / 12.0))
        }
        lastFmOrChartRecent.take(50).forEachIndexed { index, track ->
            addAffinity(track.artist, 1.30 / (1.0 + index / 14.0))
        }
        val maxTrackPlaycount = lastFmOrChartTop
            .mapNotNull { it.playcount }
            .maxOrNull()
            ?.takeIf { it > 0L }
            ?: 0L
        lastFmOrChartTop.take(50).forEachIndexed { index, track ->
            val playSignal = if (maxTrackPlaycount > 0L) {
                (track.playcount ?: 0L).toDouble() / maxTrackPlaycount.toDouble()
            } else 0.0
            addAffinity(
                track.artist,
                (0.75 + playSignal.coerceIn(0.0, 1.0) * 0.9) / (1.0 + index / 18.0),
            )
        }
        val ytAffinity = mutableMapOf<String, Double>()
        fun addYtAffinity(artist: String, amount: Double) {
            val key = artist.trim().lowercase()
            if (key.isNotBlank()) ytAffinity[key] = (ytAffinity[key] ?: 0.0) + amount
        }
        ytMusicRecentRaw.forEachIndexed { index, track ->
            addYtAffinity(track.artist, 0.48 / (1.0 + index / 12.0))
        }
        ytMusicLikedRaw.forEachIndexed { index, track ->
            addYtAffinity(track.artist, 0.32 / (1.0 + index / 16.0))
        }
        ytMusicFeedRaw.forEachIndexed { index, track ->
            addYtAffinity(track.artist, 0.38 / (1.0 + index / 18.0))
        }
        ytAffinity.forEach { (artist, value) ->
            addAffinity(artist, value.coerceAtMost(1.4))
        }
        val maxAffinity = affinity.values.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val normalizedAffinity = affinity.mapValues { (_, value) -> (value / maxAffinity).coerceIn(0.0, 1.0) }

        val recentArtists = recentRaw.map { it.artist.trim().lowercase() }.toSet()
        val topTrackKeys = (topTracksRaw + ytMusicLikedRaw).map { it.key }.toSet()
        val recentTrackKeys = (recentRaw + ytMusicRecentRaw).map { it.key }.toSet()
        val resolvedTags = topTags.ifEmpty {
            if (hasPersonalSignals) emptySet()
            else setOf("rock", "indie", "pop", "electronic", "hip-hop", "synthwave", "alternative", "rnb")
        }

        val profile = TasteProfile(
            topArtistNames = topArtistNames,
            recentArtists = recentArtists,
            topTags = resolvedTags,
            topTrackKeys = topTrackKeys,
            recentTrackKeys = recentTrackKeys,
            topTracksRaw = topTracksRaw,
            recentTracksRaw = recentRaw,
            topArtistsRaw = topArtistsRaw,
            builtAtMillis = System.currentTimeMillis(),
            artistAffinity = normalizedAffinity,
            ytMusicRecentRaw = ytMusicRecentRaw,
            ytMusicLikedRaw = ytMusicLikedRaw,
            ytMusicFeedRaw = ytMusicFeedRaw,
            hasPersonalSignals = hasPersonalSignals,
        )
        cached = profile
        cachedForUsername = username
        cachedForYtAccount = ytAccountKey
        profile
    }
}
