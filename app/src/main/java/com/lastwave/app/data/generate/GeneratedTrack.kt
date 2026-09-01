package com.lastwave.app.data.generate

import android.net.Uri
import androidx.compose.runtime.Immutable
import com.lastwave.app.data.artwork.ArtworkNormalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * [listeners]/[playcount]/[match]/[album] are best-effort extra signals —
 * present only when the source Last.fm endpoint supplies them (not every
 * endpoint does). Used exclusively by the Recommendations engine's hidden-
 * gem / similarity / album-diversity scoring (see RecommendationEngine) —
 * port of normaliseTracks()'s extra fields in app.js.
 */
@Immutable
data class GeneratedTrack(
    val name: String,
    val artist: String,
    val artworkUrl: String?,
    val url: String = "",
    val listeners: Long? = null,
    val playcount: Long? = null,
    val match: Double? = null,
    val album: String? = null,
) {
    val key: String get() = "$name|$artist".lowercase()
}

/** Serializable form of [GeneratedTrack], used for Room-persisted playlists
 *  (JSON blob column) and Playlist screen export (CSV/M3U). */
@Serializable
@Immutable
data class StoredTrack(
    val name: String,
    val artist: String,
    val url: String = "",
    val image: String? = null,
    val album: String? = null,
)

fun GeneratedTrack.toStored() = StoredTrack(name = name, artist = artist, url = url, image = artworkUrl, album = album)
fun StoredTrack.toGenerated() = GeneratedTrack(name = name, artist = artist, artworkUrl = image, url = url, album = album)

fun GeneratedTrack.youtubeVideoIdOrNull(): String? {
    val value = url.trim()
    if (YOUTUBE_VIDEO_ID_REGEX.matches(value)) return value
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val videoId = when {
        host == "youtu.be" -> uri.pathSegments.firstOrNull()
        host == "youtube.com" || host.endsWith(".youtube.com") ->
            uri.getQueryParameter("v")
                ?: uri.pathSegments
                    .takeIf { it.firstOrNull() in setOf("embed", "shorts", "live") }
                    ?.getOrNull(1)
        else -> null
    }
    return videoId?.takeIf(YOUTUBE_VIDEO_ID_REGEX::matches)
}

private val YOUTUBE_VIDEO_ID_REGEX = Regex("[A-Za-z0-9_-]{11}")

/**
 * Last.fm's quirky "bare object instead of a 1-item array" behavior (seen
 * throughout app.js as `Array.isArray(x) ? x : (x ? [x] : [])`) shows up on
 * every endpoint Generate uses, each with a slightly different response
 * shape. Rather than hand-write a dedicated @Serializable DTO + custom list
 * serializer per endpoint, this navigates the raw JsonElement directly —
 * the same normalisation app.js's normaliseTracks() does, applied generically.
 */
object GenerateJson {

    fun asObjectList(element: JsonElement?): List<JsonObject> = when (element) {
        is JsonArray -> element.mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(element)
        else -> emptyList()
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    /** artist field varies: a bare string on some endpoints, `{name, mbid}`
     *  or `{#text}` on others — matches normaliseTracks()'s artist handling. */
    private fun JsonObject.artistName(): String {
        val a = this["artist"] ?: return ""
        return when (a) {
            is JsonPrimitive -> a.contentOrNull.orEmpty()
            is JsonObject -> a.stringOrNull("name") ?: a.stringOrNull("#text").orEmpty()
            else -> ""
        }
    }

    private fun JsonObject.bestImageUrl(): String? {
        val images = (this["image"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        fun bySize(size: String) = images.firstOrNull { it.stringOrNull("size") == size }
            ?.stringOrNull("#text")?.takeIf { ArtworkNormalizer.isRealImage(it) }
        return bySize("extralarge")
            ?: bySize("large")
            ?: bySize("medium")
            ?: images.firstOrNull { ArtworkNormalizer.isRealImage(it.stringOrNull("#text")) }?.stringOrNull("#text")
    }

    /** album field varies the same way artist does: bare string, `{#text}`,
     *  or `{title}` depending on endpoint — matches normaliseTracks(). */
    private fun JsonObject.albumName(): String? {
        val a = this["album"] ?: return null
        return when (a) {
            is JsonPrimitive -> a.contentOrNull
            is JsonObject -> a.stringOrNull("#text") ?: a.stringOrNull("title")
            else -> null
        }
    }

    /** Port of normaliseTracks(): filters entries with no name, extracts
     *  artist + best image per the shared priority/placeholder rules, plus
     *  the best-effort listeners/playcount/match/album signals used only by
     *  the Recommendations engine's scoring. */
    fun normalise(element: JsonElement?): List<GeneratedTrack> =
        asObjectList(element)
            .mapNotNull { obj ->
                val name = obj.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                GeneratedTrack(
                    name = name,
                    artist = obj.artistName(),
                    artworkUrl = obj.bestImageUrl(),
                    url = obj.stringOrNull("url").orEmpty(),
                    listeners = obj.stringOrNull("listeners")?.toLongOrNull(),
                    playcount = obj.stringOrNull("playcount")?.toLongOrNull(),
                    match = obj.stringOrNull("match")?.toDoubleOrNull(),
                    album = obj.albumName(),
                )
            }

    /** Simple name reader for endpoints like artist.getsimilar, where
     *  entries are `{name, ...}` rather than track-shaped. */
    fun namesOf(element: JsonElement?): List<String> =
        asObjectList(element).mapNotNull { it.stringOrNull("name")?.takeIf(String::isNotBlank) }
}
