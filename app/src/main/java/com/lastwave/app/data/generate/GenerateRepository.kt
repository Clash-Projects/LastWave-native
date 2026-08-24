package com.lastwave.app.data.generate

import android.util.Log
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.local.db.RecommendationExclusionDao
import com.lastwave.app.data.local.db.RecommendationExclusionEntity
import com.lastwave.app.data.network.LastFmApiService
import com.lastwave.app.data.playlist.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GenerateRepository"
const val RECOMMENDATION_TRACK_COUNT = 35

/** Floor for regenerated "inspired" mixes — the generator relaxes artist
 *  diversity and widens its discovery net before ever returning fewer. */
const val MIN_TASTE_MIX_SIZE = 20

@Singleton
class GenerateRepository @Inject constructor(
    private val api: LastFmApiService,
    private val sessionPreferences: SessionPreferences,
    private val recommendationExclusionDao: RecommendationExclusionDao,
    private val tasteProfileProvider: TasteProfileProvider,
    private val playlistRepository: PlaylistRepository,
    private val viewingProfileState: com.lastwave.app.data.repository.ViewingProfileState,
    private val innerTube: com.lastwave.app.data.music.InnerTubeMusicApi,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightRequests = ConcurrentHashMap<String, Deferred<JsonObject>>()
    private val lastFmRequestGate = Semaphore(5)
    private val exclusionMutex = Mutex()
    @Volatile private var exclusionKeysCache: Set<String>? = null

    // Collapses concurrent, identical in-flight requests (e.g. two parallel
    // branches of fetchMix both wanting the same artist's top tracks at the
    // same moment) into a single network call. Entries are removed the
    // instant their call finishes — this is purely about not paying twice
    // for the same request at the same time, never a longer-lived/stale
    suspend fun call(params: Map<String, String>): JsonObject {
        val session = sessionPreferences.session.first()
        val apiKey = session.apiKey.ifBlank { com.lastwave.app.data.network.LastFmAppCredentials.API_KEY }
        val requestParams = params + ("api_key" to apiKey) + ("format" to "json")
        // Keep the in-flight key deterministic without retaining the private
        // API key in memory longer than the request itself.
        val requestKey = buildString {
            append(apiKey.hashCode())
            append('|')
            append(
                params.toSortedMap().entries.joinToString("&") {
                    "${it.key.length}:${it.key}=${it.value.length}:${it.value}"
                },
            )
        }
        val deferred = inFlightRequests.computeIfAbsent(requestKey) {
            requestScope.async {
                val response = lastFmRequestGate.withPermit { api.get(requestParams) }
                val body = response.body()?.string()
                if (!response.isSuccessful || body.isNullOrBlank()) {
                    throw IllegalStateException("Last.fm request failed (${response.code()})")
                }
                val parsed = json.parseToJsonElement(body).jsonObject
                parsed["error"]?.let {
                    throw IllegalStateException(parsed["message"]?.toString() ?: "Last.fm error")
                }
                parsed
            }.also { request ->
                request.invokeOnCompletion { inFlightRequests.remove(requestKey, request) }
            }
        }
        return try {
            deferred.await()
        } finally {
            if (deferred.isCompleted) inFlightRequests.remove(requestKey, deferred)
        }
    }



    /** Whichever profile is currently being viewed on Home (see
     *  ViewingProfileState) — a friend's username if the friend-switcher is
     *  active there, otherwise the signed-in session's own username. */
    private suspend fun username(): String =
        viewingProfileState.viewingUsername.value ?: sessionPreferences.session.first().username

    // ── Shared helpers — exact ports of shuffleArray / deduplicateTracks / _precheckTracks ──

    fun shuffle(tracks: List<GeneratedTrack>): List<GeneratedTrack> = tracks.shuffled()

    fun deduplicate(tracks: List<GeneratedTrack>): List<GeneratedTrack> {
        val seen = mutableSetOf<String>()
        return tracks.filter { seen.add(it.key) }
    }

    /** Port of _precheckTracks(): dedupe + cap at 3 tracks per artist. */
    fun precheck(tracks: List<GeneratedTrack>): List<GeneratedTrack> {
        val valid = tracks.filter { it.name.isNotBlank() && it.artist.isNotBlank() }
        val deduped = deduplicate(valid)
        val artistCount = mutableMapOf<String, Int>()
        return deduped.filter {
            val key = it.artist.lowercase().trim()
            val count = (artistCount[key] ?: 0) + 1
            artistCount[key] = count
            count <= 3
        }
    }

    /** Instant non-blocking pass-through matching web app.js generation speed.
     *  Audio resolution is performed on-demand when playing tracks. */
    suspend fun filterPlayable(tracks: List<GeneratedTrack>): List<GeneratedTrack> = tracks


    // Explicit-only recommendation exclusions. Nothing is added automatically.

    suspend fun excludeFromRecommendations(trackName: String, artistName: String): Boolean {
        if (trackName.isBlank() || artistName.isBlank()) return false
        val track = GeneratedTrack(trackName, artistName, artworkUrl = null)
        return exclusionMutex.withLock {
            try {
                recommendationExclusionDao.upsert(
                    RecommendationExclusionEntity(
                        trackKey = track.key,
                        excludedAtMillis = System.currentTimeMillis(),
                        trackName = trackName.trim(),
                        artistName = artistName.trim(),
                    ),
                )
                exclusionKeysCache = (exclusionKeysCache ?: recommendationExclusionDao.getAll()
                    .mapTo(mutableSetOf()) { it.trackKey }) + track.key
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to exclude track from recommendations", e)
                false
            }
        }
    }

    suspend fun recommendationExclusionKeys(): Set<String> {
        exclusionKeysCache?.let { return it }
        return exclusionMutex.withLock {
            exclusionKeysCache ?: try {
                recommendationExclusionDao.getAll().mapTo(mutableSetOf()) { it.trackKey }
                    .also { exclusionKeysCache = it }
            } catch (e: Exception) {
                Log.e(TAG, "Recommendation-exclusion read failed", e)
                emptySet()
            }
        }
    }

    /** Hard exclusion: these tracks never return until the list is cleared. */
    suspend fun filterRecommendationExclusions(tracks: List<GeneratedTrack>): List<GeneratedTrack> {
        if (tracks.isEmpty()) return emptyList()
        val exclusions = recommendationExclusionKeys()
        return tracks.filterNot { it.key in exclusions }
    }

    suspend fun clearRecommendationExclusions() = exclusionMutex.withLock {
        try {
            recommendationExclusionDao.clear()
            exclusionKeysCache = emptySet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear recommendation exclusions", e)
        }
    }

    suspend fun recommendationExclusionCount(): Int = recommendationExclusionKeys().size

    fun observeRecommendationExclusions() = recommendationExclusionDao.observeAll()

    suspend fun removeRecommendationExclusion(trackKey: String): Boolean = exclusionMutex.withLock {
        try {
            recommendationExclusionDao.delete(trackKey)
            exclusionKeysCache = exclusionKeysCache?.minus(trackKey)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore excluded recommendation", e)
            false
        }
    }

    fun invalidateRecommendationExclusionCache() {
        exclusionKeysCache = null
    }

    // ── Fetch modes ──

    suspend fun fetchChartTracks(limit: Int = 30): List<GeneratedTrack> {
        val page = (1..3).random()
        return try {
            val result = call(mapOf("method" to "chart.gettoptracks", "limit" to (limit * 2).coerceAtLeast(limit).toString(), "page" to page.toString()))
            val tracks = GenerateJson.normalise(result["tracks"]?.jsonObject?.get("track"))
            filterPlayable(shuffle(filterRecommendationExclusions(tracks))).take(limit)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun fetchTopTracks(limit: Int, period: String = "overall"): List<GeneratedTrack> {
        val user = username()
        if (user.isBlank() || user.equals("Guest User", ignoreCase = true)) {
            return fetchChartTracks(limit)
        }
        val page = (1..3).random()
        return try {
            val result = call(
                mapOf("method" to "user.gettoptracks", "user" to user, "period" to period, "limit" to (limit * 2).coerceAtLeast(limit).toString(), "page" to page.toString()),
            )
            val tracks = GenerateJson.normalise(result["toptracks"]?.jsonObject?.get("track"))
            val playable = filterPlayable(shuffle(filterRecommendationExclusions(tracks))).take(limit)
            if (playable.isNotEmpty()) playable else fetchChartTracks(limit)
        } catch (e: Exception) {
            fetchChartTracks(limit)
        }
    }

    suspend fun fetchRecentTracks(limit: Int): List<GeneratedTrack> {
        val user = username()
        if (user.isBlank() || user.equals("Guest User", ignoreCase = true)) {
            return fetchChartTracks(limit)
        }
        return try {
            val result = call(mapOf("method" to "user.getrecenttracks", "user" to user, "limit" to (limit * 2).coerceAtLeast(limit).toString()))
            val raw = result["recenttracks"]?.jsonObject?.get("track")
            val withoutNowPlaying = GenerateJson.asObjectList(raw)
                .filterNot { it["@attr"]?.jsonObject?.get("nowplaying") != null }
            val tracks = filterPlayable(
                shuffle(filterRecommendationExclusions(GenerateJson.normalise(JsonArray(withoutNowPlaying)))),
            ).take(limit)
            if (tracks.isNotEmpty()) tracks else fetchChartTracks(limit)
        } catch (e: Exception) {
            fetchChartTracks(limit)
        }
    }

    suspend fun fetchSimilarTracks(track: String, artist: String, limit: Int): List<GeneratedTrack> {
        val result = call(
            mapOf("method" to "track.getsimilar", "track" to track, "artist" to artist, "limit" to minOf(limit * 4, 200).toString()),
        )
        val all = GenerateJson.normalise(result["similartracks"]?.jsonObject?.get("track"))
        val allowed = filterRecommendationExclusions(all)
        return filterPlayable(shuffle(allowed)).take(limit)
    }

    suspend fun fetchSimilarArtistTracks(artist: String, limit: Int): List<GeneratedTrack> {
        val result = call(mapOf("method" to "artist.getsimilar", "artist" to artist, "limit" to "20"))
        val artistNames = GenerateJson.namesOf(result["similarartists"]?.jsonObject?.get("artist")).shuffled().take(8)
        val allTracks = coroutineScope {
            artistNames.map { a ->
                async {
                    try {
                        val page = (1..4).random()
                        val r = call(mapOf("method" to "artist.gettoptracks", "artist" to a, "limit" to kotlin.math.ceil(limit / 5.0).toInt().toString(), "page" to page.toString()))
                        GenerateJson.normalise(r["toptracks"]?.jsonObject?.get("track"))
                    } catch (e: Exception) {
                        Log.e(TAG, "artist.gettoptracks failed for $a, continuing with other artists", e)
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        val allowed = filterRecommendationExclusions(allTracks)
        return filterPlayable(shuffle(allowed)).take(limit)
    }

    suspend fun fetchTagTracks(tag: String, limit: Int): List<GeneratedTrack> {
        val page = (1..8).random()
        val result = call(mapOf("method" to "tag.gettoptracks", "tag" to tag, "limit" to minOf(limit * 3, 100).toString(), "page" to page.toString()))
        val all = GenerateJson.normalise(result["tracks"]?.jsonObject?.get("track"))
        val allowed = filterRecommendationExclusions(all)
        return filterPlayable(shuffle(allowed)).take(limit)
    }

    // ── My Mix — exact port of fetchMix(): 3-tier weighted blend ──

    suspend fun fetchMix(total: Int, onProgress: (String) -> Unit = {}): List<GeneratedTrack> {
        onProgress("Discovering tracks for you\u2026")
        data class Weighted(val track: GeneratedTrack, val weight: Int)
        val weighted = mutableListOf<Weighted>()
        val tasteProfile = runCatching { tasteProfileProvider.get() }.getOrNull()
        var topArtists: List<String> = tasteProfile?.topArtistsRaw.orEmpty()

        // Actual playable picks from the connected account's Home feed.
        tasteProfile?.ytMusicFeedRaw.orEmpty()
            .shuffled()
            .take(maxOf(8, total / 3).coerceAtMost(16))
            .forEach { weighted += Weighted(it, 3) }

        // Bucket A — weight 3: recent plays + similar
        try {
            onProgress("Personalising from recent plays\u2026")
            val rd = call(mapOf("method" to "user.getrecenttracks", "user" to username(), "limit" to "50"))
            val rRaw = rd["recenttracks"]?.jsonObject?.get("track")
            val withoutNowPlaying = GenerateJson.asObjectList(rRaw).filterNot { it["@attr"]?.jsonObject?.get("nowplaying") != null }
            val recent = GenerateJson.normalise(JsonArray(withoutNowPlaying))
            val recentSeeds = recent.shuffled().take(6)
            recentSeeds.forEach { weighted += Weighted(it, 3) }

            val similarToRecent = coroutineScope {
                recentSeeds.take(4).filter { it.name.isNotBlank() && it.artist.isNotBlank() }.map { t ->
                    async {
                        try {
                            val d = call(mapOf("method" to "track.getsimilar", "track" to t.name, "artist" to t.artist, "limit" to kotlin.math.ceil(total / 6.0).toInt().toString()))
                            GenerateJson.normalise(d["similartracks"]?.jsonObject?.get("track"))
                        } catch (e: Exception) {
                            Log.d(TAG, "fetchMix similar-to-recent miss", e)
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
            similarToRecent.forEach { weighted += Weighted(it, 3) }
        } catch (e: Exception) { Log.d(TAG, "fetchMix bucket A miss", e) }

        // Connected YT Music is supplementary: use at most three seeds while
        // Last.fm remains the primary taste source whenever it is available.
        val ytSeeds = buildList {
            addAll(tasteProfile?.ytMusicRecentRaw.orEmpty().shuffled().take(2))
            addAll(tasteProfile?.ytMusicLikedRaw.orEmpty().shuffled().take(1))
            addAll(tasteProfile?.ytMusicFeedRaw.orEmpty().shuffled().take(1))
        }.distinctBy(GeneratedTrack::key).shuffled().take(3)
        ytSeeds.forEach { weighted += Weighted(it, 2) }
        val similarToYtTaste = coroutineScope {
            ytSeeds.map { seed ->
                async {
                    try {
                        val data = call(
                            mapOf(
                                "method" to "track.getsimilar",
                                "track" to seed.name,
                                "artist" to seed.artist,
                                "limit" to maxOf(6, total / 5).toString(),
                            ),
                        )
                        GenerateJson.normalise(data["similartracks"]?.jsonObject?.get("track"))
                    } catch (e: Exception) {
                        Log.d(TAG, "fetchMix YT-taste seed miss", e)
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        similarToYtTaste.forEach { weighted += Weighted(it, 3) }

        // Bucket B — weight 2: confirmed top tracks (randomized period)
        try {
            onProgress("Pulling in your top tracks\u2026")
            val r = Math.random()
            val period = if (r < 0.4) "1month" else if (r < 0.7) "3month" else if (r < 0.9) "6month" else "12month"
            val topD = call(mapOf("method" to "user.gettoptracks", "user" to username(), "period" to period, "limit" to "30"))
            GenerateJson.normalise(topD["toptracks"]?.jsonObject?.get("track")).forEach { weighted += Weighted(it, 2) }
        } catch (e: Exception) { Log.d(TAG, "fetchMix bucket B miss", e) }

        // Bucket B2 — weight 2: top artists -> similar-artist top tracks
        try {
            val r = Math.random()
            val period = if (r < 0.5) "overall" else if (r < 0.75) "12month" else "6month"
            val d = call(mapOf("method" to "user.gettopartists", "user" to username(), "period" to period, "limit" to "30"))
            val lastFmTopArtists = GenerateJson.namesOf(d["topartists"]?.jsonObject?.get("artist"))
            if (lastFmTopArtists.isNotEmpty()) topArtists = lastFmTopArtists
        } catch (e: Exception) { Log.d(TAG, "fetchMix bucket B2 top-artists miss", e) }

        val bucketB2 = coroutineScope {
            topArtists.shuffled().take(3).map { artist ->
                async {
                    val result = mutableListOf<Weighted>()
                    try {
                        onProgress("Exploring artists like $artist\u2026")
                        val sim = call(mapOf("method" to "artist.getsimilar", "artist" to artist, "limit" to "12"))
                        val simPool = GenerateJson.namesOf(sim["similarartists"]?.jsonObject?.get("artist")).shuffled().take(3)
                        val perArtist = coroutineScope {
                            simPool.map { saName ->
                                async {
                                    try {
                                        val page = kotlin.math.ceil(Math.random() * 4).toInt().coerceAtLeast(1)
                                        val d = call(mapOf("method" to "artist.gettoptracks", "artist" to saName, "limit" to maxOf(4, kotlin.math.ceil(total / 12.0).toInt()).toString(), "page" to page.toString()))
                                        GenerateJson.normalise(d["toptracks"]?.jsonObject?.get("track"))
                                    } catch (e: Exception) {
                                        Log.d(TAG, "fetchMix similar-artist toptracks miss", e)
                                        emptyList()
                                    }
                                }
                            }.awaitAll().flatten()
                        }
                        perArtist.forEach { result += Weighted(it, 2) }
                    } catch (e: Exception) { Log.d(TAG, "fetchMix artist.getsimilar miss for $artist", e) }
                    result
                }
            }.awaitAll().flatten()
        }
        weighted += bucketB2

        // Bucket C — weight 1: genre/tag discovery pad, only if still thin
        if (weighted.size < total * 2) {
            try {
                onProgress("Adding genre discoveries\u2026")
                val td = call(mapOf("method" to "user.gettoptags", "user" to username(), "limit" to "8"))
                val tags = GenerateJson.namesOf(td["toptags"]?.jsonObject?.get("tag"))
                val tag = tags.shuffled().take(minOf(5, tags.size)).randomOrNull()
                if (tag != null) {
                    val page = (Math.random() * 8).toInt() + 1
                    val td2 = call(mapOf("method" to "tag.gettoptracks", "tag" to tag, "limit" to kotlin.math.ceil(total * 0.4).toInt().toString(), "page" to page.toString()))
                    GenerateJson.normalise(td2["tracks"]?.jsonObject?.get("track")).forEach { weighted += Weighted(it, 1) }
                }
            } catch (e: Exception) { Log.d(TAG, "fetchMix bucket C miss", e) }
        }

        onProgress("Curating your personalised mix\u2026")

        // Dedup keeping highest weight
        val bestWeight = mutableMapOf<String, Int>()
        val trackOf = mutableMapOf<String, GeneratedTrack>()
        for ((track, weight) in weighted) {
            if (track.name.isBlank() || track.artist.isBlank()) continue
            val k = track.key
            if ((bestWeight[k] ?: -1) < weight) {
                bestWeight[k] = weight
                trackOf[k] = track
            }
        }

        // Sort by weight tier descending, shuffled within tier
        val merged = listOf(3, 2, 1).flatMap { w ->
            bestWeight.entries.filter { it.value == w }.map { trackOf[it.key]!! }.shuffled()
        }

        // Artist diversity: max 3 per artist
        val artistCount = mutableMapOf<String, Int>()
        val diverse = merged.filter {
            val key = it.artist.lowercase()
            val count = (artistCount[key] ?: 0) + 1
            artistCount[key] = count
            count <= 3
        }

        var pool = filterRecommendationExclusions(diverse)

        // Fallback: similar artists if pool is thin
        if (pool.size < total && topArtists.isNotEmpty()) {
            try {
                onProgress("Finding more recommendations\u2026")
                val fa = topArtists.random()
                val fd = call(mapOf("method" to "artist.getsimilar", "artist" to fa, "limit" to "10"))
                for (saName in GenerateJson.namesOf(fd["similarartists"]?.jsonObject?.get("artist")).shuffled().take(3)) {
                    try {
                        val d = call(mapOf("method" to "artist.gettoptracks", "artist" to saName, "limit" to "6"))
                        pool = pool + GenerateJson.normalise(d["toptracks"]?.jsonObject?.get("track"))
                    } catch (e: Exception) { Log.d(TAG, "fetchMix fallback similar-artist miss", e) }
                }
            } catch (e: Exception) { Log.d(TAG, "fetchMix fallback miss", e) }
        }

        return filterPlayable(deduplicate(filterRecommendationExclusions(pool))).take(total)
    }

    /**
     * Builds a brand-new "inspired" mix from a source playlist's taste.
     * The source playlist itself is never modified and none of its tracks
     * are ever repeated — every returned song is fresh, discovered through
     * Last.fm similarity graphs plus YouTube Music search, so the regenerated
     * playlist feels like a genuinely new selection with the same taste.
     *
     * Targets [count] (30–35) but always tries to land at least
     * [MIN_TASTE_MIX_SIZE] songs by progressively relaxing the artist cap
     * and widening the discovery net before giving up.
     */
    suspend fun fetchTasteMixForPlaylist(playlistTracks: List<GeneratedTrack>, count: Int): List<GeneratedTrack> = kotlinx.coroutines.supervisorScope {
        val originalKeys = playlistTracks.mapTo(mutableSetOf()) { it.key }
        val pool = java.util.Collections.synchronizedList(mutableListOf<GeneratedTrack>())
        val distinctArtists = playlistTracks.map { it.artist.trim() }.filter { it.isNotBlank() }.distinct()
        val seedArtists = distinctArtists.shuffled().take(10)
        val seedTracks = playlistTracks.filter { it.name.isNotBlank() && it.artist.isNotBlank() }.shuffled().take(8)

        // 1. Similar artists' top tracks (Last.fm) — the core "same taste,
        //    different songs" source.
        val simArtistJobs = seedArtists.map { artist ->
            async(Dispatchers.IO) {
                try {
                    val sim = call(mapOf("method" to "artist.getsimilar", "artist" to artist, "limit" to "12"))
                    val simArtists = GenerateJson.namesOf(sim["similarartists"]?.jsonObject?.get("artist")).shuffled().take(4)
                    kotlinx.coroutines.supervisorScope {
                        simArtists.map { sa ->
                            async(Dispatchers.IO) {
                                try {
                                    val page = (1..3).random()
                                    val d = call(mapOf("method" to "artist.gettoptracks", "artist" to sa, "limit" to "8", "page" to page.toString()))
                                    GenerateJson.normalise(d["toptracks"]?.jsonObject?.get("track"))
                                } catch (_: Exception) {
                                    emptyList()
                                }
                            }
                        }.awaitAll().flatten()
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        // 2. Similar tracks for seed tracks (Last.fm).
        val simTrackJobs = seedTracks.map { seed ->
            async(Dispatchers.IO) {
                try {
                    val d = call(mapOf("method" to "track.getsimilar", "track" to seed.name, "artist" to seed.artist, "limit" to "12"))
                    GenerateJson.normalise(d["similartracks"]?.jsonObject?.get("track"))
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        // 3. Deep cuts from the playlist's own artists (Last.fm) — random
        //    pages surface album tracks beyond the hits the source playlist
        //    already captured.
        val directArtistJobs = seedArtists.map { artist ->
            async(Dispatchers.IO) {
                try {
                    val page = (1..3).random()
                    val d = call(mapOf("method" to "artist.gettoptracks", "artist" to artist, "limit" to "8", "page" to page.toString()))
                    GenerateJson.normalise(d["toptracks"]?.jsonObject?.get("track"))
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        // 4. YouTube Music search — queries built from seed tracks/artists
        //    widen the candidate pool beyond Last.fm's graph.
        val ytQueries = buildList {
            seedTracks.take(4).forEach { add("${it.artist} ${it.name}") }
            seedArtists.take(4).forEach { add(it) }
        }.shuffled().take(6)
        val ytJobs = ytQueries.map { query ->
            async(Dispatchers.IO) {
                try {
                    innerTube.searchSongs(query, 10).map { t ->
                        GeneratedTrack(
                            name = t.title,
                            artist = t.artist,
                            artworkUrl = t.artworkUrl,
                            url = "https://music.youtube.com/watch?v=${t.videoId}",
                            album = t.album,
                        )
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        pool.addAll(simArtistJobs.awaitAll().flatten())
        pool.addAll(simTrackJobs.awaitAll().flatten())
        pool.addAll(directArtistJobs.awaitAll().flatten())
        pool.addAll(ytJobs.awaitAll().flatten())

        // 5. Genre-tag discovery when the pool is still thin.
        if (pool.size < count) {
            try {
                val tagJobs = seedArtists.take(3).map { artist ->
                    async(Dispatchers.IO) {
                        try {
                            val td = call(mapOf("method" to "artist.gettoptags", "artist" to artist, "limit" to "5"))
                            val tags = GenerateJson.namesOf(td["toptags"]?.jsonObject?.get("tag")).shuffled().take(2)
                            kotlinx.coroutines.supervisorScope {
                                tags.map { tag ->
                                    async(Dispatchers.IO) {
                                        try {
                                            val page = (1..4).random()
                                            val td2 = call(mapOf("method" to "tag.gettoptracks", "tag" to tag, "limit" to "10", "page" to page.toString()))
                                            GenerateJson.normalise(td2["tracks"]?.jsonObject?.get("track"))
                                        } catch (_: Exception) {
                                            emptyList()
                                        }
                                    }
                                }.awaitAll().flatten()
                            }
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }
                pool.addAll(tagJobs.awaitAll().flatten())
            } catch (_: Exception) {}
        }

        // Fresh, de-duplicated candidates only — the source playlist's own
        // tracks never leak into the regenerated mix.
        val candidates = deduplicate(
            filterRecommendationExclusions(pool.toList()).filterNot { it.key in originalKeys },
        )

        // 6. Progressive artist-cap relaxation: strict 3/artist first for
        //    variety, widening just enough to secure MIN_TASTE_MIX_SIZE.
        fun capped(cap: Int): List<GeneratedTrack> {
            val counts = mutableMapOf<String, Int>()
            return candidates.filter {
                val key = it.artist.lowercase().trim()
                val c = (counts[key] ?: 0) + 1
                counts[key] = c
                c <= cap
            }
        }

        val minSize = minOf(MIN_TASTE_MIX_SIZE, count)
        var result = capped(3)
        if (result.size < minSize) result = capped(6)
        if (result.size < minSize) result = candidates

        // 7. Last-resort: one more similar-artist sweep, then chart filler —
        //    a slightly-less-on-taste 20+ beats a half-empty playlist.
        if (result.size < minSize) {
            try {
                val extraArtists = distinctArtists.shuffled().take(4)
                val extra = coroutineScope {
                    extraArtists.map { artist ->
                        async(Dispatchers.IO) {
                            try {
                                val d = call(mapOf("method" to "artist.getsimilar", "artist" to artist, "limit" to "20"))
                                GenerateJson.namesOf(d["similarartists"]?.jsonObject?.get("artist")).shuffled().take(6)
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten().distinct().filter { sa -> sa.lowercase() !in distinctArtists.mapTo(mutableSetOf()) { it.lowercase() } }
                }
                val extraTracks = coroutineScope {
                    extra.map { sa ->
                        async(Dispatchers.IO) {
                            try {
                                val d = call(mapOf("method" to "artist.gettoptracks", "artist" to sa, "limit" to "6"))
                                GenerateJson.normalise(d["toptracks"]?.jsonObject?.get("track"))
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten()
                }
                val extraCandidates = deduplicate(
                    filterRecommendationExclusions(extraTracks).filterNot { it.key in originalKeys },
                )
                val seen = result.mapTo(mutableSetOf()) { it.key }
                result = result + extraCandidates.filter { seen.add(it.key) }
            } catch (_: Exception) {}
        }
        if (result.size < minSize) {
            runCatching { fetchChartTracks(minSize * 2) }.getOrDefault(emptyList())
                .filterNot { it.key in originalKeys || result.any { r -> r.key == it.key } }
                .let { filler -> result = (result + filler) }
        }

        return@supervisorScope result.shuffled().take(count)
    }

    suspend fun fetchTasteMixForArtists(artists: List<String>, count: Int): List<GeneratedTrack> = coroutineScope {
        val pool = mutableListOf<GeneratedTrack>()
        val seeds = artists.shuffled().take(6)
        val deferred = seeds.map { artist ->
            async(Dispatchers.IO) {
                try {
                    val sim = call(mapOf("method" to "artist.getsimilar", "artist" to artist, "limit" to "10"))
                    val simArtists = GenerateJson.namesOf(sim["similarartists"]?.jsonObject?.get("artist")).shuffled().take(3)
                    coroutineScope {
                        simArtists.map { sa ->
                            async(Dispatchers.IO) {
                                try {
                                    val page = (1..3).random()
                                    val d = call(mapOf("method" to "artist.gettoptracks", "artist" to sa, "limit" to "6", "page" to page.toString()))
                                    GenerateJson.normalise(d["toptracks"]?.jsonObject?.get("track"))
                                } catch (_: Exception) {
                                    emptyList()
                                }
                            }
                        }.awaitAll().flatten()
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
        val tracks = deferred.awaitAll().flatten()
        pool.addAll(tracks)
        if (pool.size < count) {
            try {
                val mix = fetchMix(count)
                pool.addAll(mix)
            } catch (_: Exception) {}
        }
        val allowed = filterRecommendationExclusions(pool)
        precheck(shuffle(allowed)).take(count).ifEmpty { deduplicate(allowed).take(count) }
    }

    // ── My Recommendations — delegates the heavy scoring/pipeline logic to
    //    RecommendationEngine, kept as a separate file given its size. ──

    suspend fun fetchRecommendations(total: Int, onProgress: (String) -> Unit = {}): List<GeneratedTrack> {
        onProgress("Building your taste profile\u2026")
        val profile = tasteProfileProvider.get()

        val blacklist = (profile.recentTrackKeys + profile.topTrackKeys).toMutableSet()
        blacklist.addAll(recommendationExclusionKeys())
        try {
            val lovedRes = call(mapOf("method" to "user.getlovedtracks", "user" to username(), "limit" to "200"))
            GenerateJson.normalise(lovedRes["lovedtracks"]?.jsonObject?.get("track")).forEach { blacklist.add(it.key) }
        } catch (e: Exception) { Log.d(TAG, "fetchRecommendations loved-tracks miss", e) }
        try {
            playlistRepository.getAll()
                .forEach { playlist -> playlist.tracks.forEach { blacklist.add(it.key) } }
        } catch (e: Exception) { Log.d(TAG, "fetchRecommendations saved-playlists blacklist miss", e) }

        val engine = RecommendationEngine(
            rawCall = { params -> call(params) },
            isFresh = { tracks -> filterRecommendationExclusions(tracks) },
            onProgress = onProgress,
        )
        val recommended = engine.run(total, profile, blacklist)
        return filterPlayable(filterRecommendationExclusions(recommended)).take(total)
    }

    // ── Start Mix From Track — exact port of startMixFromTrack()'s 3-source blend ──

    suspend fun startMixFromTrack(trackName: String, artistName: String, onProgress: (String) -> Unit = {}): List<GeneratedTrack> {
        val MIX_SIZE = 25
        data class Weighted(val track: GeneratedTrack, val weight: Int)
        val pool = mutableListOf<Weighted>()

        try {
            onProgress("Finding tracks similar to \"$trackName\"\u2026")
            val d = call(mapOf("method" to "track.getsimilar", "track" to trackName, "artist" to artistName, "limit" to "80"))
            GenerateJson.normalise(d["similartracks"]?.jsonObject?.get("track")).forEach { pool += Weighted(it, 3) }
        } catch (e: Exception) { Log.d(TAG, "startMixFromTrack similar-tracks miss", e) }

        try {
            onProgress("Loading top tracks by $artistName\u2026")
            val d = call(mapOf("method" to "artist.gettoptracks", "artist" to artistName, "limit" to "30"))
            GenerateJson.normalise(d["toptracks"]?.jsonObject?.get("track")).forEach { pool += Weighted(it, 2) }
        } catch (e: Exception) { Log.d(TAG, "startMixFromTrack artist-toptracks miss", e) }

        try {
            onProgress("Exploring artists like $artistName\u2026")
            val d = call(mapOf("method" to "artist.getsimilar", "artist" to artistName, "limit" to "12"))
            val simPool = GenerateJson.namesOf(d["similarartists"]?.jsonObject?.get("artist")).shuffled().take(4)
            val perArtist = coroutineScope {
                simPool.map { saName ->
                    async {
                        try {
                            val d2 = call(mapOf("method" to "artist.gettoptracks", "artist" to saName, "limit" to "8"))
                            GenerateJson.normalise(d2["toptracks"]?.jsonObject?.get("track"))
                        } catch (e: Exception) {
                            Log.d(TAG, "startMixFromTrack similar-artist toptracks miss", e)
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
            perArtist.forEach { pool += Weighted(it, 1) }
        } catch (e: Exception) { Log.d(TAG, "startMixFromTrack similar-artists miss", e) }

        val seedKey = "$trackName|$artistName".lowercase()
        val bestWeight = mutableMapOf<String, Int>()
        val trackOf = mutableMapOf<String, GeneratedTrack>()
        for ((track, weight) in pool) {
            if (track.name.isBlank() || track.artist.isBlank()) continue
            val k = track.key
            if (k == seedKey) continue
            if ((bestWeight[k] ?: -1) < weight) {
                bestWeight[k] = weight
                trackOf[k] = track
            }
        }

        val sorted = listOf(3, 2, 1).flatMap { w ->
            bestWeight.entries.filter { it.value == w }.map { trackOf[it.key]!! }.shuffled()
        }

        // Artist cap 3, progressively relaxed to 6 then uncapped if too thin.
        fun capped(cap: Int): List<GeneratedTrack> {
            val counts = mutableMapOf<String, Int>()
            return sorted.filter {
                val key = it.artist.lowercase()
                val c = (counts[key] ?: 0) + 1
                counts[key] = c
                c <= cap
            }
        }

        var result = capped(3)
        if (result.size < MIX_SIZE) result = capped(6)
        if (result.size < MIX_SIZE) result = sorted

        val allowed = filterRecommendationExclusions(result)
        return filterPlayable(allowed).take(MIX_SIZE)
    }

    // ── Seed pickers / search ──

    suspend fun topTracksForSeed(): List<GeneratedTrack> {
        val result = call(mapOf("method" to "user.gettoptracks", "user" to username(), "limit" to "20", "period" to "overall"))
        return GenerateJson.normalise(result["toptracks"]?.jsonObject?.get("track"))
    }

    suspend fun topArtistsForSeed(): List<String> {
        val result = call(mapOf("method" to "user.gettopartists", "user" to username(), "limit" to "20", "period" to "overall"))
        return GenerateJson.namesOf(result["topartists"]?.jsonObject?.get("artist"))
    }

    suspend fun searchTracks(track: String, artist: String?): List<GeneratedTrack> {
        val params = mutableMapOf("method" to "track.search", "track" to track, "limit" to "15")
        if (!artist.isNullOrBlank()) params["artist"] = artist
        val result = call(params)
        val raw = result["results"]?.jsonObject?.get("trackmatches")?.jsonObject?.get("track")
        return GenerateJson.normalise(raw)
    }

    suspend fun searchArtists(artist: String): List<String> {
        val result = call(mapOf("method" to "artist.search", "artist" to artist, "limit" to "15"))
        val raw = result["results"]?.jsonObject?.get("artistmatches")?.jsonObject?.get("artist")
        return GenerateJson.namesOf(raw)
    }
}
