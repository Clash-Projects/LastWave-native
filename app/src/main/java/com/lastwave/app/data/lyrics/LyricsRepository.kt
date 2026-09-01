package com.lastwave.app.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class LyricSyllable(
    val timeMs: Long,
    val durationMs: Long,
    val text: String,
    val isBackground: Boolean = false,
)

data class LyricLine(
    val timeMs: Long,
    val durationMs: Long = 0L,
    val text: String,
    val syllables: List<LyricSyllable> = emptyList(),
    val transliteration: String? = null,
    val transliterationSyllables: List<LyricSyllable> = emptyList(),
) {
    val hasSyllables: Boolean get() = syllables.isNotEmpty()
}

sealed interface LyricsResult {
    data class Success(
        val lines: List<LyricLine>,
        val isSynced: Boolean,
        val isWordSynced: Boolean = false,
        val plainLyrics: String? = null,
        val isInstrumental: Boolean = false,
        val source: String? = null,
    ) : LyricsResult

    data object Empty : LyricsResult
    data class Error(val message: String) : LyricsResult
}

@Singleton
class LyricsRepository @Inject constructor(
    private val lyricsPlusApi: LyricsPlusApi,
    private val kugouApi: KugouLyricsApi,
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

        // 1. PRIMARY: Try word-by-word / syllable sync from LyricsPlus
        try {
            val wordResponse = lyricsPlusApi.fetchWordLyrics(title, artist, album, durationSeconds)
            if (wordResponse != null && !wordResponse.lyrics.isNullOrEmpty()) {
                val lines = wordResponse.lyrics.map { line ->
                    val syllables = line.syllabus?.map { syl ->
                        LyricSyllable(
                            timeMs = syl.time,
                            durationMs = syl.duration,
                            text = syl.text,
                            isBackground = syl.isBackground,
                        )
                    } ?: emptyList()

                    val transliterationSyllables = line.transliteration?.syllabus?.map { syl ->
                        LyricSyllable(
                            timeMs = syl.time,
                            durationMs = syl.duration,
                            text = syl.text,
                            isBackground = syl.isBackground,
                        )
                    } ?: emptyList()

                    LyricLine(
                        timeMs = line.time,
                        durationMs = line.duration,
                        text = line.text,
                        syllables = syllables,
                        transliteration = line.transliteration?.text,
                        transliterationSyllables = transliterationSyllables,
                    )
                }.sortedBy { it.timeMs }

                if (lines.isNotEmpty()) {
                    val hasWordTiming = lines.any { it.hasSyllables } || wordResponse.type.equals("WORD", ignoreCase = true)
                    val result = LyricsResult.Success(
                        lines = lines,
                        isSynced = true,
                        isWordSynced = hasWordTiming,
                        plainLyrics = lines.joinToString("\n") { it.text },
                        isInstrumental = false,
                        source = if (hasWordTiming) "LyricsPlus (Word-Sync)" else "LyricsPlus (Line-Sync)",
                    )
                    cache[cacheKey] = result
                    return@withContext result
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Silently fall back to secondary word-by-word provider
        }

        // 2. SECONDARY: Try Kugou KRC word-by-word / syllable sync
        try {
            val kugouLines = kugouApi.fetchWordLyrics(title, artist, durationSeconds)
            if (!kugouLines.isNullOrEmpty()) {
                val hasWordTiming = kugouLines.any { it.hasSyllables }
                val result = LyricsResult.Success(
                    lines = kugouLines,
                    isSynced = true,
                    isWordSynced = hasWordTiming,
                    plainLyrics = kugouLines.joinToString("\n") { it.text },
                    isInstrumental = false,
                    source = "Kugou KRC (Word-Sync)",
                )
                cache[cacheKey] = result
                return@withContext result
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Silently fall back to LRCLIB
        }

        // 3. TERTIARY: Fall back to LRCLIB line-by-line sync
        val lrclibRecord = try {
            lrclibApi.fetchLyrics(title, artist, album, durationSeconds)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            null
        }

        if (lrclibRecord != null) {
            if (lrclibRecord.instrumental == true) {
                val result = LyricsResult.Success(
                    lines = emptyList(),
                    isSynced = false,
                    isWordSynced = false,
                    plainLyrics = null,
                    isInstrumental = true,
                    source = "LRCLIB (Instrumental)",
                )
                cache[cacheKey] = result
                return@withContext result
            }

            val synced = lrclibRecord.syncedLyrics
            if (!synced.isNullOrBlank()) {
                val lines = parseLrc(synced)
                if (lines.isNotEmpty()) {
                    val result = LyricsResult.Success(
                        lines = lines,
                        isSynced = true,
                        isWordSynced = false,
                        plainLyrics = lrclibRecord.plainLyrics,
                        isInstrumental = false,
                        source = "LRCLIB (Line-Sync)",
                    )
                    cache[cacheKey] = result
                    return@withContext result
                }
            }

            val plain = lrclibRecord.plainLyrics
            if (!plain.isNullOrBlank()) {
                val result = LyricsResult.Success(
                    lines = emptyList(),
                    isSynced = false,
                    isWordSynced = false,
                    plainLyrics = plain.trim(),
                    isInstrumental = false,
                    source = "LRCLIB (Plain)",
                )
                cache[cacheKey] = result
                return@withContext result
            }
        }

        // 3. TERTIARY: If both fail, return Empty (no lyrics)
        val empty = LyricsResult.Empty
        cache[cacheKey] = empty
        empty
    }

    companion object {
        private val TIMESTAMP_REGEX = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{2,3}))?\]""")
        private val OFFSET_REGEX = Regex("""\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)

        fun parseLrc(lrcContent: String): List<LyricLine> {
            val result = mutableListOf<LyricLine>()
            val lines = lrcContent.lines()
            var offsetMs = 0L

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                val offsetMatch = OFFSET_REGEX.find(trimmed)
                if (offsetMatch != null) {
                    offsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                    continue
                }

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

                    val totalMs = (minutes * 60 * 1000) + (seconds * 1000) + fractionMs + offsetMs
                    result.add(
                        LyricLine(
                            timeMs = totalMs.coerceAtLeast(0L),
                            text = text,
                            syllables = emptyList(),
                        ),
                    )
                }
            }

            return result.sortedBy { it.timeMs }
        }
    }
}
