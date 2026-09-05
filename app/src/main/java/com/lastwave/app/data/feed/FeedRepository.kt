package com.lastwave.app.data.feed

import androidx.compose.runtime.Immutable
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.TasteProfileProvider
import com.lastwave.app.data.model.FriendEntry
import com.lastwave.app.data.model.RecentTrack
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.repository.HomeRepository
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

private val MIX_OR_RADIO_TITLE = Regex("""(?i)\b(?:mix(?:es)?|radio|supermix)\b""")

@Immutable
data class FeedQuickTile(
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val actionVideoId: String? = null,
    val playlistId: String? = null,
    val localPlaylistId: Long? = null,
    val isLiked: Boolean = false,
)

@Immutable
data class FeedSectionData<T>(
    val title: String,
    val subtitle: String? = null,
    val items: List<T>,
)

@Immutable
data class FeedArtist(
    val name: String,
    val browseId: String? = null,
    val artworkUrl: String? = null,
)

@Immutable
data class FeedAlbum(
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val browseId: String? = null,
)

@Immutable
data class FeedSpotlight(
    val artistName: String,
    val artworkUrl: String? = null,
    val browseId: String? = null,
    val description: String? = null,
    val topTrackTitle: String? = null,
)

@Immutable
data class FeedData(
    val isYtConnected: Boolean = false,
    val spotlight: FeedSpotlight? = null,
    val quickTiles: List<FeedQuickTile> = emptyList(),
    val quickPicks: List<YouTubeMusicTrack> = emptyList(),
    val newReleases: List<YouTubePlaylistSummary> = emptyList(),
    val charts: List<YouTubeMusicTrack> = emptyList(),
    val mixes: List<YouTubePlaylistSummary> = emptyList(),
    val jumpBackIn: List<RecentTrack> = emptyList(),
    val recentAlbums: List<FeedAlbum> = emptyList(),
    val topArtists: List<FeedArtist> = emptyList(),
    val heavyRotation: List<GeneratedTrack> = emptyList(),
    val ytLikedSongs: List<YouTubeMusicTrack> = emptyList(),
    val ytRecentSongs: List<YouTubeMusicTrack> = emptyList(),
    val becauseYouListenTo: FeedSectionData<YouTubeMusicTrack>? = null,
    val friends: List<FriendEntry> = emptyList(),
)

