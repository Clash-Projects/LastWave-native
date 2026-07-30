package com.lastwave.app.data.search

import com.lastwave.app.data.artwork.ArtworkNormalizer
import com.lastwave.app.data.generate.GenerateJson
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.network.LastFmApiService
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

import androidx.compose.runtime.Immutable

enum class SearchTab { TRACKS, ARTISTS, ALBUMS }

@Immutable
data class SearchResultItem(
    val name: String,
    val artist: String? = null,
    val url: String = "",
    val listeners: String? = null,
    val artworkUrl: String? = null,
)

/**
 * Faithful port of search.js (§6): direct, uncached Last.fm search calls
 * (deliberately not routed through GenerateRepository's cached call path —
 * search results should always be fresh) across track/artist/album.search,
 * 30-result cap each.
 */
@Singleton
class SearchRepository @Inject constructor(
    private val api: LastFmApiService,
    private val sessionPreferences: SessionPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun apiKey(): String = sessionPreferences.session.first().apiKey

    suspend fun search(tab: SearchTab, query: String): List<SearchResultItem> {
        val key = apiKey()
        if (key.isBlank() || query.isBlank()) return emptyList()

        val method = when (tab) {
            SearchTab.TRACKS -> "track.search"
            SearchTab.ARTISTS -> "artist.search"
            SearchTab.ALBUMS -> "album.search"
        }
        val paramKey = when (tab) {
            SearchTab.TRACKS -> "track"
            SearchTab.ARTISTS -> "artist"
            SearchTab.ALBUMS -> "album"
        }

        val response = api.get(
            mapOf(
                "method" to method,
                paramKey to query,
                "limit" to "30",
                "api_key" to key,
                "format" to "json",
            ),
        )
        val body = response.body()?.string() ?: return emptyList()
        if (!response.isSuccessful) return emptyList()
        val parsed = json.parseToJsonElement(body).jsonObject
        val results = parsed["results"]?.jsonObject ?: return emptyList()

        return when (tab) {
            SearchTab.TRACKS -> {
                val matches = results["trackmatches"]?.jsonObject?.get("track")
                GenerateJson.asObjectList(matches).map { obj ->
                    SearchResultItem(
                        name = (obj["name"] as? JsonPrimitive)?.content.orEmpty(),
                        artist = (obj["artist"] as? JsonPrimitive)?.content.orEmpty(),
                        url = (obj["url"] as? JsonPrimitive)?.content.orEmpty(),
                        listeners = (obj["listeners"] as? JsonPrimitive)?.content,
                    )
                }
            }
            SearchTab.ARTISTS -> {
                val matches = results["artistmatches"]?.jsonObject?.get("artist")
                GenerateJson.asObjectList(matches).map { obj ->
                    SearchResultItem(
                        name = (obj["name"] as? JsonPrimitive)?.content.orEmpty(),
                        url = (obj["url"] as? JsonPrimitive)?.content.orEmpty(),
                        listeners = (obj["listeners"] as? JsonPrimitive)?.content,
                    )
                }
            }
            SearchTab.ALBUMS -> {
                val matches = results["albummatches"]?.jsonObject?.get("album")
                GenerateJson.asObjectList(matches).map { obj ->
                    SearchResultItem(
                        name = (obj["name"] as? JsonPrimitive)?.content.orEmpty(),
                        artist = (obj["artist"] as? JsonPrimitive)?.content.orEmpty(),
                        url = (obj["url"] as? JsonPrimitive)?.content.orEmpty(),
                    )
                }
            }
        }.filter { it.name.isNotBlank() }
    }
}
