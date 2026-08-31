package com.lastwave.app.playback

import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.search.SearchRepository
import com.lastwave.app.data.search.SearchTab
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Driver-safe browse tree shared by Android Auto and the phone player. */
@Singleton
class AndroidAutoMediaLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistRepository: PlaylistRepository,
    private val downloadedTrackDao: DownloadedTrackDao,
    private val searchRepository: SearchRepository,
) {
    private val nextSearchToken = AtomicLong()
    private val searchQueues = object : LinkedHashMap<String, List<PlayableTrack>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<PlayableTrack>>?): Boolean =
            size > MAX_CACHED_SEARCHES
    }
    val playlistChanges get() = playlistRepository.changes
    val downloadChanges get() = downloadedTrackDao.getAll()

    suspend fun loadChildren(parentId: String, state: MusicPlayerState): List<MediaBrowserCompat.MediaItem> =
        when {
            parentId == ROOT_ID -> rootItems(state)
            parentId == QUEUE_ID -> state.queue.mapIndexed(::queueTrackItem)
            parentId == PLAYLISTS_ID -> playlistRepository.getAll().map { playlist ->
                browsableItem(
                    mediaId = "$PLAYLIST_PREFIX${playlist.id}",
                    title = playlist.title,
                    subtitle = trackCountLabel(playlist.tracks.size),
                    playable = playlist.tracks.isNotEmpty(),
                )
            }
            parentId.startsWith(PLAYLIST_PREFIX) -> {
                val playlistId = parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                val playlist = playlistId?.let { playlistRepository.getById(it) }
                if (playlist == null) {
                    emptyList()
                } else {
                    playlist.tracks.mapIndexed { index, track ->
                        playableItem(
                            mediaId = "$PLAYLIST_TRACK_PREFIX${playlist.id}:$index",
                            track = track.toPlayableTrack(),
                        )
                    }
                }
            }
            parentId == DOWNLOADS_ID -> downloadedTrackDao.getAllList().map(::downloadItem)
            else -> emptyList()
        }

    suspend fun search(query: String): List<MediaBrowserCompat.MediaItem> {
        val tracks = searchTracks(query)
        if (tracks.isEmpty()) return emptyList()
        val token = nextSearchToken.incrementAndGet().toString(36)
        synchronized(searchQueues) { searchQueues[token] = tracks }
        return tracks.mapIndexed { index, track ->
            playableItem("$SEARCH_PREFIX$token:$index", track)
        }
    }

    suspend fun playMediaId(mediaId: String, player: MusicPlayer): Boolean = when {
        mediaId == QUEUE_ID -> player.state.value.current?.let { player.resume(); true } ?: false
        mediaId == DOWNLOADS_ID -> playDownloads(player)
        mediaId.startsWith(QUEUE_TRACK_PREFIX) -> {
            val index = mediaId.removePrefix(QUEUE_TRACK_PREFIX).toIntOrNull()
            if (index != null && index in player.state.value.queue.indices) {
                player.seekToQueueItem(index)
                true
            } else {
                false
            }
        }
        mediaId.startsWith(PLAYLIST_TRACK_PREFIX) -> {
            val parts = mediaId.removePrefix(PLAYLIST_TRACK_PREFIX).split(':', limit = 2)
            playPlaylist(parts.getOrNull(0)?.toLongOrNull(), parts.getOrNull(1)?.toIntOrNull(), player)
        }
        mediaId.startsWith(PLAYLIST_PREFIX) ->
            playPlaylist(mediaId.removePrefix(PLAYLIST_PREFIX).toLongOrNull(), 0, player)
        mediaId.startsWith(DOWNLOAD_PREFIX) -> {
            val track = mediaId.removePrefix(DOWNLOAD_PREFIX).toLongOrNull()
                ?.let { downloadedTrackDao.findById(it) }
                ?.toPlayableTrack()
            track?.let { player.play(it, "Downloads"); true } ?: false
        }
        mediaId.startsWith(SEARCH_PREFIX) -> playCachedSearch(mediaId, player)
        else -> false
    }

    suspend fun playSearch(query: String, player: MusicPlayer): Boolean {
        val tracks = searchTracks(query)
        if (tracks.isEmpty()) return false
        player.playQueue(tracks, sourceLabel = "Android Auto Search")
        return true
    }

    private fun rootItems(state: MusicPlayerState): List<MediaBrowserCompat.MediaItem> = listOf(
        browsableItem(
            mediaId = QUEUE_ID,
            title = "Up next",
            subtitle = if (state.queue.isEmpty()) "No active queue" else trackCountLabel(state.queue.size),
            playable = state.current != null,
        ),
        browsableItem(
            mediaId = PLAYLISTS_ID,
            title = "Playlists",
            subtitle = "Your saved mixes and playlists",
        ),
        browsableItem(
            mediaId = DOWNLOADS_ID,
            title = "Downloads",
            subtitle = "Music available offline",
            playable = true,
        ),
    )

    private suspend fun playPlaylist(id: Long?, index: Int?, player: MusicPlayer): Boolean {
        val playlist = id?.let { playlistRepository.getById(it) } ?: return false
        val tracks = playlist.tracks.map { it.toPlayableTrack() }
        if (tracks.isEmpty()) return false
        player.playQueue(
            tracks = tracks,
            startIndex = index?.coerceIn(tracks.indices) ?: 0,
            sourceLabel = playlist.title,
        )
        return true
    }

    private suspend fun playDownloads(player: MusicPlayer): Boolean {
        val tracks = downloadedTrackDao.getAllList().map { it.toPlayableTrack() }
        if (tracks.isEmpty()) return false
        player.playQueue(tracks, sourceLabel = "Downloads")
        return true
    }

    private fun playCachedSearch(mediaId: String, player: MusicPlayer): Boolean {
        val parts = mediaId.removePrefix(SEARCH_PREFIX).split(':', limit = 2)
        val token = parts.getOrNull(0) ?: return false
        val index = parts.getOrNull(1)?.toIntOrNull() ?: return false
        val tracks = synchronized(searchQueues) { searchQueues[token] } ?: return false
        if (index !in tracks.indices) return false
        player.playQueue(tracks, startIndex = index, sourceLabel = "Android Auto Search")
        return true
    }

    private suspend fun searchTracks(query: String): List<PlayableTrack> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()
        return searchRepository.search(SearchTab.TRACKS, cleanQuery)
            .asSequence()
            .filter { it.name.isNotBlank() }
            .map { result ->
                PlayableTrack(
                    title = result.name,
                    artist = result.artist.orEmpty().ifBlank { "Unknown artist" },
                    album = result.subtitle,
                    artworkUrl = result.artworkUrl,
                    videoId = result.videoId,
                )
            }
            .distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
            .take(MAX_SEARCH_RESULTS)
            .toList()
    }

    private fun queueTrackItem(index: Int, track: PlayableTrack) =
        playableItem("$QUEUE_TRACK_PREFIX$index", track)

    private fun downloadItem(track: DownloadedTrackEntity) =
        playableItem("$DOWNLOAD_PREFIX${track.id}", track.toPlayableTrack())

    private fun DownloadedTrackEntity.toPlayableTrack() = PlayableTrack(
        title = title,
        artist = artist,
        album = album.takeIf(String::isNotBlank),
        artworkUrl = artworkUrl,
        playbackUrl = mediaStoreUri?.takeIf(String::isNotBlank)
            ?: Uri.fromFile(File(filePath)).toString(),
    )

    private fun browsableItem(
        mediaId: String,
        title: String,
        subtitle: String,
        playable: Boolean = false,
    ): MediaBrowserCompat.MediaItem {
        val flags = MediaBrowserCompat.MediaItem.FLAG_BROWSABLE or
            if (playable) MediaBrowserCompat.MediaItem.FLAG_PLAYABLE else 0
        return MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(mediaId)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIconUri(appIconUri())
                .build(),
            flags,
        )
    }

    private fun playableItem(mediaId: String, track: PlayableTrack) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(track.title)
            .setSubtitle(track.artist)
            .setDescription(track.album)
            .build(),
        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
    )

    private fun appIconUri(): Uri = Uri.parse(
        "android.resource://${context.packageName}/${com.lastwave.app.R.drawable.ic_car_attribution}",
    )

    private fun trackCountLabel(count: Int) = if (count == 1) "1 track" else "$count tracks"

    companion object {
        const val ROOT_ID = "lastwave_root"
        const val QUEUE_ID = "category:queue"
        const val PLAYLISTS_ID = "category:playlists"
        const val DOWNLOADS_ID = "category:downloads"
        private const val QUEUE_TRACK_PREFIX = "queue:"
        private const val PLAYLIST_PREFIX = "playlist:"
        private const val PLAYLIST_TRACK_PREFIX = "playlist-track:"
        private const val DOWNLOAD_PREFIX = "download:"
        private const val SEARCH_PREFIX = "search:"
        private const val MAX_CACHED_SEARCHES = 6
        private const val MAX_SEARCH_RESULTS = 30
    }
}
