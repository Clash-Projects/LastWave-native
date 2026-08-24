package com.lastwave.app.data.discover

import android.util.Log
import com.lastwave.app.data.generate.GenerateRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.TasteProfileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private const val TAG = "DiscoverRepository"
private const val MAX_REFILL_ATTEMPTS = 2
private const val MAX_EXPLORATION_SEEDS = 120
private const val MAX_TRACK_SEEDS = 6
private const val DISCOVER_ARTIST_CAP = 3
private const val MAX_SHOWN_KEYS = 3_000

private val CURATED_GENRE_SEEDS = listOf(
    "indie", "electronic", "synthwave", "rock", "alternative", "pop",
    "hip-hop", "r&b", "chillwave", "dream pop", "shoegaze", "ambient", "jazz"
)

private data class TasteSeed(val track: GeneratedTrack, val weight: Int)
private data class SourceBatch(val tracks: List<GeneratedTrack>, val weight: Int, val source: String)
private data class RankedCandidate(
    var track: GeneratedTrack,
    var strongestWeight: Int,
    val sources: MutableSet<String> = mutableSetOf(),
)

@Singleton
class DiscoverRepository @Inject constructor(
    private val generateRepository: GenerateRepository,
    private val tasteProfileProvider: TasteProfileProvider,
) {
    private val mutex = Mutex()
    private var queue: MutableList<GeneratedTrack> = mutableListOf()
    private val shownKeys = mutableSetOf<String>()
    private val explorationSeeds = ArrayDeque<GeneratedTrack>()
    private val _feed = MutableStateFlow<List<GeneratedTrack>>(emptyList())
    val feed: StateFlow<List<GeneratedTrack>> = _feed.asStateFlow()

    fun getCachedFeed(): List<GeneratedTrack> = _feed.value

    suspend fun allowedCachedFeed(): List<GeneratedTrack> = mutex.withLock {
        val allowed = generateRepository.filterRecommendationExclusions(_feed.value)
        if (allowed.size != _feed.value.size) _feed.value = allowed
        allowed
    }

    private suspend fun refillQueue() = coroutineScope {
        val profile = runCatching { tasteProfileProvider.get() }.getOrNull()
        val jobs = mutableListOf<kotlinx.coroutines.Deferred<SourceBatch>>()

        val frontier = buildList {
            repeat(minOf(2, explorationSeeds.size)) {
                add(explorationSeeds.removeFirst())
            }
        }
        val feedSeeds = _feed.value.shuffled()
        val trackSeeds = buildList {
            addAll(profile?.recentTracksRaw.orEmpty().shuffled().take(2).map { TasteSeed(it, 100) })
            addAll(profile?.topTracksRaw.orEmpty().shuffled().take(2).map { TasteSeed(it, 88) })
            addAll(profile?.ytMusicRecentRaw.orEmpty().shuffled().take(1).map { TasteSeed(it, 78) })
            addAll(profile?.ytMusicLikedRaw.orEmpty().shuffled().take(1).map { TasteSeed(it, 72) })
            addAll(profile?.ytMusicFeedRaw.orEmpty().shuffled().take(1).map { TasteSeed(it, 68) })
            addAll(frontier.take(1).map { TasteSeed(it, 58) })
            addAll(feedSeeds.take(1).map { TasteSeed(it, 48) })
        }
            .filter { it.track.name.isNotBlank() && it.track.artist.isNotBlank() }
            .groupBy { it.track.key }
            .map { (_, seeds) -> seeds.maxBy { it.weight } }
            .sortedByDescending(TasteSeed::weight)
            .take(MAX_TRACK_SEEDS)

        for (seed in trackSeeds) {
            jobs += async(Dispatchers.IO) {
                try {
                    SourceBatch(
                        tracks = generateRepository.fetchSimilarTracks(seed.track.name, seed.track.artist, 24),
                        weight = seed.weight,
                        source = "track:${seed.track.key}",
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "refillQueue similar-tracks miss", e)
                    SourceBatch(emptyList(), seed.weight, "track:${seed.track.key}")
                }
            }
        }

        val artistSeeds = buildList {
            addAll(
                profile?.topArtistsRaw.orEmpty()
                    .distinctBy { it.trim().lowercase() }
                    .sortedByDescending { profile?.artistAffinity?.get(it.trim().lowercase()) ?: 0.0 }
                    .take(2),
            )
            if (profile?.hasPersonalSignals != true) addAll(feedSeeds.map(GeneratedTrack::artist).take(1))
        }
            .filter(String::isNotBlank)
            .distinctBy { it.trim().lowercase() }
            .take(4)
        for (artistName in artistSeeds) {
            jobs += async(Dispatchers.IO) {
                val normalizedArtist = artistName.trim().lowercase()
                val affinity = profile?.artistAffinity?.get(normalizedArtist) ?: 0.0
                val weight = 62 + (affinity * 20).toInt()
                try {
                    SourceBatch(
                        tracks = generateRepository.fetchSimilarArtistTracks(artistName, 14),
                        weight = weight,
                        source = "artist:${normalizedArtist}",
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "refillQueue similar-artists miss", e)
                    SourceBatch(emptyList(), weight, "artist:${normalizedArtist}")
                }
            }
        }

        val tagPool = profile?.topTags.orEmpty().ifEmpty {
            if (profile?.hasPersonalSignals == true) emptySet() else CURATED_GENRE_SEEDS.toSet()
        }
        val availableTags = tagPool
            .filter(String::isNotBlank)
            .distinct()
            .shuffled()
            .take(2)

        for (tag in availableTags) {
            jobs += async(Dispatchers.IO) {
                try {
                    SourceBatch(
                        tracks = generateRepository.fetchTagTracks(tag, 18),
                        weight = 38,
                        source = "tag:$tag",
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "refillQueue tag miss", e)
                    SourceBatch(emptyList(), 38, "tag:$tag")
                }
            }
        }

        val queuedKeys = queue.mapTo(mutableSetOf()) { it.key }
        val candidates = LinkedHashMap<String, RankedCandidate>()
        val sourceBatches = jobs.awaitAll().toMutableList()
        profile?.ytMusicFeedRaw
            ?.take(40)
            ?.takeIf { it.isNotEmpty() }
            ?.let { sourceBatches += SourceBatch(it, 92, "yt-feed") }
        for (batch in sourceBatches) {
            for (track in batch.tracks) {
                if (track.name.isBlank() || track.artist.isBlank() || track.key in queuedKeys) continue
                val current = candidates[track.key]
                if (current == null) {
                    candidates[track.key] = RankedCandidate(
                        track = track,
                        strongestWeight = batch.weight,
                        sources = mutableSetOf(batch.source),
                    )
                } else {
                    current.strongestWeight = maxOf(current.strongestWeight, batch.weight)
                    current.sources += batch.source
                    if (current.track.artworkUrl.isNullOrBlank() && !track.artworkUrl.isNullOrBlank()) {
                        current.track = track
                    }
                }
            }
        }

        val allowedKeys = generateRepository.filterRecommendationExclusions(
            candidates.values.map(RankedCandidate::track),
        ).mapTo(mutableSetOf(), GeneratedTrack::key)
        fun score(candidate: RankedCandidate): Double {
            val affinity = profile?.artistAffinity?.get(candidate.track.artist.trim().lowercase()) ?: 0.0
            val consensus = minOf(24, (candidate.sources.size - 1).coerceAtLeast(0) * 8)
            val similarity = (candidate.track.match ?: 0.0).coerceIn(0.0, 1.0) * 24.0
            return candidate.strongestWeight + consensus + similarity +
                affinity * 26.0 + Random.nextDouble() * 3.0
        }
        val ranked = candidates.values
            .filterNot { it.track.key in shownKeys }
            .sortedByDescending(::score)
        val preferred = ranked.filter { it.track.key in allowedKeys }
        val ytFeedCandidates = preferred.filter { "yt-feed" in it.sources }
        val otherCandidates = preferred.filterNot { "yt-feed" in it.sources }
        val balanced = buildList {
            var otherIndex = 0
            var ytIndex = 0
            while (otherIndex < otherCandidates.size || ytIndex < ytFeedCandidates.size) {
                repeat(2) {
                    if (otherIndex < otherCandidates.size) add(otherCandidates[otherIndex++])
                }
                if (ytIndex < ytFeedCandidates.size) add(ytFeedCandidates[ytIndex++])
            }
        }
        val artistCounts = mutableMapOf<String, Int>()
        val toAdd = balanced.filter { candidate ->
            val artist = candidate.track.artist.trim().lowercase()
            val count = artistCounts[artist] ?: 0
            if (count >= DISCOVER_ARTIST_CAP) false
            else {
                artistCounts[artist] = count + 1
                true
            }
        }.map(RankedCandidate::track)

        queue.addAll(toAdd)
        queue.take(24).forEach(explorationSeeds::addLast)
        while (explorationSeeds.size > MAX_EXPLORATION_SEEDS) explorationSeeds.removeFirst()
    }

    /** Chunk-shuffled batch of [count] tracks for the feed — refills the
     *  underlying pool transparently when running low. */
    suspend fun nextBatch(count: Int = 8): List<GeneratedTrack> = mutex.withLock {
        queue = generateRepository.filterRecommendationExclusions(queue).toMutableList()
        var attempts = 0
        while (queue.size < count && attempts < MAX_REFILL_ATTEMPTS) {
            refillQueue()
            attempts++
        }

        if (queue.size < count) {
            try {
                val queuedKeys = queue.mapTo(mutableSetOf(), GeneratedTrack::key)
                val chartFallback = generateRepository.filterRecommendationExclusions(
                    generateRepository.fetchChartTracks(count * 2),
                ).filterNot { it.key in shownKeys || it.key in queuedKeys }
                queue.addAll(chartFallback)
            } catch (_: Exception) {}
        }

        val batch = queue.take(count)
        queue = queue.drop(count).toMutableList()
        shownKeys.addAll(batch.map { it.key })
        while (shownKeys.size > MAX_SHOWN_KEYS) {
            shownKeys.iterator().let { iterator ->
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        if (batch.isNotEmpty()) {
            _feed.value = _feed.value + batch
        }
        batch
    }

    /** "Surprise Me" — one genuinely random track from a fresh pull,
     *  distinct from the passive infinite-scroll batches. */
    suspend fun surpriseMe(): GeneratedTrack? = mutex.withLock {
        queue = generateRepository.filterRecommendationExclusions(queue).toMutableList()
        var attempts = 0
        while (queue.isEmpty() && attempts < MAX_REFILL_ATTEMPTS) {
            refillQueue()
            attempts++
        }
        if (queue.isEmpty()) {
            try {
                val chartFallback = generateRepository.filterRecommendationExclusions(
                    generateRepository.fetchChartTracks(10),
                )
                queue.addAll(chartFallback)
            } catch (_: Exception) {}
        }
        queue.randomOrNull()?.also {
            queue.remove(it)
            shownKeys.add(it.key)
        }
    }

    suspend fun reset() = mutex.withLock {
        queue = mutableListOf()
        shownKeys.clear()
        explorationSeeds.clear()
        _feed.value = emptyList()
    }

    suspend fun clearRecommendationExclusions() {
        generateRepository.clearRecommendationExclusions()
        reset()
    }

    /** Persists an explicit dislike and removes it from every live Discover
     * surface immediately. Saved playlists and search results are untouched. */
    suspend fun excludeFromRecommendations(trackName: String, artistName: String): Boolean {
        if (!generateRepository.excludeFromRecommendations(trackName, artistName)) return false
        val key = GeneratedTrack(trackName, artistName, artworkUrl = null).key
        mutex.withLock {
            queue.removeAll { it.key == key }
            _feed.value = _feed.value.filterNot { it.key == key }
            val retainedSeeds = explorationSeeds.filterNot { it.key == key }
            explorationSeeds.clear()
            explorationSeeds.addAll(retainedSeeds)
            shownKeys += key
        }
        return true
    }

}
