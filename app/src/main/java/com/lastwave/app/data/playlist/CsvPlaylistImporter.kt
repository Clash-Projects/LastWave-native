package com.lastwave.app.data.playlist

import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.music.InnerTubeMusicApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
     * Parses raw CSV or M3U/M3U8 playlist text (Spotify, Soundiiz, TuneMyMusic, Apple Music,
     * VLC, or generic) and performs strict, anti-hallucination track matching.
     */
    suspend fun parseAndMatchCsv(
        inputStream: InputStream,
        filename: String = "Imported Playlist",
    ): CsvImportResult = withContext(Dispatchers.IO) {
        val isM3u = filename.endsWith(".m3u", ignoreCase = true) || filename.endsWith(".m3u8", ignoreCase = true)
        val rawTracks = runCatching {
            if (isM3u) parseM3u(inputStream) else parseCsv(inputStream)
        }.getOrDefault(emptyList())

        if (rawTracks.isEmpty()) {
            return@withContext CsvImportResult(
                suggestedTitle = cleanPlaylistTitle(filename),
                totalRows = 0,
                matchedCount = 0,
                tracks = emptyList(),
            )
        }

        // Bounded parallel matching; results re-ordered to match CSV order so
        // imports preserve the original playlist sequence exactly.
        val resolved: MutableList<GeneratedTrack?> = MutableList(rawTracks.size) { null }
        val verified = BooleanArray(rawTracks.size)

        coroutineScope {
            val jobs = rawTracks.mapIndexed { index, raw ->
                async(Dispatchers.IO) {
                    if (raw.title.isBlank()) return@async
                    val match = runCatching { innerTube.findBestMatchOrNull(raw.title, raw.artist) }.getOrNull()

                    val isAccurate = match != null && match.videoId.isNotBlank() && calculateMatchConfidence(
                        sourceTitle = raw.title,
                        sourceArtist = raw.artist,
                        targetTitle = match.title,
                        targetArtist = match.artist,
                    ) >= 70

                    resolved[index] = if (isAccurate && match != null) {
                        verified[index] = true
                        GeneratedTrack(
                            name = raw.title.trim(),
                            artist = raw.artist.trim(),
                            album = raw.album?.trim()?.ifBlank { match.album },
                            artworkUrl = match.artworkUrl,
                        )
                    } else {
                        // Never attach a wrong song: retain original metadata
                        // so playback can still attempt its own resolution.
                        GeneratedTrack(
                            name = raw.title.trim(),
                            artist = raw.artist.trim().ifBlank { "Unknown Artist" },
                            album = raw.album?.trim(),
                            artworkUrl = null,
                        )
                    }
                }
            }
            jobs.awaitAll()
        }

        val tracks = resolved.filterNotNull()
        val verifiedCount = verified.count { it }

        CsvImportResult(
            suggestedTitle = cleanPlaylistTitle(filename),
            totalRows = rawTracks.size,
            matchedCount = verifiedCount,
            tracks = tracks,
        )
    }

    private fun parseCsv(inputStream: InputStream): List<CsvRawTrack> {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        // Strip BOM — Excel/Spotify exports start with \uFEFF which otherwise
        // corrupts the first header token ("﻿Track Name" matches nothing).
        val lines = reader.readLines()
            .map { it.trimStart('\uFEFF') }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val delimiter = detectDelimiter(lines.take(5))
        val firstTokens = parseCsvLine(lines.first(), delimiter)
        val headerTokens = firstTokens.map { cleanHeaderToken(it) }

        val titleIdx = headerTokens.indexOfFirst { isTitleHeader(it) }
        val artistIdx = headerTokens.indexOfFirst { isArtistHeader(it) }
        val albumIdx = headerTokens.indexOfFirst { isAlbumHeader(it) }
        val hasHeader = titleIdx >= 0 || artistIdx >= 0 || albumIdx >= 0

        val actualTitleIdx = if (titleIdx >= 0) titleIdx else 0
        val actualArtistIdx = when {
            artistIdx >= 0 -> artistIdx
            !hasHeader && firstTokens.size > 1 -> 1
            hasHeader && headerTokens.size > 1 -> 1
            else -> -1
        }
        val actualAlbumIdx = albumIdx

        // Only skip rows whose title literally equals one of THIS file's own
        // header tokens (repeated header rows mid-export are common in
        // concatenated exports) — never blanket-ignore real songs named
        // "Title" or "Name".
        val headerEchoes: Set<String> =
            if (hasHeader) headerTokens.filter { it.isNotBlank() }.toSet() else emptySet()
        val startRow = if (hasHeader) 1 else 0

        val result = mutableListOf<CsvRawTrack>()
        for (i in startRow until lines.size) {
            val tokens = parseCsvLine(lines[i], delimiter)
            if (tokens.isEmpty() || tokens.all { it.isBlank() }) continue

            val rawTitle = tokens.getOrNull(actualTitleIdx)?.trim().orEmpty()
            if (rawTitle.isBlank()) continue
            if (cleanHeaderToken(rawTitle) in headerEchoes) continue

            val rawArtist = if (actualArtistIdx >= 0) tokens.getOrNull(actualArtistIdx)?.trim().orEmpty() else ""
            val album = if (actualAlbumIdx >= 0) tokens.getOrNull(actualAlbumIdx)?.trim()?.takeIf(String::isNotBlank) else null

            result.add(
                CsvRawTrack(
                    title = cleanTrackTitle(rawTitle),
                    artist = cleanArtistName(rawArtist),
                    album = album,
                ),
            )
        }

        return result
    }

    /**
     * Robust M3U and M3U8 extended playlist parser.
     * Extracts #EXTINF metadata (seconds, artist, title) or falls back to
     * clean audio file path / URL parsing.
     */
    private fun parseM3u(inputStream: InputStream): List<CsvRawTrack> {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val lines = reader.readLines().map { it.trimStart('\uFEFF').trim() }.filter { it.isNotBlank() }
        val result = mutableListOf<CsvRawTrack>()
        var lastExtInfTitle: String? = null
        var lastExtInfArtist: String? = null

        for (line in lines) {
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val info = line.substringAfter("#EXTINF:", "").trim()
                val commaIndex = info.indexOf(',')
                val display = if (commaIndex >= 0) info.substring(commaIndex + 1).trim() else info

                // Check for artist="...", title="..." attributes
                val attrArtist = Regex("""artist="([^"]+)"""", RegexOption.IGNORE_CASE).find(info)?.groupValues?.get(1)
                val attrTitle = Regex("""title="([^"]+)"""", RegexOption.IGNORE_CASE).find(info)?.groupValues?.get(1)

                if (!attrArtist.isNullOrBlank() && !attrTitle.isNullOrBlank()) {
                    lastExtInfArtist = attrArtist.trim()
                    lastExtInfTitle = attrTitle.trim()
                } else if (display.contains(" - ")) {
                    val parts = display.split(" - ", limit = 2)
                    lastExtInfArtist = parts[0].trim()
                    lastExtInfTitle = parts[1].trim()
                } else if (display.contains(" – ")) {
                    val parts = display.split(" – ", limit = 2)
                    lastExtInfArtist = parts[0].trim()
                    lastExtInfTitle = parts[1].trim()
                } else {
                    lastExtInfArtist = ""
                    lastExtInfTitle = display.trim()
                }
            } else if (line.startsWith("#")) {
                // Ignore other directives (#EXTM3U, #EXTVLCOPT, etc.)
                continue
            } else {
                val title: String
                val artist: String

                if (!lastExtInfTitle.isNullOrBlank()) {
                    title = lastExtInfTitle
                    artist = lastExtInfArtist.orEmpty()
                } else {
                    val fileName = line.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
                    val cleaned = fileName.replace(Regex("""^\d+[\s\.\-_]+"""), "")
                    if (cleaned.contains(" - ")) {
                        val parts = cleaned.split(" - ", limit = 2)
                        artist = parts[0].trim()
                        title = parts[1].trim()
                    } else if (cleaned.contains(" – ")) {
                        val parts = cleaned.split(" – ", limit = 2)
                        artist = parts[0].trim()
                        title = parts[1].trim()
                    } else {
                        artist = ""
                        title = cleaned.trim()
                    }
                }

                if (title.isNotBlank()) {
                    result.add(
                        CsvRawTrack(
                            title = cleanTrackTitle(title),
                            artist = cleanArtistName(artist),
                            album = null,
                        ),
                    )
                }

                lastExtInfTitle = null
                lastExtInfArtist = null
            }
        }
        return result
    }

    /** Picks the delimiter that appears most consistently across sample lines. */
    private fun detectDelimiter(sampleLines: List<String>): Char {
        data class Counts(val comma: Int, val semicolon: Int, val tab: Int)

        val totals = sampleLines.fold(Counts(0, 0, 0)) { acc, line ->
            Counts(
                acc.comma + line.count { it == ',' },
                acc.semicolon + line.count { it == ';' },
                acc.tab + line.count { it == '\t' },
            )
        }
        return when {
            totals.semicolon > totals.comma && totals.semicolon > totals.tab -> ';'
            totals.tab > totals.comma && totals.tab > totals.semicolon -> '\t'
            else -> ','
        }
    }

    private fun cleanHeaderToken(value: String): String =
        value.trim().lowercase().removePrefix("\"").removeSuffix("\"").trim()

    private fun isTitleHeader(token: String): Boolean =
        token in TITLE_HEADERS ||
            token.contains("track name") || token.contains("song title") ||
            token.contains("track title") || token == "track" || token == "title" ||
            token == "song" || token == "name"

    private fun isArtistHeader(token: String): Boolean =
        token in ARTIST_HEADERS ||
            token.contains("artist") || token.contains("performer") ||
            token == "author" || token == "creator"

    private fun isAlbumHeader(token: String): Boolean =
        token in ALBUM_HEADERS || token.startsWith("album")

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