@Singleton
class FeedRepository @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
    private val homeRepository: HomeRepository,
    private val tasteProfileProvider: TasteProfileProvider,
    private val ytAuth: YtMusicAuthManager,
    private val playlistRepository: PlaylistRepository,
) {
    suspend fun loadFeed(username: String?): FeedData = coroutineScope {
        val isYtConnected = runCatching { ytAuth.awaitLoadedConnection().isConnected }
            .getOrDefault(ytAuth.connection.value.isConnected)

        val newReleasesDef = async(Dispatchers.IO) { runCatching { innerTube.fetchNewReleases() }.getOrDefault(emptyList()) }
        val chartsDef = async(Dispatchers.IO) { runCatching { innerTube.fetchCharts() }.getOrDefault(emptyList()) }
        val homeMixesDef = async(Dispatchers.IO) { runCatching { innerTube.fetchHomeMixes() }.getOrDefault(emptyList()) }
        val homeSongsDef = async(Dispatchers.IO) { runCatching { innerTube.fetchHomeSongs() }.getOrDefault(emptyList()) }

        val ytTasteDef = async(Dispatchers.IO) {
            if (isYtConnected) {
                runCatching { innerTube.fetchTasteSignals(recentLimit = 20, likedLimit = 20, feedLimit = 25) }.getOrNull()
            } else null
        }

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
        val likedSongsIdDef = async(Dispatchers.IO) {
            runCatching { playlistRepository.ensureLikedSongs().id }.getOrNull()
        }

        val newReleases = newReleasesDef.await()
        val charts = chartsDef.await()
        var mixes = homeMixesDef.await().filter { it.isMixOrRadio() }
        val homeSongs = homeSongsDef.await()
        val recentTracks = recentTracksDef.await()
        val friends = friendsDef.await()
        val tasteProfile = tasteProfileDef.await()
        val ytTaste = ytTasteDef.await()
        val likedSongsId = likedSongsIdDef.await()

        if (mixes.isEmpty()) {
            val searchedMixes = runCatching { innerTube.searchPlaylists("music mix", limit = 12) }
                .getOrDefault(emptyList())
                .filter { it.id.isNotBlank() }
            mixes = searchedMixes.filter { it.isMixOrRadio() }.ifEmpty { searchedMixes }
        }

        val ytLikedSongs = ytTaste?.likedTracks.orEmpty()
        val ytRecentSongs = ytTaste?.recentTracks.orEmpty()
        val ytQuickPicks = ytTaste?.feedTracks.orEmpty().ifEmpty { homeSongs }

        val quickPicks = (ytQuickPicks.ifEmpty { charts }).take(15)

        val artistSignalTracks = ytRecentSongs + ytLikedSongs + ytQuickPicks + homeSongs + charts
        val topArtistNames = buildList {
            addAll(tasteProfile?.topArtistsRaw.orEmpty())
            addAll(artistSignalTracks.map(YouTubeMusicTrack::artist))
            addAll(recentTracks.map { it.artist.displayName })
        }
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("Unknown artist", ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .take(10)

        val topArtists = topArtistNames.map { name ->
            async(Dispatchers.IO) {
                val trackArtwork = artistSignalTracks
                    .firstOrNull { it.artist.trim().equals(name, ignoreCase = true) }
                    ?.artworkUrl
                val entity = runCatching {
                    innerTube.searchArtists(name, limit = 3)
                        .firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
                }.getOrNull()
                FeedArtist(
                    name = name,
                    browseId = entity?.browseId,
                    artworkUrl = entity?.artworkUrl ?: trackArtwork,
                )
            }
        }.awaitAll()

        val heavyRotation = tasteProfile?.topTracksRaw.orEmpty().take(15)

        val recentAlbums = buildList {
            recentTracks.forEach { track ->
                if (track.album.displayName.isNotBlank() && track.artist.displayName.isNotBlank()) {
                    add(
                        FeedAlbum(
                            title = track.album.displayName,
                            artist = track.artist.displayName,
                            artworkUrl = track.artworkUrl,
                        ),
                    )
                }
            }
            artistSignalTracks.forEach { track ->
                val album = track.album?.takeIf(String::isNotBlank) ?: return@forEach
                if (track.artist.isNotBlank()) {
                    add(FeedAlbum(title = album, artist = track.artist, artworkUrl = track.artworkUrl))
                }
            }
        }
            .distinctBy { "${it.artist.trim().lowercase()}_${it.title.trim().lowercase()}" }
            .take(14)

        // Spotlight artist banner
        val topSpotlightArtist = topArtists.firstOrNull()
        val spotlight = if (topSpotlightArtist != null) {
            val topTrackTitle = heavyRotation
                .firstOrNull { it.artist.equals(topSpotlightArtist.name, ignoreCase = true) }
                ?.name
                ?: artistSignalTracks
                    .firstOrNull { it.artist.equals(topSpotlightArtist.name, ignoreCase = true) }
                    ?.title
            FeedSpotlight(
                artistName = topSpotlightArtist.name,
                artworkUrl = topSpotlightArtist.artworkUrl,
                browseId = topSpotlightArtist.browseId,
                description = "Spotlight Artist",
                topTrackTitle = topTrackTitle,
            )
        } else null

        val topArtist = topArtistNames.firstOrNull()
        val directRadioSeed = (ytRecentSongs + ytLikedSongs + quickPicks + homeSongs + charts)
            .distinctBy(YouTubeMusicTrack::videoId)
            .firstOrNull()
        val radioSeed = directRadioSeed ?: heavyRotation.firstOrNull()?.let { seed ->
            try {
                innerTube.findBestMatchOrNull(seed.name, seed.artist, prefetchStreams = false)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        }
        val radioTracks = radioSeed?.let { seed ->
            try {
                innerTube.fetchRelatedSongs(seed.videoId, limit = 15, prefetchStreams = false)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
        }.orEmpty()
        val radioFallback = if (radioTracks.isEmpty() && !topArtist.isNullOrBlank()) {
            try {
                innerTube.searchSongs("$topArtist radio", limit = 15, prefetchStreams = false)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()
        val becauseSection = (radioTracks.ifEmpty { radioFallback })
            .takeIf { it.isNotEmpty() }
            ?.let { tracks ->
                FeedSectionData(
                    title = radioSeed?.let { "${it.title} → infinity" } ?: "Infinite discovery radio",
                    subtitle = if (isYtConnected) {
                        "YouTube radio reshaped around your listening"
                    } else {
                        "Accountless YouTube radio • no sign-in needed"
                    },
                    items = tracks,
                )
            }

        val quickTiles = buildList {
            likedSongsId?.let {
                add(
                    FeedQuickTile(
                        title = "Liked Songs",
                        subtitle = "Your collection",
                        localPlaylistId = it,
                        isLiked = true,
                    ),
                )
            }
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
            isYtConnected = isYtConnected,
            spotlight = spotlight,
            quickTiles = quickTiles,
            quickPicks = quickPicks,
            newReleases = newReleases,
            charts = charts,
            mixes = mixes,
            jumpBackIn = recentTracks.take(15),
            recentAlbums = recentAlbums,
            topArtists = topArtists,
            heavyRotation = heavyRotation,
            ytLikedSongs = ytLikedSongs,
            ytRecentSongs = ytRecentSongs,
            becauseYouListenTo = becauseSection,
            friends = friends.take(10),
        )
    }

    private fun YouTubePlaylistSummary.isMixOrRadio(): Boolean = MIX_OR_RADIO_TITLE.containsMatchIn(title)
}
