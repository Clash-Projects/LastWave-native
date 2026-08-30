package com.lastwave.app.playback

import com.lastwave.app.data.generate.GenerateRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.search.SearchRepository
import com.lastwave.app.data.search.SearchResultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deep module encapsulating song radio and recommendation resolution.
 * Blends Last.fm similar tracks + artist top tracks with SearchRepository fallback
 * and active track pools, returning a clean, deduplicated list of playable radio tracks.
 */
@Singleton
class SongRadioResolver @Inject constructor(
    private val generateRepository: GenerateRepository,
    private val searchRepository: SearchRepository,
) {

    suspend fun resolveRadioTracks(
        seed: PlayableTrack,
        fallbackPool: List<PlayableTrack> = emptyList(),
        limit: Int = 25,
    ): List<PlayableTrack> = withContext(Dispatchers.IO) {
        val seedTitle = seed.title.trim()
        val seedArtist = seed.artist.trim()
        if (seedTitle.isBlank() || seedArtist.isBlank()) return@withContext emptyList()
        val seedKey = "${seedTitle.lowercase()}|${seedArtist.lowercase()}"

        // 1. Primary: Last.fm 3-source blend (similar tracks + artist top tracks + similar artists)
        val generated = try {
            generateRepository.startMixFromTrack(seedTitle, seedArtist)
        } catch (_: Exception) {
            emptyList()
        }

        if (generated.isNotEmpty()) {
            return@withContext generated
                .filter { "${it.name.lowercase()}|${it.artist.lowercase()}" != seedKey }
                .take(limit)
                .map(GeneratedTrack::toPlayableTrack)
        }

        // 2. Fallback: SearchRepository (Last.fm track.getsimilar or YouTube Music query)
        val searchSimilar = try {
            searchRepository.similarSongsFor(
                SearchResultItem(
                    name = seedTitle,
                    artist = seedArtist,
                    artworkUrl = seed.artworkUrl,
                    videoId = seed.videoId,
                ),
                limit = limit,
            )
        } catch (_: Exception) {
            emptyList()
        }

        if (searchSimilar.isNotEmpty()) {
            return@withContext searchSimilar
                .filter { "${it.name.lowercase()}|${it.artist.lowercase()}" != seedKey }
                .take(limit)
                .map(GeneratedTrack::toPlayableTrack)
        }

        // 3. Fallback: In-memory pool (e.g. current QuickPlay grid or playlist)
        if (fallbackPool.isNotEmpty()) {
            return@withContext fallbackPool
                .filter { "${it.title.lowercase()}|${it.artist.lowercase()}" != seedKey }
                .distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
                .take(limit)
        }

        emptyList()
    }
}
