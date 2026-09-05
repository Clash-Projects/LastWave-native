package com.lastwave.app.data.repository

import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.model.FriendEntry
import com.lastwave.app.data.model.FriendsEnvelope
import com.lastwave.app.data.model.RecentTrack
import com.lastwave.app.data.model.RecentTracksEnvelope
import com.lastwave.app.data.model.TopAlbumsEnvelope
import com.lastwave.app.data.model.TopArtistsEnvelope
import com.lastwave.app.data.model.TopTracksEnvelope
import com.lastwave.app.data.model.TopTracksFullEnvelope
import com.lastwave.app.data.model.UserInfoEnvelope
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.network.LastFmApiService
import com.lastwave.app.data.network.LastFmErrors
import com.lastwave.app.data.network.LastFmException
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred

/** playcount * 210s — the same fixed avg-track-length estimate home.js used
 *  to derive a "total lifetime listening time" from a raw scrobble count. */
private const val AVG_TRACK_SECONDS = 210L

@Immutable
data class HomeStats(
    val scrobbles: Long,
    val trackCount: Long,
    val artistCount: Long,
    val albumCount: Long,
    val avatarUrl: String? = null,
) {
    val timerBaseSeconds: Long get() = scrobbles * AVG_TRACK_SECONDS
}

data class RecentTracksPage(
    val nowPlaying: RecentTrack?,
    val tracks: List<RecentTrack>,
    val page: Int,
    val totalPages: Int,
)

/** Everything needed to build home.js's _homeAllTracks in one shot:
 *  recent scrobbles (with timestamps) + all-time top tracks (with playcounts,
 *  used both to merge counts onto matching recent entries and to supply
 *  Most-Played entries outside the last 50 scrobbles). */
data class HomeInitialData(
    val stats: HomeStats,
    val recent: RecentTracksPage,
    val topTracks: List<HomeTrack>,
)

