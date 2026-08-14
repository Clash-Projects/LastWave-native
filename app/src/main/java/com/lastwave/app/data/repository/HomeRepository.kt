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
import com.lastwave.app.data.network.LastFmApiService
import com.lastwave.app.data.network.LastFmErrors
import com.lastwave.app.data.network.LastFmException
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

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
) {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun requireSession() = sessionPreferences.session.first().also { session ->
        if (session.apiKey.isBlank() || session.username.isBlank()) {
            throw LastFmException("Not signed in")
        }
    }

    /** Every fetch below takes an optional [username] override for viewing
     *  a friend's profile (see fetchFriends/§ Home friend-switching) —
     *  every one of these Last.fm methods is an unsigned read that only
     *  ever needed a `user` param, so viewing someone else's data needs
     *  nothing more than swapping that one parameter; api_key still comes
     *  from the signed-in session either way. */
    suspend fun fetchRecentTracks(page: Int = 1, limit: Int = 50, username: String? = null): Result<RecentTracksPage> = try {
        val session = requireSession()
        val response = api.get(
            mapOf(
                "method" to "user.getrecenttracks",
                "user" to (username ?: session.username),
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
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** user.getinfo (scrobbles + timer base) + the three limit=1 stat totals —
     *  exactly the 4 calls _fetchHomeData() fires in parallel via Promise.allSettled. */
    suspend fun fetchStats(username: String? = null): Result<HomeStats> = try {
        val session = requireSession()
        val base = mapOf("user" to (username ?: session.username), "api_key" to session.apiKey, "format" to "json")

        val infoBody = api.get(base + ("method" to "user.getinfo")).body()?.string().orEmpty()
        val tracksBody = api.get(base + ("method" to "user.gettoptracks") + ("limit" to "1") + ("period" to "overall")).body()?.string().orEmpty()
        val artistsBody = api.get(base + ("method" to "user.gettopartists") + ("limit" to "1") + ("period" to "overall")).body()?.string().orEmpty()
        val albumsBody = api.get(base + ("method" to "user.gettopalbums") + ("limit" to "1") + ("period" to "overall")).body()?.string().orEmpty()

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
                // Matches loadUserProfile()'s exact priority: large > medium > first
                // available — not the extralarge>large>medium>any track-art ladder.
                avatarUrl = info?.user?.image?.let { images ->
                    images.firstOrNull { it.size == "large" }?.url
                        ?: images.firstOrNull { it.size == "medium" }?.url
                        ?: images.firstOrNull()?.url
                }?.takeIf { it.isNotBlank() },
            )
        )
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** user.gettoptracks(period=overall/7day/1month, limit=50) — real playcounts
     *  for home screen sorting modes. */
    suspend fun fetchTopTracksForPeriod(period: String = "overall", limit: Int = 50, username: String? = null): Result<List<HomeTrack>> = try {
        val session = requireSession()
        val response = api.get(
            mapOf(
                "method" to "user.gettoptracks",
                "user" to (username ?: session.username),
                "period" to period,
                "limit" to limit.toString(),
                "api_key" to session.apiKey,
                "format" to "json",
            )
        )
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

    /** Fires the full initial data-fetch set for the Home screen: recent
     *  tracks, stats, and all-time top tracks — mirrors the single
     *  Promise.allSettled batch in _fetchHomeData(). [username] switches
     *  whose data this loads — the signed-in user's own when null (default),
     *  or a friend's when viewing their profile. */
    suspend fun fetchInitialData(username: String? = null): Result<HomeInitialData> = try {
        requireSession()
        val recent = fetchRecentTracks(username = username).getOrThrow()
        val stats = fetchStats(username = username).getOrThrow()
        val topTracks = fetchTopTracksOverall(username = username).getOrElse { emptyList() }
        Result.success(HomeInitialData(stats, recent, topTracks))
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
