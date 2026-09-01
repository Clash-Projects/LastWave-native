package com.lastwave.app.data.ytmusic

import android.content.Context
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.StoredTrack
import com.lastwave.app.data.generate.toGenerated
import com.lastwave.app.data.generate.toStored
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.data.playlist.PlaylistImportManager
import com.lastwave.app.data.playlist.SavedPlaylist
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class YtCachedDetail(
    val id: Long,
    val title: String,
    val subtitle: String,
    val remotePlaylistId: String,
    val remoteArtworkUrl: String? = null,
    val remoteTrackCount: Int? = null,
    val tracks: List<StoredTrack> = emptyList(),
    val cachedAtMillis: Long = 0L,
)

/** Connected YouTube playlists as live library items; importing is optional. */
@Singleton
class YtMusicLibraryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val innerTube: InnerTubeMusicApi,
    private val auth: YtMusicAuthManager,
    private val preferences: YtMusicPreferences,
    private val importManager: PlaylistImportManager,
    private val applicationScope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheDir by lazy {
        File(context.cacheDir, "yt_remote_playlists").apply { mkdirs() }
    }

    private val _accountPlaylists = MutableStateFlow<List<YouTubePlaylistSummary>>(emptyList())
    val accountPlaylists: StateFlow<List<YouTubePlaylistSummary>> = _accountPlaylists.asStateFlow()
    val playlists: StateFlow<List<SavedPlaylist>> = combine(
        _accountPlaylists,
        preferences.hiddenLibraryPlaylistIds,
        preferences.pinnedLibraryPlaylistIds,
    ) { account, hiddenIds, pinnedIds ->
        account.filterNot { it.id in hiddenIds }.map { summary ->
            summaryToPlaylist(summary).copy(isPinned = summary.id in pinnedIds)
        }
    }.stateIn(applicationScope, SharingStarted.Eagerly, emptyList())

    private val remoteIdsByLocalId = ConcurrentHashMap<Long, String>()
    private val details = ConcurrentHashMap<Long, SavedPlaylist>()
    private val ownedPlaylistCache = ConcurrentHashMap<Long, Pair<Long, com.lastwave.app.data.music.YtOwnedPlaylist>>()
    private val ownedPlaylistLocks = ConcurrentHashMap<Long, Mutex>()
    private val knownArtworkByRemoteId = ConcurrentHashMap<String, String>()
    private val artworkRequests = ConcurrentHashMap.newKeySet<String>()
    private val artworkRetryAfter = ConcurrentHashMap<String, Long>()
    private val artworkRequestLimiter = Semaphore(4)
    private val refreshMutex = Mutex()

    init {
        applicationScope.launch(Dispatchers.IO) {
            try {
                preferences.cachedLibraryPlaylists().forEach { item ->
                    item.artworkUrl?.takeIf(String::isNotBlank)?.let {
                        knownArtworkByRemoteId[item.id] = it
                    }
                }
                cacheDir.listFiles()?.forEach { file ->
                    val raw = file.readText()
                    val cached = json.decodeFromString<YtCachedDetail>(raw)
                    cached.remoteArtworkUrl?.takeIf(String::isNotBlank)?.let {
                        knownArtworkByRemoteId[cached.remotePlaylistId] = it
                    }
                }
            } catch (_: Exception) {}
        }
        applicationScope.launch {
            auth.connection.collect { connection ->
                if (connection.isConnected) {
                    if (_accountPlaylists.value.isEmpty()) {
                        val cached = preferences.cachedLibraryPlaylists().map { it.toSummary() }
                        if (cached.isNotEmpty()) publish(cached)
                    }
                    refresh()
                } else {
                    remoteIdsByLocalId.clear()
                    details.clear()
                    knownArtworkByRemoteId.clear()
                    artworkRetryAfter.clear()
                    _accountPlaylists.value = emptyList()
                    clearDiskCache()
                }
            }
        }
        // InnerTube has no push subscription. Short foreground-process polling
        // gives connected playlists near-real-time behavior without importing.
        applicationScope.launch {
            while (true) {
                delay(REALTIME_REFRESH_MS)
                if (auth.connection.value.isConnected) refresh()
            }
        }
    }

    suspend fun refresh() = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!auth.connection.value.isConnected) return@withContext
            try {
                val fetched = innerTube.fetchLibraryPlaylists().distinctBy { it.id }
                // ArchiveTune uses the same non-destructive rule: an empty
                // library response is not authoritative. InnerTube can return
                // a structurally valid but empty page while auth/config is
                // settling, so never replace the last visible snapshot with it.
                if (fetched.isEmpty()) return@withContext
                val stablePlaylists = publish(fetched)
                preferences.setCachedLibraryPlaylists(stablePlaylists.map { it.toCache() })
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep the last successful account library visible while offline.
            }
        }
    }

    private fun publish(account: List<YouTubePlaylistSummary>): List<YouTubePlaylistSummary> {
        // Keep the complete account snapshot. Visibility is a separate,
        // persisted projection, so refresh/import/sync cannot reset it.
        val previous = _accountPlaylists.value.associateBy(YouTubePlaylistSummary::id)
        val stableAccount = account.map { summary ->
            val previousSummary = previous[summary.id]
            val localId = stableRemoteId(summary.id)
            val diskArtwork = readFromDiskCache(localId)?.remoteArtworkUrl
            val resolvedArt = summary.artworkUrl.normalizedRemoteArtwork()
                ?: knownArtworkByRemoteId[summary.id]?.normalizedRemoteArtwork()
                ?: (previousSummary?.artworkUrl).normalizedRemoteArtwork()
                ?: diskArtwork.normalizedRemoteArtwork()
            if (!resolvedArt.isNullOrBlank()) {
                knownArtworkByRemoteId[summary.id] = resolvedArt
            }
            summary.copy(
                artworkUrl = resolvedArt,
                trackCountText = summary.trackCountText?.takeIf { parseTrackCount(it) != null }
                    ?: previousSummary?.trackCountText,
            )
        }
        val allRemote = stableAccount.map(::summaryToPlaylist)
        _accountPlaylists.value = stableAccount
        remoteIdsByLocalId.clear()
        allRemote.forEach { playlist ->
            playlist.remotePlaylistId?.let { remoteIdsByLocalId[playlist.id] = it }
        }
        // Fill missing covers without downloading every continuation page of
        // large connected playlists.
        applicationScope.launch {
            allRemote.filter { it.remoteArtworkUrl.isNullOrBlank() }.forEach { playlist ->
                val remoteId = playlist.remotePlaylistId ?: return@forEach
                if (System.currentTimeMillis() < (artworkRetryAfter[remoteId] ?: 0L)) return@forEach
                if (!artworkRequests.add(remoteId)) return@forEach
                launch(Dispatchers.IO) {
                    try {
                        artworkRequestLimiter.withPermit {
                            val cached = readFromDiskCache(playlist.id)
                            if (cached != null) details.putIfAbsent(playlist.id, cached)
                            val cachedArtwork = (cached?.remoteArtworkUrl).normalizedRemoteArtwork()
                                ?: knownArtworkByRemoteId[remoteId]?.normalizedRemoteArtwork()
                                ?: cached?.tracks?.firstNotNullOfOrNull {
                                    it.artworkUrl?.takeIf(String::isNotBlank)
                                }
                            val artwork = (cachedArtwork ?: innerTube.fetchPlaylistArtwork(remoteId))
                                .normalizedRemoteArtwork()
                            if (artwork == null) {
                                artworkRetryAfter[remoteId] = System.currentTimeMillis() + ARTWORK_RETRY_DELAY_MS
                            } else {
                                artworkRetryAfter.remove(remoteId)
                                publishArtwork(remoteId, artwork)
                            }
                        }
                    } finally {
                        artworkRequests.remove(remoteId)
                    }
                }
            }
        }
        return stableAccount
    }

    private fun readFromDiskCache(localId: Long): SavedPlaylist? {
        return try {
            val file = File(cacheDir, "$localId.json")
            if (!file.exists()) return null
            val raw = file.readText()
            val cached = json.decodeFromString<YtCachedDetail>(raw)
            SavedPlaylist(
                id = cached.id,
                title = cached.title,
                subtitle = cached.subtitle,
                mode = "youtube_remote",
                tracks = cached.tracks.map(StoredTrack::toGenerated),
                createdAtMillis = 0L,
                remotePlaylistId = cached.remotePlaylistId,
                remoteArtworkUrl = cached.remoteArtworkUrl,
                remoteTrackCount = cached.remoteTrackCount ?: cached.tracks.size,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writeToDiskCache(localId: Long, playlist: SavedPlaylist) {
        try {
            val file = File(cacheDir, "$localId.json")
            val cached = YtCachedDetail(
                id = playlist.id,
                title = playlist.title,
                subtitle = playlist.subtitle,
                remotePlaylistId = playlist.remotePlaylistId ?: return,
                remoteArtworkUrl = playlist.remoteArtworkUrl,
                remoteTrackCount = playlist.remoteTrackCount ?: playlist.tracks.size,
                tracks = playlist.tracks.map(GeneratedTrack::toStored),
                cachedAtMillis = System.currentTimeMillis(),
            )
            file.writeText(json.encodeToString(cached))
        } catch (_: Exception) {
        }
    }

    private fun clearDiskCache() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    suspend fun loadDetail(
        localId: Long,
        onUpdate: ((SavedPlaylist) -> Unit)? = null,
    ): SavedPlaylist? = withContext(Dispatchers.IO) {
        // 1. Check in-memory cache for immediate zero-latency result
        details[localId]?.takeIf { it.tracks.isNotEmpty() }?.let { mem ->
            onUpdate?.invoke(mem)
            return@withContext mem
        }

        // 2. Check persistent disk cache for instant startup load
        val disk = readFromDiskCache(localId)
        if (disk != null && disk.tracks.isNotEmpty()) {
            details[localId] = disk
            onUpdate?.invoke(disk)
            // Trigger background refresh so any playlist changes on YouTube are updated
            applicationScope.launch {
                refreshDetailFromNetwork(localId, onUpdate)
            }
            return@withContext disk
        }

        var remoteId = remoteIdsByLocalId[localId]
        if (remoteId == null) {
            refresh()
            remoteId = remoteIdsByLocalId[localId]
        }
        val resolvedRemoteId = remoteId ?: return@withContext disk

        val summary = _accountPlaylists.value.firstOrNull { stableRemoteId(it.id) == localId || it.id == resolvedRemoteId }

        // Immediately seed details and UI with summary to avoid blank flash/0 songs
        if (summary != null) {
            val stub = disk ?: summaryToPlaylist(summary)
            details[localId] = stub
            onUpdate?.invoke(stub)
        }

        refreshDetailFromNetwork(localId, onUpdate)
    }

    private suspend fun refreshDetailFromNetwork(
        localId: Long,
        onUpdate: ((SavedPlaylist) -> Unit)? = null,
    ): SavedPlaylist? = withContext(Dispatchers.IO) {
        val remoteId = remoteIdsByLocalId[localId] ?: return@withContext details[localId]
        val summary = _accountPlaylists.value.firstOrNull { stableRemoteId(it.id) == localId || it.id == remoteId }

        val totalEstimatedCount = summary?.trackCountText?.substringBefore(' ')?.replace(",", "")?.toIntOrNull()

        val result = innerTube.fetchPlaylist(remoteId) { partialSongs ->
            val partialArtwork = summary?.artworkUrl?.takeIf(String::isNotBlank)
                ?: partialSongs.firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) }
            publishArtwork(remoteId, partialArtwork)
            val partialPlaylist = SavedPlaylist(
                id = localId,
                title = summary?.title?.takeIf { it.isNotBlank() } ?: "YouTube Playlist",
                subtitle = "YouTube Music • Connected account",
                mode = "youtube_remote",
                tracks = partialSongs.map(YouTubeMusicTrack::toGeneratedTrack),
                createdAtMillis = 0L,
                remotePlaylistId = remoteId,
                remoteArtworkUrl = partialArtwork,
                remoteTrackCount = totalEstimatedCount ?: partialSongs.size,
            )
            details[localId] = partialPlaylist
            onUpdate?.invoke(partialPlaylist)
        } ?: return@withContext details[localId]

        val title = if (result.title.isNotBlank() && result.title != "Imported Playlist") {
            result.title
        } else {
            summary?.title?.takeIf { it.isNotBlank() } ?: "YouTube Playlist"
        }
        val artworkUrl = result.artworkUrl?.takeIf(String::isNotBlank)
            ?: summary?.artworkUrl?.takeIf(String::isNotBlank)
            ?: result.tracks.firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) }
        publishArtwork(remoteId, artworkUrl)
        val trackCount = if (result.trackCount > 0) {
            result.trackCount
        } else {
            totalEstimatedCount ?: result.tracks.size
        }

        val playlist = SavedPlaylist(
            id = localId,
            title = title,
            subtitle = "YouTube Music • Connected account",
            mode = "youtube_remote",
            tracks = result.tracks.map(YouTubeMusicTrack::toGeneratedTrack),
            createdAtMillis = 0L,
            remotePlaylistId = result.id,
            remoteArtworkUrl = artworkUrl,
            remoteTrackCount = trackCount,
        )
        details[localId] = playlist
        writeToDiskCache(localId, playlist)
        onUpdate?.invoke(playlist)
        playlist
    }

    private fun publishArtwork(remoteId: String, artworkUrl: String?) {
        val resolvedArtwork = artworkUrl.normalizedRemoteArtwork() ?: return
        knownArtworkByRemoteId[remoteId] = resolvedArtwork
        _accountPlaylists.update { playlists ->
            playlists.map { summary ->
                if (summary.id == remoteId && summary.artworkUrl.isNullOrBlank()) {
                    summary.copy(artworkUrl = resolvedArtwork)
                } else {
                    summary
                }
            }
        }
        val localId = stableRemoteId(remoteId)
        details[localId]?.let { cur ->
            if (cur.remoteArtworkUrl.isNullOrBlank()) {
                val updated = cur.copy(remoteArtworkUrl = resolvedArtwork)
                details[localId] = updated
                writeToDiskCache(localId, updated)
            }
        }
        preferences.setCachedLibraryPlaylists(_accountPlaylists.value.map { it.toCache() })
    }

    private fun String?.normalizedRemoteArtwork(): String? {
        val value = this?.trim()?.takeIf(String::isNotBlank) ?: return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("https://") || value.startsWith("http://") -> value
            else -> null
        }
    }

    suspend fun addTrack(
        localId: Long,
        track: GeneratedTrack,
        allowDuplicate: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        val playlist = loadDetail(localId) ?: return@withContext false
        val exactVideoId = track.youtubeVideoIdOrNull()
        val match = if (exactVideoId == null) innerTube.findBestMatchOrNull(track.name, track.artist) else null
        val videoId = exactVideoId ?: match?.videoId ?: return@withContext false
        if (!allowDuplicate) {
            val expectedTrackCount = playlist.remoteTrackCount ?: return@withContext false
            if (playlist.tracks.size < expectedTrackCount) return@withContext false
            if (playlist.tracks.any { it.matches(track, match) }) return@withContext false
        }
        val remoteId = playlist.remotePlaylistId ?: return@withContext false
        val added = innerTube.addVideosToRemotePlaylist(remoteId, listOf(videoId))
        if (added) {
            ownedPlaylistCache.remove(localId)
            details.remove(localId)
            File(cacheDir, "$localId.json").delete()
            updateRemoteTrackCount(remoteId, (playlist.remoteTrackCount ?: playlist.tracks.size) + 1)
        }
        added
    }

    suspend fun findDuplicatePlaylistIds(
        localIds: Set<Long>,
        track: GeneratedTrack,
    ): Set<Long> = withContext(Dispatchers.IO) {
        if (localIds.isEmpty()) return@withContext emptySet()
        val directVideoId = track.youtubeVideoIdOrNull()
        val match = if (directVideoId == null) innerTube.findBestMatchOrNull(track.name, track.artist) else null
        if (directVideoId == null && match == null) return@withContext emptySet()
        localIds.filterTo(mutableSetOf()) { localId ->
            val playlist = refreshDetailFromNetwork(localId) ?: loadDetail(localId)
            playlist?.tracks?.any { it.matches(track, match) } == true
        }
    }

    suspend fun findCachedDuplicatePlaylistIds(
        localIds: Set<Long>,
        track: GeneratedTrack,
    ): Set<Long> = withContext(Dispatchers.IO) {
        if (localIds.isEmpty()) return@withContext emptySet()
        val directVideoId = track.youtubeVideoIdOrNull()
        val match = if (directVideoId == null) innerTube.findBestMatchOrNull(track.name, track.artist) else null
        if (directVideoId == null && match == null) return@withContext emptySet()
        localIds.filterTo(mutableSetOf()) { localId ->
            val playlist = details[localId] ?: readFromDiskCache(localId)
            playlist?.tracks?.any { it.matches(track, match) } == true
        }
    }

    private fun GeneratedTrack.matches(original: GeneratedTrack, match: YouTubeMusicTrack?): Boolean {
        val videoId = youtubeVideoIdOrNull()
        val originalVideoId = original.youtubeVideoIdOrNull()
        return (videoId != null && videoId == originalVideoId) ||
            videoId == match?.videoId ||
            key == original.key ||
            (match != null && key == "${match.title}|${match.artist}".lowercase())
    }

    suspend fun removeTrack(localId: Long, index: Int): SavedPlaylist? = withContext(Dispatchers.IO) {
        val playlist = loadDetail(localId) ?: return@withContext null
        val remoteId = playlist.remotePlaylistId ?: return@withContext null
        val target = playlist.tracks.getOrNull(index) ?: return@withContext playlist
        val targetVideoId = target.youtubeVideoIdOrNull()
        val owned = getOwnedPlaylist(localId, remoteId, targetVideoId) ?: return@withContext playlist
        val item = (if (targetVideoId != null) owned.items.firstOrNull { it.videoId == targetVideoId && it.setVideoId != null } else null)
            ?: owned.items.getOrNull(index)?.takeIf { it.setVideoId != null }
            ?: return@withContext playlist
        if (!innerTube.removeVideosFromRemotePlaylist(remoteId, listOf(item.setVideoId!! to item.videoId))) {
            return@withContext playlist
        }
        val remainingTracks = playlist.tracks.filterIndexed { i, _ -> i != index }
        val updatedPlaylist = playlist.copy(
            tracks = remainingTracks,
            remoteTrackCount = (playlist.remoteTrackCount ?: playlist.tracks.size).let { maxOf(0, it - 1) },
        )
        ownedPlaylistCache[localId] = System.currentTimeMillis() to owned.copy(items = owned.items.filterNot { it.setVideoId == item.setVideoId })
        details[localId] = updatedPlaylist
        writeToDiskCache(localId, updatedPlaylist)
        updateRemoteTrackCount(remoteId, updatedPlaylist.remoteTrackCount ?: updatedPlaylist.tracks.size)
        updatedPlaylist
    }

    private suspend fun getOwnedPlaylist(
        localId: Long,
        remoteId: String,
        targetVideoId: String?,
    ): com.lastwave.app.data.music.YtOwnedPlaylist? {
        val now = System.currentTimeMillis()
        ownedPlaylistCache[localId]?.takeIf {
            now - it.first < OWNED_PLAYLIST_CACHE_TTL_MS &&
                (targetVideoId == null || it.second.items.any { item -> item.videoId == targetVideoId && item.setVideoId != null })
        }?.let { return it.second }
        return ownedPlaylistLocks.getOrPut(localId) { Mutex() }.withLock {
            val lockedNow = System.currentTimeMillis()
            ownedPlaylistCache[localId]
                ?.takeIf {
                    lockedNow - it.first < OWNED_PLAYLIST_CACHE_TTL_MS &&
                        (targetVideoId == null || it.second.items.any { item -> item.videoId == targetVideoId && item.setVideoId != null })
                }
                ?.let { return@withLock it.second }
            innerTube.fetchOwnedPlaylist(remoteId, targetVideoId)?.let { fetched ->
                val existing = ownedPlaylistCache[localId]?.second
                val merged = if (existing == null) fetched else fetched.copy(
                    items = (existing.items + fetched.items).distinctBy { it.setVideoId ?: it.videoId },
                )
                ownedPlaylistCache[localId] = lockedNow to merged
                merged
            }
        }
    }

    private fun updateRemoteTrackCount(remoteId: String, count: Int) {
        _accountPlaylists.value = _accountPlaylists.value.map { summary ->
            if (summary.id == remoteId) summary.copy(trackCountText = "$count tracks") else summary
        }
    }

    suspend fun makeLocal(localId: Long): SavedPlaylist? = withContext(Dispatchers.IO) {
        val remote = loadDetail(localId) ?: return@withContext null
        val remoteId = remote.remotePlaylistId ?: return@withContext null
        val result = innerTube.fetchPlaylist(remoteId) ?: return@withContext null
        val saved = importManager.importOwnedYouTubePlaylist(result)
        details.remove(localId)
        File(cacheDir, "$localId.json").delete()
        refresh()
        saved
    }

    suspend fun togglePinned(localId: Long, isPinned: Boolean) {
        val remoteId = remoteIdsByLocalId[localId]
            ?: details[localId]?.remotePlaylistId
            ?: _accountPlaylists.value.firstOrNull { stableRemoteId(it.id) == localId }?.id
            ?: return
        preferences.setPinned(remoteId, isPinned)
        details[localId]?.let { cur ->
            val updated = cur.copy(isPinned = isPinned)
            details[localId] = updated
            writeToDiskCache(localId, updated)
        }
    }

    private fun summaryToPlaylist(summary: YouTubePlaylistSummary): SavedPlaylist {
        val id = stableRemoteId(summary.id)
        val count = summary.trackCountText?.let(::parseTrackCount)
        val artwork = summary.artworkUrl ?: knownArtworkByRemoteId[summary.id]
        return SavedPlaylist(
            id = id,
            title = summary.title,
            subtitle = "YouTube Music • Connected account",
            mode = "youtube_remote",
            tracks = emptyList(),
            createdAtMillis = 0L,
            remotePlaylistId = summary.id,
            remoteArtworkUrl = artwork,
            remoteTrackCount = count,
        )
    }

    private fun parseTrackCount(text: String): Int? {
        val match = Regex("""([\d,.]+)\s*([KMB]?)""", RegexOption.IGNORE_CASE).find(text) ?: return null
        val value = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].uppercase()) {
            "K" -> 1_000
            "M" -> 1_000_000
            "B" -> 1_000_000_000
            else -> 1
        }
        return (value * multiplier).toInt().coerceAtLeast(0)
    }

    private fun stableRemoteId(remoteId: String): Long {
        var hash = -0x340d631b7bdddcdbL
        remoteId.forEach { char ->
            hash = (hash xor char.code.toLong()) * 0x100000001b3L
        }
        return hash or Long.MIN_VALUE
    }

    private fun YtCachedLibraryPlaylist.toSummary() = YouTubePlaylistSummary(
        id = id,
        title = title,
        author = author,
        trackCountText = trackCountText,
        artworkUrl = artworkUrl,
    )

    private fun YouTubePlaylistSummary.toCache() = YtCachedLibraryPlaylist(
        id = id,
        title = title,
        author = author,
        trackCountText = trackCountText,
        artworkUrl = artworkUrl,
    )

    private companion object {
        const val REALTIME_REFRESH_MS = 8_000L
        const val ARTWORK_RETRY_DELAY_MS = 5 * 60 * 1000L
        const val OWNED_PLAYLIST_CACHE_TTL_MS = 10 * 60 * 1000L
    }
}

internal fun YouTubeMusicTrack.toGeneratedTrack() = GeneratedTrack(
    name = title,
    artist = artist,
    album = album,
    artworkUrl = artworkUrl,
    url = "https://music.youtube.com/watch?v=$videoId",
)
