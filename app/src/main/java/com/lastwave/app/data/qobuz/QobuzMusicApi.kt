package com.lastwave.app.data.qobuz

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class QobuzAudioStream(
    val url: String,
    val mimeType: String = "audio/flac",
    val bitDepth: Int = 16,
    val samplingRate: Double = 44.1,
    val formatId: Int = 6,
    val bitrateKbps: Int? = null,
    val trackId: Long = 0,
)

@Serializable
private data class QobuzSearchResponse(
    val success: Boolean = false,
    val results: QobuzSearchResults? = null,
)

@Serializable
private data class QobuzSearchResults(
    val tracks: QobuzTrackList? = null,
)

@Serializable
private data class QobuzTrackList(
    val items: List<QobuzTrackItem> = emptyList(),
)

@Serializable
private data class QobuzTrackItem(
    val id: Long,
    val title: String,
    val duration: Int = 0,
    val version: String? = null,
    val performer: QobuzPerformer? = null,
    val performers: String? = null,
    val album: QobuzAlbumInfo? = null,
    val hires: Boolean = false,
    @SerialName("maximum_bit_depth")
    val maxBitDepth: Int? = null,
    @SerialName("maximum_sampling_rate")
    val maxSamplingRate: Double? = null,
)

@Serializable
private data class QobuzPerformer(
    val name: String,
    val id: Long = 0,
)

@Serializable
private data class QobuzAlbumInfo(
    val title: String? = null,
    val artist: QobuzPerformer? = null,
)

@Serializable
private data class QobuzTrackUrlResponse(
    val success: Boolean = false,
    val data: QobuzTrackUrlData? = null,
    val error: String? = null,
)

@Serializable
private data class QobuzTrackUrlData(
    val url: String? = null,
    @SerialName("format_id")
    val formatId: Int = 6,
    @SerialName("mime_type")
    val mimeType: String = "audio/flac",
    @SerialName("sampling_rate")
    val samplingRate: Double = 44.1,
    @SerialName("bit_depth")
    val bitDepth: Int = 16,
    val duration: Int = 0,
)

