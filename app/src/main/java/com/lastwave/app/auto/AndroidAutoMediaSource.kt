package com.lastwave.app.auto

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import com.lastwave.app.data.discover.DiscoverRepository
import com.lastwave.app.data.favorite.FavoritesRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.local.db.AppDatabase
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.repository.HomeRepository
import com.lastwave.app.data.search.SearchRepository
import com.lastwave.app.data.search.SearchTab
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidAutoMediaSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val homeRepository: HomeRepository,
    private val playlistRepository: PlaylistRepository,
    private val favoritesRepository: FavoritesRepository,
    private val searchRepository: SearchRepository,
    private val discoverRepository: DiscoverRepository,
    private val database: AppDatabase,
    private val musicPlayer: MusicPlayer,
) {

    fun getRootCategories(): List<MediaItem> {
        return listOf(
            buildBrowsableItem(
                id = AndroidAutoConstants.CATEGORY_RECENTS,
                title = "Recent Listening",
                subtitle = "Your scrobbles & listening history",
            ),
            buildBrowsableItem(
                id = AndroidAutoConstants.CATEGORY_FAVORITES,
                title = "Liked Songs",
                subtitle = "Your favorite tracks",
            ),
            buildBrowsableItem(
                id = AndroidAutoConstants.CATEGORY_PLAYLISTS,
                title = "Playlists",
                subtitle = "Your playlists & collections",
            ),
            buildBrowsableItem(
                id = AndroidAutoConstants.CATEGORY_OFFLINE,
                title = "Downloaded Music",
                subtitle = "Offline songs on device",
            ),
            buildBrowsableItem(
                id = AndroidAutoConstants.CATEGORY_TOP_TRACKS,
                title = "Top Tracks",
                subtitle = "Most played songs",
            ),
            buildBrowsableItem(
                id = AndroidAutoConstants.CATEGORY_RECOMMENDED,
                title = "Discover Mix",
                subtitle = "Recommended for you",
            ),
        )
    }

    suspend fun getChildren(parentId: String): List<MediaItem> = withContext(Dispatchers.IO) {
        when {
            parentId == AndroidAutoConstants.MEDIA_ROOT_ID -> getRootCategories()

            parentId == AndroidAutoConstants.CATEGORY_RECENTS -> {
                runCatching {
                    val result = homeRepository.fetchRecentTracks(page = 1, limit = 50).getOrNull()
                    val tracks = result?.tracks ?: emptyList()
                    tracks.map { track ->
                        val artistName = track.artist.displayName
                        val albumName = track.album.displayName
                        buildPlayableTrackItem(
                            mediaId = "recents|${track.name}|$artistName|$albumName|${track.artworkUrl.orEmpty()}",
                            title = track.name,
                            artist = artistName,
                            album = albumName,
                            artworkUrl = track.artworkUrl,
                        )
                    }
                }.getOrElse { emptyList() }
            }

            parentId == AndroidAutoConstants.CATEGORY_FAVORITES -> {
                runCatching {
                    val playlists = playlistRepository.getAll()
                    val favPlaylist = playlists.find { it.title.equals("Favorites", ignoreCase = true) }
                    val tracks = favPlaylist?.tracks ?: emptyList()
                    tracks.map { track ->
                        buildPlayableTrackItem(
                            mediaId = "fav|${track.name}|${track.artist}|${track.album.orEmpty()}|${track.artworkUrl.orEmpty()}",
                            title = track.name,
                            artist = track.artist,
                            album = track.album,
                            artworkUrl = track.artworkUrl,
                        )
                    }
                }.getOrElse { emptyList() }
            }

            parentId == AndroidAutoConstants.CATEGORY_PLAYLISTS -> {
                runCatching {
                    val playlists = playlistRepository.getAll()
                    playlists.map { playlist ->
                        buildBrowsableItem(
                            id = "${AndroidAutoConstants.PREFIX_PLAYLIST}${playlist.id}",
                            title = playlist.title,
                            subtitle = "${playlist.tracks.size} tracks",
                            artworkUrl = playlist.customCoverUri,
                        )
                    }
                }.getOrElse { emptyList() }
            }

            parentId.startsWith(AndroidAutoConstants.PREFIX_PLAYLIST) -> {
                val playlistId = parentId.removePrefix(AndroidAutoConstants.PREFIX_PLAYLIST).toLongOrNull()
                if (playlistId != null) {
                    runCatching {
                        val playlist = playlistRepository.getById(playlistId)
                        val tracks = playlist?.tracks ?: emptyList()
                        tracks.mapIndexed { index, track ->
                            buildPlayableTrackItem(
                                mediaId = "pltrack|$playlistId|$index|${track.name}|${track.artist}|${track.album.orEmpty()}|${track.artworkUrl.orEmpty()}",
                                title = track.name,
                                artist = track.artist,
                                album = track.album,
                                artworkUrl = track.artworkUrl,
                            )
                        }
                    }.getOrElse { emptyList() }
                } else emptyList()
            }

            parentId == AndroidAutoConstants.CATEGORY_OFFLINE -> {
                runCatching {
                    val downloads = database.downloadedTrackDao().getAllList()
                    downloads.map { track ->
                        buildPlayableTrackItem(
                            mediaId = "${AndroidAutoConstants.PREFIX_OFFLINE}${track.id}|${track.title}|${track.artist}",
                            title = track.title,
                            artist = track.artist,
                            album = track.album,
                            artworkUrl = track.artworkUrl,
                        )
                    }
                }.getOrElse { emptyList() }
            }

            parentId == AndroidAutoConstants.CATEGORY_TOP_TRACKS -> {
                runCatching {
                    val topTracks = homeRepository.fetchTopTracksOverall(limit = 40).getOrNull() ?: emptyList()
                    topTracks.map { track ->
                        buildPlayableTrackItem(
                            mediaId = "top|${track.name}|${track.artist}||${track.artworkUrl.orEmpty()}",
                            title = track.name,
                            artist = track.artist,
                            album = null,
                            artworkUrl = track.artworkUrl,
                        )
                    }
                }.getOrElse { emptyList() }
            }

            parentId == AndroidAutoConstants.CATEGORY_RECOMMENDED -> {
                runCatching {
                    val recommended = discoverRepository.allowedCachedFeed()
                    recommended.map { track ->
                        buildPlayableTrackItem(
                            mediaId = "rec|${track.name}|${track.artist}|${track.album.orEmpty()}|${track.artworkUrl.orEmpty()}",
                            title = track.name,
                            artist = track.artist,
                            album = track.album,
                            artworkUrl = track.artworkUrl,
                        )
                    }
                }.getOrElse { emptyList() }
            }

            else -> emptyList()
        }
    }

    suspend fun searchTracks(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()
        runCatching {
            val results = searchRepository.search(SearchTab.TRACKS, trimmed)
            results.map { result ->
                buildPlayableTrackItem(
                    mediaId = "search|${result.name}|${result.artist.orEmpty()}|${result.subtitle.orEmpty()}|${result.artworkUrl.orEmpty()}|${result.videoId.orEmpty()}",
                    title = result.name,
                    artist = result.artist.orEmpty(),
                    album = result.subtitle,
                    artworkUrl = result.artworkUrl,
                )
            }
        }.getOrElse { emptyList() }
    }

    suspend fun playFromMediaId(mediaId: String) = withContext(Dispatchers.IO) {
        val parts = mediaId.split("|")
        val prefix = parts.firstOrNull().orEmpty()

        when {
            prefix == "recents" && parts.size >= 3 -> {
                val title = parts[1]
                val artist = parts[2]
                val album = parts.getOrNull(3)?.ifEmpty { null }
                val artworkUrl = parts.getOrNull(4)?.ifEmpty { null }
                val track = PlayableTrack(title = title, artist = artist, album = album, artworkUrl = artworkUrl)
                musicPlayer.play(track, sourceLabel = "Android Auto Recents")
            }

            prefix == "fav" && parts.size >= 3 -> {
                val title = parts[1]
                val artist = parts[2]
                val album = parts.getOrNull(3)?.ifEmpty { null }
                val artworkUrl = parts.getOrNull(4)?.ifEmpty { null }
                val favPlaylist = playlistRepository.getAll().find { it.title.equals("Favorites", ignoreCase = true) }
                val queue = favPlaylist?.tracks?.map { it.toPlayableTrack() } ?: listOf(
                    PlayableTrack(title = title, artist = artist, album = album, artworkUrl = artworkUrl)
                )
                val targetIndex = queue.indexOfFirst { it.title.equals(title, ignoreCase = true) && it.artist.equals(artist, ignoreCase = true) }.coerceAtLeast(0)
                musicPlayer.playQueue(queue, startIndex = targetIndex, sourceLabel = "Favorites")
            }

            prefix == "pltrack" && parts.size >= 4 -> {
                val playlistId = parts[1].toLongOrNull() ?: 0L
                val index = parts[2].toIntOrNull() ?: 0
                val playlist = playlistRepository.getById(playlistId)
                if (playlist != null && playlist.tracks.isNotEmpty()) {
                    val queue = playlist.tracks.map { it.toPlayableTrack() }
                    musicPlayer.playQueue(queue, startIndex = index.coerceIn(0, queue.lastIndex), sourceLabel = playlist.title)
                }
            }

            prefix == "top" && parts.size >= 3 -> {
                val title = parts[1]
                val artist = parts[2]
                val album = parts.getOrNull(3)?.ifEmpty { null }
                val artworkUrl = parts.getOrNull(4)?.ifEmpty { null }
                val track = PlayableTrack(title = title, artist = artist, album = album, artworkUrl = artworkUrl)
                musicPlayer.play(track, sourceLabel = "Top Tracks")
            }

            prefix == "rec" && parts.size >= 3 -> {
                val title = parts[1]
                val artist = parts[2]
                val album = parts.getOrNull(3)?.ifEmpty { null }
                val artworkUrl = parts.getOrNull(4)?.ifEmpty { null }
                val recommended = discoverRepository.allowedCachedFeed().map { it.toPlayableTrack() }
                val targetIndex = recommended.indexOfFirst { it.title.equals(title, ignoreCase = true) }.coerceAtLeast(0)
                if (recommended.isNotEmpty()) {
                    musicPlayer.playQueue(recommended, startIndex = targetIndex, sourceLabel = "Discover Mix")
                } else {
                    musicPlayer.play(PlayableTrack(title = title, artist = artist, album = album, artworkUrl = artworkUrl), sourceLabel = "Discover Mix")
                }
            }

            prefix.startsWith(AndroidAutoConstants.PREFIX_OFFLINE) -> {
                val idStr = prefix.removePrefix(AndroidAutoConstants.PREFIX_OFFLINE)
                val id = idStr.toLongOrNull()
                val offlineTrack = if (id != null) database.downloadedTrackDao().findById(id) else null
                if (offlineTrack != null) {
                    val track = PlayableTrack(
                        title = offlineTrack.title,
                        artist = offlineTrack.artist,
                        album = offlineTrack.album,
                        artworkUrl = offlineTrack.artworkUrl,
                        playbackUrl = offlineTrack.filePath,
                    )
                    musicPlayer.play(track, sourceLabel = "Offline")
                }
            }

            prefix == "search" && parts.size >= 3 -> {
                val title = parts[1]
                val artist = parts[2]
                val album = parts.getOrNull(3)?.ifEmpty { null }
                val artworkUrl = parts.getOrNull(4)?.ifEmpty { null }
                val videoId = parts.getOrNull(5)?.ifEmpty { null }
                val track = PlayableTrack(
                    title = title,
                    artist = artist,
                    album = album,
                    artworkUrl = artworkUrl,
                    videoId = videoId,
                )
                musicPlayer.play(track, sourceLabel = "Android Auto Search")
            }

            mediaId == AndroidAutoConstants.CATEGORY_RECENTS -> {
                val recent = homeRepository.fetchRecentTracks(page = 1, limit = 50).getOrNull()?.tracks?.map {
                    PlayableTrack(title = it.name, artist = it.artist.displayName, album = it.album.displayName, artworkUrl = it.artworkUrl)
                } ?: emptyList()
                if (recent.isNotEmpty()) musicPlayer.playQueue(recent, startIndex = 0, sourceLabel = "Recent Listening")
            }

            mediaId == AndroidAutoConstants.CATEGORY_FAVORITES -> {
                val favPlaylist = playlistRepository.getAll().find { it.title.equals("Favorites", ignoreCase = true) }
                val queue = favPlaylist?.tracks?.map { it.toPlayableTrack() } ?: emptyList()
                if (queue.isNotEmpty()) musicPlayer.playQueue(queue, startIndex = 0, sourceLabel = "Favorites")
            }

            mediaId.startsWith(AndroidAutoConstants.PREFIX_PLAYLIST) -> {
                val playlistId = mediaId.removePrefix(AndroidAutoConstants.PREFIX_PLAYLIST).toLongOrNull()
                if (playlistId != null) {
                    val playlist = playlistRepository.getById(playlistId)
                    val queue = playlist?.tracks?.map { it.toPlayableTrack() } ?: emptyList()
                    if (queue.isNotEmpty()) musicPlayer.playQueue(queue, startIndex = 0, sourceLabel = playlist?.title ?: "Playlist")
                }
            }

            mediaId == AndroidAutoConstants.CATEGORY_OFFLINE -> {
                val downloads = database.downloadedTrackDao().getAllList().map {
                    PlayableTrack(title = it.title, artist = it.artist, album = it.album, artworkUrl = it.artworkUrl, playbackUrl = it.filePath)
                }
                if (downloads.isNotEmpty()) musicPlayer.playQueue(downloads, startIndex = 0, sourceLabel = "Downloaded Music")
            }

            mediaId == AndroidAutoConstants.CATEGORY_RECOMMENDED -> {
                val recommended = discoverRepository.allowedCachedFeed().map { it.toPlayableTrack() }
                if (recommended.isNotEmpty()) musicPlayer.playQueue(recommended, startIndex = 0, sourceLabel = "Discover Mix")
            }
        }
    }

    suspend fun playFromSearch(query: String) = withContext(Dispatchers.IO) {
        val trimmed = query.trim().lowercase()
        when {
            trimmed.contains("favorite") || trimmed.contains("liked") -> {
                playFromMediaId(AndroidAutoConstants.CATEGORY_FAVORITES)
            }
            trimmed.contains("recent") || trimmed.contains("history") -> {
                playFromMediaId(AndroidAutoConstants.CATEGORY_RECENTS)
            }
            trimmed.contains("offline") || trimmed.contains("download") -> {
                playFromMediaId(AndroidAutoConstants.CATEGORY_OFFLINE)
            }
            trimmed.contains("discover") || trimmed.contains("mix") || trimmed.contains("recommend") -> {
                playFromMediaId(AndroidAutoConstants.CATEGORY_RECOMMENDED)
            }
            trimmed.isNotBlank() -> {
                val results = searchRepository.search(SearchTab.TRACKS, query)
                if (results.isNotEmpty()) {
                    val queue = results.map { result ->
                        PlayableTrack(
                            title = result.name,
                            artist = result.artist.orEmpty(),
                            album = result.subtitle,
                            artworkUrl = result.artworkUrl,
                            videoId = result.videoId,
                        )
                    }
                    musicPlayer.playQueue(queue, startIndex = 0, sourceLabel = "Search: $query")
                }
            }
        }
    }

    private fun GeneratedTrack.toPlayableTrack(): PlayableTrack = PlayableTrack(
        title = name,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        videoId = null,
    )

    private fun buildBrowsableItem(
        id: String,
        title: String,
        subtitle: String? = null,
        artworkUrl: String? = null,
    ): MediaItem {
        val extras = Bundle().apply {
            putInt(AndroidAutoConstants.CONTENT_STYLE_BROWSABLE_HINT, AndroidAutoConstants.CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt(AndroidAutoConstants.CONTENT_STYLE_PLAYABLE_HINT, AndroidAutoConstants.CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
        }
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIconUri(artworkUrl?.takeIf(String::isNotBlank)?.let(Uri::parse))
            .setExtras(extras)
            .build()
        return MediaItem(description, MediaItem.FLAG_BROWSABLE)
    }

    private fun buildPlayableTrackItem(
        mediaId: String,
        title: String,
        artist: String,
        album: String? = null,
        artworkUrl: String? = null,
    ): MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .setSubtitle(artist)
            .setDescription(album)
            .setIconUri(artworkUrl?.takeIf(String::isNotBlank)?.let(Uri::parse))
            .build()
        return MediaItem(description, MediaItem.FLAG_PLAYABLE)
    }
}
