package com.lastwave.app.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class LrclibRecord(
    val id: Long? = null,
    val name: String? = null,
    @SerialName("trackName")
    val trackName: String? = null,
    @SerialName("artistName")
    val artistName: String? = null,
    @SerialName("albumName")
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean? = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

@Singleton
class LrclibLyricsApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Attempts to fetch lyrics from LRCLIB.
     * Tries exact match via /api/get first, and falls back to /api/search if not found or 404.
     */
    suspend fun fetchLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
    ): LrclibRecord? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null

        // 1. Try exact match with raw track & artist
        val exact = getLyricsExact(title, artist, album, durationSeconds)
        if (exact != null && (!exact.syncedLyrics.isNullOrBlank() || !exact.plainLyrics.isNullOrBlank() || exact.instrumental == true)) {
            return@withContext exact
        }

        // 2. Clean title (strip "(feat. ...)", "- Extended", "[Official Video]", etc.) and retry exact
        val cleanedTitle = cleanTrackTitle(title)
        val cleanedArtist = cleanArtistName(artist)
        if (cleanedTitle != title || cleanedArtist != artist) {
            val cleanedExact = getLyricsExact(cleanedTitle, cleanedArtist, album, durationSeconds)
            if (cleanedExact != null && (!cleanedExact.syncedLyrics.isNullOrBlank() || !cleanedExact.plainLyrics.isNullOrBlank() || cleanedExact.instrumental == true)) {
                return@withContext cleanedExact
            }
        }

        // 3. Fallback to /api/search query
        searchLyrics(cleanedTitle, cleanedArtist) ?: searchLyrics(title, artist)
    }

    private fun getLyricsExact(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
    ): LrclibRecord? {
        val urlBuilder = "https://lrclib.net/api/get".toHttpUrlOrNull()?.newBuilder() ?: return null
        urlBuilder.addQueryParameter("track_name", title.trim())
        urlBuilder.addQueryParameter("artist_name", artist.trim())
        if (!album.isNullOrBlank()) {
            urlBuilder.addQueryParameter("album_name", album.trim())
        }
        if (durationSeconds != null && durationSeconds > 0) {
            urlBuilder.addQueryParameter("duration", durationSeconds.toString())
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", "LastWave-Android/1.0 (https://github.com/duxtami/LastWave)")
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                json.decodeFromString<LrclibRecord>(body)
            }
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun searchLyrics(
        title: String,
        artist: String,
    ): LrclibRecord? {
        val urlBuilder = "https://lrclib.net/api/search".toHttpUrlOrNull()?.newBuilder() ?: return null
        urlBuilder.addQueryParameter("q", "$artist $title".trim())

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", "LastWave-Android/1.0 (https://github.com/duxtami/LastWave)")
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val list = json.decodeFromString<List<LrclibRecord>>(body)
                // Prefer item with syncedLyrics first, then plainLyrics, or instrumental
                list.firstOrNull { !it.syncedLyrics.isNullOrBlank() }
                    ?: list.firstOrNull { !it.plainLyrics.isNullOrBlank() }
                    ?: list.firstOrNull { it.instrumental == true }
                    ?: list.firstOrNull()
            }
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun cleanTrackTitle(raw: String): String {
            return raw
                // Strip bracketed/parenthesized extra text: (feat. ...), (Official Video), [HQ], etc.
                .replace(Regex("""(?i)\s*[\(\[](?:feat\.?|ft\.?|official|music video|audio|remaster(?:ed)?|live|version|edit|extended|deluxe|explicit|hd|hq|4k|lyric video)[^\)\]]*[\)\]]"""), "")
                // Strip trailing "- Extended", "- Remix", etc.
                .replace(Regex("""(?i)\s*-\s*(?:extended|remix|radio edit|remaster(?:ed)?|bonus track|instrumental).*$"""), "")
                .trim()
        }

        fun cleanArtistName(raw: String): String {
            return raw
                .replace(Regex("""(?i)\s*[\(\[](?:feat\.?|ft\.?)[^\)\]]*[\)\]]"""), "")
                .replace(Regex("""(?i)\s*(?:feat\.?|ft\.?)\s+.*$"""), "")
                .trim()
        }
    }
}
