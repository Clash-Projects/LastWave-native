package com.lastwave.app.data.repository

import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.model.ArtistAlbumItem
import com.lastwave.app.data.model.ArtistPageData
import com.lastwave.app.data.model.ArtistSummaryItem
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
class ArtistRepository @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
    private val lastFmApi: LastFmApiService,
    private val sessionPreferences: SessionPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getArtistDetails(
        artistName: String,
        browseId: String? = null,
    ): ArtistPageData = withContext(Dispatchers.IO) {
        val cleanName = artistName.trim()
        var targetBrowseId = browseId?.takeIf(String::isNotBlank)

        // 1. Resolve browseId if missing
        if (targetBrowseId == null && cleanName.isNotBlank()) {
            val searchResults = runCatching { innerTube.searchArtists(cleanName, limit = 5) }.getOrNull().orEmpty()
            val match = searchResults.firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
                ?: searchResults.firstOrNull()
            targetBrowseId = match?.browseId
        }

        coroutineScope {
            // Load InnerTube artist data in parallel with Last.fm metadata
            val innerTubeDeferred = async {
                targetBrowseId?.let { id ->
                    runCatching { innerTube.fetchArtistPage(id, artistNameFallback = cleanName) }.getOrNull()
                }
            }

            val lastFmDeferred = async {
                if (cleanName.isNotBlank()) {
                    runCatching { fetchLastFmArtistInfo(cleanName) }.getOrNull()
                } else null
            }

            val ytData = innerTubeDeferred.await()
            val lfmData = lastFmDeferred.await()

            // Merge InnerTube rich playable songs & discography with Last.fm bio & tags
            val finalName = ytData?.name?.takeIf(String::isNotBlank) ?: cleanName.ifBlank { "Artist" }
            val artwork = ytData?.artworkUrl ?: lfmData?.artworkUrl
            val banner = ytData?.bannerUrl ?: artwork
            val bio = ytData?.bio?.takeIf(String::isNotBlank) ?: lfmData?.bio
            val tags = lfmData?.tags.orEmpty()
            val listeners = ytData?.subscribers ?: lfmData?.listeners

            var topSongs = ytData?.topSongs.orEmpty()

            // Fallback: If InnerTube returned no songs, search songs by artist
            if (topSongs.isEmpty() && finalName.isNotBlank()) {
                val songs = runCatching { innerTube.searchSongs(finalName, limit = 25) }.getOrDefault(emptyList())
                topSongs = songs.map { track ->
                    PlayableTrack(
                        title = track.title,
                        artist = track.artist.takeUnless { it == "Unknown artist" } ?: finalName,
                        album = track.album,
                        artworkUrl = track.artworkUrl ?: artwork,
                        videoId = track.videoId,
                    )
                }
            }

            ArtistPageData(
                name = finalName,
                browseId = targetBrowseId.orEmpty(),
                artworkUrl = artwork,
                bannerUrl = banner,
                monthlyListeners = ytData?.monthlyListeners ?: listeners,
                subscribers = ytData?.subscribers ?: listeners,
                bio = bio,
                tags = tags,
                topSongs = topSongs,
                albums = ytData?.albums.orEmpty(),
                singles = ytData?.singles.orEmpty(),
                similarArtists = if (ytData?.similarArtists?.isNotEmpty() == true) ytData.similarArtists else lfmData?.similarArtists.orEmpty(),
            )
        }
    }

    private data class LastFmArtistMeta(
        val bio: String? = null,
        val artworkUrl: String? = null,
        val listeners: String? = null,
        val tags: List<String> = emptyList(),
        val similarArtists: List<ArtistSummaryItem> = emptyList(),
    )

    private suspend fun fetchLastFmArtistInfo(artistName: String): LastFmArtistMeta? {
        val session = runCatching { sessionPreferences.session.first() }.getOrNull()
        val apiKey = session?.apiKey?.takeIf(String::isNotBlank) ?: LastFmAppCredentials.API_KEY
        if (apiKey.isBlank()) return null

        val response = lastFmApi.get(
            mapOf(
                "method" to "artist.getinfo",
                "artist" to artistName,
                "autocorrect" to "1",
                "api_key" to apiKey,
                "format" to "json",
            ),
        )
        if (!response.isSuccessful) return null
        val body = response.body()?.string().orEmpty()
        val artistObj = json.parseToJsonElement(body).jsonObject["artist"]?.jsonObject ?: return null

        val bio = artistObj["bio"]?.jsonObject?.get("summary")?.jsonPrimitive?.contentOrNull
            ?.replace(Regex("<a\\b[^>]*>.*?</a>", RegexOption.IGNORE_CASE), "")
            ?.trim()?.takeIf(String::isNotBlank)

        val listenersCount = artistObj["stats"]?.jsonObject?.get("listeners")?.jsonPrimitive?.contentOrNull
        val listenersFormatted = listenersCount?.toLongOrNull()?.let { count ->
            when {
                count >= 1_000_000 -> "%.1fM listeners".format(count / 1_000_000.0)
                count >= 1_000 -> "%.1fK listeners".format(count / 1_000.0)
                else -> "$count listeners"
            }
        }

        val tags = artistObj["tags"]?.jsonObject?.get("tag")?.jsonArray?.mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
        }.orEmpty()

        val images = artistObj["image"]?.jsonArray?.mapNotNull { it.jsonObject }
        val artworkUrl = images?.lastOrNull {
            it["#text"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
        }?.get("#text")?.jsonPrimitive?.contentOrNull

        val similar = artistObj["similar"]?.jsonObject?.get("artist")?.jsonArray?.mapNotNull {
            val name = it.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val img = it.jsonObject["image"]?.jsonArray?.lastOrNull()?.jsonObject?.get("#text")?.jsonPrimitive?.contentOrNull
            ArtistSummaryItem(name = name, artworkUrl = img)
        }.orEmpty()

        return LastFmArtistMeta(
            bio = bio,
            artworkUrl = artworkUrl,
            listeners = listenersFormatted,
            tags = tags,
            similarArtists = similar,
        )
    }
}
