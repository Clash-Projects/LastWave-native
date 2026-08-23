package com.lastwave.app.data.playlist

import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.music.InnerTubeMusicApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

data class CsvRawTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
)

data class CsvImportResult(
    val suggestedTitle: String,
    val totalRows: Int,
    val matchedCount: Int,
    val tracks: List<GeneratedTrack>,
)

@Singleton
class CsvPlaylistImporter @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
) {

    /**
     * Parses raw CSV text (Spotify, Soundiiz, TuneMyMusic, Apple Music, or generic)
     * and performs strict, anti-hallucination track matching.
     */
    suspend fun parseAndMatchCsv(
        inputStream: InputStream,
        filename: String = "Imported Playlist",
    ): CsvImportResult = withContext(Dispatchers.IO) {
        val rawTracks = parseCsv(inputStream)
        if (rawTracks.isEmpty()) {
            return@withContext CsvImportResult(
                suggestedTitle = cleanPlaylistTitle(filename),
                totalRows = 0,
                matchedCount = 0,
                tracks = emptyList(),
            )
        }

        val matchedTracks = mutableListOf<GeneratedTrack>()
        var verifiedCount = 0

        for (raw in rawTracks) {
            if (raw.title.isBlank()) continue

            // 1. Strict match search
            val match = runCatching { innerTube.findBestMatch(raw.title, raw.artist) }.getOrNull()

            // 2. High-precision similarity scoring to avoid false positives
            val isAccurate = if (match != null && match.videoId.isNotBlank()) {
                val score = calculateMatchConfidence(
                    sourceTitle = raw.title,
                    sourceArtist = raw.artist,
                    targetTitle = match.title,
                    targetArtist = match.artist,
                )
                score >= 70
            } else false

            val finalTrack = if (isAccurate && match != null) {
                verifiedCount++
                GeneratedTrack(
                    name = raw.title.trim(),
                    artist = raw.artist.trim(),
                    album = raw.album?.trim()?.ifBlank { match.album },
                    artworkUrl = match.artworkUrl,
                )
            } else {
                // Never attach a wrong song: retain original metadata with clean state
                GeneratedTrack(
                    name = raw.title.trim(),
                    artist = raw.artist.trim().ifBlank { "Unknown Artist" },
                    album = raw.album?.trim(),
                    artworkUrl = null,
                )
            }

            matchedTracks.add(finalTrack)
        }

        CsvImportResult(
            suggestedTitle = cleanPlaylistTitle(filename),
            totalRows = rawTracks.size,
            matchedCount = verifiedCount,
            tracks = matchedTracks,
        )
    }

    private fun parseCsv(inputStream: InputStream): List<CsvRawTrack> {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val lines = reader.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val delimiter = detectDelimiter(lines.first())
        val headerTokens = parseCsvLine(lines.first(), delimiter).map { it.trim().lowercase() }

        val titleIdx = headerTokens.indexOfFirst { it in TITLE_HEADERS }
        val artistIdx = headerTokens.indexOfFirst { it in ARTIST_HEADERS }
        val albumIdx = headerTokens.indexOfFirst { it in ALBUM_HEADERS }

        val actualTitleIdx = if (titleIdx >= 0) titleIdx else 0
        val actualArtistIdx = if (artistIdx >= 0) artistIdx else if (headerTokens.size > 1) 1 else -1

        val result = mutableListOf<CsvRawTrack>()
        val startRow = if (titleIdx >= 0 || artistIdx >= 0) 1 else 0

        for (i in startRow until lines.size) {
            val tokens = parseCsvLine(lines[i], delimiter)
            if (tokens.isEmpty()) continue

            val title = tokens.getOrNull(actualTitleIdx)?.trim().orEmpty()
            val artist = if (actualArtistIdx >= 0) tokens.getOrNull(actualArtistIdx)?.trim().orEmpty() else ""
            val album = if (albumIdx >= 0) tokens.getOrNull(albumIdx)?.trim() else null

            if (title.isNotBlank() && !title.equals("track name", ignoreCase = true) && !title.equals("title", ignoreCase = true)) {
                result.add(CsvRawTrack(title = cleanTrackTitle(title), artist = cleanArtistName(artist), album = album))
            }
        }

        return result
    }

    private fun detectDelimiter(headerLine: String): Char {
        val commas = headerLine.count { it == ',' }
        val semicolons = headerLine.count { it == ';' }
        val tabs = headerLine.count { it == '\t' }
        return when {
            semicolons > commas && semicolons > tabs -> ';'
            tabs > commas && tabs > semicolons -> '\t'
            else -> ','
        }
    }

    private fun parseCsvLine(line: String, delimiter: Char): List<String> {
        val tokens = mutableListOf<String>()
        val sb = java.lang.StringBuilder()
        var inQuotes = false

        for (c in line) {
            when {
                c == '\"' -> inQuotes = !inQuotes
                c == delimiter && !inQuotes -> {
                    tokens.add(sb.toString().trim())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
        }
        tokens.add(sb.toString().trim())
        return tokens
    }

    private fun calculateMatchConfidence(
        sourceTitle: String,
        sourceArtist: String,
        targetTitle: String,
        targetArtist: String,
    ): Int {
        val sTitle = normalize(cleanTrackTitle(sourceTitle))
        val tTitle = normalize(cleanTrackTitle(targetTitle))
        val sArtist = normalize(cleanArtistName(sourceArtist))
        val tArtist = normalize(cleanArtistName(targetArtist))

        if (sTitle == tTitle && (sArtist.isBlank() || sArtist == tArtist || tArtist.contains(sArtist) || sArtist.contains(tArtist))) {
            return 100
        }

        val titleSim = tokenSimilarity(sTitle, tTitle)
        val artistSim = if (sArtist.isNotBlank() && tArtist.isNotBlank()) tokenSimilarity(sArtist, tArtist) else 75

        return (titleSim * 0.65 + artistSim * 0.35).toInt()
    }

    private fun tokenSimilarity(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        if (a == b || a.contains(b) || b.contains(a)) return 90
        val setA = a.split(" ").filter { it.length > 1 }.toSet()
        val setB = b.split(" ").filter { it.length > 1 }.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0
        val intersection = setA.intersect(setB).size
        return ((2.0 * intersection) / (setA.size + setB.size) * 100).toInt()
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun cleanTrackTitle(title: String): String =
        title.replace(Regex("(?i)\\s*[\\[(](official\\s*(video|audio|music\\s*video)|remastered|extended|lyric\\s*video|hd|hq|4k)[\\])]"), "")
            .replace(Regex("(?i)\\s*-\\s*(remastered|extended|live).*"), "")
            .trim()

    private fun cleanArtistName(artist: String): String =
        artist.replace(Regex("(?i)\\s*(feat\\.|ft\\.|featuring).*"), "").trim()

    private fun cleanPlaylistTitle(filename: String): String =
        filename.substringBeforeLast('.')
            .replace(Regex("[_\\-]"), " ")
            .trim()
            .ifBlank { "Imported Playlist" }

    companion object {
        private val TITLE_HEADERS = setOf("track name", "title", "song", "name", "track", "song title", "track title")
        private val ARTIST_HEADERS = setOf("artist name(s)", "artist", "artists", "artist name", "performer", "author", "creator")
        private val ALBUM_HEADERS = setOf("album name", "album", "release")
    }
}
