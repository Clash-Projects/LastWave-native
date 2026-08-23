package com.lastwave.app.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class LyricLine(
    val timeMs: Long,
    val text: String,
)

sealed interface LyricsResult {
    data class Success(
        val lines: List<LyricLine>,
        val isSynced: Boolean,
        val plainLyrics: String? = null,
        val isInstrumental: Boolean = false,
    ) : LyricsResult

    data object Empty : LyricsResult
    data class Error(val message: String) : LyricsResult
}

@Singleton
class LyricsRepository @Inject constructor(
    private val lrclibApi: LrclibLyricsApi,
) {
    private val cache = ConcurrentHashMap<String, LyricsResult>()

    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
        forceRefresh: Boolean = false,
    ): LyricsResult = withContext(Dispatchers.Default) {
        val cacheKey = "${artist.trim().lowercase()}|${title.trim().lowercase()}"
        if (!forceRefresh) {
            cache[cacheKey]?.let { return@withContext it }
        }

        val record = try {
            lrclibApi.fetchLyrics(title, artist, album, durationSeconds)
        } catch (e: Exception) {
            return@withContext LyricsResult.Error(e.localizedMessage ?: "Failed to load lyrics")
        }

        if (record == null) {
            val empty = LyricsResult.Empty
            cache[cacheKey] = empty
            return@withContext empty
        }

        if (record.instrumental == true) {
            val result = LyricsResult.Success(
                lines = emptyList(),
                isSynced = false,
                plainLyrics = null,
                isInstrumental = true,
            )
            cache[cacheKey] = result
            return@withContext result
        }

        val synced = record.syncedLyrics
        if (!synced.isNullOrBlank()) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) {
                val result = LyricsResult.Success(
                    lines = lines,
                    isSynced = true,
                    plainLyrics = record.plainLyrics,
                    isInstrumental = false,
                )
                cache[cacheKey] = result
                return@withContext result
            }
        }

        val plain = record.plainLyrics
        if (!plain.isNullOrBlank()) {
            val result = LyricsResult.Success(
                lines = emptyList(),
                isSynced = false,
                plainLyrics = plain.trim(),
                isInstrumental = false,
            )
            cache[cacheKey] = result
            return@withContext result
        }

        val empty = LyricsResult.Empty
        cache[cacheKey] = empty
        empty
    }

    companion object {
        private val TIMESTAMP_REGEX = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{2,3}))?\]""")

        fun parseLrc(lrcContent: String): List<LyricLine> {
            val result = mutableListOf<LyricLine>()
            val lines = lrcContent.lines()

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                // Check if line contains timestamp(s)
                val matches = TIMESTAMP_REGEX.findAll(trimmed).toList()
                if (matches.isEmpty()) continue

                // Extract text after all timestamps
                val text = trimmed.replace(TIMESTAMP_REGEX, "").trim()

                for (match in matches) {
                    val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                    val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                    val fractionStr = match.groupValues.getOrNull(3).orEmpty()
                    val fractionMs = when (fractionStr.length) {
                        2 -> (fractionStr.toLongOrNull() ?: 0L) * 10
                        3 -> fractionStr.toLongOrNull() ?: 0L
                        1 -> (fractionStr.toLongOrNull() ?: 0L) * 100
                        else -> 0L
                    }

                    val totalMs = (minutes * 60 * 1000) + (seconds * 1000) + fractionMs
                    result.add(LyricLine(timeMs = totalMs, text = text))
                }
            }

            return result.sortedBy { it.timeMs }
        }
    }
}
