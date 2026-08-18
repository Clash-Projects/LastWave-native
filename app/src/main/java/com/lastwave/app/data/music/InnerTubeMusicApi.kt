package com.lastwave.app.data.music

import kotlinx.coroutines.Dispatchers
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

/**
 * Small, account-free client for the same private InnerTube endpoints used
 * by the YouTube Music web/mobile clients. Search uses WEB_REMIX while
 * playback tries mobile clients that return direct adaptive audio formats.
 * No Google cookie, account, API project, or redirect is involved.
 *
 * InnerTube is not a public/stable Google API. The web client key/version
 * are therefore bootstrapped from music.youtube.com and cached instead of
 * permanently tying search to a stale build identifier.
 */
@Singleton
class InnerTubeMusicApi @Inject constructor(
    private val http: OkHttpClient,
    private val streamExtractor: YouTubeStreamExtractor,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val configMutex = Mutex()
    private val matchCache = ConcurrentHashMap<String, YouTubeMusicTrack>()
    @Volatile private var webConfig: WebConfig? = null

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
        parseSongRenderers(root).take(limit)
    }

    suspend fun searchArtists(query: String, limit: Int = 30): List<YouTubeMusicEntity> =
        searchEntities(query, YouTubeMusicEntityKind.ARTIST, ARTIST_SEARCH_FILTER, limit)

    suspend fun searchAlbums(query: String, limit: Int = 30): List<YouTubeMusicEntity> =
        searchEntities(query, YouTubeMusicEntityKind.ALBUM, ALBUM_SEARCH_FILTER, limit)

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
        parseSongRenderers(primaryShelf ?: root).take(limit)
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

    /** Resolves a fresh, expiring googlevideo URL immediately before use. */
    suspend fun resolveAudioStream(videoId: String): YouTubeAudioStream = withContext(Dispatchers.IO) {
        require(videoId.isNotBlank()) { "Missing YouTube Music video id" }
        var lastReason = runCatching {
            return@withContext streamExtractor.resolveAudioStream(videoId)
        }.exceptionOrNull()?.message ?: "This track is unavailable"
        for (client in PLAYER_CLIENTS) {
            runCatching {
                val body = buildJsonObject {
                    put("context", context(client.name, client.version, null, client.osVersion))
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }
                post(
                    url = "$YOUTUBE_API/player?key=${client.apiKey}&prettyPrint=false",
                    body = body,
                    clientName = client.name,
                    clientVersion = client.version,
                    userAgent = client.userAgent,
                )
            }.onSuccess { root ->
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
                            YouTubeAudioStream(
                                url = url,
                                mimeType = mime.substringBefore(';'),
                                bitrate = format.int("bitrate") ?: 0,
                            )
                        }
                    candidates.maxByOrNull { it.bitrate }?.let { return@withContext it }
                    lastReason = "YouTube Music did not return a direct audio stream"
                } else {
                    lastReason = status?.string("reason")
                        ?: status?.obj("errorScreen")?.toString()
                        ?: "YouTube Music playback status: ${state ?: "unknown"}"
                }
            }.onFailure { lastReason = it.message ?: lastReason }
        }
        throw IOException(lastReason)
    }

    suspend fun findBestMatch(title: String, artist: String): YouTubeMusicTrack {
        val cacheKey = "${normalize(artist)}|${normalize(title)}"
        matchCache[cacheKey]?.let { return it }
        val results = searchSongs(listOf(title, artist).filter { it.isNotBlank() }.joinToString(" "), 30)
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
        return best.also { matchCache[cacheKey] = it }
    }

    private suspend fun getWebConfig(): WebConfig {
        webConfig?.let { return it }
        return configMutex.withLock {
            webConfig?.let { return@withLock it }
            val request = Request.Builder()
                .url("https://music.youtube.com/")
                .header("User-Agent", WEB_USER_AGENT)
                .build()
            val html = runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("YouTube Music bootstrap HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
            }.getOrDefault("")
            WebConfig(
                apiKey = findConfig(html, "INNERTUBE_API_KEY") ?: FALLBACK_WEB_KEY,
                clientVersion = findConfig(html, "INNERTUBE_CLIENT_VERSION") ?: FALLBACK_WEB_VERSION,
                visitorData = findConfig(html, "VISITOR_DATA"),
            ).also { webConfig = it }
        }
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
    ): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("X-Goog-Api-Format-Version", "1")
            .header("X-YouTube-Client-Name", CLIENT_IDS[clientName] ?: clientName)
            .header("X-YouTube-Client-Version", clientVersion)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("InnerTube HTTP ${response.code}: ${text.take(180)}")
            json.parseToJsonElement(text).jsonObject
        }
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
        return renderers.mapNotNull(::parseSong).distinctBy { it.videoId }
    }

    private fun parseSong(renderer: JsonObject): YouTubeMusicTrack? {
        val videoId = renderer.obj("playlistItemData")?.string("videoId")
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

    private fun parseDuration(value: String): Int? {
        val parts = value.trim().split(':').mapNotNull(String::toIntOrNull)
        if (parts.size !in 2..3) return null
        return parts.fold(0) { total, part -> total * 60 + part }
    }

    private fun similarity(a: String, b: String): Int {
        val left = tokens(a)
        val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) return 0
        val common = left.intersect(right).size
        return (200 * common) / (left.size + right.size)
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
        val CLIENT_IDS = mapOf("WEB_REMIX" to "67", "IOS" to "5", "ANDROID" to "3")
        const val MUSIC_API = "https://music.youtube.com/youtubei/v1"
        const val YOUTUBE_API = "https://www.youtube.com/youtubei/v1"
        const val WEB_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        const val FALLBACK_WEB_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
        const val FALLBACK_WEB_VERSION = "1.20240715.00.00"
        const val ARTIST_SEARCH_FILTER = "EgWKAQIgAWoKEAkQBRAKEAMQBA=="
        const val ALBUM_SEARCH_FILTER = "EgWKAQIYAWoKEAkQBRAKEAMQBA=="
        val PLAYER_CLIENTS = listOf(
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
        )
    }
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()
private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
