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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DiscoverRepository"
private const val MAX_REFILL_ATTEMPTS = 2
private const val MAX_EXPLORATION_SEEDS = 120

private val CURATED_GENRE_SEEDS = listOf(
    "indie", "electronic", "synthwave", "rock", "alternative", "pop",
    "hip-hop", "r&b", "chillwave", "dream pop", "shoegaze", "ambient", "jazz"
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

    suspend fun freshCachedFeed(): List<GeneratedTrack> = mutex.withLock {
        val fresh = generateRepository.filterOutsideDiscoveryHistory(_feed.value)
        if (fresh.size != _feed.value.size) _feed.value = fresh
        fresh
    }

    private suspend fun refillQueue() = coroutineScope {
        val profile = runCatching { tasteProfileProvider.get() }.getOrNull()
        val pool = Collections.synchronizedList(mutableListOf<GeneratedTrack>())
        val jobs = mutableListOf<kotlinx.coroutines.Deferred<*>>()

        val frontier = buildList {
            repeat(minOf(5, explorationSeeds.size)) {
                add(explorationSeeds.removeFirst())
            }
        }
        val feedSeeds = _feed.value.shuffled()
        val recentSeeds = profile?.recentTracksRaw.orEmpty().shuffled().take(5)
        val topSeeds = profile?.topTracksRaw.orEmpty().shuffled().take(5)
        val trackSeeds = (recentSeeds + topSeeds + frontier + feedSeeds.take(3))
            .filter { it.name.isNotBlank() && it.artist.isNotBlank() }
            .distinctBy(GeneratedTrack::key)
            .take(8)

        for (seed in trackSeeds) {
            jobs += async(Dispatchers.IO) {
                withTimeoutOrNull(4000L) {
                    try {
                        val tracks = generateRepository.fetchSimilarTracks(seed.name, seed.artist, 20)
                        pool.addAll(tracks)
                    } catch (e: Exception) { Log.d(TAG, "refillQueue similar-tracks miss", e) }
                }
            }
        }

        val artistSeeds = (profile?.topArtistNames.orEmpty().shuffled().take(4) + feedSeeds.map(GeneratedTrack::artist).take(3))
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase() }
            .take(4)
        for (artistName in artistSeeds) {
            jobs += async(Dispatchers.IO) {
                withTimeoutOrNull(4000L) {
                    try {
                        val tracks = generateRepository.fetchSimilarArtistTracks(artistName, 12)
                        pool.addAll(tracks)
                    } catch (e: Exception) { Log.d(TAG, "refillQueue similar-artists miss", e) }
                }
            }
        }

        val availableTags = (profile?.topTags.orEmpty() + CURATED_GENRE_SEEDS)
            .filter(String::isNotBlank)
            .distinct()
            .shuffled()
            .take(4)
        for (tag in availableTags) {
            jobs += async(Dispatchers.IO) {
                withTimeoutOrNull(4000L) {
                    try {
                        val tracks = generateRepository.fetchTagTracks(tag, 20)
                        pool.addAll(tracks)
                    } catch (e: Exception) { Log.d(TAG, "refillQueue tag miss", e) }
                }
            }
        }

        jobs += async(Dispatchers.IO) {
            withTimeoutOrNull(4000L) {
                try {
                    val chartTracks = generateRepository.fetchChartTracks(30)
                    pool.addAll(chartTracks)
                } catch (_: Exception) {}
            }
        }

        jobs.awaitAll()

        val queuedKeys = queue.mapTo(mutableSetOf()) { it.key }
        val deduped = generateRepository.deduplicate(pool.toList())
            .filter { it.name.isNotBlank() && it.artist.isNotBlank() }
            .filterNot { it.key in queuedKeys }

        val fresh = generateRepository.filterOutsideDiscoveryHistory(deduped)
            .filterNot { it.key in shownKeys }
            .shuffled()

        val toAdd = when {
            fresh.isNotEmpty() -> fresh
            deduped.isNotEmpty() -> deduped.filterNot { it.key in shownKeys }.shuffled().ifEmpty { deduped.shuffled() }
            else -> emptyList()
        }

        queue.addAll(toAdd)
        queue.take(24).forEach(explorationSeeds::addLast)
        while (explorationSeeds.size > MAX_EXPLORATION_SEEDS) explorationSeeds.removeFirst()
    }

    /** Chunk-shuffled batch of [count] tracks for the feed — refills the
     *  underlying pool transparently when running low. */
    suspend fun nextBatch(count: Int = 8): List<GeneratedTrack> = mutex.withLock {
        queue = generateRepository.filterOutsideDiscoveryHistory(queue).toMutableList()
        var attempts = 0
        while (queue.size < count && attempts < MAX_REFILL_ATTEMPTS) {
            refillQueue()
            attempts++
        }

        if (queue.size < count) {
            try {
                val chartFallback = generateRepository.fetchChartTracks(count * 2)
                    .filterNot { it.key in shownKeys }
                queue.addAll(chartFallback)
            } catch (_: Exception) {}
        }

        val batch = queue.take(count)
        queue = queue.drop(count).toMutableList()
        shownKeys.addAll(batch.map { it.key })
        if (batch.isNotEmpty()) {
            _feed.value = _feed.value + batch
        }
        batch
    }

    /** "Surprise Me" — one genuinely random track from a fresh pull,
     *  distinct from the passive infinite-scroll batches. */
    suspend fun surpriseMe(): GeneratedTrack? = mutex.withLock {
        var attempts = 0
        while (queue.isEmpty() && attempts < MAX_REFILL_ATTEMPTS) {
            refillQueue()
            attempts++
        }
        if (queue.isEmpty()) {
            try {
                val chartFallback = generateRepository.fetchChartTracks(10)
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
}