@Singleton
class HomeRepository @Inject constructor(
    private val api: LastFmApiService,
    private val sessionPreferences: SessionPreferences,
    private val innerTube: InnerTubeMusicApi,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val inFlightScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightInitialData = ConcurrentHashMap<String, Deferred<Result<HomeInitialData>>>()
    private val inFlightStats = ConcurrentHashMap<String, Deferred<Result<HomeStats>>>()
    private val inFlightRecent = ConcurrentHashMap<String, Deferred<Result<RecentTracksPage>>>()

    private var cachedInitialData: Pair<String, HomeInitialData>? = null
    private var cachedInitialDataTimestamp: Long = 0L

    private val playableCheckSemaphore = kotlinx.coroutines.sync.Semaphore(6)

    private suspend fun filterPlayable(tracks: List<HomeTrack>): List<HomeTrack> = coroutineScope {
        if (tracks.isEmpty()) return@coroutineScope emptyList()
        val checks = tracks.map { track ->
            async(Dispatchers.IO) {
                playableCheckSemaphore.withPermit {
                    if (innerTube.isPlayable(track.name, track.artist)) track else null
                }
            }
        }
        checks.awaitAll().filterNotNull()
    }

    private suspend fun requireSession(): com.lastwave.app.data.local.SessionData =
        sessionPreferences.session.first().let { session ->
            session.copy(apiKey = session.apiKey.ifBlank { com.lastwave.app.data.network.LastFmAppCredentials.API_KEY })
        }

    /** Every fetch below takes an optional [username] override for viewing
     *  a friend's profile (see fetchFriends/§ Home friend-switching). */
    suspend fun fetchRecentTracks(
        page: Int = 1,
        limit: Int = 50,
        username: String? = null,
        forceRefresh: Boolean = false,
    ): Result<RecentTracksPage> {
        val session = requireSession()
        val targetUser = username ?: session.username
        val cacheKey = "$targetUser:$page:$limit"
        // There is no stale response cache here: entries exist only while a
        // real request is running. Even forced refreshes should join that
        // request instead of starting a duplicate when poll timers overlap.
        val deferred = inFlightRecent.getOrPut(cacheKey) {
            inFlightScope.async {
                fetchRecentTracksInternal(session, targetUser, page, limit)
            }.also { d ->
                d.invokeOnCompletion { inFlightRecent.remove(cacheKey, d) }
            }
        }
        return deferred.await()
    }

    private suspend fun fetchRecentTracksInternal(
        session: com.lastwave.app.data.local.SessionData,
        targetUser: String,
        page: Int,
        limit: Int,
    ): Result<RecentTracksPage> = try {
        if (targetUser.isBlank() || targetUser.equals("Guest User", ignoreCase = true)) {
            val chartResponse = api.get(
                mapOf(
                    "method" to "chart.gettoptracks",
                    "limit" to limit.toString(),
                    "page" to page.toString(),
                    "api_key" to session.apiKey,
                    "format" to "json",
                )
            )
            val body = chartResponse.body()?.string().orEmpty()
            val parsed = json.decodeFromString<TopTracksFullEnvelope>(body)
            val tracks = parsed.toptracks?.track?.tracks.orEmpty().filter { it.name.isNotBlank() }.map {
                RecentTrack(
                    name = it.name,
                    artist = it.artist,
                    image = it.image,
                    url = it.url,
                    date = null,
                )
            }
            Result.success(
                RecentTracksPage(
                    nowPlaying = null,
                    tracks = tracks,
                    page = page,
                    totalPages = 10,
                )
            )
        } else {
            val response = api.get(
                mapOf(
                    "method" to "user.getrecenttracks",
                    "user" to targetUser,
                    "limit" to limit.toString(),
                    "page" to page.toString(),
                    "extended" to "0",
                    "api_key" to session.apiKey,
                    "format" to "json",
                )
            )
            val body = response.body()?.string() ?: throw LastFmException("Empty response from Last.fm")
            val parsed = json.decodeFromString<RecentTracksEnvelope>(body)
            if (parsed.error != null || parsed.recenttracks == null) {
                throw LastFmException(LastFmErrors.friendlyMessage(parsed.error, parsed.message), parsed.error)
            }
            val all = parsed.recenttracks.track.tracks
            val nowPlaying = all.firstOrNull { it.isNowPlaying }
            val history = all.filter { it.name.isNotBlank() && !it.isNowPlaying }
            Result.success(
                RecentTracksPage(
                    nowPlaying = nowPlaying,
                    tracks = history,
                    page = parsed.recenttracks.attr.page.toIntOrNull() ?: page,
                    totalPages = parsed.recenttracks.attr.totalPages.toIntOrNull() ?: 1,
                )
            )
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** user.getinfo (scrobbles + timer base) + the three limit=1 stat totals, parallelized */
    suspend fun fetchStats(username: String? = null, forceRefresh: Boolean = false): Result<HomeStats> {
        val session = requireSession()
        val targetUser = username ?: session.username
        val cacheKey = targetUser.ifBlank { "guest" }
        // Coalesce overlapping refreshes. The map contains only active work,
        // and completion removes it immediately.
        val deferred = inFlightStats.getOrPut(cacheKey) {
            inFlightScope.async {
                fetchStatsInternal(session, targetUser)
            }.also { d ->
                d.invokeOnCompletion { inFlightStats.remove(cacheKey, d) }
            }
        }
        return deferred.await()
    }

    private suspend fun fetchStatsInternal(
        session: com.lastwave.app.data.local.SessionData,
        targetUser: String,
    ): Result<HomeStats> = try {
        if (targetUser.isBlank() || targetUser.equals("Guest User", ignoreCase = true)) {
            Result.success(
                HomeStats(
                    scrobbles = 0L,
                    trackCount = 0L,
                    artistCount = 0L,
                    albumCount = 0L,
                    avatarUrl = null,
                )
            )
        } else {
            val base = mapOf("user" to targetUser, "api_key" to session.apiKey, "format" to "json")

            coroutineScope {
                val infoDeferred = async(Dispatchers.IO) {
                    try { api.get(base + ("method" to "user.getinfo")).body()?.string().orEmpty() } catch (_: Exception) { "" }
                }
                val tracksDeferred = async(Dispatchers.IO) {
                    try { api.get(base + ("method" to "user.gettoptracks") + ("limit" to "1") + ("period" to "overall")).body()?.string().orEmpty() } catch (_: Exception) { "" }
                }
                val artistsDeferred = async(Dispatchers.IO) {
                    try { api.get(base + ("method" to "user.gettopartists") + ("limit" to "1") + ("period" to "overall")).body()?.string().orEmpty() } catch (_: Exception) { "" }
                }
                val albumsDeferred = async(Dispatchers.IO) {
                    try { api.get(base + ("method" to "user.gettopalbums") + ("limit" to "1") + ("period" to "overall")).body()?.string().orEmpty() } catch (_: Exception) { "" }
                }

                val infoBody = infoDeferred.await()
                val tracksBody = tracksDeferred.await()
                val artistsBody = artistsDeferred.await()
                val albumsBody = albumsDeferred.await()

                val info = runCatching { json.decodeFromString<UserInfoEnvelope>(infoBody) }.getOrNull()
                val tracks = runCatching { json.decodeFromString<TopTracksEnvelope>(tracksBody) }.getOrNull()
                val artists = runCatching { json.decodeFromString<TopArtistsEnvelope>(artistsBody) }.getOrNull()
                val albums = runCatching { json.decodeFromString<TopAlbumsEnvelope>(albumsBody) }.getOrNull()

                Result.success(
                    HomeStats(
                        scrobbles = info?.user?.playcount?.toLongOrNull() ?: 0L,
                        trackCount = tracks?.toptracks?.attr?.total?.toLongOrNull() ?: 0L,
                        artistCount = artists?.topartists?.attr?.total?.toLongOrNull() ?: 0L,
                        albumCount = albums?.topalbums?.attr?.total?.toLongOrNull() ?: 0L,
                        avatarUrl = info?.user?.image?.let { images ->
                            images.firstOrNull { it.size == "large" }?.url
                                ?: images.firstOrNull { it.size == "medium" }?.url
                                ?: images.firstOrNull()?.url
                        }?.takeIf { it.isNotBlank() },
                    )
                )
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun fetchTopTracksForPeriod(period: String = "overall", limit: Int = 50, username: String? = null): Result<List<HomeTrack>> = try {
        val session = requireSession()
        val targetUser = username ?: session.username
        val response = if (targetUser.isBlank() || targetUser.equals("Guest User", ignoreCase = true)) {
            api.get(
                mapOf(
                    "method" to "chart.gettoptracks",
                    "limit" to limit.toString(),
                    "api_key" to session.apiKey,
                    "format" to "json",
                )
            )
        } else {
            api.get(
                mapOf(
                    "method" to "user.gettoptracks",
                    "user" to targetUser,
                    "period" to period,
                    "limit" to limit.toString(),
                    "api_key" to session.apiKey,
                    "format" to "json",
                )
            )
        }
        val body = response.body()?.string() ?: throw LastFmException("Empty response from Last.fm")
        val parsed = json.decodeFromString<TopTracksFullEnvelope>(body)
        if (parsed.error != null) {
            throw LastFmException(LastFmErrors.friendlyMessage(parsed.error, parsed.message), parsed.error)
        }
        val tracks = parsed.toptracks?.track?.tracks.orEmpty().filter { it.name.isNotBlank() }.map {
            HomeTrack(
                name = it.name,
                artist = it.artist.displayName,
                artworkUrl = it.artworkUrl,
                timestampMillis = null,
                playCount = it.playCount,
            )
        }
        Result.success(tracks)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** user.gettoptracks(period=overall, limit=50) — real playcounts, used
     *  for the Most Played tab and to merge counts onto Recent entries. */
    suspend fun fetchTopTracksOverall(limit: Int = 50, username: String? = null): Result<List<HomeTrack>> = fetchTopTracksForPeriod("overall", limit, username)

    suspend fun fetchTopArtistsForPeriod(period: String = "overall", limit: Int = 30, username: String? = null): Result<List<HomeArtistItem>> = try {
        val session = requireSession()
        val targetUser = username ?: session.username
        val response = if (targetUser.isBlank() || targetUser.equals("Guest User", ignoreCase = true)) {
            api.get(
                mapOf(
                    "method" to "chart.gettopartists",
                    "limit" to limit.toString(),
                    "api_key" to session.apiKey,
                    "format" to "json",
                )
            )
        } else {
            api.get(
                mapOf(
                    "method" to "user.gettopartists",
                    "user" to targetUser,
                    "period" to period,
                    "limit" to limit.toString(),
                    "api_key" to session.apiKey,
                    "format" to "json",
                )
            )
        }
        val body = response.body()?.string() ?: throw LastFmException("Empty response from Last.fm")
        val jsonElem = json.parseToJsonElement(body).jsonObject
        if (jsonElem.containsKey("error")) {
            val errCode = jsonElem["error"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val errMsg = jsonElem["message"]?.jsonPrimitive?.contentOrNull
            throw LastFmException(LastFmErrors.friendlyMessage(errCode ?: 0, errMsg), errCode)
        }
        val topartists = jsonElem["topartists"]?.jsonObject
        val artistElem = topartists?.get("artist")
        val artistList = when (artistElem) {
            is JsonArray -> artistElem
            is JsonObject -> listOf(artistElem)
            else -> emptyList()
        }
        val artists = artistList.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (name.isBlank()) return@mapNotNull null
            val playcount = obj["playcount"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val imgArr = obj["image"] as? JsonArray
            val bestImg = imgArr?.lastOrNull {
                val text = (it as? JsonObject)?.get("#text")?.jsonPrimitive?.contentOrNull
                !text.isNullOrBlank()
            }?.let { (it as JsonObject)["#text"]?.jsonPrimitive?.contentOrNull }
            HomeArtistItem(
                name = name,
                playCount = playcount,
                artworkUrl = bestImg?.takeIf { !it.contains("2a96cbd8b46e442fc41c2b86b821562f") },
            )
        }
        Result.success(artists)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Fires the full initial data-fetch set for the Home screen: recent
     *  tracks, stats, and all-time top tracks in parallel with caching and deduplication. */
    suspend fun fetchInitialData(username: String? = null, forceRefresh: Boolean = false): Result<HomeInitialData> {
        val session = requireSession()
        val targetUser = username ?: session.username
        val cacheKey = targetUser.ifBlank { "guest" }

        val now = System.currentTimeMillis()
        if (forceRefresh) {
            cachedInitialData = null
        } else if (cachedInitialData?.first == cacheKey && (now - cachedInitialDataTimestamp < 30_000L)) {
            return Result.success(cachedInitialData!!.second)
        }

        val deferred = inFlightInitialData.getOrPut(cacheKey) {
            inFlightScope.async {
                fetchInitialDataInternal(targetUser)
            }.also { d ->
                d.invokeOnCompletion { inFlightInitialData.remove(cacheKey, d) }
            }
        }
        val result = deferred.await()
        if (result.isSuccess) {
            cachedInitialData = cacheKey to result.getOrThrow()
            cachedInitialDataTimestamp = System.currentTimeMillis()
        }
        return result
    }

    private suspend fun fetchInitialDataInternal(username: String): Result<HomeInitialData> = try {
        coroutineScope {
            val recentDeferred = async(Dispatchers.IO) { fetchRecentTracks(username = username) }
            val statsDeferred = async(Dispatchers.IO) { fetchStats(username = username) }
            val topTracksDeferred = async(Dispatchers.IO) { fetchTopTracksOverall(username = username) }

            val recentResult = recentDeferred.await()
            val statsResult = statsDeferred.await()
            val topTracksResult = topTracksDeferred.await()

            // Degrade gracefully: one flaky sub-request (stats/top-tracks, and
            // now recents too) used to fail the ENTIRE Home payload — a single
            // transient socket reset emptied the whole screen. Each surface
            // now falls back independently; only a session-level failure
            // (handled by requireSession() inside each fetch) still fails all.
            val recent = recentResult.getOrElse {
                RecentTracksPage(nowPlaying = null, tracks = emptyList(), page = 1, totalPages = 1)
            }
            val stats = statsResult.getOrElse {
                HomeStats(scrobbles = 0L, trackCount = 0L, artistCount = 0L, albumCount = 0L, avatarUrl = null)
            }
            val topTracks = topTracksResult.getOrElse { emptyList<HomeTrack>() }

            // Everything failed = genuinely offline → surface a retryable
            // failure. Any partial success renders what we have.
            if (recentResult.isFailure && statsResult.isFailure && topTracksResult.isFailure) {
                Result.failure(
                    recentResult.exceptionOrNull()
                        ?: statsResult.exceptionOrNull()
                        ?: IllegalStateException("Home data unavailable"),
                )
            } else {
                Result.success(HomeInitialData(stats, recent, topTracks))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** user.getfriends — always the SIGNED-IN user's own friends list
     *  (never a friend-of-a-friend's — Last.fm's `user` param here is
     *  always the current session's username, unlike every fetch above). */
    suspend fun fetchFriends(limit: Int = 50): Result<List<FriendEntry>> = try {
        val session = requireSession()
        val response = api.get(
            mapOf(
                "method" to "user.getfriends",
                "user" to session.username,
                "limit" to limit.toString(),
                "api_key" to session.apiKey,
                "format" to "json",
            )
        )
        val body = response.body()?.string() ?: throw LastFmException("Empty response from Last.fm")
        val parsed = json.decodeFromString<FriendsEnvelope>(body)
        if (parsed.error != null) {
            throw LastFmException(LastFmErrors.friendlyMessage(parsed.error, parsed.message), parsed.error)
        }
        Result.success(parsed.friends?.user.orEmpty())
    } catch (e: Exception) {
        Result.failure(e)
    }
}
