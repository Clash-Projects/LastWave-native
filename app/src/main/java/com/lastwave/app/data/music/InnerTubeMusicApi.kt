package com.lastwave.app.data.music

import android.net.Uri
import com.lastwave.app.data.music.potoken.BotGuardTokenGenerator
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
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
    val videoId: String,
    val url: String,
    val itag: Int?,
    val mimeType: String?,
    val codec: String?,
    val bitrate: Int,
    val sampleRateHz: Int?,
    val durationMs: Long?,
    val contentLength: Long?,
    val isAdaptive: Boolean,
    val clientProfile: String,
    val authScope: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val expiresAtEpochMs: Long? = null,
) {
    val mediaCacheKey: String
        get() = "youtube:$videoId:$clientProfile:${itag ?: -1}:$authScope:${expiresAtEpochMs ?: 0L}"
}

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
 * web/mobile clients. Search uses WEB_REMIX; playback delegates first to
 * InnerTubeX and keeps the older direct/NewPipe extractors as bounded fallbacks.
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
    private val innerTubeXExtractor: InnerTubeXStreamExtractor,
    private val ytAuth: YtMusicAuthManager,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val configMutex = Mutex()
    private val matchCache = ConcurrentHashMap<String, YouTubeMusicTrack>()
    private val streamCache = ConcurrentHashMap<StreamCacheKey, CachedStream>()
    private val activeStreamRequests = ConcurrentHashMap<String, SharedStreamRequest>()
    private val apiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val failedClientsUntil = ConcurrentHashMap<String, Long>()
    private val lastResolvedStreams = ConcurrentHashMap<String, YouTubeAudioStream>()
    @Volatile private var lastSuccessfulClientName: String? = null
    @Volatile private var webConfig: WebConfig? = null

    fun invalidateCache(videoId: String) {
        streamCache.keys.removeIf { it.videoId == videoId }
        lastResolvedStreams.entries.removeIf { it.value.videoId == videoId }
        activeStreamRequests.entries.removeIf { (key, request) ->
            if (!key.startsWith("$videoId|")) return@removeIf false
            request.deferred.cancel()
            true
        }
        matchCache.values.removeIf { it.videoId == videoId }
        streamExtractor.invalidateCache(videoId)
    }

    /** Marks the exact extractor/client that produced a rejected URL, then
     * clears its state so the next attempt cannot pick the same stale result. */
    fun reportPlaybackFailure(videoId: String, rejected: YouTubeAudioStream? = null) {
        val stream = rejected ?: lastResolvedStreams[resolutionKey(videoId, playbackAuthScope())]
        if (stream != null && stream.clientProfile != NEWPIPE_SOURCE) {
            failedClientsUntil[clientFailureKey(videoId, stream.clientProfile, stream.authScope)] =
                System.currentTimeMillis() + CLIENT_COOLDOWN_MS
            if (stream.clientProfile.startsWith("INNERTUBEX:")) {
                innerTubeXExtractor.reportPlaybackFailure(videoId, stream.authScope, stream.clientProfile)
            }
        }
        streamCache.keys.removeIf { key ->
            key.videoId == videoId && (stream == null || key.matches(stream))
        }
        lastResolvedStreams.entries.removeIf { it.value.videoId == videoId }
        activeStreamRequests.entries.removeIf { (key, request) ->
            if (!key.startsWith("$videoId|")) return@removeIf false
            request.deferred.cancel()
            true
        }
        if (stream == null || stream.clientProfile == NEWPIPE_SOURCE) {
            streamExtractor.invalidatePlayerState(videoId)
        } else {
            streamExtractor.invalidateCache(videoId)
        }
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
     * [maxTracks] keeps preview/radio surfaces bounded; imports leave it null
     * and retain the exhaustive continuation behavior above.
     */
    suspend fun fetchPlaylist(
        playlistIdOrUrl: String,
        maxTracks: Int? = null,
        onPageLoaded: ((List<YouTubeMusicTrack>) -> Unit)? = null,
    ): YouTubePlaylistResult? = withContext(Dispatchers.IO) {
        val rawId = extractPlaylistId(playlistIdOrUrl)
        if (rawId.isBlank()) return@withContext null
        val browseId = when {
            rawId.startsWith("VL") || rawId.startsWith("RDCLAK") || rawId.startsWith("FE") || rawId.startsWith("MPRE") || rawId.startsWith("UC") -> rawId
            else -> "VL$rawId"
        }

        val (root, authenticatedAs) = fetchPlaylistRoot(browseId) ?: return@withContext null
        val header = playlistHeader(root)

        val title = extractTitleFromHeader(header, root)

        val author = header?.obj("subtitle")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: header?.obj("straplineTextOne")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: findFirstAuthor(header)

        val artworkUrl = extractArtworkFromHeader(header, root)

        val trackLimit = maxTracks?.coerceAtLeast(1)
        val songs = mutableListOf<YouTubeMusicTrack>()
        val initialSongs = parseSongRenderers(root).let { parsed ->
            trackLimit?.let { parsed.take(it) } ?: parsed
        }
        songs += initialSongs
        if (songs.isNotEmpty()) {
            onPageLoaded?.invoke(songs.toList())
        }

        // Follow continuation pages until gone. Safety cap is enormous on
        // purpose (60k tracks) — it only exists to bound a pathological loop.
        var token = playlistShelfContinuationToken(root)
        var page = 0
        while (
            !token.isNullOrBlank() &&
            page < MAX_CONTINUATION_PAGES &&
            (trackLimit == null || songs.size < trackLimit)
        ) {
            val currentToken = token ?: break
            val nextPage = runCatching {
                browseContinuation(browseId, currentToken, authenticated = authenticatedAs)
            }.getOrNull() ?: break
            val pageSongs = parseSongRenderers(nextPage)
            if (pageSongs.isEmpty()) break
            val knownVideoIds = songs.mapTo(mutableSetOf()) { it.videoId }
            val newSongs = pageSongs
                .filterNot { it.videoId in knownVideoIds }
                .let { parsed -> trackLimit?.let { parsed.take(it - songs.size) } ?: parsed }
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

    suspend fun fetchPlaylistArtwork(playlistIdOrUrl: String): String? = withContext(Dispatchers.IO) {
        val rawId = extractPlaylistId(playlistIdOrUrl)
        if (rawId.isBlank()) return@withContext null
        val browseId = when {
            rawId.startsWith("VL") || rawId.startsWith("RDCLAK") || rawId.startsWith("FE") || rawId.startsWith("MPRE") || rawId.startsWith("UC") -> rawId
            else -> "VL$rawId"
        }
        val root = fetchPlaylistRoot(browseId)?.first ?: return@withContext null
        extractArtworkFromHeader(playlistHeader(root), root)
            ?: parseSongRenderers(root).firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) }
    }

    private suspend fun fetchPlaylistRoot(browseId: String): Pair<JsonElement, Boolean>? {
        if (ytAuth.connection.value.isConnected) {
            runCatching { browseRoot(browseId, authenticated = true) }.getOrNull()?.let {
                return it to true
            }
        }
        return runCatching { browseRoot(browseId, authenticated = false) }.getOrNull()?.let { it to false }
    }

    private fun playlistHeader(root: JsonElement): JsonObject? {
        val rootObj = root as? JsonObject ?: return findFirstHeaderRenderer(root)
        val header = rootObj.obj("header")
        return header?.obj("musicDetailHeaderRenderer")
            ?: header?.obj("musicResponsiveHeaderRenderer")
            ?: header?.obj("musicEditablePlaylistDetailHeaderRenderer")?.obj("header")?.obj("musicResponsiveHeaderRenderer")
            ?: header?.obj("musicEditablePlaylistDetailHeaderRenderer")?.obj("header")?.obj("musicDetailHeaderRenderer")
            ?: header?.obj("musicEditablePlaylistDetailHeaderRenderer")
            ?: header?.obj("musicVisualHeaderRenderer")
            ?: header?.obj("musicHeaderRenderer")
            ?: header?.obj("playlistHeaderRenderer")
            ?: findFirstHeaderRenderer(root)
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
        if (header != null) {
            extractThumbnailsUrl(header)?.let { return it }
        }
        val thumbObjects = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveHeaderRenderer", thumbObjects)
        collectObjects(root, "musicDetailHeaderRenderer", thumbObjects)
        collectObjects(root, "musicEditablePlaylistDetailHeaderRenderer", thumbObjects)
        collectObjects(root, "musicVisualHeaderRenderer", thumbObjects)
        collectObjects(root, "musicThumbnailRenderer", thumbObjects)
        for (to in thumbObjects) {
            extractThumbnailsUrl(to)?.let { return it }
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
                    .distinctBy { it.videoId }
                    .take(recentLimit.coerceIn(0, 50))
            }
            val liked = async {
                runCatching { parseSongRenderers(browseRoot(YT_LIKED_BROWSE_ID, authenticated = true)) }
                    .getOrDefault(emptyList())
                    .distinctBy { it.videoId }
                    .take(likedLimit.coerceIn(0, 50))
            }
            val feed = async {
                runCatching {
                    parseHomeFeedSongs(browseRoot(YT_HOME_BROWSE_ID, authenticated = true))
                }
                    .getOrDefault(emptyList())
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

    suspend fun fetchNewReleases(): List<YouTubePlaylistSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val root = browseRoot(YT_NEW_RELEASES_BROWSE_ID, authenticated = false)
            parsePlaylistRenderers(root)
        }.getOrDefault(emptyList())
    }

    suspend fun fetchCharts(): List<YouTubeMusicTrack> = withContext(Dispatchers.IO) {
        runCatching {
            val root = browseRoot(YT_CHARTS_BROWSE_ID, authenticated = false)
            parseSongRenderers(root)
        }.getOrDefault(emptyList())
    }

    suspend fun fetchHomeMixes(): List<YouTubePlaylistSummary> = withContext(Dispatchers.IO) {
        val isAuth = ytAuth.connection.value.isConnected
        runCatching {
            val root = browseRoot(YT_HOME_BROWSE_ID, authenticated = isAuth)
            parsePlaylistRenderers(root)
        }.getOrDefault(emptyList())
    }

    suspend fun fetchHomeSongs(): List<YouTubeMusicTrack> = withContext(Dispatchers.IO) {
        val isAuth = ytAuth.connection.value.isConnected
        runCatching {
            val root = browseRoot(YT_HOME_BROWSE_ID, authenticated = isAuth)
            parseHomeFeedSongs(root)
        }.getOrDefault(emptyList())
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

    suspend fun searchSongs(
        query: String,
        limit: Int = 30,
        prefetchStreams: Boolean = true,
    ): List<YouTubeMusicTrack> = withContext(Dispatchers.IO) {
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
        if (prefetchStreams) results.take(2).forEach { prefetchStream(it.videoId) }
        results
    }

    /** Anonymous YouTube Music radio for a seed video. This intentionally
     * stays cookie-free so related-song playlists work without an account. */
    suspend fun fetchRelatedSongs(
        videoId: String,
        limit: Int = 30,
        prefetchStreams: Boolean = true,
    ): List<YouTubeMusicTrack> = withContext(Dispatchers.IO) {
        if (videoId.isBlank() || limit <= 0) return@withContext emptyList()
        val config = getWebConfig()
        val root = post(
            url = "$MUSIC_API/next?key=${config.apiKey}&prettyPrint=false",
            body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                put("videoId", videoId)
                put("playlistId", "RDAMVM$videoId")
                put("params", "wAEB")
                put("isAudioOnly", true)
            },
            clientName = "WEB_REMIX",
            clientVersion = config.clientVersion,
            userAgent = WEB_USER_AGENT,
            callTimeoutMs = RELATED_REQUEST_TIMEOUT_MS,
        )
        val results = parseSongRenderers(root)
            .filterNot { it.videoId == videoId }
            .take(limit)
        if (prefetchStreams) results.take(2).forEach { prefetchStream(it.videoId) }
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
                        val previewTracks = parsed.map { track ->
                            com.lastwave.app.playback.PlayableTrack(
                                title = track.title,
                                artist = track.artist.takeUnless { it == "Unknown artist" } ?: title,
                                album = track.album,
                                artworkUrl = track.artworkUrl ?: artworkUrl,
                                videoId = track.videoId,
                            )
                        }

                        // YouTube Music artist overview only embeds 5 preview tracks in the initial shelf.
                        // Follow the shelf's "Show all" / "More" endpoint (e.g. VLOLAK... or FEmusic_artist_more_tracks) to get full top tracks.
                        val moreEndpoint = shelf.obj("bottomEndpoint")?.obj("browseEndpoint")
                            ?: shelf.obj("bottomText")?.array("runs")?.firstOrNull()?.asObject()?.obj("navigationEndpoint")?.obj("browseEndpoint")
                            ?: shelf.obj("title")?.array("runs")?.firstOrNull()?.asObject()?.obj("navigationEndpoint")?.obj("browseEndpoint")
                            ?: shelf.obj("header")?.obj("musicShelfHeaderRenderer")?.obj("title")?.array("runs")?.firstOrNull()?.asObject()?.obj("navigationEndpoint")?.obj("browseEndpoint")
                            ?: shelf.obj("header")?.obj("musicCarouselShelfBasicHeaderRenderer")?.obj("moreContentButton")?.obj("buttonRenderer")?.obj("navigationEndpoint")?.obj("browseEndpoint")

                        val moreBrowseId = moreEndpoint?.string("browseId")
                        val moreParams = moreEndpoint?.string("params")

                        val fullTracks = if (!moreBrowseId.isNullOrBlank()) {
                            runCatching {
                                browseSongs(moreBrowseId, params = moreParams, limit = null).map { track ->
                                    com.lastwave.app.playback.PlayableTrack(
                                        title = track.title,
                                        artist = track.artist.takeUnless { it == "Unknown artist" } ?: title,
                                        album = track.album,
                                        artworkUrl = track.artworkUrl ?: artworkUrl,
                                        videoId = track.videoId,
                                    )
                                }
                            }.getOrNull()
                        } else null

                        topSongs = if (!fullTracks.isNullOrEmpty()) {
                            fullTracks
                        } else {
                            previewTracks
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
    suspend fun browseSongs(browseId: String, params: String? = null, limit: Int? = null): List<YouTubeMusicTrack> = withContext(Dispatchers.IO) {
        require(browseId.isNotBlank()) { "Missing YouTube Music browse id" }
        val config = getWebConfig()
        val root = post(
            url = "$MUSIC_API/browse?key=${config.apiKey}&prettyPrint=false",
            body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData))
                put("browseId", browseId)
                if (!params.isNullOrBlank()) {
                    put("params", params)
                }
            },
            clientName = "WEB_REMIX",
            clientVersion = config.clientVersion,
            userAgent = WEB_USER_AGENT,
        )
        val shelves = mutableListOf<JsonObject>()
        collectObjects(root, "musicShelfRenderer", shelves)
        collectObjects(root, "musicPlaylistShelfRenderer", shelves)
        val primaryShelf = shelves.firstOrNull { shelf ->
            val heading = shelf.obj("title")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            heading.equals("Songs", ignoreCase = true) || heading.equals("Tracks", ignoreCase = true)
        } ?: shelves.firstOrNull()

        val songs = mutableListOf<YouTubeMusicTrack>()
        songs.addAll(parseSongRenderers(primaryShelf ?: root))

        // Follow continuations to collect every song by the artist
        var token = playlistShelfContinuationToken(root)
        var page = 0
        while (!token.isNullOrBlank() && page < MAX_CONTINUATION_PAGES && (limit == null || songs.size < limit)) {
            val currentToken = token ?: break
            val nextPage = runCatching {
                browseContinuation(browseId, currentToken, authenticated = false)
            }.getOrNull() ?: break
            val pageSongs = parseSongRenderers(nextPage)
            if (pageSongs.isEmpty()) break
            val knownVideoIds = songs.mapTo(mutableSetOf()) { it.videoId }
            val newSongs = pageSongs.filterNot { it.videoId in knownVideoIds }
            if (newSongs.isEmpty()) break
            songs.addAll(newSongs)
            token = playlistShelfContinuationToken(nextPage)
            page++
        }

        val result = if (limit != null) songs.take(limit) else songs
        result.take(2).forEach { prefetchStream(it.videoId) }
        result
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

    /** Resolves and byte-probes an expiring googlevideo URL immediately before use. */
    suspend fun resolveAudioStream(videoId: String): YouTubeAudioStream = withContext(Dispatchers.IO) {
        require(videoId.isNotBlank()) { "Missing YouTube Music video id" }
        val now = System.currentTimeMillis()
        val authScope = playbackAuthScope()

        streamCache.entries
            .asSequence()
            .filter { it.key.videoId == videoId && it.key.authScope == authScope }
            .sortedByDescending { it.value.cachedAtEpochMs }
            .firstOrNull()
            ?.let { entry ->
                val cached = entry.value
                val stream = cached.stream
                if (stream.isFresh(cached.cachedAtEpochMs, now) && probeStream(stream, "cache-validate")) {
                    lastResolvedStreams[resolutionKey(videoId, authScope)] = stream
                    logStreamEvent("cache-hit", stream)
                    return@withContext stream
                }
                streamCache.remove(entry.key, entry.value)
                lastResolvedStreams.remove(resolutionKey(videoId, authScope), stream)
                if (stream.expiresAtEpochMs == null || stream.expiresAtEpochMs - now > URL_EXPIRY_MARGIN_MS) {
                    reportPlaybackFailure(videoId, stream)
                }
                logStreamEvent("cache-rejected", stream)
            }

        val requestKey = resolutionKey(videoId, authScope)
        val shared = activeStreamRequests.computeIfAbsent(requestKey) {
            lateinit var request: SharedStreamRequest
            val deferred = apiScope.async(start = CoroutineStart.LAZY) {
                resolveAudioStreamInternal(videoId, authScope)
            }
            request = SharedStreamRequest(deferred)
            deferred.invokeOnCompletion {
                activeStreamRequests.remove(requestKey, request)
            }
            request
        }
        shared.waiters.incrementAndGet()
        shared.deferred.start()
        try {
            shared.deferred.await()
        } finally {
            if (shared.waiters.decrementAndGet() == 0 && !shared.deferred.isCompleted) {
                shared.deferred.cancel()
                activeStreamRequests.remove(requestKey, shared)
            }
        }
    }

    /** Resolves stream specifically optimized for download compatibility (M4A AAC container). */
    suspend fun resolveDownloadStream(videoId: String): YouTubeAudioStream = withContext(Dispatchers.IO) {
        require(videoId.isNotBlank()) { "Missing YouTube Music video id" }
        runCatching {
            streamExtractor.resolveAudioStream(videoId, preferM4a = true)
        }.getOrNull() ?: resolveAudioStream(videoId)
    }

    private suspend fun resolveAudioStreamInternal(
        videoId: String,
        authScope: String,
    ): YouTubeAudioStream = kotlinx.coroutines.coroutineScope {
        val now = System.currentTimeMillis()

        // InnerTubeX owns the current player/cipher/client fallback strategy.
        // Probe its result before handing it to Media3; the older direct and
        // NewPipe paths below remain bounded compatibility fallbacks.
        val innerTubeXCandidate = try {
            val visitorData = try {
                getWebConfig().visitorData
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            kotlinx.coroutines.withTimeoutOrNull(INNERTUBEX_TIMEOUT_MS) {
                innerTubeXExtractor.resolve(videoId, visitorData, authScope)
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            logClientFailure(videoId, "INNERTUBEX", failure)
            null
        }
        if (innerTubeXCandidate != null) {
            val compatible = innerTubeXCandidate.isAdaptive || isCompatibleAudioCandidate(innerTubeXCandidate)
            if (compatible && probeStream(innerTubeXCandidate, "innertubex-probe")) {
                cacheResolvedStream(innerTubeXCandidate, now)
                lastResolvedStreams[resolutionKey(videoId, authScope)] = innerTubeXCandidate
                logStreamEvent("innertubex-resolved", innerTubeXCandidate)
                return@coroutineScope innerTubeXCandidate
            }
            logStreamEvent("innertubex-rejected", innerTubeXCandidate, detail = "compatible=$compatible")
            // A candidate that fails validation must not make the same
            // InnerTubeX profile win the next resolution again.
            innerTubeXExtractor.reportPlaybackFailure(
                videoId = videoId,
                authScope = authScope,
                clientProfile = innerTubeXCandidate.clientProfile,
            )
        }

        val configDeferred = async(Dispatchers.IO) {
            getWebConfig()
        }
        val signatureTimestampDeferred = async(Dispatchers.IO) {
            streamExtractor.getSignatureTimestamp(videoId)
        }
        val poTokenDeferred = async(Dispatchers.IO) {
            val visitorData = getWebConfig().visitorData
            BotGuardTokenGenerator.mintToken(videoId, visitorData ?: FALLBACK_TOKEN_SESSION)
        }

        val channel = kotlinx.coroutines.channels.Channel<YouTubeAudioStream>(2)
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        val confirmedUnavailableReasons = ConcurrentHashMap.newKeySet<String>()
        val transientFailures = ConcurrentHashMap.newKeySet<String>()
        val remainingResolvers = AtomicInteger(2)

        fun resolverFinished() {
            if (remainingResolvers.decrementAndGet() == 0) channel.close()
        }

        jobs += launch(Dispatchers.IO) {
            var lastFailure: Throwable? = null
            try {
                for (attempt in 0..1) {
                    try {
                        val stream = streamExtractor.resolveAudioStream(videoId)
                        if (!probeStream(stream, "newpipe-probe", attempt)) {
                            throw IOException("NewPipe returned a rejected media URL for $videoId")
                        }
                        channel.trySend(stream)
                        return@launch
                    } catch (cancellation: kotlinx.coroutines.CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        lastFailure = error
                        if (attempt == 0 && error.confirmedUnavailableReasonOrNull() == null) {
                            streamExtractor.invalidatePlayerState(videoId)
                            delay(NEWPIPE_RETRY_BASE_DELAY_MS + Random.nextLong(NEWPIPE_RETRY_JITTER_MS + 1L))
                        } else {
                            break
                        }
                    }
                }
                val confirmedReason = lastFailure?.confirmedUnavailableReasonOrNull()
                if (confirmedReason != null) confirmedUnavailableReasons += confirmedReason
                else transientFailures += "NewPipe: ${lastFailure?.message.orEmpty()}"
            } finally {
                resolverFinished()
            }
        }

        jobs += launch(Dispatchers.IO) {
            try {
                val config = configDeferred.await()
                val signatureTimestamp = kotlinx.coroutines.withTimeoutOrNull(SIGNATURE_TIMESTAMP_TIMEOUT_MS) {
                    signatureTimestampDeferred.await()
                }
                val poTokenResult = kotlinx.coroutines.withTimeoutOrNull(PO_TOKEN_FAST_WAIT_MS) {
                    poTokenDeferred.await()
                }
                val poToken = poTokenResult?.playerToken
                val gvsPoToken = poTokenResult?.sessionToken?.takeIf { config.visitorData != null }
                val availableClients = playerClients(config).filter { candidate ->
                    now >= (failedClientsUntil[clientFailureKey(videoId, candidate.key, authScope)] ?: 0L)
                }
                if (availableClients.isEmpty()) transientFailures += "All player clients are cooling down"

                val prioritizedClients = availableClients.sortedByDescending { it.name == lastSuccessfulClientName }

                kotlinx.coroutines.coroutineScope {
                    val clientJobs = mutableListOf<kotlinx.coroutines.Job>()
                    val winnerFound = AtomicBoolean(false)

                    for (client in prioritizedClients) {
                        if (winnerFound.get() || !isActive) break

                        clientJobs += launch(Dispatchers.IO) {
                            try {
                                val stream = resolveDirectClientStream(
                                    videoId = videoId,
                                    client = client,
                                    visitorData = config.visitorData,
                                    signatureTimestamp = signatureTimestamp,
                                    playerPoToken = poToken,
                                    gvsPoToken = gvsPoToken,
                                    authScope = authScope,
                                )
                                if (winnerFound.compareAndSet(false, true)) {
                                    lastSuccessfulClientName = client.name
                                    channel.trySend(stream)
                                    clientJobs.forEach { if (it != coroutineContext[kotlinx.coroutines.Job]) it.cancel() }
                                }
                            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                                throw cancellation
                            } catch (failure: Throwable) {
                                failedClientsUntil[clientFailureKey(videoId, client.key, authScope)] =
                                    System.currentTimeMillis() + CLIENT_COOLDOWN_MS
                                logClientFailure(videoId, client.key, failure)
                                val confirmedReason = failure.confirmedUnavailableReasonOrNull()
                                if (confirmedReason != null) confirmedUnavailableReasons += confirmedReason
                                else transientFailures += "${client.key}: ${failure.message.orEmpty()}"
                            }
                        }

                        if (prioritizedClients.size > 1 && !winnerFound.get()) {
                            delay(HEDGED_CLIENT_STAGGER_DELAY_MS)
                        }
                    }
                    clientJobs.joinAll()
                }
            } finally {
                resolverFinished()
            }
        }

        try {
            // extractor breakage); an unbounded receive() would hang stream
            // resolution (and therefore playback) forever.
            val winner = kotlinx.coroutines.withTimeoutOrNull(STREAM_RACE_TIMEOUT_MS) {
                channel.receiveCatching().getOrNull()
            }
            if (winner != null) {
                cacheResolvedStream(winner, now)
                lastResolvedStreams[resolutionKey(videoId, authScope)] = winner
                logStreamEvent("resolved", winner)
                winner
            } else {
                val confirmedReason = confirmedUnavailableReasons.firstOrNull()
                if (confirmedReason != null && transientFailures.isEmpty()) {
                    throw ConfirmedUnplayableMediaException(confirmedReason)
                }
                val details = transientFailures.firstOrNull()?.take(160).orEmpty()
                val suffix = details.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
                throw IOException("Unable to resolve a playable audio stream for $videoId$suffix")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is ConfirmedUnplayableMediaException) throw e
            jobs.forEach { it.cancel() }
            streamExtractor.invalidatePlayerState(videoId)
            // Last-resort direct NewPipe extraction — bounded as well so a
            // stalled socket can never wedge the player's loader thread.
            val npStream = try {
                kotlinx.coroutines.withTimeoutOrNull(FALLBACK_EXTRACT_TIMEOUT_MS) {
                    streamExtractor.resolveAudioStream(videoId)
                } ?: throw IOException("Timed out during fallback extraction for $videoId")
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (fallbackFailure: Throwable) {
                val fallbackConfirmed = fallbackFailure.confirmedUnavailableReasonOrNull()
                if (fallbackConfirmed != null) confirmedUnavailableReasons += fallbackConfirmed
                else transientFailures += "Fallback NewPipe: ${fallbackFailure.message.orEmpty()}"
                val confirmedReason = confirmedUnavailableReasons.firstOrNull()
                if (confirmedReason != null && transientFailures.isEmpty()) {
                    throw ConfirmedUnplayableMediaException(confirmedReason, fallbackFailure)
                }
                throw IOException("Unable to resolve audio stream for $videoId", fallbackFailure)
            }
            if (!probeStream(npStream, "fallback-probe")) {
                streamExtractor.invalidatePlayerState(videoId)
                throw IOException("Fallback extraction returned a rejected media URL for $videoId")
            }
            cacheResolvedStream(npStream, now)
            lastResolvedStreams[resolutionKey(videoId, authScope)] = npStream
            logStreamEvent("fallback-resolved", npStream)
            npStream
        } finally {
            channel.close()
            jobs.forEach { it.cancel() }
        }
    }

    private suspend fun resolveDirectClientStream(
        videoId: String,
        client: PlayerClient,
        visitorData: String?,
        signatureTimestamp: Int?,
        playerPoToken: String?,
        gvsPoToken: String?,
        authScope: String,
    ): YouTubeAudioStream {
        val body = buildJsonObject {
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("clientName", client.name)
                    put("clientVersion", client.version)
                    put("hl", "en")
                    put("gl", "US")
                    if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
                    if (!client.osName.isNullOrBlank()) put("osName", client.osName)
                    if (!client.osVersion.isNullOrBlank()) put("osVersion", client.osVersion)
                    if (!client.deviceMake.isNullOrBlank()) put("deviceMake", client.deviceMake)
                    if (!client.deviceModel.isNullOrBlank()) put("deviceModel", client.deviceModel)
                    if (!client.androidSdkVersion.isNullOrBlank()) {
                        put("androidSdkVersion", client.androidSdkVersion)
                    }
                })
                if (!playerPoToken.isNullOrBlank()) {
                    put("serviceIntegrityDimensions", buildJsonObject {
                        put("poToken", playerPoToken)
                    })
                }
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            if (signatureTimestamp != null) {
                put("playbackContext", buildJsonObject {
                    put("contentPlaybackContext", buildJsonObject {
                        put("signatureTimestamp", signatureTimestamp)
                    })
                })
            }
        }
        val playerApi = if (client.name == "WEB_REMIX") MUSIC_API else YOUTUBE_API
        val root = post(
            url = "$playerApi/player?key=${client.apiKey}&prettyPrint=false",
            body = body,
            clientName = client.name,
            clientVersion = client.version,
            userAgent = client.userAgent,
            authenticated = client.name == "WEB_REMIX" && ytAuth.connection.value.isConnected,
            origin = client.origin,
            referer = client.referer,
            visitorData = visitorData,
            maxAttempts = MAX_PLAYER_REQUEST_ATTEMPTS,
            callTimeoutMs = PLAYER_REQUEST_TIMEOUT_MS,
        )
        val status = root.obj("playabilityStatus")
        val state = status?.string("status")
        if (state != "OK") {
            val reason = status?.string("reason").orEmpty()
            if (state == "UNPLAYABLE" && reason.isConfirmedUnavailableReason()) {
                throw ConfirmedUnplayableMediaException(reason.ifBlank { state.orEmpty() })
            }
            throw IOException(reason.ifBlank { "Player status ${state ?: "missing"}" })
        }

        val streaming = root.obj("streamingData")
        val streamRequestHeaders = buildMap {
            putAll(client.streamRequestHeaders)
            if (!visitorData.isNullOrBlank()) put("X-Goog-Visitor-Id", visitorData)
            if (client.name == "WEB_REMIX" && ytAuth.connection.value.isConnected) {
                ytAuth.cookieHeaderValue()?.let { put("Cookie", it) }
                ytAuth.authorizationHeaderValue()?.let { put("Authorization", it) }
            }
        }
        val responseExpiry = streaming?.string("expiresInSeconds")
            ?.toLongOrNull()
            ?.let { System.currentTimeMillis() + it.coerceAtLeast(1L) * 1_000L }
        val formats = buildList {
            streaming?.array("formats").orEmpty().forEach { add(it to false) }
            streaming?.array("adaptiveFormats").orEmpty().forEach { add(it to true) }
        }
        val candidates = formats.mapNotNull { (element, isAdaptive) ->
                val format = element as? JsonObject ?: return@mapNotNull null
                val url = format.string("url")
                    ?: (format.string("signatureCipher") ?: format.string("cipher"))
                        ?.let { streamExtractor.decipherStreamUrl(videoId, it) }
                    ?: return@mapNotNull null
                val mime = format.string("mimeType")
                if (mime?.startsWith("audio/") != true) return@mapNotNull null
                val finalUrl = appendPoToken(url, gvsPoToken)
                YouTubeAudioStream(
                    videoId = videoId,
                    url = finalUrl,
                    itag = format.int("itag"),
                    mimeType = mime.substringBefore(';'),
                    codec = extractCodec(mime),
                    bitrate = format.int("bitrate") ?: 0,
                    sampleRateHz = format.string("audioSampleRate")?.toIntOrNull(),
                    durationMs = format.string("approxDurationMs")?.toLongOrNull(),
                    contentLength = format.string("contentLength")?.toLongOrNull(),
                    isAdaptive = isAdaptive,
                    clientProfile = client.key,
                    authScope = authScope,
                    requestHeaders = streamRequestHeaders,
                    expiresAtEpochMs = listOfNotNull(streamExpiryEpochMs(finalUrl), responseExpiry).minOrNull()
                        ?: System.currentTimeMillis() + UNKNOWN_STREAM_EXPIRY_TTL_MS,
                )
            }
            .filter(::isCompatibleAudioCandidate)
            .sortedWith(
                compareBy<YouTubeAudioStream> { it.isAdaptive }
                    .thenByDescending { it.bitrate },
            )
        for (candidate in candidates.take(MAX_FORMAT_PROBES_PER_CLIENT)) {
            if (probeStream(candidate, "client-probe")) return candidate
        }
        throw IOException("${client.key} returned no usable audio URL")
    }

    private fun cacheResolvedStream(stream: YouTubeAudioStream, cachedAt: Long) {
        streamCache[StreamCacheKey.from(stream)] = CachedStream(cachedAt, stream)
        pruneStreamCache()
    }

    private suspend fun probeStream(
        stream: YouTubeAudioStream,
        stage: String,
        retry: Int = 0,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (stream.expiresAtEpochMs != null && stream.expiresAtEpochMs - now <= URL_EXPIRY_MARGIN_MS) {
            logStreamEvent(stage, stream, retry = retry, detail = "expired=true")
            return false
        }
        val requestBuilder = Request.Builder()
            .url(stream.url)
            .header("Accept-Encoding", "identity")
            .apply {
                if (!stream.isAdaptive) header("Range", "bytes=0-1")
                stream.requestHeaders.forEach { (name, value) -> header(name, value) }
            }
        val request = requestBuilder.build()
        val call = http.newCall(request).apply {
            timeout().timeout(STREAM_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        val cancellationHandle = currentCoroutineContext()[kotlinx.coroutines.Job]
            ?.invokeOnCompletion { cause ->
                if (cause is kotlinx.coroutines.CancellationException) call.cancel()
            }
        return try {
            call.execute().use { response ->
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val validType = !contentType.contains("text/html") &&
                    !contentType.contains("application/json") &&
                    !contentType.contains("text/plain")
                val valid = (response.code == 200 || response.code == 206) &&
                    validType && response.body?.source()?.request(1L) == true
                logStreamEvent(
                    stage = stage,
                    stream = stream,
                    httpStatus = response.code,
                    retry = retry,
                    detail = "valid=$valid type=${contentType.substringBefore(';').take(40)}",
                )
                valid
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logStreamEvent(stage, stream, retry = retry, detail = "error=${error::class.java.simpleName}")
            false
        } finally {
            cancellationHandle?.dispose()
        }
    }

    private fun YouTubeAudioStream.isFresh(cachedAt: Long, now: Long): Boolean =
        now - cachedAt < STREAM_TTL_MS &&
            (expiresAtEpochMs == null || expiresAtEpochMs - now > URL_EXPIRY_MARGIN_MS)

    private fun appendPoToken(url: String, token: String?): String {
        if (token.isNullOrBlank()) return url
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (parsed.queryParameter("pot") != null) return url
        val fragmentIndex = url.indexOf('#').takeIf { it >= 0 } ?: url.length
        val base = url.substring(0, fragmentIndex)
        val fragment = url.substring(fragmentIndex)
        val separator = when {
            base.endsWith('?') || base.endsWith('&') -> ""
            '?' in base -> "&"
            else -> "?"
        }
        return "$base${separator}${Uri.encode("pot")}=${Uri.encode(token)}$fragment"
    }

    private fun streamExpiryEpochMs(url: String): Long? = url.toHttpUrlOrNull()
        ?.queryParameter("expire")
        ?.toLongOrNull()
        ?.times(1_000L)

    private fun clientFailureKey(videoId: String, clientKey: String, authScope: String): String =
        "$videoId|$clientKey|$authScope"

    private fun resolutionKey(videoId: String, authScope: String): String = "$videoId|$authScope"

    private fun playbackAuthScope(): String {
        val connection = ytAuth.connection.value
        if (!connection.isConnected) return ANONYMOUS_AUTH_SCOPE
        val cookieDigest = ytAuth.cookieHeaderValue()
            ?.let { cookie ->
                MessageDigest.getInstance("SHA-256")
                    .digest(cookie.toByteArray())
                    .take(8)
                    .joinToString("") { byte ->
                        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                    }
            }
            ?: "none"
        return "account:${connection.connectedAtMillis}:$cookieDigest"
    }

    private fun extractCodec(mimeType: String): String? = CODEC_PATTERN
        .find(mimeType)
        ?.groupValues
        ?.getOrNull(1)
        ?.substringBefore(',')
        ?.trim()

    private fun isCompatibleAudioCandidate(stream: YouTubeAudioStream): Boolean {
        val mime = stream.mimeType.orEmpty().lowercase()
        val codec = stream.codec.orEmpty().lowercase()
        return when {
            mime == "audio/webm" -> codec.isBlank() || codec.contains("opus") || codec.contains("vorbis")
            mime == "audio/mp4" || mime == "audio/m4a" -> codec.isBlank() || codec.contains("mp4a") || codec.contains("aac")
            mime == "audio/ogg" -> codec.isBlank() || codec.contains("opus") || codec.contains("vorbis")
            mime == "audio/mpeg" -> true
            else -> false
        }
    }

    private fun logStreamEvent(
        stage: String,
        stream: YouTubeAudioStream,
        httpStatus: Int? = null,
        retry: Int = 0,
        detail: String = "",
    ) {
        val now = System.currentTimeMillis()
        val expiryState = when {
            stream.expiresAtEpochMs == null -> "unknown"
            stream.expiresAtEpochMs <= now -> "expired"
            else -> "fresh"
        }
        android.util.Log.d(
            STREAM_LOG_TAG,
            "stage=$stage videoId=${stream.videoId} client=${stream.clientProfile} " +
                "itag=${stream.itag ?: -1} mime=${stream.mimeType.orEmpty()} " +
                "expiry=$expiryState retry=$retry http=${httpStatus ?: 0} ${detail.take(80)}",
        )
    }

    private fun logClientFailure(videoId: String, client: String, error: Throwable?) {
        val status = generateSequence(error) { it.cause }
            .filterIsInstance<InnerTubeHttpException>()
            .firstOrNull()
            ?.responseCode ?: 0
        android.util.Log.d(
            STREAM_LOG_TAG,
            "stage=client-resolve-failed videoId=$videoId client=$client itag=-1 " +
                "mime=unknown expiry=unknown retry=0 http=$status error=${error?.javaClass?.simpleName.orEmpty()}",
        )
    }

    private fun playerClients(config: WebConfig): List<PlayerClient> = PLAYER_CLIENTS.map { client ->
        if (client.name == "WEB_REMIX") {
            client.copy(version = config.clientVersion, apiKey = config.apiKey)
        } else {
            client
        }
    }

    private fun Throwable.confirmedUnavailableReasonOrNull(): String? {
        val causes = generateSequence(this) { it.cause }.take(10).toList()
        causes.filterIsInstance<ConfirmedUnplayableMediaException>()
            .firstOrNull()
            ?.message
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        return causes.firstNotNullOfOrNull { cause ->
            cause.message?.takeIf { it.isConfirmedUnavailableReason() }
        }
    }

    private fun String.isConfirmedUnavailableReason(): Boolean {
        val reason = lowercase()
        return CONFIRMED_UNAVAILABLE_REASONS.any(reason::contains)
    }

    /** Keeps the stream cache from growing without bound over long sessions:
     *  drops expired entries first, then trims the oldest inserts. */
    private fun pruneStreamCache() {
        if (streamCache.size <= MAX_STREAM_CACHE_ENTRIES) return
        val now = System.currentTimeMillis()
        streamCache.entries.removeIf { entry ->
            !entry.value.stream.isFresh(entry.value.cachedAtEpochMs, now)
        }
        if (streamCache.size > MAX_STREAM_CACHE_ENTRIES) {
            streamCache.entries
                .sortedBy { it.value.cachedAtEpochMs }
                .take(streamCache.size - MAX_STREAM_CACHE_ENTRIES)
                .forEach { streamCache.remove(it.key, it.value) }
        }
    }

    suspend fun findBestMatch(
        title: String,
        artist: String,
        prefetchStreams: Boolean = true,
    ): YouTubeMusicTrack {
        val cacheKey = "${normalize(artist)}|${normalize(title)}"
        matchCache[cacheKey]?.let { return it }
        val results = searchSongs(
            query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" "),
            limit = 30,
            prefetchStreams = prefetchStreams,
        )
        val best = results.maxByOrNull { candidate -> matchScore(candidate, title, artist) }
            ?: throw IOException("No YouTube Music match found for $title")
        val titleSimilarity = maxOf(
            similarity(best.title, title),
            similarity(baseTitle(best.title), baseTitle(title)),
        )
        val artistSimilarity = similarity(best.artist, artist)
        if (titleSimilarity < 72 || (artist.isNotBlank() && artistSimilarity < 50)) {
            throw IOException("No reliable YouTube Music match found for $title by $artist")
        }
        return best.also {
            if (matchCache.size > MAX_MATCH_CACHE_ENTRIES) matchCache.clear()
            matchCache[cacheKey] = it
        }
    }

    suspend fun findBestMatchOrNull(
        title: String,
        artist: String,
        prefetchStreams: Boolean = true,
    ): YouTubeMusicTrack? =
        try {
            kotlinx.coroutines.withTimeoutOrNull(2500L) {
                findBestMatch(title, artist, prefetchStreams)
            }
        } catch (_: Exception) {
            null
        }

    suspend fun isPlayable(title: String, artist: String): Boolean =
        findBestMatchOrNull(title, artist) != null

    private suspend fun getWebConfig(): WebConfig {
        webConfig?.let { return it }
        return configMutex.withLock {
            webConfig?.let { return@withLock it }
            val config = runCatching { fetchWebConfig() }
                .getOrElse { WebConfig(FALLBACK_WEB_KEY, FALLBACK_WEB_VERSION, null) }
            webConfig = config
            config
        }
    }

    private fun fetchWebConfig(): WebConfig {
        val request = Request.Builder()
            .url("$YOUTUBE_MUSIC_ORIGIN/")
            .header("User-Agent", WEB_USER_AGENT)
            .build()
        val call = http.newCall(request).apply {
            timeout().timeout(CONFIG_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        val html = call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("YouTube Music config HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        return WebConfig(
            apiKey = findConfig(html, "INNERTUBE_API_KEY") ?: FALLBACK_WEB_KEY,
            clientVersion = findConfig(html, "INNERTUBE_CONTEXT_CLIENT_VERSION") ?: FALLBACK_WEB_VERSION,
            visitorData = findConfig(html, "VISITOR_DATA"),
        )
    }

    private fun findConfig(html: String, key: String): String? {
        if (html.isBlank()) return null
        val escaped = Regex("\\\"$key\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(html)?.groupValues?.getOrNull(1)
        return escaped
            ?.replace("\\u003d", "=")
            ?.replace("\\x3d", "=")
            ?.replace("\\/", "/")
    }

    private suspend fun post(
        url: String,
        body: JsonObject,
        clientName: String,
        clientVersion: String,
        userAgent: String,
        authenticated: Boolean = false,
        origin: String = YOUTUBE_MUSIC_ORIGIN,
        referer: String = "$YOUTUBE_MUSIC_ORIGIN/",
        visitorData: String? = null,
        maxAttempts: Int = 2,
        callTimeoutMs: Long? = null,
    ): JsonObject {
        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
            .header("Origin", origin)
            .header("X-Origin", origin)
            .header("Referer", referer)
            .header("X-Goog-Api-Format-Version", "1")
            .header("X-YouTube-Client-Name", CLIENT_IDS[clientName] ?: clientName)
            .header("X-YouTube-Client-Version", clientVersion)

        if (!visitorData.isNullOrBlank()) {
            builder.header("X-Goog-Visitor-Id", visitorData)
        }

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
        for (attempt in 1..maxAttempts) {
            try {
                val call = http.newCall(request)
                callTimeoutMs?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
                val cancellationHandle = currentCoroutineContext()[kotlinx.coroutines.Job]
                    ?.invokeOnCompletion { cause ->
                        if (cause is kotlinx.coroutines.CancellationException) call.cancel()
                    }
                return try {
                    call.execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            if (response.code == 400 || response.code == 403 || response.code == 429) {
                                webConfig = null
                            }
                            throw InnerTubeHttpException(response.code)
                        }
                    // A non-JSON body (HTML interstitial / error page) used to
                    // escape the retry loop entirely — treat it like any
                    // other transient failure and retry once.
                        runCatching { json.parseToJsonElement(text).jsonObject }
                            .getOrElse { cause ->
                                throw IOException("Invalid InnerTube response", cause)
                            }
                    }
                } finally {
                    cancellationHandle?.dispose()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                if (attempt >= maxAttempts || !e.isTransientRequestFailure()) break
                val exponential = REQUEST_RETRY_BASE_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(3))
                delay(exponential + Random.nextLong(REQUEST_RETRY_JITTER_MS + 1L))
            }
        }
        throw (lastException as? IOException) ?: IOException("InnerTube call failed: ${lastException}")
    }

    private fun Exception.isTransientRequestFailure(): Boolean = when (this) {
        is InnerTubeHttpException -> responseCode == 408 || responseCode == 429 || responseCode in 500..599
        is IOException -> true
        else -> false
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
        val queueRenderers = mutableListOf<JsonObject>()
        collectObjects(root, "playlistPanelVideoRenderer", queueRenderers)
        songs.addAll(queueRenderers.mapNotNull(::parsePlaylistPanelSong))
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
            ?: "Unknown artist"
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

    private fun parsePlaylistPanelSong(renderer: JsonObject): YouTubeMusicTrack? {
        if (renderer.obj("unplayableText") != null) return null
        val videoId = renderer.string("videoId")
            ?: renderer.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: return null
        val title = renderer.obj("title")?.array("runs")
            ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?.trim()?.takeIf(String::isNotBlank)
            ?: renderer.obj("title")?.string("simpleText")?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val detailRuns = (renderer.obj("longBylineText") ?: renderer.obj("shortBylineText"))
            ?.array("runs")?.mapNotNull { it.asObject() }.orEmpty()
        val artist = detailRuns.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?.string("browseId")?.startsWith("UC") == true
        }?.string("text") ?: detailRuns.mapNotNull { it.string("text") }
            .firstOrNull { it.isLikelyArtistDetail() }
            ?: "Unknown artist"
        val album = detailRuns.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?.string("browseId")?.startsWith("MPRE") == true
        }?.string("text")
        val duration = renderer.obj("lengthText")?.array("runs")
            ?.mapNotNull { it.asObject()?.string("text") }
            ?.firstNotNullOfOrNull(::parseDuration)
        val artwork = renderer.obj("thumbnail")?.array("thumbnails")
            ?.lastOrNull()?.asObject()?.string("url")?.let {
                if (it.startsWith("//")) "https:$it" else it
            }?.highResolutionArtwork()
        return YouTubeMusicTrack(videoId, title, artist, album, artwork, duration)
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
        collectObjects(root, "gridPlaylistRenderer", renderers)
        collectObjects(root, "musicGridItemRenderer", renderers)
        collectObjects(root, "playlistRenderer", renderers)
        return renderers.mapNotNull { renderer ->
            val nav = renderer.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?: renderer.obj("title")?.array("runs")?.firstOrNull()?.asObject()?.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?: renderer.obj("thumbnailOverlay")?.obj("musicItemThumbnailOverlayRenderer")?.obj("content")?.obj("musicPlayButtonRenderer")?.obj("playNavigationEndpoint")?.obj("watchEndpoint")
                ?: renderer.obj("onTap")?.obj("browseEndpoint")
            val browseId = nav?.string("browseId") ?: nav?.string("playlistId") ?: return@mapNotNull null
            val playlistId = if (browseId.startsWith("VL")) browseId.removePrefix("VL") else browseId

            val title = renderer.array("flexColumns")?.getOrNull(0)?.asObject()
                ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?: renderer.obj("title")?.array("runs")?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?: renderer.obj("title")?.string("simpleText")
                ?: return@mapNotNull null

            val subtitleRuns = renderer.array("flexColumns")?.getOrNull(1)?.asObject()
                ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
                ?: renderer.obj("subtitle")?.array("runs")
            val author = subtitleRuns?.firstOrNull()?.asObject()?.string("text")

            val trackCountText = subtitleRuns?.mapNotNull { it.asObject()?.string("text") }?.lastOrNull { "song" in it.lowercase() || "track" in it.lowercase() }

            val artwork = extractThumbnailsUrl(renderer)

            YouTubePlaylistSummary(
                id = playlistId,
                title = title.trim(),
                author = author?.trim(),
                trackCountText = trackCountText,
                artworkUrl = artwork,
            )
        }.distinctBy { it.id }
    }

    private fun extractThumbnailsUrl(renderer: JsonElement): String? {
        val foundArrays = mutableListOf<JsonArray>()
        fun findThumbnails(el: JsonElement) {
            when (el) {
                is JsonObject -> {
                    el.array("thumbnails")?.takeIf { it.isNotEmpty() }?.let { foundArrays += it }
                    el.values.forEach { findThumbnails(it) }
                }
                is JsonArray -> el.forEach { findThumbnails(it) }
                else -> Unit
            }
        }
        val thumbnailNode = renderer.asObject()?.let {
            it.obj("thumbnail") ?: it.obj("thumbnailRenderer") ?: it
        } ?: renderer
        findThumbnails(thumbnailNode)
        val bestArray = foundArrays.firstOrNull { it.isNotEmpty() } ?: return null
        val bestUrl = bestArray.lastOrNull()?.asObject()?.string("url")
            ?: bestArray.firstOrNull()?.asObject()?.string("url")
            ?: return null
        val formatted = if (bestUrl.startsWith("//")) "https:$bestUrl" else bestUrl
        return formatted.highResolutionArtwork()
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
            substringBeforeLast('=') + "=w512-h512-l90-rj"
        else -> this
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .replace(NON_WORD, " ")
        .trim()
        .replace(MULTI_SPACE, " ")

    private data class SharedStreamRequest(
        val deferred: Deferred<YouTubeAudioStream>,
        val waiters: AtomicInteger = AtomicInteger(0),
    )

    private data class CachedStream(
        val cachedAtEpochMs: Long,
        val stream: YouTubeAudioStream,
    )

    private data class StreamCacheKey(
        val videoId: String,
        val clientProfile: String,
        val itag: Int?,
        val authScope: String,
        val expiresAtEpochMs: Long?,
    ) {
        fun matches(stream: YouTubeAudioStream): Boolean =
            clientProfile == stream.clientProfile &&
                itag == stream.itag &&
                authScope == stream.authScope &&
                expiresAtEpochMs == stream.expiresAtEpochMs

        companion object {
            fun from(stream: YouTubeAudioStream) = StreamCacheKey(
                videoId = stream.videoId,
                clientProfile = stream.clientProfile,
                itag = stream.itag,
                authScope = stream.authScope,
                expiresAtEpochMs = stream.expiresAtEpochMs,
            )
        }
    }

    private class InnerTubeHttpException(val responseCode: Int) :
        IOException("InnerTube HTTP $responseCode")

    private data class WebConfig(val apiKey: String, val clientVersion: String, val visitorData: String?)

    private data class PlayerClient(
        val name: String,
        val version: String,
        val apiKey: String,
        val userAgent: String,
        val osName: String? = null,
        val osVersion: String? = null,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val androidSdkVersion: String? = null,
    ) {
        val key = "$name@$version"
        val origin = if (name == "WEB_REMIX") YOUTUBE_MUSIC_ORIGIN else YOUTUBE_ORIGIN
        val referer = when (name) {
            "WEB_REMIX" -> "$YOUTUBE_MUSIC_ORIGIN/"
            "TVHTML5", "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> "$YOUTUBE_ORIGIN/tv"
            "WEB_EMBEDDED_PLAYER" -> "$YOUTUBE_ORIGIN/embed"
            else -> "$YOUTUBE_ORIGIN/"
        }
        val streamRequestHeaders = buildMap {
            put("User-Agent", userAgent)
            put("X-YouTube-Client-Name", CLIENT_IDS[name] ?: name)
            put("X-YouTube-Client-Version", version)
            if (name == "WEB_REMIX" || name == "TVHTML5" || name == "TVHTML5_SIMPLY_EMBEDDED_PLAYER" || name == "WEB_EMBEDDED_PLAYER" || name == "MWEB") {
                put("Origin", origin)
                put("Referer", referer)
            }
        }
    }

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
            "IOS_CREATOR" to "15",
            "ANDROID" to "3",
            "ANDROID_MUSIC" to "21",
            "ANDROID_VR" to "28",
            "ANDROID_TESTSUITE" to "30",
            "ANDROID_CREATOR" to "14",
            "TVHTML5" to "7",
            "TVHTML5_SIMPLY_EMBEDDED_PLAYER" to "85",
            "VISIONOS" to "101",
            "WEB_EMBEDDED_PLAYER" to "56",
            "MWEB" to "62",
        )
        const val YOUTUBE_MUSIC_ORIGIN = "https://music.youtube.com"
        const val YOUTUBE_ORIGIN = "https://www.youtube.com"
        const val MUSIC_API = "https://music.youtube.com/youtubei/v1"
        const val YOUTUBE_API = "https://www.youtube.com/youtubei/v1"
        const val LIBRARY_PLAYLISTS_BROWSE_ID = "FEmusic_liked_playlists"
        const val YT_HISTORY_BROWSE_ID = "FEmusic_history"
        const val YT_LIKED_BROWSE_ID = "VLLM"
        const val YT_HOME_BROWSE_ID = "FEmusic_home"
        const val YT_NEW_RELEASES_BROWSE_ID = "FEmusic_new_releases"
        const val YT_CHARTS_BROWSE_ID = "FEmusic_charts"
        const val MAX_CONTINUATION_PAGES = 600
        const val WRITE_ACTIONS_PER_REQUEST = 50

        /** Upper bound on how long both stream-resolution racers combined may
         *  take before falling back to direct extraction. */
        const val STREAM_RACE_TIMEOUT_MS = 15_000L
        const val INNERTUBEX_TIMEOUT_MS = 8_000L
        const val HEDGED_CLIENT_STAGGER_DELAY_MS = 300L
        const val STREAM_PROBE_TIMEOUT_MS = 2_000L
        const val MAX_FORMAT_PROBES_PER_CLIENT = 2
        const val PLAYER_REQUEST_TIMEOUT_MS = 3_000L
        const val MAX_PLAYER_REQUEST_ATTEMPTS = 2
        const val CONFIG_REQUEST_TIMEOUT_MS = 4_000L
        const val RELATED_REQUEST_TIMEOUT_MS = 8_000L
        const val SIGNATURE_TIMESTAMP_TIMEOUT_MS = 3_000L
        const val PO_TOKEN_FAST_WAIT_MS = 750L
        const val URL_EXPIRY_MARGIN_MS = 2 * 60 * 1000L
        const val REQUEST_RETRY_BASE_DELAY_MS = 250L
        const val REQUEST_RETRY_JITTER_MS = 180L
        const val NEWPIPE_RETRY_BASE_DELAY_MS = 300L
        const val NEWPIPE_RETRY_JITTER_MS = 220L
        const val UNKNOWN_STREAM_EXPIRY_TTL_MS = 5 * 60 * 1000L

        /** Upper bound for the last-resort direct NewPipe extraction. */
        const val FALLBACK_EXTRACT_TIMEOUT_MS = 12_000L
        val CONFIRMED_UNAVAILABLE_REASONS = listOf(
            "video has been removed",
            "video has been deleted",
            "video was removed",
            "video was deleted",
            "this video is private",
            "this is a private video",
            "not available in your country",
            "blocked in your country",
            "copyright claim",
        )

        /** How long a failed player client is skipped by the racer. */
        const val CLIENT_COOLDOWN_MS = 60_000L

        /** Hard cap so long sessions can't grow the caches without bound. */
        const val MAX_STREAM_CACHE_ENTRIES = 64
        const val STREAM_TTL_MS = 4 * 60 * 60 * 1000L
        const val MAX_MATCH_CACHE_ENTRIES = 1024
        const val FALLBACK_TOKEN_SESSION = "lastwave_session"
        const val NEWPIPE_SOURCE = "NEWPIPE"
        const val ANONYMOUS_AUTH_SCOPE = "anonymous"
        const val STREAM_LOG_TAG = "LastWaveStream"
        const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
        const val FALLBACK_WEB_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
        const val FALLBACK_WEB_VERSION = "1.20260707.12.00"
        const val ARTIST_SEARCH_FILTER = "EgWKAQIgAWoKEAkQBRAKEAMQBA=="
        const val ALBUM_SEARCH_FILTER = "EgWKAQIYAWoKEAkQBRAKEAMQBA=="
        val CODEC_PATTERN = Regex("""codecs?=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
        val PLAYER_CLIENTS = listOf(
            PlayerClient(
                name = "ANDROID_VR",
                version = "1.37",
                apiKey = "AIzaSyD-p045F_WzU-vA_YgX20SCx4KAo",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.37 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)",
                osName = "Android",
                osVersion = "12",
                deviceMake = "Oculus",
                deviceModel = "Quest 3",
                androidSdkVersion = "32",
            ),
            PlayerClient(
                name = "VISIONOS",
                version = "0.1",
                apiKey = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
                userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
                osName = "visionOS",
                osVersion = "1.3.21O771",
                deviceMake = "Apple",
                deviceModel = "RealityDevice14,1",
            ),
            PlayerClient(
                name = "TVHTML5",
                version = "7.20260308.08.00",
                apiKey = "AIzaSyAO_FJ2SlqAz8GlBg1fA54p0wDE7Xk80mU",
                userAgent = "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/4.0 Chrome/76.0.3809.146 TV Safari/537.36",
            ),
            PlayerClient(
                name = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                version = "2.0",
                apiKey = "AIzaSyAO_FJ2SlqAz8GlBg1fA54p0wDE7Xk80mU",
                userAgent = "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/4.0 Chrome/76.0.3809.146 TV Safari/537.36",
            ),
            PlayerClient(
                name = "ANDROID_TESTSUITE",
                version = "1.9",
                apiKey = "AIzaSyD-p045F_WzU-vA_YgX20SCx4KAo",
                userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 12) gzip",
                osName = "Android",
                osVersion = "12",
            ),
            PlayerClient(
                name = "IOS_MUSIC",
                version = "7.27.0",
                apiKey = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
                userAgent = "com.google.ios.youtubemusic/7.27.0 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)",
                osName = "iOS",
                osVersion = "17.5.1.21F90",
                deviceMake = "Apple",
                deviceModel = "iPhone16,2",
            ),
            PlayerClient(
                name = "ANDROID_MUSIC",
                version = "7.27.52",
                apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
                userAgent = "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 14; en_US; Pixel 8; Build/UD1A.230803.041) gzip",
                osName = "Android",
                osVersion = "14",
                deviceMake = "Google",
                deviceModel = "Pixel 8",
                androidSdkVersion = "34",
            ),
            PlayerClient(
                name = "IOS",
                version = "21.26.4",
                apiKey = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
                userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2;)",
                osName = "iPhone",
                osVersion = "18.3.2.22D82",
                deviceMake = "Apple",
                deviceModel = "iPhone16,2",
            ),
            PlayerClient(
                name = "ANDROID",
                version = "21.26.364",
                apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
                userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
                osName = "Android",
                osVersion = "11",
            ),
            PlayerClient(
                name = "ANDROID_CREATOR",
                version = "24.32.100",
                apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
                userAgent = "com.google.android.apps.youtube.creator/24.32.100 (Linux; U; Android 13; en_US) gzip",
                osName = "Android",
                osVersion = "13",
            ),
            PlayerClient(
                name = "IOS_CREATOR",
                version = "24.32.100",
                apiKey = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
                userAgent = "com.google.ios.creator/24.32.100 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)",
                osName = "iOS",
                osVersion = "17.5.1.21F90",
            ),
            PlayerClient(
                name = "WEB_REMIX",
                version = FALLBACK_WEB_VERSION,
                apiKey = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30",
                userAgent = WEB_USER_AGENT,
            ),
            PlayerClient(
                name = "WEB_EMBEDDED_PLAYER",
                version = FALLBACK_WEB_VERSION,
                apiKey = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30",
                userAgent = WEB_USER_AGENT,
            ),
            PlayerClient(
                name = "MWEB",
                version = "2.20260707.01.00",
                apiKey = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30",
                userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
            ),
        )
    }
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()
private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
private fun JsonElement.obj(key: String): JsonObject? = (this as? JsonObject)?.obj(key)
private fun JsonElement.array(key: String): JsonArray? = (this as? JsonObject)?.array(key)
private fun JsonElement.string(key: String): String? = (this as? JsonObject)?.string(key)
private fun JsonElement.int(key: String): Int? = (this as? JsonObject)?.int(key)
