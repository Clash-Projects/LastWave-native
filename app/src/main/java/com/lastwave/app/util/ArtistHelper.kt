package com.lastwave.app.util

/**
 * Utility for splitting and handling composite multi-artist strings cleanly.
 * Handles separators such as commas, ampersands, slashes, "feat.", "ft.", "with", and "x".
 */
object ArtistHelper {
    private val SEPARATOR_REGEX = Regex(
        "(?:\\s*,\\s*|\\s+&\\s+|\\s*\\/\\s*|\\s+(?:ft\\.?|feat\\.?|featuring|with|x|X)\\s+)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Splits multi-artist strings (e.g. "Arijit Singh, Badshah", "Alan Walker feat. Au/Ra", "Drake & 21 Savage")
     * into clean, individual artist names.
     */
    fun splitArtists(rawArtist: String?): List<String> {
        if (rawArtist.isNullOrBlank()) return emptyList()
        val trimmed = rawArtist.trim()
        val parts = trimmed.split(SEPARATOR_REGEX)
            .map { it.trim().trim(',', '&', '/', ';').trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return if (parts.isNotEmpty()) parts else listOf(trimmed)
    }

    /**
     * Returns the primary / first artist from a composite string.
     */
    fun primaryArtist(rawArtist: String?): String {
        return splitArtists(rawArtist).firstOrNull() ?: rawArtist.orEmpty()
    }
}
