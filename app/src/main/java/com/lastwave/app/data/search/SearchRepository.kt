package com.lastwave.app.data.search

import androidx.compose.runtime.Immutable
import com.lastwave.app.data.generate.GenerateJson
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.network.LastFmApiService
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

enum class SearchTab { TRACKS, ARTISTS, ALBUMS, USERS }

@Immutable
data class SearchResultItem(
    val name: String,
    val artist: String? = null,
    val url: String = "",
    val listeners: String? = null,
    val artworkUrl: String? = null,
    val subtitle: String? = null,
    val videoId: String? = null,
    val entityId: String? = null,
)

/**
 * Account-free YouTube Music search for songs, artists and albums. Last.fm
 * is used only for its explicitly labelled exact-user lookup tab.
 */
@Singleton
class SearchRepository @Inject constructor(
    private val api: LastFmApiService,
    private val sessionPreferences: SessionPreferences,
    private val innerTube: InnerTubeMusicApi,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(tab: SearchTab, query: String): List<SearchResultItem> {
        if (query.isBlank()) return emptyList()
        return when (tab) {
            SearchTab.TRACKS -> innerTube.searchSongs(query).map { track ->
                SearchResultItem(
                    name = track.title,
                    artist = track.artist,
                    artworkUrl = track.artworkUrl,
                    subtitle = track.album,
                    videoId = track.videoId,
                    entityId = track.videoId,
                )
            }
            SearchTab.ARTISTS -> innerTube.searchArtists(query).map { artist ->
                SearchResultItem(
                    name = artist.name,
                    artworkUrl = artist.artworkUrl,
                    subtitle = artist.subtitle,
                    entityId = artist.browseId,
                )
            }
            SearchTab.ALBUMS -> innerTube.searchAlbums(query).map { album ->
                SearchResultItem(
                    name = album.name,
                    artist = album.artist,
                    artworkUrl = album.artworkUrl,
                    subtitle = album.subtitle,
                    entityId = album.browseId,
                )
            }
            SearchTab.USERS -> {
                val key = sessionPreferences.session.first().apiKey
                if (key.isBlank()) emptyList() else lookupUser(key, query)
            }
        }.filter { it.name.isNotBlank() }
    }

    /** Returns the native-playable tracks behind a YT Music artist/album result. */
    suspend fun songsFor(item: SearchResultItem): List<YouTubeMusicTrack> =
        item.entityId?.takeIf(String::isNotBlank)?.let { innerTube.browseSongs(it) }.orEmpty()

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
        val user = json.parseToJsonElement(body).jsonObject["user"]?.jsonObject ?: return emptyList()
        val name = (user["name"] as? JsonPrimitive)?.content.orEmpty()
        if (name.isBlank()) return emptyList()
        val images = user["image"]?.let { GenerateJson.asObjectList(it) }.orEmpty()
        val avatarUrl = images.lastOrNull {
            (it["#text"] as? JsonPrimitive)?.content?.isNotBlank() == true
        }?.get("#text")?.let { (it as? JsonPrimitive)?.content }
        val realName = (user["realname"] as? JsonPrimitive)?.content
        val playcount = (user["playcount"] as? JsonPrimitive)?.content
        return listOf(
            SearchResultItem(
                name = name,
                artist = realName?.takeIf(String::isNotBlank),
                url = (user["url"] as? JsonPrimitive)?.content.orEmpty(),
                listeners = playcount,
                artworkUrl = avatarUrl,
            ),
        )
    }
}
