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

enum class SearchTab { TRACKS, ARTISTS, ALBUMS, USERS }

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

        // Last.fm's API has no user.search method — there's no way to look
        // up users by a partial/fuzzy name, only user.getinfo for one
        // EXACT username at a time. So this tab is a lookup, not a fuzzy
        // search: it checks whether the typed text is a real Last.fm
        // username and, if so, returns that one profile.
        if (tab == SearchTab.USERS) return lookupUser(key, query)

        val method = when (tab) {
            SearchTab.TRACKS -> "track.search"
            SearchTab.ARTISTS -> "artist.search"
            SearchTab.ALBUMS -> "album.search"
            SearchTab.USERS -> error("handled above")
        }
        val paramKey = when (tab) {
            SearchTab.TRACKS -> "track"
            SearchTab.ARTISTS -> "artist"
            SearchTab.ALBUMS -> "album"
            SearchTab.USERS -> error("handled above")
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
            SearchTab.USERS -> error("handled above")
        }.filter { it.name.isNotBlank() }
    }

    /** user.getinfo(user=exact username) — the closest thing Last.fm's API
     *  has to a user lookup. Returns an "Invalid parameters"-style error
     *  body (no "results" object at all) when the username doesn't exist,
     *  which is how a miss is distinguished from a real profile here. */
    private suspend fun lookupUser(key: String, username: String): List<SearchResultItem> {
        val response = api.get(
            mapOf(
                "method" to "user.getinfo",
                "user" to username.trim(),
                "api_key" to key,
                "format" to "json",
            ),
        )
        val body = response.body()?.string() ?: return emptyList()
        if (!response.isSuccessful) return emptyList()
        val parsed = json.parseToJsonElement(body).jsonObject
        val user = parsed["user"]?.jsonObject ?: return emptyList()
        val name = (user["name"] as? JsonPrimitive)?.content.orEmpty()
        if (name.isBlank()) return emptyList()
        val images = user["image"]?.let { GenerateJson.asObjectList(it) }.orEmpty()
        val avatarUrl = images.lastOrNull { (it["#text"] as? JsonPrimitive)?.content?.isNotBlank() == true }
            ?.get("#text")?.let { (it as? JsonPrimitive)?.content }
        val realName = (user["realname"] as? JsonPrimitive)?.content
        val playcount = (user["playcount"] as? JsonPrimitive)?.content
        return listOf(
            SearchResultItem(
                name = name,
                artist = realName?.takeIf { it.isNotBlank() },
                url = (user["url"] as? JsonPrimitive)?.content.orEmpty(),
                listeners = playcount,
                artworkUrl = avatarUrl,
            ),
        )
    }
}
