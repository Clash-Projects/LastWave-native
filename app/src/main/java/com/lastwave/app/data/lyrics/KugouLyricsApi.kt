package com.lastwave.app.data.lyrics

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.InflaterInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class KugouSearchResponse(
    val status: Int = 0,
    val errcode: Int = 0,
    val error: String = "",
    val candidates: List<KugouCandidate> = emptyList(),
)

@Serializable
private data class KugouCandidate(
    val id: String,
    val accesskey: String,
    val song: String = "",
    val singer: String = "",
    val duration: Long = 0L,
    @SerialName("score")
    val score: Int = 0,
)

@Serializable
private data class KugouDownloadResponse(
    val status: Int = 0,
    val errcode: Int = 0,
    val error: String = "",
    val content: String? = null,
    val fmt: String = "krc",
)

@Singleton
class KugouLyricsApi @Inject constructor(
    okHttpClient: OkHttpClient,
) {
    private val client = okHttpClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val krcKey = byteArrayOf(
        0x40.toByte(), 0x47.toByte(), 0x61.toByte(), 0x77.toByte(),
        0x5e.toByte(), 0x32.toByte(), 0x74.toByte(), 0x47.toByte(),
        0x51.toByte(), 0x36.toByte(), 0x31.toByte(), 0x2d.toByte(),
        0xce.toByte(), 0xd2.toByte(), 0x6e.toByte(), 0x69.toByte()
    )

    private val krcLineRegex = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val krcSyllableRegex = Regex("""<(\d+),(\d+),\d+>([^<]*)""")

    suspend fun fetchWordLyrics(
        title: String,
        artist: String,
        durationSeconds: Int? = null,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null

        try {
            // 1. Search candidate
            val searchUrl = "http://lyrics.kugou.com/search".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("ver", "1")
                ?.addQueryParameter("man", "yes")
                ?.addQueryParameter("client", "pc")
                ?.addQueryParameter("keyword", "$artist - $title")
                ?.addQueryParameter("duration", if (durationSeconds != null && durationSeconds > 0) "${durationSeconds * 1000}" else "")
                ?.addQueryParameter("hash", "")
                ?.build() ?: return@withContext null

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .get()
                .build()

            val searchJsonString = client.newCall(searchRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string() ?: return@withContext null
            }

            val searchResult = json.decodeFromString<KugouSearchResponse>(searchJsonString)
            val candidate = searchResult.candidates.firstOrNull() ?: return@withContext null

            // 2. Download KRC encrypted content
            val downloadUrl = "http://lyrics.kugou.com/download".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("ver", "1")
                ?.addQueryParameter("client", "pc")
                ?.addQueryParameter("id", candidate.id)
                ?.addQueryParameter("accesskey", candidate.accesskey)
                ?.addQueryParameter("fmt", "krc")
                ?.addQueryParameter("charset", "utf8")
                ?.build() ?: return@withContext null

            val downloadRequest = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .get()
                .build()

            val downloadJsonString = client.newCall(downloadRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string() ?: return@withContext null
            }

            val downloadResult = json.decodeFromString<KugouDownloadResponse>(downloadJsonString)
            val rawBase64 = downloadResult.content ?: return@withContext null

            // 3. Decrypt and decompress KRC
            val decryptedKrcText = decryptKrc(rawBase64) ?: return@withContext null

            // 4. Parse KRC into LyricLine / LyricSyllable
            parseKrc(decryptedKrcText)
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptKrc(base64Content: String): String? {
        return try {
            val encBytes = Base64.decode(base64Content, Base64.DEFAULT)
            if (encBytes.size <= 4) return null

            // Skip first 4 magic bytes ('krc1'), XOR decode with key
            val xorBytes = ByteArray(encBytes.size - 4)
            for (i in 4 until encBytes.size) {
                val keyByte = krcKey[(i - 4) % krcKey.size]
                xorBytes[i - 4] = (encBytes[i].toInt() xor keyByte.toInt()).toByte()
            }

            // Decompress ZLIB payload
            val inflaterStream = InflaterInputStream(ByteArrayInputStream(xorBytes))
            val outputStream = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (inflaterStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            outputStream.toString("UTF-8")
        } catch (_: Exception) {
            null
        }
    }

    private fun parseKrc(krcText: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()

        for (rawLine in krcText.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("[")) continue

            val match = krcLineRegex.matchEntire(trimmed) ?: continue
            val lineStartMs = match.groupValues[1].toLongOrNull() ?: continue
            val lineDurationMs = match.groupValues[2].toLongOrNull() ?: 0L
            val syllablesContent = match.groupValues[3]

            val syllables = mutableListOf<LyricSyllable>()
            val syllableMatches = krcSyllableRegex.findAll(syllablesContent)

            val lineTextBuilder = StringBuilder()
            for (sylMatch in syllableMatches) {
                val offsetMs = sylMatch.groupValues[1].toLongOrNull() ?: 0L
                val durMs = sylMatch.groupValues[2].toLongOrNull() ?: 0L
                val sylText = sylMatch.groupValues[3]

                syllables.add(
                    LyricSyllable(
                        timeMs = lineStartMs + offsetMs,
                        durationMs = durMs,
                        text = sylText,
                        isBackground = false
                    )
                )
                lineTextBuilder.append(sylText)
            }

            val fullLineText = lineTextBuilder.toString().trim()
            if (fullLineText.isNotEmpty() && syllables.isNotEmpty()) {
                lines.add(
                    LyricLine(
                        timeMs = lineStartMs,
                        durationMs = lineDurationMs,
                        text = fullLineText,
                        syllables = syllables
                    )
                )
            }
        }

        return lines.sortedBy { it.timeMs }
    }
}
