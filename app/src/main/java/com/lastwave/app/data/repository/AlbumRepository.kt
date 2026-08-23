package com.lastwave.app.data.repository

import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.model.AlbumPageData
import com.lastwave.app.data.model.ArtistAlbumItem
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.network.LastFmApiService
import com.lastwave.app.data.network.LastFmAppCredentials
import com.lastwave.app.playback.PlayableTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlbumRepository @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
    private val lastFmApi: LastFmApiService,
    private val sessionPreferences: SessionPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getAlbumDetails(
        albumTitle: String,
        artistName: String = "",
        browseId: String? = null,
    ): AlbumPageData = withContext(Dispatchers.IO) {
        val cleanTitle = albumTitle.trim()
        val cleanArtist = artistName.trim()
        var targetBrowseId = browseId?.takeIf(String::isNotBlank)

        // 1. Resolve browseId if missing
        if (targetBrowseId == null && cleanTitle.isNotBlank()) {
            val query = if (cleanArtist.isNotBlank()) "$cleanTitle $cleanArtist" else cleanTitle
            val searchResults = runCatching { innerTube.searchAlbums(query, limit = 5) }.getOrNull().orEmpty()
            val match = searchResults.firstOrNull { it.name.equals(cleanTitle, ignoreCase = true) }
                ?: searchResults.firstOrNull()
            targetBrowseId = match?.browseId
        }

        coroutineScope {
            // Load InnerTube album data in parallel with Last.fm metadata
            val innerTubeDeferred = async {
                targetBrowseId?.let { id ->
                    runCatching {
                        innerTube.fetchAlbumPage(id, albumTitleFallback = cleanTitle, artistFallback = cleanArtist)
                    }.getOrNull()
                }
            }

            val lastFmDeferred = async {
                if (cleanTitle.isNotBlank() && cleanArtist.isNotBlank()) {
                    runCatching { fetchLastFmAlbumInfo(cleanTitle, cleanArtist) }.getOrNull()
                } else null
            }

            val ytData = innerTubeDeferred.await()
            val lfmData = lastFmDeferred.await()

            val finalTitle = ytData?.title?.takeIf(String::isNotBlank) ?: cleanTitle.ifBlank { "Album" }
            val finalArtist = ytData?.artist?.takeIf(String::isNotBlank) ?: cleanArtist.ifBlank { "Various Artists" }
            val artwork = ytData?.artworkUrl ?: lfmData?.artworkUrl
            val description = ytData?.description?.takeIf(String::isNotBlank) ?: lfmData?.description
            val genres = lfmData?.tags.orEmpty()
            val releaseYear = ytData?.releaseYear ?: lfmData?.releaseYear

            var tracks = ytData?.tracks.orEmpty()

            // Fallback: If InnerTube returned no tracks, search tracks by album and artist
            if (tracks.isEmpty() && finalTitle.isNotBlank()) {
                val songs = runCatching {
                    innerTube.searchSongs("$finalTitle $finalArtist", limit = 20)
                }.getOrDefault(emptyList())

                tracks = songs.map { track ->
                    PlayableTrack(
                        title = track.title,
                        artist = track.artist.takeUnless { it == "Unknown artist" } ?: finalArtist,
                        album = finalTitle,
                        artworkUrl = track.artworkUrl ?: artwork,
                        videoId = track.videoId,
                    )
                }
            }

            AlbumPageData(
                title = finalTitle,
                artist = finalArtist,
                artistBrowseId = ytData?.artistBrowseId,
                browseId = targetBrowseId.orEmpty(),
                artworkUrl = artwork,
                releaseYear = releaseYear,
                trackCountText = ytData?.trackCountText ?: "${tracks.size} songs",
                durationText = ytData?.durationText,
                description = description,
                genres = genres,
                tracks = tracks,
                otherAlbums = ytData?.otherAlbums.orEmpty(),
            )
        }
    }

    private data class LastFmAlbumMeta(
        val description: String? = null,
        val artworkUrl: String? = null,
        val releaseYear: String? = null,
        val tags: List<String> = emptyList(),
    )

    private suspend fun fetchLastFmAlbumInfo(albumTitle: String, artistName: String): LastFmAlbumMeta? {
        val session = runCatching { sessionPreferences.session.first() }.getOrNull()
        val apiKey = session?.apiKey?.takeIf(String::isNotBlank) ?: LastFmAppCredentials.API_KEY
        if (apiKey.isBlank()) return null

        val response = lastFmApi.get(
            mapOf(
                "method" to "album.getinfo",
                "album" to albumTitle,
                "artist" to artistName,
                "autocorrect" to "1",
                "api_key" to apiKey,
                "format" to "json",
            ),
        )
        if (!response.isSuccessful) return null
        val body = response.body()?.string().orEmpty()
        val albumObj = json.parseToJsonElement(body).jsonObject["album"]?.jsonObject ?: return null

        val desc = albumObj["wiki"]?.jsonObject?.get("summary")?.jsonPrimitive?.contentOrNull
            ?.replace(Regex("<a\\b[^>]*>.*?</a>", RegexOption.IGNORE_CASE), "")
            ?.trim()?.takeIf(String::isNotBlank)

        val published = albumObj["wiki"]?.jsonObject?.get("published")?.jsonPrimitive?.contentOrNull
        val releaseYear = published?.let { Regex("(19|20)\\d{2}").find(it)?.value }

        val tags = albumObj["tags"]?.jsonObject?.get("tag")?.jsonArray?.mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
        }.orEmpty()

        val images = albumObj["image"]?.jsonArray?.mapNotNull { it.jsonObject }
        val artworkUrl = images?.lastOrNull {
            it["#text"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
        }?.get("#text")?.jsonPrimitive?.contentOrNull

        return LastFmAlbumMeta(
            description = desc,
            artworkUrl = artworkUrl,
            releaseYear = releaseYear,
            tags = tags,
        )
    }
}
