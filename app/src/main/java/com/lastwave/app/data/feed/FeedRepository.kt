package com.lastwave.app.data.feed

import androidx.compose.runtime.Immutable
import com.lastwave.app.data.model.FriendEntry
import com.lastwave.app.data.model.RecentTrack
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.data.repository.HomeRepository
import com.lastwave.app.data.generate.TasteProfileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Immutable
data class FeedQuickTile(
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val actionVideoId: String? = null,
    val playlistId: String? = null,
    val isLiked: Boolean = false,
)

@Immutable
data class FeedSectionData<T>(
    val title: String,
    val subtitle: String? = null,
    val items: List<T>,
)

@Immutable
data class FeedData(
    val quickTiles: List<FeedQuickTile> = emptyList(),
    val quickPicks: List<YouTubeMusicTrack> = emptyList(),
    val newReleases: List<YouTubePlaylistSummary> = emptyList(),
    val charts: List<YouTubeMusicTrack> = emptyList(),
    val mixes: List<YouTubePlaylistSummary> = emptyList(),
    val jumpBackIn: List<RecentTrack> = emptyList(),
    val becauseYouListenTo: FeedSectionData<YouTubeMusicTrack>? = null,
    val friends: List<FriendEntry> = emptyList(),
)

@Singleton
class FeedRepository @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
    private val homeRepository: HomeRepository,
    private val tasteProfileProvider: TasteProfileProvider,
) {
    suspend fun loadFeed(username: String?): FeedData = coroutineScope {
        val newReleasesDef = async(Dispatchers.IO) { innerTube.fetchNewReleases() }
        val chartsDef = async(Dispatchers.IO) { innerTube.fetchCharts() }
        val homeMixesDef = async(Dispatchers.IO) { innerTube.fetchHomeMixes() }
        val homeSongsDef = async(Dispatchers.IO) { innerTube.fetchHomeSongs() }

        val recentTracksDef = async(Dispatchers.IO) {
            if (!username.isNullOrBlank()) {
                homeRepository.fetchRecentTracks(page = 1, limit = 30, username = username).getOrNull()?.tracks.orEmpty()
            } else emptyList()
        }
        val friendsDef = async(Dispatchers.IO) {
            if (!username.isNullOrBlank()) {
                homeRepository.fetchFriends(limit = 20).getOrNull().orEmpty()
            } else emptyList()
        }
        val tasteProfileDef = async(Dispatchers.IO) {
            runCatching { tasteProfileProvider.get() }.getOrNull()
        }

        val newReleases = newReleasesDef.await()
        val charts = chartsDef.await()
        var mixes = homeMixesDef.await()
        val homeSongs = homeSongsDef.await()
        val recentTracks = recentTracksDef.await()
        val friends = friendsDef.await()
        val tasteProfile = tasteProfileDef.await()

        if (mixes.isEmpty()) {
            mixes = innerTube.searchPlaylists("mix", limit = 10)
        }

        val quickPicks = (homeSongs.ifEmpty { charts }).take(12)

        val topArtist = tasteProfile?.topArtistsRaw?.firstOrNull() ?: recentTracks.firstOrNull()?.artist?.displayName
        val becauseSection = if (!topArtist.isNullOrBlank()) {
            val similarSongs = runCatching { innerTube.searchSongs(topArtist, limit = 8) }.getOrDefault(emptyList())
            if (similarSongs.isNotEmpty()) {
                FeedSectionData("Because you listen to $topArtist", "Recommended based on your listening", similarSongs)
            } else null
        } else null

        val quickTiles = buildList {
            add(FeedQuickTile(title = "Liked Songs", subtitle = "Your collection", isLiked = true))
            mixes.firstOrNull()?.let {
                add(FeedQuickTile(title = it.title, subtitle = it.author ?: "Mix", artworkUrl = it.artworkUrl, playlistId = it.id))
            }
            if (quickPicks.isNotEmpty()) {
                val q = quickPicks.first()
                add(FeedQuickTile(title = q.title, subtitle = q.artist, artworkUrl = q.artworkUrl, actionVideoId = q.videoId))
            }
            mixes.getOrNull(1)?.let {
                add(FeedQuickTile(title = it.title, subtitle = it.author ?: "Mix", artworkUrl = it.artworkUrl, playlistId = it.id))
            }
            newReleases.firstOrNull()?.let {
                add(FeedQuickTile(title = it.title, subtitle = it.author ?: "New Release", artworkUrl = it.artworkUrl, playlistId = it.id))
            }
            charts.firstOrNull()?.let {
                add(FeedQuickTile(title = it.title, subtitle = it.artist, artworkUrl = it.artworkUrl, actionVideoId = it.videoId))
            }
        }.take(6)

        FeedData(
            quickTiles = quickTiles,
            quickPicks = quickPicks,
            newReleases = newReleases,
            charts = charts,
            mixes = mixes,
            jumpBackIn = recentTracks.take(15),
            becauseYouListenTo = becauseSection,
            friends = friends.take(10),
        )
    }

    suspend fun fetchMoodPlaylists(mood: String): List<YouTubePlaylistSummary> = withContext(Dispatchers.IO) {
        if (mood.equals("All", ignoreCase = true)) {
            innerTube.fetchHomeMixes().ifEmpty { innerTube.searchPlaylists("mix", limit = 15) }
        } else {
            innerTube.searchPlaylists("$mood mix", limit = 15)
        }
    }
}
