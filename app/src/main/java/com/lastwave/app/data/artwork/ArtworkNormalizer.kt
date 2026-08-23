package com.lastwave.app.data.artwork

import com.lastwave.app.data.model.ImageDto

/**
 * Faithful port of app.js's image-selection helpers (_isRealImg /
 * _isRealHomeImg and the repeated
 * `find(extralarge) || find(large) || find(medium) || find(any)` chain used
 * everywhere a Last.fm image array is read). Kept as free functions, not a
 * class, because the original has no state here — just filtering.
 */
object ArtworkNormalizer {

    /** The exact hash Last.fm embeds in its own gray "no artwork" placeholder
     *  image. Last.fm returns this as a real, non-blank URL, so a blank
     *  check alone isn't enough to tell real art from a placeholder — this
     *  is the check app.js's _isRealImg() does before trusting any image URL. */
    private const val LASTFM_NO_ART_HASH = "2a96cbd8b46e442fc41c2b86b821562f"

    fun isRealImage(url: String?): Boolean =
        !url.isNullOrBlank() && !url.contains(LASTFM_NO_ART_HASH)

    /** extralarge > large > medium > (any remaining real image) — the exact
     *  priority order used at every image-array read site in app.js/home.js. */
    fun bestImageUrl(images: List<ImageDto>): String? {
        val bySize = { size: String -> images.firstOrNull { it.size == size && isRealImage(it.url) }?.url }
        return bySize("extralarge")
            ?: bySize("large")
            ?: bySize("medium")
            ?: images.firstOrNull { isRealImage(it.url) }?.url
    }

    /** `t:${name}|${artist}`.toLowerCase() — the exact cache key format
     *  _resolveTrackArt() uses. No punctuation/feat./remaster stripping:
     *  the original app doesn't do any of that, so neither does this. */
    fun cacheKey(name: String, artist: String): String = "t:${name}|${artist}".lowercase()

    private val FEAT_REGEX = Regex("(?i)\\s*[(|\\[](feat|ft|with|featuring)\\.?\\s+.*?[)|\\]]")
    private val REMASTER_REGEX = Regex("(?i)\\s*[(|\\[].*?(remaster|live|version|edit|mono|stereo|deluxe|bonus).*?[)|\\]]")

    fun cleanTitle(title: String): String = title
        .replace(FEAT_REGEX, "")
        .replace(REMASTER_REGEX, "")
        .trim()

    fun cleanArtist(artist: String): String = artist
        .replace(Regex("(?i)\\s*(feat|ft|with|&|,|/|x)\\s+.*"), "")
        .trim()
}
