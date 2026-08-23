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
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DiscoverRepository"
private const val MAX_REFILL_ATTEMPTS = 3
private const val MAX_EXPLORATION_SEEDS = 120

/**
 * Faithful port of discover.js (§7): builds seed pools from the taste
 * profile, gathers candidates from similar-tracks/similar-artists/tag
 * sources in parallel, and serves them in shuffled batches for the
 * infinite-scroll feed. Caches previously fetched discovery tracks so the
 * screen opens instantly.
 */
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
        val profile = tasteProfileProvider.get()
        val pool = Collections.synchronizedList(mutableListOf<GeneratedTrack>())
        val jobs = mutableListOf<kotlinx.coroutines.Deferred<*>>()

        val frontier = buildList {
            repeat(minOf(5, explorationSeeds.size)) {
                add(explorationSeeds.removeFirst())
            }
        }
        val feedSeeds = _feed.value.shuffled()
        val trackSeeds = (profile.recentTracksRaw.shuffled().take(5) + frontier + feedSeeds.take(3))
            .filter { it.name.isNotBlank() && it.artist.isNotBlank() }
            .distinctBy(GeneratedTrack::key)
            .take(8)

        for (seed in trackSeeds) {
            jobs += async(Dispatchers.IO) {
                try {
                    val tracks = generateRepository.fetchSimilarTracks(seed.name, seed.artist, 20)
                    pool.addAll(tracks)
                } catch (e: Exception) { Log.d(TAG, "refillQueue similar-tracks miss", e) }
            }
        }

        val artistSeeds = (profile.topArtistNames.shuffled().take(3) + feedSeeds.map(GeneratedTrack::artist).take(3))
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase() }
            .take(3)
        for (artistName in artistSeeds) {
            jobs += async(Dispatchers.IO) {
                try {
                    val tracks = generateRepository.fetchSimilarArtistTracks(artistName, 12)
                    pool.addAll(tracks)
                } catch (e: Exception) { Log.d(TAG, "refillQueue similar-artists miss", e) }
            }
        }

        for (tag in profile.topTags.shuffled().take(2)) {
            jobs += async(Dispatchers.IO) {
                try {
                    val tracks = generateRepository.fetchTagTracks(tag, 20)
                    pool.addAll(tracks)
                } catch (e: Exception) { Log.d(TAG, "refillQueue tag miss", e) }
            }
        }

        jobs.awaitAll()

        val queuedKeys = queue.mapTo(mutableSetOf()) { it.key }
        val deduped = generateRepository.deduplicate(pool.toList())
        val fresh = generateRepository.filterOutsideDiscoveryHistory(deduped)
            .filterNot { it.key in shownKeys || it.key in queuedKeys }
            .shuffled()
        queue.addAll(fresh)
        fresh.take(24).forEach(explorationSeeds::addLast)
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
        val batch = queue.take(count)
        queue = queue.drop(count).toMutableList()
        shownKeys.addAll(batch.map { it.key })
        if (batch.isNotEmpty()) _feed.value = _feed.value + batch
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
