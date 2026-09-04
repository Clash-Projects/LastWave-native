package com.lastwave.app.data.artwork

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ArtworkPipeline"
private const val CRASH_TAG = "ArtworkCrash"

@Serializable
private data class ITunesResult(
    val artworkUrl100: String? = null,
    val artworkUrl60: String? = null,
)

@Serializable
private data class ITunesSearchResponse(val results: List<ITunesResult> = emptyList())

/**
 * Faithful port of _itunesFetchArtwork(name, artist, 'track') — the only
 * iTunes call type Home actually uses. Same term format, same 600x600
 * upscale regex, same 6s timeout.
 */
@Singleton
class ITunesArtworkProvider @Inject constructor(
    okHttpClient: OkHttpClient,
) {
    private val client = okHttpClient.newBuilder()
        .callTimeout(6, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val upscalePattern = Regex("""/\d+x\d+bb\.(jpg|png|webp)$""", RegexOption.IGNORE_CASE)

    suspend fun fetchArtworkUrl(track: String, artist: String): String? = withContext(Dispatchers.IO) {
        // Try direct term first
        fetchByTerm(if (artist.isNotBlank()) "$track $artist" else track)
            ?: run {
                val cleanedTrack = ArtworkNormalizer.cleanTitle(track)
                val cleanedArtist = ArtworkNormalizer.cleanArtist(artist)
                val cleanedTerm = if (cleanedArtist.isNotBlank()) "$cleanedTrack $cleanedArtist" else cleanedTrack
                if (cleanedTerm != "$track $artist") {
                    fetchByTerm(cleanedTerm)
                } else null
            }
    }

    private suspend fun fetchByTerm(term: String): String? {
        val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(term, "UTF-8")}&media=music&entity=song&limit=1"
        try {
            val request = Request.Builder().url(url).build()
            val body = client.newCall(request).awaitSuccessfulBodyOrNull() ?: return null
            val parsed = json.decodeFromString<ITunesSearchResponse>(body)
            val raw = parsed.results.firstOrNull()?.let { it.artworkUrl100 ?: it.artworkUrl60 }
            return raw?.let { upscale(it) }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            return null
        }
    }

    /** …/100x100bb.jpg -> …/600x600bb.jpg */
    private fun upscale(rawUrl: String): String = upscalePattern.replace(rawUrl, "/600x600bb.jpg")
}
