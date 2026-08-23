package com.lastwave.app.playback

import android.content.Intent
import android.net.Uri
import android.util.Log
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LinkPlaybackResolver"

@Singleton
class LinkPlaybackResolver @Inject constructor(
    private val musicPlayer: MusicPlayer,
    private val innerTube: InnerTubeMusicApi,
    private val http: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    fun handleIntent(intent: Intent?): Boolean {
        if (intent == null) return false

        val action = intent.action
        val dataUri = intent.data
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)

        val rawTarget = when {
            action == Intent.ACTION_VIEW && dataUri != null -> dataUri.toString()
            action == Intent.ACTION_SEND && !sharedText.isNullOrBlank() -> extractUrlOrText(sharedText)
            dataUri != null -> dataUri.toString()
            else -> null
        } ?: return false

        scope.launch {
            resolveAndPlay(rawTarget)
        }
        return true
    }

    private fun extractUrlOrText(text: String): String {
        val trimmed = text.trim()
        val urlRegex = Regex("(https?://[^\\s]+)")
        val match = urlRegex.find(trimmed)
        return match?.value ?: trimmed
    }

    suspend fun resolveAndPlay(target: String) = withContext(Dispatchers.IO) {
        val clean = target.trim()
        Log.d(TAG, "Resolving incoming target for direct playback: $clean")

        try {
            when {
                // 1. YouTube & YouTube Music URLs
                isYouTubeUrl(clean) -> resolveYouTube(clean)

                // 2. Spotify URLs
                isSpotifyUrl(clean) -> resolveSpotify(clean)

                // 3. Plain search query shared to app
                clean.isNotBlank() -> resolveGenericQuery(clean)

                else -> Unit
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Never eat cancellation — it would keep a cancelled resolve
            // "running" and mask structured shutdown.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve link for playback: $clean", e)
        }
    }

    private fun isYouTubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be") || lower.contains("music.youtube")
    }

    private fun isSpotifyUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("spotify.com") || lower.contains("spotify.link")
    }

    private suspend fun resolveYouTube(url: String) {
        // A) Playlist URL
        if (url.contains("list=")) {
            val playlist = innerTube.fetchPlaylist(url)
            if (playlist != null && playlist.tracks.isNotEmpty()) {
                val playableList = playlist.tracks.map { it.toPlayable() }
                withContext(Dispatchers.Main) {
                    musicPlayer.playQueue(playableList, startIndex = 0, sourceLabel = playlist.title)
                }
                return
            }
        }

        // B) Direct Song / Video URL
        val videoId = extractYouTubeVideoId(url)
        if (!videoId.isNullOrBlank()) {
            val songDetails = innerTube.fetchSongDetails(videoId)
            val playable = if (songDetails != null) {
                songDetails.toPlayable()
            } else {
                val tracks = innerTube.searchSongs(videoId, limit = 1)
                val matched = tracks.firstOrNull { it.videoId == videoId }
                    ?: innerTube.searchSongs(url, limit = 1).firstOrNull()

                if (matched != null) {
                    matched.toPlayable()
                } else {
                    PlayableTrack(
                        title = "YouTube Stream",
                        artist = "YouTube Music",
                        artworkUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                        videoId = videoId,
                    )
                }
            }

            withContext(Dispatchers.Main) {
                musicPlayer.play(playable, sourceLabel = "YouTube Music")
            }
        }
    }

    private fun extractYouTubeVideoId(url: String): String? {
        val clean = url.trim()
        if (clean.contains("youtu.be/")) {
            return clean.substringAfter("youtu.be/").substringBefore('?').substringBefore('&').substringBefore('/')
        }
        if (clean.contains("v=")) {
            return clean.substringAfter("v=").substringBefore('&').substringBefore('#')
        }
        if (clean.contains("/watch/")) {
            return clean.substringAfter("/watch/").substringBefore('?').substringBefore('&')
        }
        if (clean.contains("/shorts/")) {
            return clean.substringAfter("/shorts/").substringBefore('?').substringBefore('&')
        }
        return null
    }

    private suspend fun resolveSpotify(url: String) {
        // Fetch metadata via Spotify's public oEmbed service
        val oembedUrl = "https://open.spotify.com/oembed?url=${Uri.encode(url)}"
        val request = Request.Builder()
            .url(oembedUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()

        val response = runCatching { http.newCall(request).execute() }.getOrNull()
        // use{} guarantees the connection returns to the pool even when the
        // body is abandoned; without it a failed parse leaked the socket.
        val jsonString = response?.use { it.body?.string().orEmpty() } ?: ""

        var title = ""
        var artist = ""
        var artworkUrl: String? = null

        if (jsonString.isNotBlank()) {
            runCatching {
                val obj = json.parseToJsonElement(jsonString).jsonObject
                val rawTitle = obj["title"]?.jsonPrimitive?.content.orEmpty()
                artworkUrl = obj["thumbnail_url"]?.jsonPrimitive?.content

                if (rawTitle.contains(" - ")) {
                    title = rawTitle.substringBefore(" - ").trim()
                    artist = rawTitle.substringAfter(" - ").trim()
                } else if (rawTitle.contains(" by ")) {
                    title = rawTitle.substringBefore(" by ").trim()
                    artist = rawTitle.substringAfter(" by ").trim()
                } else {
                    title = rawTitle
                    artist = obj["author_name"]?.jsonPrimitive?.content.orEmpty()
                }
            }
        }

        if (title.isBlank()) {
            title = url.substringAfterLast('/').substringBefore('?')
        }

        // Match the song with exact high quality audio
        val match = runCatching { innerTube.findBestMatch(title, artist) }.getOrNull()
        val playable = if (match != null) {
            PlayableTrack(
                title = title.ifBlank { match.title },
                artist = artist.ifBlank { match.artist },
                album = match.album,
                artworkUrl = artworkUrl ?: match.artworkUrl,
                videoId = match.videoId,
            )
        } else {
            PlayableTrack(
                title = title,
                artist = artist.ifBlank { "Spotify" },
                artworkUrl = artworkUrl,
            )
        }

        withContext(Dispatchers.Main) {
            musicPlayer.play(playable, sourceLabel = "Spotify Link")
        }
    }

    private suspend fun resolveGenericQuery(query: String) {
        val tracks = innerTube.searchSongs(query, limit = 1)
        val first = tracks.firstOrNull() ?: return
        withContext(Dispatchers.Main) {
            musicPlayer.play(first.toPlayable(), sourceLabel = "Shared Song")
        }
    }

    private fun YouTubeMusicTrack.toPlayable(): PlayableTrack = PlayableTrack(
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        videoId = videoId,
    )
}
