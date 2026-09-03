package com.lastwave.app.data.qobuz

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.Locale
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
    private val resolutionClient = client.newBuilder()
        .callTimeout(4, TimeUnit.SECONDS)
        .build()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    companion object {
        val BACKEND_BASE_URL: String
            get() = decodeSecretBytes(
                com.lastwave.app.BuildConfig.QOBUZ_BACKEND_URL_BYTES,
                com.lastwave.app.BuildConfig.SECRET_MASK_BYTES
            )

        val BACKEND_API_KEY: String
            get() = decodeSecretBytes(
                com.lastwave.app.BuildConfig.QOBUZ_API_KEY_BYTES,
                com.lastwave.app.BuildConfig.SECRET_MASK_BYTES
            )

        fun decodeSecretBytes(data: ByteArray, mask: ByteArray): String {
            if (data.isEmpty() || mask.isEmpty()) return ""
            val decoded = ByteArray(data.size) { i ->
                (data[i].toInt() xor mask[i % mask.size].toInt()).toByte()
            }
            return String(decoded, Charsets.UTF_8)
        }

        // Quality presets
        const val QUALITY_MAX_HI_RES = 27 // Up to 24-bit / 192 kHz (Qobuz)
        const val QUALITY_HI_RES_96 = 7   // Up to 24-bit / 96 kHz (Qobuz)
        const val QUALITY_CD_LOSSLESS = 6 // 16-bit / 44.1 kHz FLAC (Qobuz)
        const val QUALITY_MP3_320 = 5     // 320 kbps MP3 (Qobuz)
        const val QUALITY_YOUTUBE = -1    // YouTube Music standard stream

        /**
         * Resolves the order of quality tiers to attempt.
         * If the requested quality fails, it prioritizes the tiers ABOVE it in ascending order,
         * followed by the tiers below it before falling back to YouTube Music.
         */
        fun getQualityAttemptOrder(preferred: Int): List<Int> {
            val qobuzTiersAscending = listOf(
                QUALITY_MP3_320,     // 5
                QUALITY_CD_LOSSLESS, // 6
                QUALITY_HI_RES_96,   // 7
                QUALITY_MAX_HI_RES,  // 27
            )
            val index = qobuzTiersAscending.indexOf(preferred)
            if (index == -1) return listOf(QUALITY_MAX_HI_RES, QUALITY_HI_RES_96, QUALITY_CD_LOSSLESS, QUALITY_MP3_320)

            val preferredQuality = qobuzTiersAscending[index]
            val above = qobuzTiersAscending.subList(index + 1, qobuzTiersAscending.size)
            val below = qobuzTiersAscending.subList(0, index).reversed()

            return (listOf(preferredQuality) + above + below).distinct()
        }

        private const val TAG = "QobuzMusicApi"
        private const val MAX_DURATION_DIFFERENCE_SECONDS = 8
        private val DIACRITICS = Regex("\\p{M}+")
        private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
        private val MULTI_SPACE = Regex("\\s+")
        private val FEATURING_CLAUSE = Regex("""(?i)(?:\s*[\[(])?\s*(feat\.?|ft\.?|featuring)\s+.*$""")
        private val BRACKETED_DISPLAY_NOISE = Regex(
            """(?i)[\[(]\s*(?:official\s+)?(?:music\s+)?(?:audio|video|lyrics?|lyric\s+video|visualizer|hd|4k)\s*(?:\]|\))""",
        )
        private val TRAILING_DISPLAY_NOISE = Regex("""(?i)\s*[-–—]\s*(?:official\s+)?(?:music\s+)?(?:audio|video|lyrics?|visualizer)\s*$""")
        private val ARTIST_NOISE_WORDS = setOf("the", "and", "feat", "ft", "featuring", "with", "x")
        private val PERFORMING_ROLE_WORDS = setOf(
            "mainartist", "featuredartist", "performer", "vocal", "vocals", "vocalist", "singer",
        )
        private val IDENTITY_VARIANT_PATTERNS = listOf(
            "live" to Regex("\\blive\\b"),
            "acoustic" to Regex("\\bacoustic\\b"),
            "karaoke" to Regex("\\bkaraoke\\b"),
            "instrumental" to Regex("\\binstrumental\\b"),
            "tribute" to Regex("\\btribute\\b"),
            "cover" to Regex("\\bcover\\b"),
            "remix" to Regex("\\bremix(?:ed)?\\b"),
            "mashup" to Regex("""\bmash[ -]?up\b|\b[a-z0-9]+\s+x\s+[a-z0-9]+\b"""),
            "demo" to Regex("\\bdemo\\b"),
            "slowed" to Regex("\\bslowed\\b"),
            "reverb" to Regex("\\breverb\\b"),
            "sped-up" to Regex("\\bsped up\\b"),
            "nightcore" to Regex("\\bnightcore\\b"),
            "radio-edit" to Regex("\\bradio edit\\b"),
            "extended" to Regex("\\bextended(?: version| mix)?\\b"),
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
        expectedAlbum: String? = null,
        preferredQuality: Int = QUALITY_MAX_HI_RES,
    ): QobuzAudioStream? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null

        try {
            // 1. Search Qobuz catalog via backend
            val candidate = findBestVerifiedMatch(
                title = title,
                artist = artist,
                expectedDurationSeconds = expectedDurationSeconds,
                expectedAlbum = expectedAlbum,
            ) ?: return@withContext null

            // 2. Fetch direct CDN streaming URL with upward/fallback tier resolution
            val qualitiesToTry = getQualityAttemptOrder(preferredQuality)
            for (quality in qualitiesToTry) {
                currentCoroutineContext().ensureActive()
                val stream = fetchTrackStreamUrl(candidate, quality, fallback = false)
                if (stream != null) return@withContext stream
            }
            // Final fail-open fallback attempt
            fetchTrackStreamUrl(candidate, preferredQuality, fallback = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Lossless resolution failed gracefully: ${e.message}")
            null
        }
    }

    private fun fetchTrackStreamUrl(
        candidate: LosslessTrackItem,
        quality: Int,
        fallback: Boolean,
    ): LosslessAudioStream? {
        val urlBuilder = "$BACKEND_BASE_URL/api/track/${candidate.id}/url".toHttpUrlOrNull()?.newBuilder()
            ?: return null
        urlBuilder.addQueryParameter("quality", quality.toString())
        urlBuilder.addQueryParameter("fallback", fallback.toString())

        val requestBuilder = Request.Builder().url(urlBuilder.build()).get()
        if (BACKEND_API_KEY.isNotBlank()) requestBuilder.addHeader("X-API-Key", BACKEND_API_KEY)

        return try {
            resolutionClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Qobuz stream request for track ${candidate.id} quality $quality failed with HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val parsed = json.decodeFromString<QobuzTrackUrlResponse>(body)
                if (!parsed.success) {
                    Log.w(TAG, "Qobuz stream response for track ${candidate.id} quality $quality rejected: ${parsed.error.orEmpty()}")
                    return null
                }
                val data = parsed.data ?: return null
                val streamUrl = data.url?.takeIf { it.isNotBlank() } ?: return null

                val bitrateKbps = when (data.formatId) {
                    QUALITY_MAX_HI_RES -> ((data.bitDepth * data.samplingRate * 2 * 1000) / 1000).toInt()
                    QUALITY_HI_RES_96 -> ((data.bitDepth * data.samplingRate * 2 * 1000) / 1000).toInt()
                    QUALITY_CD_LOSSLESS -> 1411
                    QUALITY_MP3_320 -> 320
                    else -> null
                }

                QobuzAudioStream(
                    url = streamUrl,
                    mimeType = data.mimeType.ifBlank { "audio/flac" },
                    bitDepth = data.bitDepth.takeIf { it > 0 } ?: 16,
                    samplingRate = data.samplingRate.takeIf { it > 0 } ?: 44.1,
                    formatId = data.formatId,
                    bitrateKbps = bitrateKbps,
                    trackId = candidate.id,
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "Qobuz stream fetch for quality $quality failed: ${e.message}")
            null
        }
    }

    private suspend fun findBestVerifiedMatch(
        title: String,
        artist: String,
        expectedDurationSeconds: Int?,
        expectedAlbum: String?,
    ): QobuzTrackItem? {
        val cleanTitle = cleanForSearch(title)
        val cleanArtist = cleanForSearch(artist)

        // Qobuz search ranking varies by catalog/locale. Try precise metadata
        // first, then progressively broader queries, but never relax identity
        // verification for the returned track.
        val queries = listOfNotNull(
            "$cleanTitle $cleanArtist".trim().takeIf { it.isNotBlank() },
            "$cleanArtist $cleanTitle".trim().takeIf { it.isNotBlank() },
            cleanTitle.takeIf { it.isNotBlank() },
            title.trim().takeIf { it.isNotBlank() },
        ).distinct()

        for (query in queries) {
            currentCoroutineContext().ensureActive()
            val urlBuilder = "$BACKEND_BASE_URL/api/search".toHttpUrlOrNull()?.newBuilder() ?: continue
            urlBuilder.addQueryParameter("q", query)
            urlBuilder.addQueryParameter("type", "track")
            urlBuilder.addQueryParameter("limit", "15")

            val reqBuilder = Request.Builder().url(urlBuilder.build()).get()
            if (BACKEND_API_KEY.isNotBlank()) {
                reqBuilder.addHeader("X-API-Key", BACKEND_API_KEY)
            }

            val items = try {
                resolutionClient.newCall(reqBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Qobuz search failed with HTTP ${response.code}")
                        return@use emptyList<QobuzTrackItem>()
                    }
                    val body = response.body?.string() ?: return@use emptyList<QobuzTrackItem>()
                    val searchRes = json.decodeFromString<QobuzSearchResponse>(body)
                    if (searchRes.success) searchRes.results?.tracks?.items.orEmpty() else emptyList()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Qobuz search failed gracefully: ${e.message}")
                emptyList()
            }

            if (items.isEmpty()) continue

            items.asSequence()
                .mapNotNull { item ->
                    verifiedMatchScore(
                        item = item,
                        title = title,
                        artist = artist,
                        expectedDurationSeconds = expectedDurationSeconds,
                        expectedAlbum = expectedAlbum,
                    )?.let { score -> item to score }
                }
                .maxByOrNull { it.second }
                ?.first
                ?.let { return it }
        }

        return null
    }

    private fun verifiedMatchScore(
        item: QobuzTrackItem,
        title: String,
        artist: String,
        expectedDurationSeconds: Int?,
        expectedAlbum: String?,
    ): Int? {
        val targetTitle = normalizeTitle(title, artist)
        val candidateTitle = normalizeTitle(item.title, artist)
        if (targetTitle.isBlank() || targetTitle != candidateTitle) return null

        val targetVariants = identityVariants(title, artist)
        val candidateVariants = identityVariants("${item.title} ${item.version.orEmpty()}", artist)
        if (targetVariants != candidateVariants) return null

        val performer = item.performer?.name.orEmpty()
        val albumArtist = item.album?.artist?.name.orEmpty()
        if (!isVerifiedArtistMatch(artist, performer, albumArtist, item.performers)) return null

        val durationDifference = if (expectedDurationSeconds != null && expectedDurationSeconds > 0) {
            if (item.duration <= 0) return null
            kotlin.math.abs(item.duration - expectedDurationSeconds).also { if (it > MAX_DURATION_DIFFERENCE_SECONDS) return null }
        } else null

        var score = 1_000
        val normalizedArtist = normalizeText(artist)
        if (listOf(performer, albumArtist).any { normalizeText(it) == normalizedArtist }) score += 300
        expectedAlbum?.takeIf(String::isNotBlank)?.let { album ->
            if (normalizeTitle(album, "") == normalizeTitle(item.album?.title.orEmpty(), "")) score += 120
        }
        durationDifference?.let { score += (MAX_DURATION_DIFFERENCE_SECONDS - it) * 10 }
        return score
    }

    private fun cleanForSearch(raw: String): String {
        return raw
            .replace(FEATURING_CLAUSE, " ")
            .replace(BRACKETED_DISPLAY_NOISE, " ")
            .replace(TRAILING_DISPLAY_NOISE, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeTitle(raw: String, artist: String): String {
        val withoutArtistPrefix = if (artist.isBlank()) raw else raw.replaceFirst(
            Regex("""^\s*${Regex.escape(artist)}\s*[-–—:]\s*""", RegexOption.IGNORE_CASE),
            "",
        )
        return normalizeText(cleanForSearch(withoutArtistPrefix))
    }

    private fun normalizeText(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase(Locale.ROOT)
        .replace(NON_ALPHANUMERIC, " ")
        .replace(MULTI_SPACE, " ")
        .trim()

    private fun identityVariants(raw: String, artist: String): Set<String> {
        val withoutArtistPrefix = if (artist.isBlank()) raw else raw.replaceFirst(
            Regex("""^\s*${Regex.escape(artist)}\s*[-–—:]\s*""", RegexOption.IGNORE_CASE),
            "",
        )
        val normalized = normalizeText(withoutArtistPrefix)
        return IDENTITY_VARIANT_PATTERNS.mapNotNullTo(linkedSetOf()) { (name, pattern) ->
            name.takeIf { pattern.containsMatchIn(normalized) }
        }
    }

    private fun isVerifiedArtistMatch(
        targetArtist: String,
        performer: String,
        albumArtist: String,
        performersText: String?,
    ): Boolean {
        val target = normalizeText(targetArtist)
        if (target.isBlank()) return false
        val primaryIdentities = listOf(performer, albumArtist)
            .map(::normalizeText)
            .filter(String::isNotBlank)
        if (primaryIdentities.any { it == target }) return true

        val targetTokens = target.split(' ').filter { it !in ARTIST_NOISE_WORDS }.toSet()
        if (targetTokens.isEmpty()) return false
        if (primaryIdentities.any { identity -> targetTokens.all(identity.split(' ').toSet()::contains) }) return true

        // Qobuz's performers string also lists writers/producers. Only use
        // credits explicitly labelled as a performing role; a songwriter's
        // name must never make a cover look like the requested artist.
        val performingCredits = performersText.orEmpty()
            .split(Regex("""\s+-\s+"""))
            .map(::normalizeText)
            .filter { credit -> PERFORMING_ROLE_WORDS.any { role -> role in credit.split(' ') } }
        val performingTokens = (primaryIdentities + performingCredits)
            .flatMap { it.split(' ') }
            .toSet()
        return targetTokens.all(performingTokens::contains)
    }

}
