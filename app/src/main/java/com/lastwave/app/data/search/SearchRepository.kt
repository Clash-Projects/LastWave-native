package com.lastwave.app.data.search

import androidx.compose.runtime.Immutable
import com.lastwave.app.data.generate.GenerateJson
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.network.LastFmAppCredentials
import com.lastwave.app.data.network.LastFmApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import com.lastwave.app.data.music.YOUTUBE_WEB_USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
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
    private val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()
        val url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=${URLEncoder.encode(trimmed, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", YOUTUBE_WEB_USER_AGENT)
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val array = json.parseToJsonElement(body) as? JsonArray ?: return@withContext emptyList()
                if (array.size < 2) return@withContext emptyList()
                val suggestions = array[1] as? JsonArray ?: return@withContext emptyList()
                suggestions.mapNotNull { (it as? JsonPrimitive)?.content }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun search(tab: SearchTab, query: String): List<SearchResultItem> = withContext(Dispatchers.Default) {
        if (query.isBlank()) return@withContext emptyList()
        when (tab) {
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
                val key = sessionPreferences.session.first().apiKey.ifBlank { com.lastwave.app.data.network.LastFmAppCredentials.API_KEY }
                lookupUser(key, query)
            }
        }.filter { it.name.isNotBlank() }
    }

    /** Returns the native-playable tracks behind a YT Music artist/album result. */
    suspend fun songsFor(item: SearchResultItem): List<YouTubeMusicTrack> =
        item.entityId?.takeIf(String::isNotBlank)?.let { innerTube.browseSongs(it) }.orEmpty()

    /**
     * Builds playback continuation for a selected search result without
     * changing the visible search results. Similar tracks come from Last.fm;
     * an artist search is only a fallback when that graph is unavailable.
     * Titles are deliberately unique so covers/remakes of the searched song
     * cannot fill the queue.
     */
    suspend fun similarSongsFor(item: SearchResultItem, limit: Int = 25): List<GeneratedTrack> =
        withContext(Dispatchers.IO) {
            val seedTitle = item.name.trim()
            val seedArtist = item.artist.orEmpty().trim()
            if (seedTitle.isBlank() || seedArtist.isBlank()) return@withContext emptyList()

            val similar = try {
                val apiKey = sessionPreferences.session.first().apiKey.ifBlank { LastFmAppCredentials.API_KEY }
                val response = api.get(
                    mapOf(
                        "method" to "track.getsimilar",
                        "track" to seedTitle,
                        "artist" to seedArtist,
                        "limit" to (limit * 3).coerceAtMost(100).toString(),
                        "api_key" to apiKey,
                        "format" to "json",
                        "autocorrect" to "1",
                    ),
                )
                val body = response.body()?.string()
                if (!response.isSuccessful || body.isNullOrBlank()) {
                    emptyList()
                } else {
                    val root = json.parseToJsonElement(body).jsonObject
                    GenerateJson.normalise(root["similartracks"]?.jsonObject?.get("track"))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }

            val candidates = if (similar.isNotEmpty()) {
                similar
            } else {
                try {
                    innerTube.searchSongs("$seedArtist songs", limit = limit * 2).map { track ->
                        GeneratedTrack(
                            name = track.title,
                            artist = track.artist,
                            artworkUrl = track.artworkUrl,
                            album = track.album,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptyList()
                }
            }

            val seedTitleKey = queueTitleKey(seedTitle)
            val seenTitles = mutableSetOf(seedTitleKey)
            val artistCounts = mutableMapOf<String, Int>()
            candidates.filter { candidate ->
                val titleKey = queueTitleKey(candidate.name)
                val artistKey = candidate.artist.trim().lowercase()
                val artistCount = artistCounts[artistKey] ?: 0
                val keep = titleKey.isNotBlank() &&
                    candidate.artist.isNotBlank() &&
                    artistCount < 3 &&
                    seenTitles.add(titleKey)
                if (keep) artistCounts[artistKey] = artistCount + 1
                keep
            }.take(limit)
        }

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

    private fun queueTitleKey(title: String): String = title
        .lowercase()
        .replace(TITLE_VARIANT, " ")
        .replace(NON_TITLE_CHARACTER, "")

    private companion object {
        val TITLE_VARIANT = Regex(
            """\s*[\[(][^)\]]*\b(?:official|video|audio|lyrics?|cover|karaoke|remaster(?:ed)?|live|version|edit|mix|slowed|reverb)[^)\]]*[])]""",
            RegexOption.IGNORE_CASE,
        )
        val NON_TITLE_CHARACTER = Regex("[^\\p{L}\\p{N}]+")
    }
}
