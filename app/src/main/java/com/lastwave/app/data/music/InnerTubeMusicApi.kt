package com.lastwave.app.data.music

import com.lastwave.app.data.music.potoken.BotGuardTokenGenerator
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class YouTubeMusicTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationSeconds: Int? = null,
)

enum class YouTubeMusicEntityKind { ARTIST, ALBUM }

data class YouTubeMusicEntity(
    val kind: YouTubeMusicEntityKind,
    val name: String,
    val artist: String? = null,
    val subtitle: String? = null,
    val browseId: String,
    val playlistId: String? = null,
    val artworkUrl: String? = null,
)

data class YouTubeAudioStream(
    val url: String,
    val mimeType: String?,
    val bitrate: Int,
)

data class YtMusicTasteSignals(
    val recentTracks: List<YouTubeMusicTrack> = emptyList(),
    val likedTracks: List<YouTubeMusicTrack> = emptyList(),
    val feedTracks: List<YouTubeMusicTrack> = emptyList(),
)

/** A provider explicitly identified the media as unavailable, rather than a
 * request merely failing because the network or extractor was slow. */
class ConfirmedUnplayableMediaException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

data class YouTubePlaylistResult(
    val id: String,
    val title: String,
    val author: String? = null,
    val artworkUrl: String? = null,
    val trackCount: Int = 0,
    val tracks: List<YouTubeMusicTrack> = emptyList(),
)

data class YouTubePlaylistSummary(
    val id: String,
    val title: String,
    val author: String? = null,
    val trackCountText: String? = null,
    val artworkUrl: String? = null,
)

data class YtAccountInfo(
    val accountName: String,
    val channelHandle: String? = null,
    val photoUrl: String? = null,
)

/** One item of an OWNED playlist, carrying its `setVideoId` — the unique
 *  per-entry token required by ACTION_REMOVE_VIDEO edits. */
data class YtOwnedPlaylistItem(
    val videoId: String,
    val setVideoId: String? = null,
)

data class YtOwnedPlaylist(
    val id: String,
    val title: String,
    val items: List<YtOwnedPlaylistItem> = emptyList(),
)

/**
 * Client for the same private InnerTube endpoints used by the YouTube Music
 * web/mobile clients. Search uses WEB_REMIX while playback tries mobile
 * clients that return direct adaptive audio formats.
 *
 * Anonymous by default — but when a YouTube Music account is connected via
 * [YtMusicAuthManager], requests can opt in to the account's cookies +
 * SAPISIDHASH Authorization header, unlocking library browsing, playlist
 * creation and playlist edits (the same surfaces music.youtube.com uses).
 *
 * InnerTube is not a public/stable Google API. The web client key/version
 * are therefore bootstrapped from music.youtube.com and cached instead of
 * permanently tying search to a stale build identifier.
 */