@Singleton
class QobuzMusicApi @Inject constructor(
    okHttpClient: OkHttpClient,
) {
    private val client = okHttpClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val streamCache = ConcurrentHashMap<String, Pair<Long, QobuzAudioStream>>()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    companion object {
        const val BACKEND_BASE_URL = "https://qobuz-backend.clashgram.workers.dev"

        val BACKEND_API_KEY: String
            get() = com.lastwave.app.BuildConfig.QOBUZ_API_KEY

        // Quality presets
        const val QUALITY_MAX_HI_RES = 27 // Up to 24-bit / 192 kHz
        const val QUALITY_HI_RES_96 = 7   // Up to 24-bit / 96 kHz
        const val QUALITY_CD_LOSSLESS = 6 // 16-bit / 44.1 kHz FLAC
        const val QUALITY_MP3_320 = 5     // 320 kbps MP3

        val EXCLUDED_VARIANTS = listOf(
            "live", "acoustic", "karaoke", "instrumental", "tribute", "cover", "remix", "demo", "slowed", "reverb", "sped up", "nightcore"
        )
    }

    /**
     * Resolves a high-confidence, verified Qobuz direct CDN audio stream URL for a given track.
     * If no high-confidence exact match is found, returns null so playback safely falls back to YouTube Music.
     */
    suspend fun resolveStream(
        title: String,
        artist: String,
        expectedDurationSeconds: Int? = null,
        preferredQuality: Int = QUALITY_MAX_HI_RES,
    ): QobuzAudioStream? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null

        val cacheKey = "${artist.trim().lowercase(Locale.ROOT)}|${title.trim().lowercase(Locale.ROOT)}|$preferredQuality"
        val now = System.currentTimeMillis()
        streamCache[cacheKey]?.let { (cachedAt, stream) ->
            if (now - cachedAt < 4 * 60 * 60 * 1000L) {
                return@withContext stream
            }
        }

        try {
            // 1. Search Qobuz catalog via backend
            val candidate = findBestVerifiedMatch(title, artist, expectedDurationSeconds) ?: return@withContext null

            // 2. Fetch direct CDN streaming URL
            val urlBuilder = "$BACKEND_BASE_URL/api/track/${candidate.id}/url".toHttpUrlOrNull()?.newBuilder()
                ?: return@withContext null
            urlBuilder.addQueryParameter("quality", preferredQuality.toString())
            urlBuilder.addQueryParameter("fallback", "true")

            val request = Request.Builder()
                .url(urlBuilder.build())
                .addHeader("X-API-Key", BACKEND_API_KEY)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val parsed = json.decodeFromString<QobuzTrackUrlResponse>(body)
                val data = parsed.data ?: return@withContext null
                val streamUrl = data.url ?: return@withContext null

                val bitrateKbps = when (data.formatId) {
                    QUALITY_MAX_HI_RES -> ((data.bitDepth * data.samplingRate * 2 * 1000) / 1000).toInt()
                    QUALITY_HI_RES_96 -> ((data.bitDepth * data.samplingRate * 2 * 1000) / 1000).toInt()
                    QUALITY_CD_LOSSLESS -> 1411
                    QUALITY_MP3_320 -> 320
                    else -> null
                }

                val stream = QobuzAudioStream(
                    url = streamUrl,
                    mimeType = data.mimeType.ifBlank { "audio/flac" },
                    bitDepth = data.bitDepth.takeIf { it > 0 } ?: 16,
                    samplingRate = data.samplingRate.takeIf { it > 0 } ?: 44.1,
                    formatId = data.formatId,
                    bitrateKbps = bitrateKbps,
                    trackId = candidate.id,
                )
                streamCache[cacheKey] = Pair(now, stream)
                stream
            }
        } catch (e: Exception) {
            android.util.Log.d("QobuzMusicApi", "Qobuz resolution failed gracefully: ${e.message}")
            null
        }
    }

    private suspend fun findBestVerifiedMatch(
        title: String,
        artist: String,
        expectedDurationSeconds: Int?,
    ): QobuzTrackItem? {
        val queries = listOf(
            "$artist $title".trim(),
            title.trim(),
        )

        for (query in queries) {
            val urlBuilder = "$BACKEND_BASE_URL/api/search".toHttpUrlOrNull()?.newBuilder() ?: continue
            urlBuilder.addQueryParameter("q", query)
            urlBuilder.addQueryParameter("type", "track")
            urlBuilder.addQueryParameter("limit", "15")

            val request = Request.Builder()
                .url(urlBuilder.build())
                .addHeader("X-API-Key", BACKEND_API_KEY)
                .get()
                .build()

            val items = try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    val body = response.body?.string() ?: return@use emptyList()
                    val searchRes = json.decodeFromString<QobuzSearchResponse>(body)
                    searchRes.results?.tracks?.items.orEmpty()
                }
            } catch (e: Exception) {
                emptyList()
            }

            if (items.isEmpty()) continue

            val rawTargetTitle = title.lowercase(Locale.ROOT)
            val normTargetTitle = normalizeString(title)
            val normTargetArtist = normalizeString(artist)

            for (item in items) {
                val rawCandidateTitle = "${item.title} ${item.version.orEmpty()}".lowercase(Locale.ROOT)
                val itemTitle = normalizeString(item.title)
                val itemArtist = normalizeString(item.performer?.name ?: item.album?.artist?.name.orEmpty())

                // Anti-mismatch check: prevent live, acoustic, karaoke, cover, remix, instrumental if not requested
                val hasUnrequestedVariant = EXCLUDED_VARIANTS.any { variant ->
                    rawCandidateTitle.contains(variant) && !rawTargetTitle.contains(variant)
                }
                if (hasUnrequestedVariant) continue

                // Strict title verification
                val titleMatches = isTitleMatch(normTargetTitle, itemTitle)
                if (!titleMatches) continue

                // Strict artist verification
                val artistMatches = isArtistMatch(normTargetArtist, itemArtist, item.performers)
                if (!artistMatches) continue

                // Optional duration verification if expected duration is known
                if (expectedDurationSeconds != null && expectedDurationSeconds > 0 && item.duration > 0) {
                    val diff = kotlin.math.abs(item.duration - expectedDurationSeconds)
                    if (diff > 12) continue
                }

                return item
            }
        }

        return null
    }

    private fun normalizeString(raw: String): String {
        return raw.lowercase(Locale.ROOT)
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\[[^\]]*]"""), " ")
            .replace(Regex("""[-–—/].*"""), " ")
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun isTitleMatch(target: String, candidate: String): Boolean {
        if (target.isBlank() || candidate.isBlank()) return false
        if (target == candidate) return true
        if (candidate.startsWith(target) || target.startsWith(candidate)) return true

        val targetWords = target.split(" ").filter { it.isNotBlank() }
        val candWords = candidate.split(" ").filter { it.isNotBlank() }
        if (targetWords.isEmpty() || candWords.isEmpty()) return false

        val common = targetWords.count { candWords.contains(it) }
        val targetRatio = common.toFloat() / targetWords.size

        return targetRatio >= 0.75f
    }

    private fun isArtistMatch(targetArtist: String, candArtist: String, performersText: String?): Boolean {
        if (targetArtist.isBlank()) return false
        if (candArtist.isNotBlank()) {
            if (targetArtist == candArtist) return true
            if (candArtist.contains(targetArtist) || targetArtist.contains(candArtist)) return true
            val targetTokens = targetArtist.split(" ").filter { it.length > 1 }
            val candTokens = candArtist.split(" ").filter { it.length > 1 }
            if (targetTokens.isNotEmpty() && candTokens.isNotEmpty() && targetTokens.any { candTokens.contains(it) }) {
                return true
            }
        }
        if (!performersText.isNullOrBlank()) {
            val normPerformers = normalizeString(performersText)
            if (normPerformers.contains(targetArtist)) return true
        }
        return false
    }
}
