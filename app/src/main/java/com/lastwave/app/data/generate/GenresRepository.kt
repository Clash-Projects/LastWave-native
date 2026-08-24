package com.lastwave.app.data.generate

import android.util.Log
import androidx.compose.runtime.Immutable
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.network.LastFmApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GenresRepository"

@Immutable
data class GenreStat(val name: String, val count: Long, val percentOfTop: Float)

/**
 * Faithful port of genres.js's data derivation (§5.2): tries user.getTopTags
 * first; if too sparse, derives from the user's top artists' own top tags,
 * weighted by artist playcount. Also owns Genre Detail's track list (§5.3),
 * "Discover More" (§5.5), and "Explore This Genre" (§5.4) — all of which
 * reuse GenerateRepository's authenticated call path and taste profile.
 */
@Singleton
class GenresRepository @Inject constructor(
    private val api: LastFmApiService,
    private val sessionPreferences: SessionPreferences,
    private val generateRepository: GenerateRepository,
    private val tasteProfileProvider: TasteProfileProvider,
    private val viewingProfileState: com.lastwave.app.data.repository.ViewingProfileState,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Whichever profile is currently being viewed on Home (a friend's, via
     *  the friend-switcher, or your own by default) — same ViewingProfileState
     *  GenerateRepository already reads, so tapping the stats card's arrow
     *  into Genres while viewing a friend shows THEIR genre breakdown, not
     *  always your own regardless of whose profile you're actually on. */
    private suspend fun username(): String =
        viewingProfileState.viewingUsername.value ?: sessionPreferences.session.first().username

    private suspend fun call(params: Map<String, String>): JsonObject = generateRepository.call(params)

    /** Port of genres.js's period dropdown values. */
    suspend fun fetchGenreStats(period: String): List<GenreStat> {
        // Tier 1: user.getTopTags
        try {
            val d = call(mapOf("method" to "user.gettoptags", "user" to username(), "limit" to "18"))
            val tags = GenerateJson.asObjectList(d["toptags"]?.jsonObject?.get("tag"))
                .mapNotNull { obj ->
                    val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
                    val count = (obj["count"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                    name to count
                }
                .filter { it.second > 0 }
            if (tags.size >= 3) {
                return normalizeStats(tags)
            }
        } catch (e: Exception) {
            Log.d(TAG, "user.gettoptags miss", e)
        }

        // Tier 2: derive from top artists' own top tags, weighted by artist playcount
        val tasteProfile = runCatching { tasteProfileProvider.get() }.getOrNull()
        return try {
            val artistsD = call(mapOf("method" to "user.gettopartists", "user" to username(), "period" to period, "limit" to "30"))
            val artists = GenerateJson.asObjectList(artistsD["topartists"]?.jsonObject?.get("artist"))
                .mapNotNull { obj ->
                    val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
                    val playcount = (obj["playcount"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 1L
                    name to playcount
                }

            val profileArtists = (tasteProfile?.topArtistsRaw.orEmpty() +
                tasteProfile?.ytMusicFeedRaw.orEmpty().map { it.artist })
                .filter(String::isNotBlank)
                .distinctBy { it.trim().lowercase() }
                .mapIndexed { index, artist -> artist to (24L - index).coerceAtLeast(1L) }
            val weighted = mutableMapOf<String, Long>()
            coroutineScope {
                val artistTagResults = (artists + profileArtists)
                    .distinctBy { it.first.trim().lowercase() }
                    .take(14)
                    .map { (artistName, playcount) ->
                        async(Dispatchers.IO) {
                            val tags = try {
                                val td = call(mapOf("method" to "artist.gettoptags", "artist" to artistName))
                                GenerateJson.asObjectList(td["toptags"]?.jsonObject?.get("tag"))
                                    .mapNotNull { (it["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content }
                                    .take(5)
                            } catch (e: Exception) {
                                Log.d(TAG, "artist.gettoptags miss for $artistName", e)
                                emptyList()
                            }
                            (artistName to playcount) to tags
                        }
                    }.awaitAll()
                for ((artistWeight, tags) in artistTagResults) {
                    for (tag in tags) {
                        val key = tag.lowercase()
                        weighted[key] = (weighted[key] ?: 0L) + artistWeight.second
                    }
                }
            }
            val sorted = weighted.entries.sortedByDescending { it.value }.take(15).map { it.key to it.value }
            if (sorted.isNotEmpty()) {
                normalizeStats(sorted)
            } else {
                fetchChartTags()
            }
        } catch (e: Exception) {
            Log.d(TAG, "tier-2 genre derivation failed", e)
            fetchChartTags()
        }
    }

    private suspend fun fetchChartTags(): List<GenreStat> {
        return try {
            val d = call(mapOf("method" to "chart.gettoptags", "limit" to "18"))
            val tags = GenerateJson.asObjectList(d["tags"]?.jsonObject?.get("tag"))
                .mapNotNull { obj ->
                    val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
                    val count = (obj["count"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 100L
                    name to count
                }
            normalizeStats(tags)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeStats(tags: List<Pair<String, Long>>): List<GenreStat> {
        if (tags.isEmpty()) return emptyList()
        val top = tags.maxOf { it.second }.coerceAtLeast(1)
        return tags.map { (name, count) -> GenreStat(name, count, (count.toFloat() / top.toFloat())) }
    }

    /**
     * Personalized Genre Detail track list ("Your Tracks"): blends the user's
     * own scrobbles and top tracks matching this genre with top tracks from
     * their favorite artists in this genre, complemented by genre top tracks.
     */
    suspend fun fetchGenreTracks(genre: String, page: Int): List<GeneratedTrack> {
        val profile = try { tasteProfileProvider.get() } catch (_: Exception) { null }
        val targetTag = genre.lowercase().trim()
        val pool = mutableListOf<GeneratedTrack>()

        val userTopArtists = profile?.topArtistNames?.toList() ?: emptyList()

        if (page == 1 && profile != null) {
            // 1. User's own scrobbled tracks from their top / recent history
            val userTracks = (profile.topTracksRaw + profile.recentTracksRaw).distinctBy { it.key }

            // Find top artists in user's profile that match this genre
            val candidateArtists = (profile.topArtistsRaw +
                profile.ytMusicFeedRaw.map { it.artist } + userTopArtists)
                .filter(String::isNotBlank)
                .distinctBy { it.trim().lowercase() }
                .take(14)
            val genreMatchedArtists = coroutineScope {
                candidateArtists.map { artistName ->
                    async(Dispatchers.IO) {
                        try {
                            val td = call(mapOf("method" to "artist.gettoptags", "artist" to artistName))
                            val tags = GenerateJson.namesOf(td["toptags"]?.jsonObject?.get("tag"))
                                .map { it.lowercase() }
                            artistName to tags.any { it.contains(targetTag) || targetTag.contains(it) }
                        } catch (_: Exception) {
                            artistName to false
                        }
                    }
                }.awaitAll()
                    .filter { it.second }
                    .map { it.first.lowercase() }
                    .toSet()
            }

            // Include user's tracks for those artists
            for (track in userTracks) {
                if (track.artist.trim().lowercase() in genreMatchedArtists) {
                    pool += track
                }
            }

            // Fetch top tracks for user's favorite artists in this genre
            pool += coroutineScope {
                genreMatchedArtists.take(4).map { artist ->
                    async(Dispatchers.IO) {
                        try {
                            val d = call(mapOf("method" to "artist.gettoptracks", "artist" to artist, "limit" to "10"))
                            GenerateJson.normalise(d["toptracks"]?.jsonObject?.get("track")).toList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
        }

        // 2. Fetch tag's top tracks for the requested page
        try {
            val d = call(mapOf("method" to "tag.gettoptracks", "tag" to genre, "limit" to "40", "page" to page.toString()))
            pool += GenerateJson.normalise(d["tracks"]?.jsonObject?.get("track"))
        } catch (_: Exception) {}

        if (pool.isEmpty()) return emptyList()

        val deduped = generateRepository.deduplicate(pool)
        val userTopArtistSet = profile?.topArtistNames ?: emptySet()
        val userRecentArtistSet = profile?.recentArtists ?: emptySet()
        val userHeardKeys = (profile?.topTrackKeys ?: emptySet()) + (profile?.recentTrackKeys ?: emptySet())
        val ytFeedKeys = profile?.ytMusicFeedRaw?.map { it.key }?.toSet().orEmpty()
        val ytLikedKeys = profile?.ytMusicLikedRaw?.map { it.key }?.toSet().orEmpty()
        val artistAffinity = profile?.artistAffinity.orEmpty()
        val normalizedBoostArtists = sourceBoostArtists.map { it.trim().lowercase() }.toSet()

        // Blend explicit Last.fm history, YT Music feed/likes, and learned
        // artist affinity. This keeps genre results personal without making
        // every page a copy of the user's listening history.
        val scored = deduped.map { track ->
            val aKey = track.artist.trim().lowercase()
            var score = ((artistAffinity[aKey] ?: 0.0) * 20.0).toInt()
            if (track.key in userHeardKeys) score += 8
            if (track.key in ytFeedKeys) score += 10
            if (track.key in ytLikedKeys) score += 8
            if (aKey in userTopArtistSet) score += 7
            if (aKey in userRecentArtistSet) score += 5
            if (aKey in normalizedBoostArtists) score += 5
            track to score
        }

        val sorted = scored
            .groupBy { it.second }
            .entries
            .sortedByDescending { it.key }
            .flatMap { it.value.shuffled() }
            .map { it.first }
        val allowed = generateRepository.filterRecommendationExclusions(sorted)
        return generateRepository.filterPlayable(allowed).take(30)
    }

    /** Port of §5.5 Discover More: tag.gettoptracks (fresh random page) +
     *  similar-artists-of-known-genre-artists' top tracks (or a cold-start
     *  tag.gettopartists seed if the user has no known artists in this
     *  genre) + track.getsimilar for a few pool tracks — filtered against
     *  the user's own top-200 all-time history, with an unfiltered fallback
     *  if filtering leaves too few. */
    suspend fun discoverMore(genre: String): List<GeneratedTrack> = coroutineScope {
        val pool = mutableListOf<GeneratedTrack>()
        val profile = try { tasteProfileProvider.get() } catch (e: Exception) { null }

        // 1. Tag top tracks across random pages
        val tagTracksDeferred = async(Dispatchers.IO) {
            try {
                val page = (1..5).random()
                val d = call(mapOf("method" to "tag.gettoptracks", "tag" to genre.trim(), "limit" to "50", "page" to page.toString()))
                GenerateJson.normalise(d["tracks"]?.jsonObject?.get("track"))
            } catch (e: Exception) {
                Log.d(TAG, "discoverMore tag.gettoptracks miss", e)
                emptyList()
            }
        }

        // 2. Tag top artists
        val tagArtistsDeferred = async(Dispatchers.IO) {
            try {
                val d = call(mapOf("method" to "tag.gettopartists", "tag" to genre.trim(), "limit" to "12"))
                GenerateJson.namesOf(d["topartists"]?.jsonObject?.get("artist"))
            } catch (e: Exception) {
                Log.d(TAG, "discoverMore tag.gettopartists miss", e)
                emptyList()
            }
        }

        val tagTracks = tagTracksDeferred.await()
        val tagArtists = tagArtistsDeferred.await()
        pool += tagTracks

        // 3. Concurrently fetch top tracks for top genre artists
        val profileArtists = (profile?.topArtistsRaw.orEmpty() +
            profile?.ytMusicFeedRaw.orEmpty().map { it.artist })
            .filter(String::isNotBlank)
                .distinctBy { it.trim().lowercase() }
        val artistSeeds = profileArtists.shuffled().take(3) + tagArtists.shuffled().take(5)
        val artistTopTracks = artistSeeds.distinct().take(6).map { artistName ->
            async(Dispatchers.IO) {
                try {
                    val page = (1..3).random()
                    val d = call(mapOf("method" to "artist.gettoptracks", "artist" to artistName, "limit" to "6", "page" to page.toString()))
                    GenerateJson.normalise(d["toptracks"]?.jsonObject?.get("track"))
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()
        pool += artistTopTracks

        // 4. Concurrently fetch similar tracks for 2-3 pool seeds
        val trackSeeds = pool.filter { it.name.isNotBlank() && it.artist.isNotBlank() }.shuffled().take(3)
        val similarTracks = trackSeeds.map { seed ->
            async(Dispatchers.IO) {
                try {
                    val d = call(mapOf("method" to "track.getsimilar", "track" to seed.name, "artist" to seed.artist, "limit" to "8"))
                    GenerateJson.normalise(d["similartracks"]?.jsonObject?.get("track"))
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()
        pool += similarTracks

        val heardKeys = (profile?.topTracksRaw ?: emptyList()).map { it.key }.toSet()
        val deduped = generateRepository.deduplicate(pool)
        val filtered = deduped.filterNot { it.key in heardKeys }
        // Keep this surface genuinely exploratory whenever possible. Only
        // fall back to heard tracks if the provider returned no alternatives.
        val finalPool = filtered.ifEmpty { deduped }

        val affinity = profile?.artistAffinity.orEmpty()
        val ytFeedKeys = profile?.ytMusicFeedRaw?.map { it.key }?.toSet().orEmpty()
        val personalized = finalPool
            .groupBy { track ->
                val artist = track.artist.trim().lowercase()
                ((affinity[artist] ?: 0.0) * 20.0).toInt() +
                    if (track.key in ytFeedKeys) 10 else 0
            }
            .entries
            .sortedByDescending { it.key }
            .flatMap { it.value.shuffled() }

        val allowed = generateRepository.filterRecommendationExclusions(personalized)
        generateRepository.filterPlayable(allowed).take(35)
    }

    /**
     * Faithful port of §5.4's "Explore This Genre" (_doExploreGenrePlaylist):
     * personalized single-genre playlist scored by taste-profile signals.
     * [sourceBoostArtists] mirrors the original's context-aware source boost
     * (e.g. recently-played artists get +4 when opened from a "recent"
     * context) — optional, empty by default for contexts with no special
     * source framing.
     */
    suspend fun explorePersonalizedGenre(genre: String, sourceBoostArtists: Set<String> = emptySet()): List<GeneratedTrack> = coroutineScope {
        val profile = try { tasteProfileProvider.get() } catch (e: Exception) { null }
        val pool = mutableListOf<GeneratedTrack>()

        try {
            val page = (1..6).random()
            val d = call(mapOf("method" to "tag.gettoptracks", "tag" to genre, "limit" to "50", "page" to page.toString()))
            pool += GenerateJson.normalise(d["tracks"]?.jsonObject?.get("track"))
        } catch (e: Exception) { Log.d(TAG, "explorePersonalizedGenre tag.gettoptracks miss", e) }

        val knownArtists = (profile?.topArtistsRaw.orEmpty() +
            profile?.ytMusicFeedRaw.orEmpty().map { it.artist })
            .filter(String::isNotBlank)
            .distinctBy { it.trim().lowercase() }
            .shuffled()
            .take(5)
        val similarArtists = knownArtists.map { artistName ->
            async(Dispatchers.IO) {
                try {
                    val sim = call(mapOf("method" to "artist.getsimilar", "artist" to artistName, "limit" to "10"))
                    GenerateJson.namesOf(sim["similarartists"]?.jsonObject?.get("artist"))
                        .shuffled().take(2)
                } catch (e: Exception) {
                    Log.d(TAG, "explorePersonalizedGenre artist.getsimilar miss", e)
                    emptyList()
                }
            }
        }.awaitAll().flatten().distinctBy { it.trim().lowercase() }.take(10)
        pool += similarArtists.map { artistName ->
            async(Dispatchers.IO) {
                try {
                    val d = call(mapOf("method" to "artist.gettoptracks", "artist" to artistName, "limit" to "6"))
                    GenerateJson.normalise(d["toptracks"]?.jsonObject?.get("track"))
                } catch (e: Exception) {
                    Log.d(TAG, "explorePersonalizedGenre similar-artist toptracks miss", e)
                    emptyList()
                }
            }
        }.awaitAll().flatten()

        val deduped = generateRepository.deduplicate(pool)
        val normalizedSourceBoostArtists = sourceBoostArtists.map { it.trim().lowercase() }.toSet()

        val scored = deduped.map { track ->
            val artistKey = track.artist.trim().lowercase()
            var score = 0
            score += ((profile?.artistAffinity?.get(artistKey) ?: 0.0) * 18.0).toInt()
            if (profile?.topArtistNames?.contains(artistKey) == true) score += 4
            if (profile?.recentArtists?.contains(artistKey) == true) score += 3
            if (profile?.ytMusicFeedRaw?.any { it.key == track.key } == true) score += 8
            if (artistKey in normalizedSourceBoostArtists) score += 4
            track to score
        }

        val sorted = scored.sortedByDescending { it.second }
            .let { list ->
                // Shuffle within equal-score ties.
                list.groupBy { it.second }.entries.sortedByDescending { it.key }.flatMap { it.value.shuffled() }
            }
            .map { it.first }

        val allowed = generateRepository.filterRecommendationExclusions(sorted)
        generateRepository.filterPlayable(allowed).take(30)
    }
}
