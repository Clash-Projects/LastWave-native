package com.lastwave.app.data.lyrics

import com.lastwave.app.BuildConfig
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
data class LyricsPlusSyllable(
    val time: Long = 0L,
    val duration: Long = 0L,
    val text: String = "",
    val isBackground: Boolean = false,
)

@Serializable
data class LyricsPlusTransliteration(
    val lang: String? = null,
    val text: String? = null,
    val syllabus: List<LyricsPlusSyllable>? = null,
)

@Serializable
data class LyricsPlusLine(
    val time: Long = 0L,
    val duration: Long = 0L,
    val text: String = "",
    val syllabus: List<LyricsPlusSyllable>? = null,
    val transliteration: LyricsPlusTransliteration? = null,
)

@Serializable
data class LyricsPlusResponse(
    val type: String? = null, // "WORD" or "LINE"
    val lyrics: List<LyricsPlusLine>? = null,
    val error: LyricsPlusError? = null,
)

@Serializable
data class LyricsPlusError(
    val message: String? = null,
    val status: Int? = null,
)

@Singleton
class LyricsPlusApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val endpoints = listOf(
        "https://lyricsplus.prjktla.my.id/v2/lyrics/get",
        "https://lyricsplus.clashgram.workers.dev/v2/lyrics/get",
    )

    suspend fun fetchWordLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
    ): LyricsPlusResponse? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null

        val cleanedTitle = LrclibLyricsApi.cleanTrackTitle(title)
        val cleanedArtist = LrclibLyricsApi.cleanArtistName(artist)

        val directResult = queryEndpoints(title, artist, album, durationSeconds)
        if (directResult != null && !directResult.lyrics.isNullOrEmpty()) {
            return@withContext directResult
        }

        if (cleanedTitle != title || cleanedArtist != artist) {
            val cleanedResult = queryEndpoints(cleanedTitle, cleanedArtist, album, durationSeconds)
            if (cleanedResult != null && !cleanedResult.lyrics.isNullOrEmpty()) {
                return@withContext cleanedResult
            }
        }

        null
    }

    private fun queryEndpoints(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
    ): LyricsPlusResponse? {
        val apiKey = com.lastwave.app.data.lossless.LosslessMusicApi.decodeSecretBytes(
            BuildConfig.LYRICS_API_KEY_BYTES,
            BuildConfig.SECRET_MASK_BYTES
        ).trim()

        for (baseUrl in endpoints) {
            val urlBuilder = baseUrl.toHttpUrlOrNull()?.newBuilder() ?: continue
            urlBuilder.addQueryParameter("title", title.trim())
            urlBuilder.addQueryParameter("artist", artist.trim())
            if (!album.isNullOrBlank()) {
                urlBuilder.addQueryParameter("album", album.trim())
            }
            if (durationSeconds != null && durationSeconds > 0) {
                urlBuilder.addQueryParameter("duration", durationSeconds.toString())
            }

            val requestBuilder = Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", "LastWave-Android/1.0 (https://github.com/duxtami/LastWave)")
                .header("Accept", "application/json")

            if (apiKey.isNotBlank()) {
                requestBuilder.header("x-api-key", apiKey)
            }

            try {
                okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val parsed = json.decodeFromString<LyricsPlusResponse>(body)
                        if (!parsed.lyrics.isNullOrEmpty()) {
                            return parsed
                        }
                    }
                }
            } catch (e: IOException) {
                // Continue to fallback endpoint
            } catch (e: Exception) {
                // Continue to fallback endpoint
            }
        }
        return null
    }
}
