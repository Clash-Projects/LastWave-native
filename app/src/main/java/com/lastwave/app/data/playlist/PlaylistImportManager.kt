package com.lastwave.app.data.playlist

import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubePlaylistResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistImportManager @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val innerTube: InnerTubeMusicApi,
    private val csvPlaylistImporter: CsvPlaylistImporter,
) {

    suspend fun importYouTubePlaylist(
        playlist: YouTubePlaylistResult,
        selectedTracks: List<com.lastwave.app.data.music.YouTubeMusicTrack> = playlist.tracks,
    ): SavedPlaylist = withContext(Dispatchers.IO) {
        val tracks = selectedTracks.map { yt ->
            GeneratedTrack(
                name = yt.title,
                artist = yt.artist,
                album = yt.album,
                artworkUrl = yt.artworkUrl,
            )
        }

        playlistRepository.save(
            title = playlist.title,
            subtitle = "YouTube Music \u2022 ${tracks.size} tracks",
            mode = "custom",
            tracks = tracks,
        )
    }

    suspend fun importCsvStream(
        inputStream: InputStream,
        filename: String,
    ): Pair<SavedPlaylist, CsvImportResult> = withContext(Dispatchers.IO) {
        val result = csvPlaylistImporter.parseAndMatchCsv(inputStream, filename)
        val fileType = if (filename.endsWith(".m3u", ignoreCase = true) || filename.endsWith(".m3u8", ignoreCase = true)) "M3U" else "CSV"
        val saved = playlistRepository.save(
            title = result.suggestedTitle,
            subtitle = "$fileType Import \u2022 ${result.tracks.size} tracks (${result.matchedCount} matched)",
            mode = "custom",
            tracks = result.tracks,
        )
        Pair(saved, result)
    }
}