@Singleton
class InnerTubeMusicApi @Inject constructor(
    private val http: OkHttpClient,
    private val streamExtractor: YouTubeStreamExtractor,
    private val ytAuth: YtMusicAuthManager,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val configMutex = Mutex()
    private val matchCache = ConcurrentHashMap<String, YouTubeMusicTrack>()
    private val streamCache = ConcurrentHashMap<String, Pair<Long, YouTubeAudioStream>>()
    private val activeStreamRequests = ConcurrentHashMap<String, Deferred<YouTubeAudioStream>>()
    private val apiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val failedClientsUntil = ConcurrentHashMap<String, Long>()
    @Volatile private var webConfig: WebConfig? = null

    fun invalidateCache(videoId: String) {
        streamCache.remove(videoId)
        activeStreamRequests.remove(videoId)?.cancel()
        matchCache.values.removeIf { it.videoId == videoId }
        streamExtractor.invalidateCache(videoId)
    }

    /** Proactively resolves and seeds the in-memory stream cache in the background */
    fun prefetchStream(videoId: String) {
        if (videoId.isBlank()) return
        apiScope.launch {
            runCatching { resolveAudioStream(videoId) }
        }
    }

    fun extractPlaylistId(input: String): String {
        val clean = input.trim()
        if (clean.contains("list=")) {
            return clean.substringAfter("list=").substringBefore('&').substringBefore('#')
        }
        if (clean.contains("playlist/")) {
            return clean.substringAfter("playlist/").substringBefore('?').substringBefore('/')
        }
        return clean
    }

    /**
     * Loads and parses any YouTube Music or standard YouTube playlist by ID or
     * URL — following continuation pages until the playlist is exhausted, so
     * playlists of ANY length import fully (a single browse response only
     * returns ~100 items, which used to silently truncate imports).
     *
     * When an account is connected, the first attempt is authenticated so
     * owned/private playlists resolve too; it transparently falls back to
     * anonymous for public ones.
     */
    suspend fun fetchPlaylist(
        playlistIdOrUrl: String,
        onPageLoaded: ((List<YouTubeMusicTrack>) -> Unit)? = null,
    ): YouTubePlaylistResult? = withContext(Dispatchers.IO) {
        val rawId = extractPlaylistId(playlistIdOrUrl)
        if (rawId.isBlank()) return@withContext null
        val browseId = if (rawId.startsWith("VL")) rawId else "VL$rawId"

        val useAuth = ytAuth.connection.value.isConnected
        var root = if (useAuth) {
            runCatching { browseRoot(browseId, authenticated = true) }.getOrNull()
        } else null
        val authenticatedAs = root != null
        if (root == null) {
            root = runCatching { browseRoot(browseId, authenticated = false) }.getOrNull() ?: return@withContext null
        }

        val header = root.obj("header")?.obj("musicDetailHeaderRenderer")
            ?: root.obj("header")?.obj("musicResponsiveHeaderRenderer")
            ?: root.obj("header")?.obj("musicEditablePlaylistDetailHeaderRenderer")?.obj("header")?.obj("musicResponsiveHeaderRenderer")
            ?: root.obj("header")?.obj("musicEditablePlaylistDetailHeaderRenderer")?.obj("header")?.obj("musicDetailHeaderRenderer")
            ?: root.obj("header")?.obj("musicEditablePlaylistDetailHeaderRenderer")
            ?: root.obj("header")?.obj("musicVisualHeaderRenderer")
            ?: root.obj("header")?.obj("musicHeaderRenderer")
            ?: root.obj("header")?.obj("playlistHeaderRenderer")
            ?: findFirstHeaderRenderer(root)

        val title = extractTitleFromHeader(header, root)

        val author = header?.obj("subtitle")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: header?.obj("straplineTextOne")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: findFirstAuthor(header)

        val artworkUrl = extractArtworkFromHeader(header, root)

        val songs = mutableListOf<YouTubeMusicTrack>()
        val initialSongs = parseSongRenderers(root)
        songs += initialSongs
        if (songs.isNotEmpty()) {
            onPageLoaded?.invoke(songs.toList())
        }

        // Follow continuation pages until gone. Safety cap is enormous on
        // purpose (60k tracks) — it only exists to bound a pathological loop.
        var token = playlistShelfContinuationToken(root)
        var page = 0
        while (!token.isNullOrBlank() && page < MAX_CONTINUATION_PAGES) {
            val currentToken = token ?: break
            val nextPage = runCatching {
                browseContinuation(browseId, currentToken, authenticated = authenticatedAs)
            }.getOrNull() ?: break
            val pageSongs = parseSongRenderers(nextPage)
            if (pageSongs.isEmpty()) break
            val knownVideoIds = songs.mapTo(mutableSetOf()) { it.videoId }
            val newSongs = pageSongs.filterNot { it.videoId in knownVideoIds }
            if (newSongs.isEmpty()) break
            songs += newSongs
            onPageLoaded?.invoke(songs.toList())
            token = playlistShelfContinuationToken(nextPage)
            page++
        }

        songs.take(3).forEach { prefetchStream(it.videoId) }
        YouTubePlaylistResult(
            id = rawId,
            title = title ?: "",
            author = author,
            artworkUrl = artworkUrl,
            trackCount = songs.size,
            tracks = songs,
        )
    }

    private fun findFirstHeaderRenderer(root: JsonElement): JsonObject? {
        val renderers = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveHeaderRenderer", renderers)
        collectObjects(root, "musicDetailHeaderRenderer", renderers)
        collectObjects(root, "musicEditablePlaylistDetailHeaderRenderer", renderers)
        collectObjects(root, "musicVisualHeaderRenderer", renderers)
        collectObjects(root, "musicHeaderRenderer", renderers)
        return renderers.firstOrNull()
    }

    private fun extractTitleFromHeader(header: JsonObject?, root: JsonElement): String? {
        if (header != null) {
            val runsText = header.obj("title")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?.trim()?.takeIf { it.isNotBlank() }
            if (runsText != null) return runsText

            val nestedHeader = header.obj("header")?.obj("musicResponsiveHeaderRenderer")
                ?: header.obj("header")?.obj("musicDetailHeaderRenderer")
                ?: header.obj("header")
            if (nestedHeader != null) {
                val nestedRuns = nestedHeader.obj("title")?.array("runs")
                    ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                    ?.trim()?.takeIf { it.isNotBlank() }
                if (nestedRuns != null) return nestedRuns
            }

            val simpleTitle = header.obj("title")?.string("simpleText")
                ?: header.string("title")
            if (!simpleTitle.isNullOrBlank()) return simpleTitle.trim()
        }

        val titles = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveHeaderRenderer", titles)
        for (h in titles) {
            val t = h.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }?.trim()
            if (!t.isNullOrBlank()) return t
        }
        return null
    }

    private fun findFirstAuthor(header: JsonObject?): String? {
        if (header == null) return null
        return header.obj("subtitle")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: header.obj("straplineTextOne")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: header.obj("secondSubtitle")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
    }

    private fun extractArtworkFromHeader(header: JsonObject?, root: JsonElement): String? {
        fun extractFromThumbnailsArray(arr: JsonArray?): String? {
            val url = arr?.lastOrNull()?.asObject()?.string("url") ?: return null
            val formatted = if (url.startsWith("//")) "https:$url" else url
            return formatted.highResolutionArtwork()
        }

        if (header != null) {
            val direct = header.obj("thumbnail")?.obj("croppedSquareThumbnailRenderer")?.array("thumbnails")
                ?: header.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.array("thumbnails")
                ?: header.obj("thumbnail")?.obj("musicThumbnailRenderer")?.array("thumbnails")
                ?: header.obj("thumbnail")?.obj("musicCustomThumbnailRenderer")?.obj("thumbnail")?.array("thumbnails")
                ?: header.obj("thumbnail")?.array("thumbnails")
                ?: header.obj("thumbnailRenderer")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.array("thumbnails")
            extractFromThumbnailsArray(direct)?.let { return it }

            val nestedHeader = header.obj("header")
            if (nestedHeader != null) {
                val nestedThumbs = nestedHeader.obj("thumbnail")?.obj("croppedSquareThumbnailRenderer")?.array("thumbnails")
                    ?: nestedHeader.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.array("thumbnails")
                    ?: nestedHeader.obj("thumbnail")?.array("thumbnails")
                extractFromThumbnailsArray(nestedThumbs)?.let { return it }
            }
        }

        val thumbObjects = mutableListOf<JsonObject>()
        if (header != null) collectObjects(header, "musicThumbnailRenderer", thumbObjects)
        collectObjects(root, "musicResponsiveHeaderRenderer", thumbObjects)
        for (to in thumbObjects) {
            val arr = to.array("thumbnails") ?: to.obj("thumbnail")?.array("thumbnails")
            extractFromThumbnailsArray(arr)?.let { return it }
        }
        return null
    }

    /** The account's own library playlists (FEmusic_liked_playlists). */
    suspend fun fetchLibraryPlaylists(): List<YouTubePlaylistSummary> = withContext(Dispatchers.IO) {
        val config = getWebConfig()
        // Let the initial request failure propagate. Treating a network/auth
        // failure as a real empty library made valid playlists flash away.
        val root = post(
            url = "$MUSIC_API/browse?key=${config.apiKey}&prettyPrint=false",
            body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                put("browseId", LIBRARY_PLAYLISTS_BROWSE_ID)
            },
            clientName = "WEB_REMIX",
            clientVersion = config.clientVersion,
            userAgent = WEB_USER_AGENT,
            authenticated = true,
        )

        val summaries = parsePlaylistRenderers(root).toMutableList()
        var token = genericContinuationToken(root)
        var page = 0
        while (!token.isNullOrBlank() && page < 20) {
            val nextPage = runCatching {
                post(
                    url = "$MUSIC_API/browse?key=${config.apiKey}&prettyPrint=false",
                    body = buildJsonObject {
                        put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                        put("browseId", LIBRARY_PLAYLISTS_BROWSE_ID)
                        put("continuation", token)
                    },
                    clientName = "WEB_REMIX",
                    clientVersion = config.clientVersion,
                    userAgent = WEB_USER_AGENT,
                    authenticated = true,
                )
            }.getOrNull() ?: break
            summaries += parsePlaylistRenderers(nextPage)
            token = genericContinuationToken(nextPage)
            page++
        }
        summaries.distinctBy { it.id }.filter { it.id.isNotBlank() }
    }

    /** Read-only signals and playable Home-feed candidates from a connected
     * account. Each request is isolated so a missing surface cannot break the
     * remaining signals or the normal recommendation fallback. */
    suspend fun fetchTasteSignals(
        recentLimit: Int = 30,
        likedLimit: Int = 24,
        feedLimit: Int = 40,
    ): YtMusicTasteSignals = withContext(Dispatchers.IO) {
        if (!ytAuth.connection.value.isConnected) return@withContext YtMusicTasteSignals()
        kotlinx.coroutines.coroutineScope {
            val recent = async {
                runCatching { parseSongRenderers(browseRoot(YT_HISTORY_BROWSE_ID, authenticated = true)) }
                    .getOrDefault(emptyList())
                    .filterNot { it.artist.equals("Unknown artist", ignoreCase = true) }
                    .distinctBy { it.videoId }
                    .take(recentLimit.coerceIn(0, 50))
            }
            val liked = async {
                runCatching { parseSongRenderers(browseRoot(YT_LIKED_BROWSE_ID, authenticated = true)) }
                    .getOrDefault(emptyList())
                    .filterNot { it.artist.equals("Unknown artist", ignoreCase = true) }
                    .distinctBy { it.videoId }
                    .take(likedLimit.coerceIn(0, 50))
            }
            val feed = async {
                runCatching {
                    parseHomeFeedSongs(browseRoot(YT_HOME_BROWSE_ID, authenticated = true))
                }
                    .getOrDefault(emptyList())
                    .filterNot { it.artist.equals("Unknown artist", ignoreCase = true) }
                    .distinctBy { it.videoId }
                    .take(feedLimit.coerceIn(0, 60))
            }
            YtMusicTasteSignals(
                recentTracks = recent.await(),
                likedTracks = liked.await(),
                feedTracks = feed.await(),
            )
        }
    }

    /** Identity of the signed-in account (account_menu endpoint). */
    suspend fun fetchAccountInfo(): YtAccountInfo? = withContext(Dispatchers.IO) {
        if (!ytAuth.connection.value.isConnected) return@withContext null
        val config = getWebConfig()
        val root = runCatching {
            post(
                url = "$MUSIC_API/account/account_menu?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
                authenticated = true,
            )
        }.getOrNull() ?: return@withContext null

        val headers = mutableListOf<JsonObject>()
        collectObjects(root, "activeAccountHeaderRenderer", headers)
        val header = headers.firstOrNull() ?: return@withContext null
        val accountName = header.obj("accountName")?.array("runs")?.firstOrNull()
            ?.asObject()?.string("text")?.trim().orEmpty()
        if (accountName.isBlank()) return@withContext null
        YtAccountInfo(
            accountName = accountName,
            channelHandle = header.obj("channelHandle")?.array("runs")?.firstOrNull()
                ?.asObject()?.string("text"),
            photoUrl = header.obj("accountPhoto")?.obj("thumbnails")?.array("thumbnails")
                ?.lastOrNull()?.asObject()?.string("url"),
        )
    }

    /** Creates a PRIVATE playlist owned by the connected account; returns its id. */
    suspend fun createRemotePlaylist(title: String): String? = withContext(Dispatchers.IO) {
        if (!ytAuth.connection.value.isConnected) return@withContext null
        val cleanTitle = title.replace("<", "(").replace(">", ")").take(150)
            .ifBlank { "LastWave Playlist" }
        val config = getWebConfig()
        val root = runCatching {
            post(
                url = "$MUSIC_API/playlist/create?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                    put("title", cleanTitle)
                    put("privacyStatus", "PRIVATE")
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
                authenticated = true,
            )
        }.getOrNull() ?: return@withContext null
        root.string("playlistId")?.takeIf { it.isNotBlank() }
    }

    /** Renames an owned remote playlist via ACTION_SET_PLAYLIST_NAME. */
    suspend fun renameRemotePlaylist(playlistId: String, title: String): Boolean = withContext(Dispatchers.IO) {
        editRemotePlaylist(
            playlistId = playlistId,
            actions = listOf(buildJsonObject {
                put("action", "ACTION_SET_PLAYLIST_NAME")
                put("playlistName", title.take(150))
            }),
        )
    }

    /** Deletes a remote playlist owned by the connected account. */
    suspend fun deleteRemotePlaylist(playlistId: String): Boolean = withContext(Dispatchers.IO) {
        if (!ytAuth.connection.value.isConnected) return@withContext false
        val config = getWebConfig()
        // HTTP success is authoritative for this endpoint; some responses omit
        // the "status" field entirely, so don't require it.
        runCatching {
            post(
                url = "$MUSIC_API/playlist/delete?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                    put("playlistId", playlistId)
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
                authenticated = true,
            )
            true
        }.getOrDefault(false)
    }

    /** Appends videos (deduped server-side); batched at InnerTube's ~50 actions/request. */
    suspend fun addVideosToRemotePlaylist(playlistId: String, videoIds: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            if (videoIds.isEmpty()) return@withContext true
            videoIds.chunked(WRITE_ACTIONS_PER_REQUEST).all { chunk ->
                editRemotePlaylist(
                    playlistId = playlistId,
                    actions = chunk.map { videoId ->
                        buildJsonObject {
                            put("action", "ACTION_ADD_VIDEO")
                            put("addedVideoId", videoId)
                            put("dedupeOption", "DEDUPE_OPTION_SKIP")
                        }
                    },
                )
            }
        }

    /** Removes entries by their per-entry setVideoId (from [fetchOwnedPlaylist]). */
    suspend fun removeVideosFromRemotePlaylist(
        playlistId: String,
        removals: List<Pair<String, String>>,
    ): Boolean = withContext(Dispatchers.IO) {
        if (removals.isEmpty()) return@withContext true
        removals.chunked(WRITE_ACTIONS_PER_REQUEST).all { chunk ->
            editRemotePlaylist(
                playlistId = playlistId,
                actions = chunk.map { (setVideoId, removedVideoId) ->
                    buildJsonObject {
                        put("action", "ACTION_REMOVE_VIDEO")
                        put("setVideoId", setVideoId)
                        put("removedVideoId", removedVideoId)
                    }
                },
            )
        }
    }

    /** Reads back an OWNED playlist with each item's setVideoId for diffs/removals. */
    suspend fun fetchOwnedPlaylist(
        playlistIdOrUrl: String,
        stopAfterVideoId: String? = null,
    ): YtOwnedPlaylist? = withContext(Dispatchers.IO) {
        if (!ytAuth.connection.value.isConnected) return@withContext null
        val rawId = extractPlaylistId(playlistIdOrUrl)
        if (rawId.isBlank()) return@withContext null
        val browseId = if (rawId.startsWith("VL")) rawId else "VL$rawId"

        val root = runCatching { browseRoot(browseId, authenticated = true) }.getOrNull()
            ?: return@withContext null

        val title = root.obj("header")?.obj("musicEditablePlaylistDetailHeaderRenderer")
            ?.obj("header")?.obj("musicResponsiveHeaderRenderer")?.obj("title")?.array("runs")
            ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: root.obj("header")?.obj("musicDetailHeaderRenderer")?.obj("title")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: root.obj("header")?.obj("musicResponsiveHeaderRenderer")?.obj("title")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: "Playlist"

        val shelves = mutableListOf<JsonObject>()
        collectObjects(root, "musicPlaylistShelfRenderer", shelves)

        val items = mutableListOf<YtOwnedPlaylistItem>()
        val seenEntries = mutableSetOf<String>()
        fun absorb(element: JsonElement) {
            val renderers = mutableListOf<JsonObject>()
            collectObjects(element, "musicResponsiveListItemRenderer", renderers)
            collectObjects(element, "playlistVideoRenderer", renderers)
            for (renderer in renderers) {
                val videoId = renderer.obj("playlistItemData")?.string("videoId")
                    ?: (renderer["videoId"] as? JsonPrimitive)?.contentOrNull
                    ?: findString(renderer, "videoId")
                    ?: continue
                val setVideoId = extractSetVideoId(renderer)
                val entryKey = setVideoId ?: videoId
                if (videoId.isBlank() || !seenEntries.add(entryKey)) continue
                items += YtOwnedPlaylistItem(videoId, setVideoId)
            }
        }
        shelves.forEach(::absorb)
        if (shelves.isEmpty()) absorb(root)

        var token = playlistShelfContinuationToken(root)
        var page = 0
        fun targetFound() = stopAfterVideoId != null && items.any {
            it.videoId == stopAfterVideoId && !it.setVideoId.isNullOrBlank()
        }
        while (!targetFound() && !token.isNullOrBlank() && page < MAX_CONTINUATION_PAGES) {
            val currentToken = token ?: break
            val nextPage = runCatching { browseContinuation(browseId, currentToken, authenticated = true) }
                .getOrNull() ?: break
            absorb(nextPage)
            token = playlistShelfContinuationToken(nextPage)
            page++
        }

        YtOwnedPlaylist(id = rawId, title = title, items = items)
    }

    private suspend fun editRemotePlaylist(playlistId: String, actions: List<JsonElement>): Boolean =
        withContext(Dispatchers.IO) {
            if (!ytAuth.connection.value.isConnected) return@withContext false
            val cleanId = playlistId.removePrefix("VL")
            val config = getWebConfig()
            runCatching {
                val root = post(
                    url = "$MUSIC_API/browse/edit_playlist?key=${config.apiKey}&prettyPrint=false",
                    body = buildJsonObject {
                        put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                        put("playlistId", cleanId)
                        put("actions", JsonArray(actions))
                    },
                    clientName = "WEB_REMIX",
                    clientVersion = config.clientVersion,
                    userAgent = WEB_USER_AGENT,
                    authenticated = true,
                )
                val status = root.string("status").orEmpty()
                status.isBlank() || status.contains("SUCCEEDED", ignoreCase = true) || root["actions"] != null
            }.getOrElse { false }
        }

    private suspend fun browseRoot(browseId: String, authenticated: Boolean): JsonObject {
        val config = getWebConfig()
        return post(
            url = "$MUSIC_API/browse?key=${config.apiKey}&prettyPrint=false",
            body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                put("browseId", browseId)
            },
            clientName = "WEB_REMIX",
            clientVersion = config.clientVersion,
            userAgent = WEB_USER_AGENT,
            authenticated = authenticated,
        )
    }

    private suspend fun browseContinuation(browseId: String, token: String, authenticated: Boolean): JsonObject {
        val config = getWebConfig()
        return post(
            url = "$MUSIC_API/browse?key=${config.apiKey}&prettyPrint=false",
            body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                put("browseId", browseId)
                put("continuation", token)
            },
            clientName = "WEB_REMIX",
            clientVersion = config.clientVersion,
            userAgent = WEB_USER_AGENT,
            authenticated = authenticated,
        )
    }

    /** Continuation of the playlist track shelf specifically (not other shelves). */
    private fun playlistShelfContinuationToken(root: JsonElement): String? {
        val shelves = mutableListOf<JsonObject>()
        collectObjects(root, "musicPlaylistShelfRenderer", shelves)
        for (shelf in shelves) {
            val continuations = shelf.array("continuations") ?: continue
            val token = continuations.firstOrNull()?.asObject()
                ?.obj("nextContinuationData")?.string("continuation")
            if (!token.isNullOrBlank()) return token
        }
        return genericContinuationToken(root)
    }

    /** First continuation token anywhere in the tree (grid/list fallbacks). */
    private fun genericContinuationToken(root: JsonElement): String? {
        val commands = mutableListOf<JsonObject>()
        collectObjects(root, "continuationCommand", commands)
        commands.firstNotNullOfOrNull { cmd ->
            cmd.string("token")?.takeIf(String::isNotBlank)
        }?.let { return it }

        val legacyItems = mutableListOf<JsonObject>()
        collectObjects(root, "nextContinuationData", legacyItems)
        return legacyItems.firstNotNullOfOrNull { it.string("continuation")?.takeIf(String::isNotBlank) }
    }

    private fun extractSetVideoId(renderer: JsonObject): String? {
        (renderer["setVideoId"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let { return it }
        renderer.obj("playlistItemData")?.let { data ->
            (data.string("playlistSetVideoId") ?: data.string("videoSetVideoId") ?: data.string("setVideoId"))
                ?.takeIf(String::isNotBlank)?.let { return it }
        }
        val editEndpoints = mutableListOf<JsonObject>()
        collectObjects(renderer, "playlistEditEndpoint", editEndpoints)
        for (ep in editEndpoints) {
            val actions = ep.array("actions") ?: continue
            for (action in actions) {
                val actObj = action.asObject() ?: continue
                val svId = actObj.string("setVideoId")
                if (!svId.isNullOrBlank()) return svId
            }
        }
        return findString(renderer, "setVideoId") ?: findString(renderer, "playlistSetVideoId")
    }

    suspend fun searchSongs(query: String, limit: Int = 30): List<YouTubeMusicTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val config = getWebConfig()
        val body = buildJsonObject {
            put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
            put("query", query.trim())
            // YouTube Music's Songs filter, decoded (the endpoint JSON body
            // accepts the base64 value directly).
            put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA==")
        }
        val root = post(
            url = "$MUSIC_API/search?key=${config.apiKey}&prettyPrint=false",
            body = body,
            clientName = "WEB_REMIX",
            clientVersion = config.clientVersion,
            userAgent = WEB_USER_AGENT,
        )
        val results = parseSongRenderers(root).take(limit)
        results.take(2).forEach { prefetchStream(it.videoId) }
        results
    }

    suspend fun searchArtists(query: String, limit: Int = 30): List<YouTubeMusicEntity> =
        searchEntities(query, YouTubeMusicEntityKind.ARTIST, ARTIST_SEARCH_FILTER, limit)

    suspend fun searchAlbums(query: String, limit: Int = 30): List<YouTubeMusicEntity> =
        searchEntities(query, YouTubeMusicEntityKind.ALBUM, ALBUM_SEARCH_FILTER, limit)

    /** Loads and parses full artist details including top songs, albums, singles, and similar artists. */
    suspend fun fetchArtistPage(browseId: String, artistNameFallback: String = ""): com.lastwave.app.data.model.ArtistPageData? = withContext(Dispatchers.IO) {
        if (browseId.isBlank()) return@withContext null
        val config = getWebConfig()
        val root = runCatching {
            post(
                url = "$MUSIC_API/browse?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                    put("browseId", browseId)
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
            )
        }.getOrNull() ?: return@withContext null

        val header = root.obj("header")?.obj("musicVisualHeaderRenderer")
            ?: root.obj("header")?.obj("musicImmersiveHeaderRenderer")
            ?: root.obj("header")?.obj("musicHeaderRenderer")

        val title = header?.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?.ifBlank { null }
            ?: header?.string("title")
            ?: artistNameFallback.ifBlank { "Artist" }

        val subscriberText = header?.obj("subscriptionButton")?.obj("subscribeButtonRenderer")?.obj("subscriberCountText")?.array("runs")
            ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: header?.obj("subtitle")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: header?.obj("straplineTextOne")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }

        val descRuns = header?.obj("description")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }

        val bannerThumbs = header?.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.array("thumbnails")
            ?: header?.obj("thumbnail")?.array("thumbnails")
        val avatarThumbs = header?.obj("foregroundThumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.array("thumbnails")
            ?: bannerThumbs

        val artworkUrl = avatarThumbs?.lastOrNull()?.asObject()?.string("url")?.highResolutionArtwork()
        val bannerUrl = bannerThumbs?.lastOrNull()?.asObject()?.string("url")?.highResolutionArtwork()

        val shelves = mutableListOf<JsonObject>()
        collectObjects(root, "musicShelfRenderer", shelves)
        collectObjects(root, "musicCarouselShelfRenderer", shelves)

        var topSongs = emptyList<com.lastwave.app.playback.PlayableTrack>()
        val albums = mutableListOf<com.lastwave.app.data.model.ArtistAlbumItem>()
        val singles = mutableListOf<com.lastwave.app.data.model.ArtistAlbumItem>()
        val similarArtists = mutableListOf<com.lastwave.app.data.model.ArtistSummaryItem>()

        for (shelf in shelves) {
            val heading = shelf.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?: shelf.obj("header")?.obj("musicCarouselShelfBasicHeaderRenderer")?.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?: ""

            when {
                heading.contains("song", ignoreCase = true) || heading.contains("track", ignoreCase = true) -> {
                    if (topSongs.isEmpty()) {
                        val parsed = parseSongRenderers(shelf)
                        topSongs = parsed.map { track ->
                            com.lastwave.app.playback.PlayableTrack(
                                title = track.title,
                                artist = track.artist.takeUnless { it == "Unknown artist" } ?: title,
                                album = track.album,
                                artworkUrl = track.artworkUrl ?: artworkUrl,
                                videoId = track.videoId,
                            )
                        }
                    }
                }
                heading.contains("album", ignoreCase = true) -> {
                    albums.addAll(parseAlbumTwoRowItems(shelf, defaultType = "Album"))
                }
                heading.contains("single", ignoreCase = true) || heading.contains("ep", ignoreCase = true) -> {
                    singles.addAll(parseAlbumTwoRowItems(shelf, defaultType = "Single"))
                }
                heading.contains("similar", ignoreCase = true) || heading.contains("fans", ignoreCase = true) || heading.contains("like", ignoreCase = true) -> {
                    similarArtists.addAll(parseArtistTwoRowItems(shelf))
                }
            }
        }

        // Fallback: If no top songs shelf was explicitly labelled, try parsing songs from whole root
        if (topSongs.isEmpty()) {
            val parsed = parseSongRenderers(root)
            topSongs = parsed.map { track ->
                com.lastwave.app.playback.PlayableTrack(
                    title = track.title,
                    artist = track.artist.takeUnless { it == "Unknown artist" } ?: title,
                    album = track.album,
                    artworkUrl = track.artworkUrl ?: artworkUrl,
                    videoId = track.videoId,
                )
            }
        }

        // Prefetch first few tracks for instant playback
        topSongs.take(3).forEach { it.videoId?.let { id -> prefetchStream(id) } }

        com.lastwave.app.data.model.ArtistPageData(
            name = title,
            browseId = browseId,
            artworkUrl = artworkUrl,
            bannerUrl = bannerUrl,
            subscribers = subscriberText?.takeIf(String::isNotBlank),
            bio = descRuns?.takeIf(String::isNotBlank),
            topSongs = topSongs,
            albums = albums.distinctBy { it.browseId.ifBlank { it.title } },
            singles = singles.distinctBy { it.browseId.ifBlank { it.title } },
            similarArtists = similarArtists.distinctBy { it.browseId.ifBlank { it.name } },
        )
    }

    /** Loads and parses complete album details including ordered tracklist and metadata. */
    suspend fun fetchAlbumPage(browseId: String, albumTitleFallback: String = "", artistFallback: String = ""): com.lastwave.app.data.model.AlbumPageData? = withContext(Dispatchers.IO) {
        if (browseId.isBlank()) return@withContext null
        val config = getWebConfig()
        val root = runCatching {
            post(
                url = "$MUSIC_API/browse?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                    put("browseId", browseId)
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
            )
        }.getOrNull() ?: return@withContext null

        val header = root.obj("header")?.obj("musicDetailHeaderRenderer")
            ?: root.obj("header")?.obj("musicResponsiveHeaderRenderer")
            ?: root.obj("header")?.obj("musicEditablePlaylistDetailHeaderRenderer")?.obj("header")?.obj("musicResponsiveHeaderRenderer")

        val title = header?.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?.ifBlank { null }
            ?: header?.string("title")
            ?: albumTitleFallback.ifBlank { "Album" }

        val subRuns = header?.obj("subtitle")?.array("runs")?.mapNotNull { it.asObject() }.orEmpty()
        val artistRun = subRuns.firstOrNull { it.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("UC") == true }
        val artist = artistRun?.string("text") ?: header?.obj("straplineTextOne")?.array("runs")?.firstOrNull()?.asObject()?.string("text") ?: artistFallback.ifBlank { "Various Artists" }
        val artistBrowseId = artistRun?.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")

        val releaseYear = subRuns.mapNotNull { it.string("text") }.firstOrNull { it.trim().matches(Regex("^(19|20)\\d{2}$")) }

        val secondSubtitleRuns = header?.obj("secondSubtitle")?.array("runs")?.mapNotNull { it.asObject()?.string("text") }.orEmpty()
        val trackCountText = secondSubtitleRuns.firstOrNull { "song" in it.lowercase() || "track" in it.lowercase() }
        val durationText = secondSubtitleRuns.firstOrNull { "min" in it.lowercase() || "hour" in it.lowercase() || "sec" in it.lowercase() }

        val descRuns = header?.obj("description")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }

        val thumbs = header?.obj("thumbnail")?.obj("croppedSquareThumbnailRenderer")?.array("thumbnails")
            ?: header?.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.array("thumbnails")
            ?: header?.obj("thumbnail")?.array("thumbnails")
        val artworkUrl = thumbs?.lastOrNull()?.asObject()?.string("url")?.highResolutionArtwork()

        val parsedSongs = parseSongRenderers(root)
        val tracks = parsedSongs.map { track ->
            com.lastwave.app.playback.PlayableTrack(
                title = track.title,
                artist = track.artist.takeUnless { it == "Unknown artist" } ?: artist,
                album = title,
                artworkUrl = track.artworkUrl ?: artworkUrl,
                videoId = track.videoId,
            )
        }

        // Prefetch first few tracks for instant playback
        tracks.take(3).forEach { it.videoId?.let { id -> prefetchStream(id) } }

        val otherAlbums = mutableListOf<com.lastwave.app.data.model.ArtistAlbumItem>()
        val shelves = mutableListOf<JsonObject>()
        collectObjects(root, "musicCarouselShelfRenderer", shelves)
        for (shelf in shelves) {
            val heading = shelf.obj("header")?.obj("musicCarouselShelfBasicHeaderRenderer")?.obj("title")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() } ?: ""
            if (heading.contains("album", ignoreCase = true) || heading.contains("more by", ignoreCase = true)) {
                otherAlbums.addAll(parseAlbumTwoRowItems(shelf, defaultType = "Album"))
            }
        }

        com.lastwave.app.data.model.AlbumPageData(
            title = title,
            artist = artist,
            artistBrowseId = artistBrowseId,
            browseId = browseId,
            artworkUrl = artworkUrl,
            releaseYear = releaseYear,
            trackCountText = trackCountText ?: "${tracks.size} songs",
            durationText = durationText,
            description = descRuns?.takeIf(String::isNotBlank),
            tracks = tracks,
            otherAlbums = otherAlbums.distinctBy { it.browseId.ifBlank { it.title } },
        )
    }

    private fun parseAlbumTwoRowItems(container: JsonObject, defaultType: String): List<com.lastwave.app.data.model.ArtistAlbumItem> {
        val items = mutableListOf<JsonObject>()
        collectObjects(container, "musicTwoRowItemRenderer", items)
        return items.mapNotNull { item ->
            val title = item.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?: item.obj("title")?.string("simpleText")
                ?: return@mapNotNull null
            val nav = item.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?: item.obj("title")?.array("runs")?.firstOrNull()?.asObject()?.obj("navigationEndpoint")?.obj("browseEndpoint")
            val browseId = nav?.string("browseId") ?: ""
            val subtitleRuns = item.obj("subtitle")?.array("runs")?.mapNotNull { it.asObject()?.string("text") }.orEmpty()
            val year = subtitleRuns.firstOrNull { it.trim().matches(Regex("^(19|20)\\d{2}$")) }
            val type = subtitleRuns.firstOrNull { it.equals("Single", true) || it.equals("EP", true) || it.equals("Album", true) } ?: defaultType
            val thumbs = item.obj("thumbnailRenderer")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.array("thumbnails")
                ?: item.obj("thumbnail")?.array("thumbnails")
            val artworkUrl = thumbs?.lastOrNull()?.asObject()?.string("url")?.highResolutionArtwork()

            com.lastwave.app.data.model.ArtistAlbumItem(
                title = title.trim(),
                browseId = browseId,
                year = year,
                type = type,
                artworkUrl = artworkUrl,
            )
        }
    }

    private fun parseArtistTwoRowItems(container: JsonObject): List<com.lastwave.app.data.model.ArtistSummaryItem> {
        val items = mutableListOf<JsonObject>()
        collectObjects(container, "musicTwoRowItemRenderer", items)
        return items.mapNotNull { item ->
            val title = item.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?: item.obj("title")?.string("simpleText")
                ?: return@mapNotNull null
            val nav = item.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?: item.obj("title")?.array("runs")?.firstOrNull()?.asObject()?.obj("navigationEndpoint")?.obj("browseEndpoint")
            val browseId = nav?.string("browseId") ?: ""
            val subtitle = item.obj("subtitle")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            val thumbs = item.obj("thumbnailRenderer")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.array("thumbnails")
                ?: item.obj("thumbnail")?.array("thumbnails")
            val artworkUrl = thumbs?.lastOrNull()?.asObject()?.string("url")?.highResolutionArtwork()

            com.lastwave.app.data.model.ArtistSummaryItem(
                name = title.trim(),
                browseId = browseId,
                artworkUrl = artworkUrl,
                subtitle = subtitle?.takeIf { it.isNotBlank() },
            )
        }
    }

    /** Loads playable songs for an artist or album without opening YouTube. */
    suspend fun browseSongs(browseId: String, limit: Int = 50): List<YouTubeMusicTrack> = withContext(Dispatchers.IO) {
        require(browseId.isNotBlank()) { "Missing YouTube Music browse id" }
        val config = getWebConfig()
        val root = post(
            url = "$MUSIC_API/browse?key=${config.apiKey}&prettyPrint=false",
            body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                put("browseId", browseId)
            },
            clientName = "WEB_REMIX",
            clientVersion = config.clientVersion,
            userAgent = WEB_USER_AGENT,
        )
        val shelves = mutableListOf<JsonObject>()
        collectObjects(root, "musicShelfRenderer", shelves)
        val primaryShelf = shelves.firstOrNull { shelf ->
            val heading = shelf.obj("title")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            heading.equals("Songs", ignoreCase = true) || heading.equals("Tracks", ignoreCase = true)
        } ?: shelves.firstOrNull()
        val songs = parseSongRenderers(primaryShelf ?: root).take(limit)
        songs.take(2).forEach { prefetchStream(it.videoId) }
        songs
    }

    private suspend fun searchEntities(
        query: String,
        kind: YouTubeMusicEntityKind,
        filter: String,
        limit: Int,
    ): List<YouTubeMusicEntity> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val config = getWebConfig()
        val root = post(
            url = "$MUSIC_API/search?key=${config.apiKey}&prettyPrint=false",
            body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                put("query", query.trim())
                put("params", filter)
            },
            clientName = "WEB_REMIX",
            clientVersion = config.clientVersion,
            userAgent = WEB_USER_AGENT,
        )
        parseEntityRenderers(root, kind).take(limit)
    }

    suspend fun searchPlaylists(query: String, limit: Int = 30): List<YouTubePlaylistSummary> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val config = getWebConfig()
        val root = runCatching {
            post(
                url = "$MUSIC_API/search?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                    put("query", query.trim())
                    put("params", "Eg-KAQwIABAAGAAgACgB")
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
            )
        }.getOrNull() ?: return@withContext emptyList()
        parsePlaylistRenderers(root).take(limit)
    }

    /** Fetches rich metadata (title, artist, album, artwork) for a single YouTube video ID */
    suspend fun fetchSongDetails(videoId: String): YouTubeMusicTrack? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null

        // 1. Try InnerTube /player or /next (returns exact artist, title, album, artwork from YouTube Music)
        try {
            val config = getWebConfig()
            val root = post(
                url = "$MUSIC_API/player?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                    put("videoId", videoId)
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                userAgent = WEB_USER_AGENT,
            )
            val videoDetails = root.obj("videoDetails")
            var title = videoDetails?.string("title")
            var artist = videoDetails?.string("author")
            val thumbs = videoDetails?.obj("thumbnail")?.array("thumbnails")
            val artworkUrl = thumbs?.lastOrNull()?.let { (it as? JsonObject)?.string("url") }

            if (!title.isNullOrBlank() && !artist.isNullOrBlank()) {
                if (artist.endsWith(" - Topic")) {
                    artist = artist.removeSuffix(" - Topic").trim()
                }
                if (title.contains(" - ")) {
                    val parts = title.split(" - ", limit = 2)
                    if (artist.isBlank() || artist == "YouTube Music" || artist.equals(parts[0].trim(), ignoreCase = true)) {
                        artist = parts[0].trim()
                        title = parts[1].trim()
                    }
                }
                return@withContext YouTubeMusicTrack(
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    artworkUrl = artworkUrl ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                )
            }
        } catch (_: Exception) {}

        // 2. Fallback: YouTube oEmbed
        try {
            val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            val request = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            val resp = http.newCall(request).execute()
            val jsonStr = resp.use { it.body?.string().orEmpty() }
            if (jsonStr.isNotBlank()) {
                val obj = json.parseToJsonElement(jsonStr).jsonObject
                val rawTitle = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val author = obj["author_name"]?.jsonPrimitive?.contentOrNull.orEmpty().removeSuffix(" - Topic").trim()
                val thumbnail = obj["thumbnail_url"]?.jsonPrimitive?.contentOrNull ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                var finalTitle = rawTitle
                var finalArtist = author.ifBlank { "YouTube Music" }
                if (rawTitle.contains(" - ")) {
                    val split = rawTitle.split(" - ", limit = 2)
                    finalArtist = split[0].trim()
                    finalTitle = split[1].trim()
                }

                if (finalTitle.isNotBlank()) {
                    return@withContext YouTubeMusicTrack(
                        videoId = videoId,
                        title = finalTitle,
                        artist = finalArtist,
                        artworkUrl = thumbnail,
                    )
                }
            }
        } catch (_: Exception) {}

        null
    }

    /** Resolves a fresh, expiring googlevideo URL immediately before use with PO Token support and in-flight deduplication. */
    suspend fun resolveAudioStream(videoId: String): YouTubeAudioStream = withContext(Dispatchers.IO) {
        require(videoId.isNotBlank()) { "Missing YouTube Music video id" }
        val now = System.currentTimeMillis()

        // 1. In-memory cache hit (0ms)
        streamCache[videoId]?.let { (cachedAt, stream) ->
            if (now - cachedAt < 4 * 60 * 60 * 1000L) {
                return@withContext stream
            }
        }

        // 2. In-flight request deduplication
        val deferred = activeStreamRequests.computeIfAbsent(videoId) { id ->
            apiScope.async {
                resolveAudioStreamInternal(id)
            }
        }

        try {
            deferred.await()
        } finally {
            activeStreamRequests.remove(videoId)
        }
    }

    /** Resolves stream specifically optimized for download compatibility (M4A AAC container). */
    suspend fun resolveDownloadStream(videoId: String): YouTubeAudioStream = withContext(Dispatchers.IO) {
        require(videoId.isNotBlank()) { "Missing YouTube Music video id" }
        runCatching {
            streamExtractor.resolveAudioStream(videoId, preferM4a = true)
        }.getOrNull() ?: resolveAudioStream(videoId)
    }

    private suspend fun resolveAudioStreamInternal(videoId: String): YouTubeAudioStream = kotlinx.coroutines.coroutineScope {
        val now = System.currentTimeMillis()
        
        // Fast non-blocking PO Token lookup (0-50ms if cached, background mint if not)
        val poToken = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(50L) {
                BotGuardTokenGenerator.mintToken(videoId)
            }
        }.getOrNull()?.playerToken ?: run {
            apiScope.launch { runCatching { BotGuardTokenGenerator.mintToken(videoId) } }
            null
        }

        val channel = kotlinx.coroutines.channels.Channel<YouTubeAudioStream>(2)
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        val confirmedUnavailableReasons = ConcurrentHashMap.newKeySet<String>()

        // 1. Primary: High-speed NewPipe Extractor (direct audio format with JS signature deciphering)
        jobs += launch(Dispatchers.IO) {
            runCatching {
                val npStream = streamExtractor.resolveAudioStream(videoId)
                val finalUrl = if (!poToken.isNullOrBlank() && !npStream.url.contains("&pot=")) {
                    if (npStream.url.contains("?")) "${npStream.url}&pot=$poToken" else "${npStream.url}?pot=$poToken"
                } else npStream.url
                channel.trySend(npStream.copy(url = finalUrl))
            }.onFailure { error ->
                error.confirmedUnavailableReasonOrNull()?.let(confirmedUnavailableReasons::add)
            }
        }

        // 2. Parallel Racer: Direct InnerTube Client
        jobs += launch(Dispatchers.IO) {
            val client = PLAYER_CLIENTS.firstOrNull { candidate ->
                val blockedUntil = failedClientsUntil[candidate.name] ?: 0L
                now >= blockedUntil
            } ?: return@launch

            var delivered = false
            val outcome = runCatching {
                val body = buildJsonObject {
                    put("context", buildJsonObject {
                        put("client", buildJsonObject {
                            put("clientName", client.name)
                            put("clientVersion", client.version)
                            put("hl", "en")
                            put("gl", "US")
                            if (!client.osVersion.isNullOrBlank()) put("osVersion", client.osVersion)
                        })
                        if (!poToken.isNullOrBlank()) {
                            put("serviceIntegrityDimensions", buildJsonObject {
                                put("poToken", poToken)
                            })
                        }
                    })
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                    put("playbackContext", buildJsonObject {
                        put("contentPlaybackContext", buildJsonObject {
                            put("signatureTimestamp", 19940)
                        })
                    })
                }
                val root = post(
                    url = "$YOUTUBE_API/player?key=${client.apiKey}&prettyPrint=false",
                    body = body,
                    clientName = client.name,
                    clientVersion = client.version,
                    userAgent = client.userAgent,
                )
                val status = root.obj("playabilityStatus")
                val state = status?.string("status")
                if (state == "OK") {
                    val streaming = root.obj("streamingData")
                    val candidates = buildList {
                        addAll(streaming?.array("adaptiveFormats").orEmpty())
                        addAll(streaming?.array("formats").orEmpty())
                    }.mapNotNull { it as? JsonObject }
                        .mapNotNull { format ->
                            val url = format.string("url") ?: return@mapNotNull null
                            val mime = format.string("mimeType")
                            if (mime?.startsWith("audio/") != true) return@mapNotNull null
                            val finalUrl = if (!poToken.isNullOrBlank() && !url.contains("&pot=")) {
                                if (url.contains("?")) "$url&pot=$poToken" else "$url?pot=$poToken"
                            } else url
                            YouTubeAudioStream(
                                url = finalUrl,
                                mimeType = mime.substringBefore(';'),
                                bitrate = format.int("bitrate") ?: 0,
                            )
                        }
                    val bestStream = candidates.maxByOrNull { it.bitrate }
                    if (bestStream != null) {
                        delivered = channel.trySend(bestStream).isSuccess
                    }
                } else if (state != null) {
                    val reason = status?.string("reason").orEmpty()
                    val confirmedReason = when {
                        state in PERMANENT_PLAYABILITY_STATES -> reason.ifBlank { state }
                        else -> IOException(reason).confirmedUnavailableReasonOrNull()
                    }
                    confirmedReason?.let(confirmedUnavailableReasons::add)
                }
            }
            // Circuit breaker: a client that threw or produced nothing sits
            // out for a cooldown so the next resolution falls through to the
            // following client instead of retrying a dead one every time.
            if (!delivered || outcome.isFailure) {
                failedClientsUntil[client.name] = System.currentTimeMillis() + CLIENT_COOLDOWN_MS
            }
        }

        try {
            // Bounded wait — both racers can fail silently (offline, bot-wall,
            // extractor breakage); an unbounded receive() would hang stream
            // resolution (and therefore playback) forever.
            val winner = kotlinx.coroutines.withTimeoutOrNull(STREAM_RACE_TIMEOUT_MS) { channel.receive() }
            if (winner != null) {
                pruneStreamCache()
                streamCache[videoId] = Pair(now, winner)
                winner
            } else {
                val confirmedReason = confirmedUnavailableReasons.firstOrNull()
                if (confirmedReason != null) {
                    throw ConfirmedUnplayableMediaException(confirmedReason)
                }
                throw IOException("Timed out resolving an audio stream for $videoId")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is ConfirmedUnplayableMediaException) throw e
            // Last-resort direct NewPipe extraction — bounded as well so a
            // stalled socket can never wedge the player's loader thread.
            val npStream = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(FALLBACK_EXTRACT_TIMEOUT_MS) {
                    streamExtractor.resolveAudioStream(videoId)
                } ?: throw IOException("Timed out during fallback extraction for $videoId")
            }.getOrElse { fallbackFailure ->
                fallbackFailure.confirmedUnavailableReasonOrNull()
                    ?.let(confirmedUnavailableReasons::add)
                val confirmedReason = confirmedUnavailableReasons.firstOrNull()
                if (confirmedReason != null) {
                    throw ConfirmedUnplayableMediaException(confirmedReason, fallbackFailure)
                }
                throw IOException("Unable to resolve audio stream for $videoId", fallbackFailure)
            }
            val result = npStream.copy(
                url = if (!poToken.isNullOrBlank() && !npStream.url.contains("&pot=")) {
                    if (npStream.url.contains("?")) "${npStream.url}&pot=$poToken" else "${npStream.url}?pot=$poToken"
                } else npStream.url,
            )
            pruneStreamCache()
            streamCache[videoId] = Pair(now, result)
            result
        } finally {
            runCatching { channel.close() }
            jobs.forEach { runCatching { it.cancel() } }
        }
    }

    private fun Throwable.confirmedUnavailableReasonOrNull(): String? {
        val causes = generateSequence(this) { it.cause }.take(10).toList()
        val diagnostic = causes.joinToString(" ") {
            "${it::class.java.simpleName} ${it.message.orEmpty()}"
        }.lowercase()
        val confirmedMarkers = listOf(
            "agerestricted", "age restricted", "confirm your age",
            "geographicrestriction", "not available in your country",
            "contentnotavailable", "video is unavailable", "video unavailable",
            "privatecontent", "this video is private", "paidcontent",
            "members-only", "login required", "sign in to watch",
        )
        return if (confirmedMarkers.any(diagnostic::contains)) {
            causes.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) }
                ?: "Media is unavailable"
        } else {
            null
        }
    }

    /** Keeps the stream cache from growing without bound over long sessions:
     *  drops expired entries first, then trims the oldest inserts. */
    private fun pruneStreamCache() {
        if (streamCache.size <= MAX_STREAM_CACHE_ENTRIES) return
        val now = System.currentTimeMillis()
        streamCache.entries.removeIf { now - it.value.first >= STREAM_TTL_MS }
        if (streamCache.size > MAX_STREAM_CACHE_ENTRIES) {
            streamCache.entries
                .sortedBy { it.value.first }
                .take(streamCache.size - MAX_STREAM_CACHE_ENTRIES)
                .forEach { streamCache.remove(it.key) }
        }
    }

    suspend fun findBestMatch(title: String, artist: String): YouTubeMusicTrack {
        val cacheKey = "${normalize(artist)}|${normalize(title)}"
        matchCache[cacheKey]?.let { return it }
        val results = searchSongs(listOf(title, artist).filter { it.isNotBlank() }.joinToString(" "), 30)
        val best = results.maxByOrNull { candidate -> matchScore(candidate, title, artist) }
            ?: throw ConfirmedUnplayableMediaException("No YouTube Music match found for $title")
        val titleSimilarity = maxOf(
            similarity(best.title, title),
            similarity(baseTitle(best.title), baseTitle(title)),
        )
        val artistSimilarity = similarity(best.artist, artist)
        if (titleSimilarity < 72 || (artist.isNotBlank() && artistSimilarity < 50)) {
            throw ConfirmedUnplayableMediaException("No reliable YouTube Music match found for $title by $artist")
        }
        return best.also {
            if (matchCache.size > MAX_MATCH_CACHE_ENTRIES) matchCache.clear()
            matchCache[cacheKey] = it
        }
    }

    suspend fun findBestMatchOrNull(title: String, artist: String): YouTubeMusicTrack? =
        try {
            kotlinx.coroutines.withTimeoutOrNull(2500L) {
                findBestMatch(title, artist)
            }
        } catch (_: Exception) {
            null
        }

    suspend fun isPlayable(title: String, artist: String): Boolean =
        findBestMatchOrNull(title, artist) != null

    private fun getWebConfig(): WebConfig {
        webConfig?.let { return it }
        val initial = WebConfig(FALLBACK_WEB_KEY, FALLBACK_WEB_VERSION, null)
        webConfig = initial
        return initial
    }

    private fun findConfig(html: String, key: String): String? {
        if (html.isBlank()) return null
        val escaped = Regex("\\\"$key\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(html)?.groupValues?.getOrNull(1)
        return escaped?.replace("\\u003d", "=")?.replace("\\/", "/")
    }

    private fun post(
        url: String,
        body: JsonObject,
        clientName: String,
        clientVersion: String,
        userAgent: String,
        authenticated: Boolean = false,
    ): JsonObject {
        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("X-Goog-Api-Format-Version", "1")
            .header("X-YouTube-Client-Name", CLIENT_IDS[clientName] ?: clientName)
            .header("X-YouTube-Client-Version", clientVersion)

        // Account-authenticated surface: cookies + per-request SAPISIDHASH.
        // Only applied when explicitly requested AND a connection exists —
        // anonymous endpoints must stay cookie-free so playback never
        // depends on login state.
        if (authenticated) {
            ytAuth.cookieHeaderValue()?.let { builder.header("Cookie", it) }
            ytAuth.authorizationHeaderValue()?.let { builder.header("Authorization", it) }
        }

        val request = builder
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        var lastException: Exception? = null
        for (attempt in 1..2) {
            try {
                return http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        if (response.code == 400 || response.code == 403 || response.code == 429) {
                            webConfig = null
                        }
                        throw IOException("InnerTube HTTP ${response.code}: ${text.take(180)}")
                    }
                    // A non-JSON body (HTML interstitial / error page) used to
                    // escape the retry loop entirely — treat it like any
                    // other transient failure and retry once.
                    json.parseToJsonElement(text).jsonObject
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                if (attempt < 2) {
                    try { Thread.sleep(200L) } catch (_: InterruptedException) {}
                }
            }
        }
        throw (lastException as? IOException) ?: IOException("InnerTube call failed: ${lastException}")
    }

    private fun context(name: String, version: String, visitorData: String?, osVersion: String? = null): JsonObject =
        buildJsonObject {
            put("client", buildJsonObject {
                put("clientName", name)
                put("clientVersion", version)
                put("hl", "en")
                put("gl", "US")
                if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
                if (!osVersion.isNullOrBlank()) put("osVersion", osVersion)
            })
        }

    private fun parseSongRenderers(root: JsonElement): List<YouTubeMusicTrack> {
        val renderers = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveListItemRenderer", renderers)
        val songs = renderers.mapNotNull(::parseSong).toMutableList()
        if (songs.isEmpty()) {
            val ytVideos = mutableListOf<JsonObject>()
            collectObjects(root, "playlistVideoRenderer", ytVideos)
            songs.addAll(ytVideos.mapNotNull(::parsePlaylistVideoRenderer))
        }
        return songs.distinctBy { it.videoId }
    }

    /** Home carousels use compact two-row cards. Only cards whose own
     * navigation is a direct watch endpoint are songs; album, artist and
     * playlist cards are deliberately ignored. */
    private fun parseHomeFeedSongs(root: JsonElement): List<YouTubeMusicTrack> {
        val rows = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveListItemRenderer", rows)
        val songs = rows.filter { row ->
            directWatchVideoId(row) != null
        }.mapNotNull(::parseSong).toMutableList()
        val renderers = mutableListOf<JsonObject>()
        collectObjects(root, "musicTwoRowItemRenderer", renderers)
        songs += renderers.mapNotNull(::parseTwoRowSong)
        return songs.distinctBy { it.videoId }
    }

    private fun directWatchVideoId(renderer: JsonObject): String? =
        renderer.obj("playlistItemData")?.string("videoId")
            ?: renderer.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: renderer.obj("thumbnailOverlay")
                ?.obj("musicItemThumbnailOverlayRenderer")
                ?.obj("content")?.obj("musicPlayButtonRenderer")
                ?.obj("playNavigationEndpoint")?.obj("watchEndpoint")?.string("videoId")

    private fun parseTwoRowSong(renderer: JsonObject): YouTubeMusicTrack? {
        val titleRuns = renderer.obj("title")?.array("runs")
        val videoId = directWatchVideoId(renderer)
            ?: titleRuns?.firstOrNull()?.asObject()
                ?.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: return null
        val title = titleRuns?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?.trim()?.takeIf(String::isNotBlank)
            ?: renderer.obj("title")?.string("simpleText")?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val details = renderer.obj("subtitle")?.array("runs")
            ?.mapNotNull { it.asObject() }.orEmpty()
        val artist = details.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?.string("browseId")?.startsWith("UC") == true
        }?.string("text") ?: details.mapNotNull { it.string("text") }
            .firstOrNull { it.isLikelyArtistDetail() }
            ?: return null
        val album = details.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?.string("browseId")?.startsWith("MPRE") == true
        }?.string("text")
        val duration = details.mapNotNull { it.string("text") }.firstNotNullOfOrNull(::parseDuration)
        val thumbnails = renderer.obj("thumbnailRenderer")?.obj("musicThumbnailRenderer")
            ?.obj("thumbnail")?.array("thumbnails")
            ?: renderer.obj("thumbnail")?.obj("musicThumbnailRenderer")
                ?.obj("thumbnail")?.array("thumbnails")
        val artwork = thumbnails?.lastOrNull()?.asObject()?.string("url")?.let {
            if (it.startsWith("//")) "https:$it" else it
        }?.highResolutionArtwork()
        return YouTubeMusicTrack(videoId, title, artist, album, artwork, duration)
    }

    private fun parsePlaylistVideoRenderer(renderer: JsonObject): YouTubeMusicTrack? {
        val videoId = renderer.string("videoId") ?: return null
        val title = renderer.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?: renderer.obj("title")?.string("simpleText")
            ?: return null
        val artist = renderer.obj("shortBylineText")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: "Unknown artist"
        val duration = renderer.string("lengthSeconds")?.toIntOrNull()
        val thumbnails = renderer.obj("thumbnail")?.array("thumbnails")
        val artwork = thumbnails?.lastOrNull()?.asObject()?.string("url")?.highResolutionArtwork()
        return YouTubeMusicTrack(videoId, title, artist, null, artwork, duration)
    }

    private fun parseSong(renderer: JsonObject): YouTubeMusicTrack? {
        val videoId = directWatchVideoId(renderer)
            ?: findString(renderer, "videoId")
            ?: return null
        val columns = renderer.array("flexColumns")
        val titleRuns = columns?.getOrNull(0)?.asObject()
            ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
        val title = titleRuns?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val detailRuns = columns?.getOrNull(1)?.asObject()
            ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
            ?.mapNotNull { it.asObject() }.orEmpty()
        val artist = detailRuns.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("UC") == true
        }?.string("text") ?: detailRuns.mapNotNull { it.string("text") }
            .firstOrNull { it.isUsefulDetail() && parseDuration(it) == null }
            ?: "Unknown artist"
        val album = detailRuns.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("MPRE") == true
        }?.string("text")
        val duration = detailRuns.mapNotNull { it.string("text") }.firstNotNullOfOrNull(::parseDuration)
        val thumbnails = renderer.obj("thumbnail")?.obj("musicThumbnailRenderer")
            ?.obj("thumbnail")?.array("thumbnails")
        val artwork = thumbnails?.lastOrNull()?.asObject()?.string("url")?.let {
            if (it.startsWith("//")) "https:$it" else it
        }?.highResolutionArtwork()
        return YouTubeMusicTrack(videoId, title, artist, album, artwork, duration)
    }

    private fun parseEntityRenderers(root: JsonElement, kind: YouTubeMusicEntityKind): List<YouTubeMusicEntity> {
        val renderers = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveListItemRenderer", renderers)
        return renderers.mapNotNull { renderer -> parseEntity(renderer, kind) }
            .distinctBy { it.browseId }
    }

    private fun parseEntity(renderer: JsonObject, kind: YouTubeMusicEntityKind): YouTubeMusicEntity? {
        val navigation = renderer.obj("navigationEndpoint")?.obj("browseEndpoint") ?: return null
        val browseId = navigation.string("browseId") ?: return null
        if (kind == YouTubeMusicEntityKind.ARTIST && !browseId.startsWith("UC")) return null
        if (kind == YouTubeMusicEntityKind.ALBUM && !browseId.startsWith("MPRE")) return null

        val columns = renderer.array("flexColumns")
        val name = columns?.getOrNull(0)?.asObject()
            ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
            ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?.trim()?.takeIf(String::isNotBlank) ?: return null
        val details = columns?.getOrNull(1)?.asObject()
            ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
            ?.mapNotNull { it.asObject() }.orEmpty()
        val artist = if (kind == YouTubeMusicEntityKind.ALBUM) {
            details.firstOrNull { run ->
                run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("UC") == true
            }?.string("text")
        } else null
        val subtitle = details.mapNotNull { it.string("text")?.trim() }
            .filter { it.isNotBlank() && it !in setOf("•", "·", "Artist", "Album", "EP", "Single") }
            .joinToString(" · ")
            .trim()
            .takeIf(String::isNotBlank)
        val thumbnails = renderer.obj("thumbnail")?.obj("musicThumbnailRenderer")
            ?.obj("thumbnail")?.array("thumbnails")
        val artwork = thumbnails?.lastOrNull()?.asObject()?.string("url")?.let {
            (if (it.startsWith("//")) "https:$it" else it).highResolutionArtwork()
        }
        return YouTubeMusicEntity(
            kind = kind,
            name = name,
            artist = artist,
            subtitle = subtitle,
            browseId = browseId,
            playlistId = findString(renderer, "playlistId"),
            artworkUrl = artwork,
        )
    }

    private fun parsePlaylistRenderers(root: JsonElement): List<YouTubePlaylistSummary> {
        val renderers = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveListItemRenderer", renderers)
        collectObjects(root, "musicTwoRowItemRenderer", renderers)
        return renderers.mapNotNull { renderer ->
            val nav = renderer.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?: renderer.obj("title")?.array("runs")?.firstOrNull()?.asObject()?.obj("navigationEndpoint")?.obj("browseEndpoint")
            val browseId = nav?.string("browseId") ?: return@mapNotNull null
            val playlistId = if (browseId.startsWith("VL")) browseId.removePrefix("VL") else browseId
            if (!browseId.startsWith("VL") && !browseId.startsWith("PL") && !browseId.startsWith("RDCLAK")) return@mapNotNull null

            val title = renderer.array("flexColumns")?.getOrNull(0)?.asObject()
                ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?: renderer.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?: return@mapNotNull null

            val subtitleRuns = renderer.array("flexColumns")?.getOrNull(1)?.asObject()
                ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
                ?: renderer.obj("subtitle")?.array("runs")
            val author = subtitleRuns?.firstOrNull()?.asObject()?.string("text")

            val trackCountText = subtitleRuns?.mapNotNull { it.asObject()?.string("text") }?.lastOrNull { "song" in it.lowercase() || "track" in it.lowercase() }

            val thumbs = renderer.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.array("thumbnails")
                ?: renderer.obj("thumbnailRenderer")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.array("thumbnails")
            val artwork = thumbs?.lastOrNull()?.asObject()?.string("url")?.highResolutionArtwork()

            YouTubePlaylistSummary(
                id = playlistId,
                title = title.trim(),
                author = author?.trim(),
                trackCountText = trackCountText,
                artworkUrl = artwork,
            )
        }.distinctBy { it.id }
    }

    private fun collectObjects(element: JsonElement, key: String, output: MutableList<JsonObject>) {
        when (element) {
            is JsonObject -> element.forEach { (name, child) ->
                if (name == key && child is JsonObject) output += child
                collectObjects(child, key, output)
            }
            is JsonArray -> element.forEach { collectObjects(it, key, output) }
            else -> Unit
        }
    }

    private fun findString(element: JsonElement, key: String): String? = when (element) {
        is JsonObject -> {
            (element[key] as? JsonPrimitive)?.contentOrNull
                ?: element.values.firstNotNullOfOrNull { findString(it, key) }
        }
        is JsonArray -> element.firstNotNullOfOrNull { findString(it, key) }
        else -> null
    }

    private fun String.isUsefulDetail(): Boolean =
        trim().isNotBlank() && trim() !in setOf("•", "·", "Song", "Video")

    private fun String.isLikelyArtistDetail(): Boolean {
        val value = trim()
        if (!value.isUsefulDetail()) return false
        if (value.equals("Album", true) || value.equals("Single", true) ||
            value.equals("EP", true) || value.equals("Playlist", true)
        ) return false
        if (parseDuration(value) != null || value.matches(Regex("^(19|20)\\d{2}$"))) return false
        if (value.contains(" view", ignoreCase = true) || value.contains(" song", ignoreCase = true)) return false
        return true
    }

    private fun parseDuration(value: String): Int? {
        val parts = value.trim().split(':').mapNotNull(String::toIntOrNull)
        if (parts.size !in 2..3) return null
        return parts.fold(0) { total, part -> total * 60 + part }
    }

    private fun similarity(a: String, b: String): Int {
        val normA = normalize(a)
        val normB = normalize(b)
        if (normA == normB) return 100
        if (normA.isNotBlank() && normB.isNotBlank()) {
            if (normA.contains(normB) || normB.contains(normA)) {
                val ratio = (minOf(normA.length, normB.length) * 100) / maxOf(normA.length, normB.length)
                if (ratio >= 45) return maxOf(85, ratio)
            }
        }
        val left = tokens(a)
        val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) return 0
        val common = left.intersect(right).size
        val dice = (200 * common) / (left.size + right.size)
        val subset = if (common == minOf(left.size, right.size) && common > 0) 80 else 0
        return maxOf(dice, subset)
    }

    private fun matchScore(candidate: YouTubeMusicTrack, title: String, artist: String): Int {
        val wantedTitle = normalize(title)
        val wantedArtist = normalize(artist)
        val candidateTitle = normalize(candidate.title)
        val candidateArtist = normalize(candidate.artist)
        var score = maxOf(
            similarity(candidate.title, title),
            similarity(baseTitle(candidate.title), baseTitle(title)),
        ) * 5 + similarity(candidate.artist, artist) * 3
        if (candidateTitle == wantedTitle) score += 600
        if (wantedArtist.isNotBlank() && candidateArtist == wantedArtist) score += 350
        val wantedVariants = tokens(title).intersect(VARIANT_WORDS)
        val unexpectedVariants = tokens(candidate.title).intersect(VARIANT_WORDS) - wantedVariants
        score -= unexpectedVariants.size * 250
        return score
    }

    private fun tokens(value: String): Set<String> = normalize(value)
        .split(' ')
        .filter { it.isNotBlank() && it !in MATCH_NOISE_WORDS }
        .toSet()

    private fun baseTitle(value: String): String = value
        .replace(FEATURING_CLAUSE, " ")
        .replace(VERSION_CLAUSE, " ")

    private fun String.highResolutionArtwork(): String = when {
        (contains("googleusercontent.com") || contains("ggpht.com")) && '=' in this ->
            substringBeforeLast('=') + "=w1200-h1200-l90-rj"
        else -> this
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .replace(NON_WORD, " ")
        .trim()
        .replace(MULTI_SPACE, " ")

    private data class WebConfig(val apiKey: String, val clientVersion: String, val visitorData: String?)
    private data class PlayerClient(
        val name: String,
        val version: String,
        val apiKey: String,
        val userAgent: String,
        val osVersion: String? = null,
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val NON_WORD = Regex("[^a-z0-9]+")
        val DIACRITICS = Regex("\\p{M}+")
        val MULTI_SPACE = Regex("\\s+")
        val VARIANT_WORDS = setOf(
            "live", "remix", "karaoke", "cover", "instrumental", "slowed", "sped", "nightcore",
            "acoustic", "demo", "edit", "remaster", "remastered", "mono", "stereo",
        )
        val MATCH_NOISE_WORDS = setOf("official", "audio", "video", "visualizer", "lyrics", "lyric")
        val FEATURING_CLAUSE = Regex("(?i)[(\\[]\\s*(feat(?:uring)?|ft)\\.?\\s+.*?[)\\]]")
        val VERSION_CLAUSE = Regex("(?i)[(\\[][^)\\]]*(live|remix|acoustic|demo|edit|remaster(?:ed)?|mono|stereo)[^)\\]]*[)\\]]")
        val CLIENT_IDS = mapOf(
            "WEB_REMIX" to "67",
            "IOS" to "5",
            "IOS_MUSIC" to "26",
            "ANDROID" to "3",
            "ANDROID_VR" to "28",
            "TVHTML5" to "85",
        )
        const val MUSIC_API = "https://music.youtube.com/youtubei/v1"
        const val YOUTUBE_API = "https://www.youtube.com/youtubei/v1"
        const val LIBRARY_PLAYLISTS_BROWSE_ID = "FEmusic_liked_playlists"
        const val YT_HISTORY_BROWSE_ID = "FEmusic_history"
        const val YT_LIKED_BROWSE_ID = "VLLM"
        const val YT_HOME_BROWSE_ID = "FEmusic_home"
        const val MAX_CONTINUATION_PAGES = 600
        const val WRITE_ACTIONS_PER_REQUEST = 50

        /** Upper bound on how long both stream-resolution racers combined may
         *  take before falling back to direct extraction. */
        const val STREAM_RACE_TIMEOUT_MS = 15_000L

        /** Upper bound for the last-resort direct NewPipe extraction. */
        const val FALLBACK_EXTRACT_TIMEOUT_MS = 12_000L
        val PERMANENT_PLAYABILITY_STATES = setOf(
            "UNPLAYABLE",
            "LOGIN_REQUIRED",
            "AGE_CHECK_REQUIRED",
            "CONTENT_CHECK_REQUIRED",
            "LIVE_STREAM_OFFLINE",
        )

        /** How long a failed player client is skipped by the racer. */
        const val CLIENT_COOLDOWN_MS = 60_000L

        /** Hard cap so long sessions can't grow the caches without bound. */
        const val MAX_STREAM_CACHE_ENTRIES = 64
        const val STREAM_TTL_MS = 4 * 60 * 60 * 1000L
        const val MAX_MATCH_CACHE_ENTRIES = 1024
        const val WEB_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        const val FALLBACK_WEB_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
        const val FALLBACK_WEB_VERSION = "1.20240715.00.00"
        const val ARTIST_SEARCH_FILTER = "EgWKAQIgAWoKEAkQBRAKEAMQBA=="
        const val ALBUM_SEARCH_FILTER = "EgWKAQIYAWoKEAkQBRAKEAMQBA=="
        val PLAYER_CLIENTS = listOf(
            PlayerClient(
                name = "ANDROID_VR",
                version = "1.65.10",
                apiKey = "AIzaSyD-p045F_WzU-vA_YgX20SCx4KAo",
                userAgent = "Mozilla/5.0 (Linux; Android 12; Quest 2) AppleWebKit/537.36 (KHTML, like Gecko) OculusBrowser/23.1.0.3.38.384668277 SamsungBrowser/4.0 Chrome/104.0.5112.114 Mobile VR Safari/537.36",
            ),
            PlayerClient(
                name = "TVHTML5",
                version = "7.20240715.00.00",
                apiKey = "AIzaSyAo_F83w5AmL_YgX20SCx4KAo",
                userAgent = "Mozilla/5.0 (ChromiumStylePlatform; Linux; Android 14) Cobalt/24.lts.4-gold (unlike Gecko) Chrome/124.0.0.0 Safari/537.36",
            ),
            PlayerClient(
                name = "IOS_MUSIC",
                version = "6.42.1",
                apiKey = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
                userAgent = "com.google.ios.youtubemusic/6.42.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)",
                osVersion = "17.5.1.21F90",
            ),
            PlayerClient(
                name = "IOS",
                version = "19.29.1",
                apiKey = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
                userAgent = "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)",
                osVersion = "17.5.1.21F90",
            ),
            PlayerClient(
                name = "ANDROID",
                version = "19.13.36",
                apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
                userAgent = "com.google.android.youtube/19.13.36 (Linux; U; Android 14) gzip",
                osVersion = "14",
            ),
            PlayerClient(
                name = "WEB_REMIX",
                version = "1.20240715.00.00",
                apiKey = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30",
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0",
            ),
        )
    }
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()
private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
