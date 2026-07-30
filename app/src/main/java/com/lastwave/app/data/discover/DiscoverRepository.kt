package com.lastwave.app.data.discover

import android.util.Log
import com.lastwave.app.data.generate.GenerateRepository
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.TasteProfileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DiscoverRepository"

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
    private var queue: MutableList<GeneratedTrack> = mutableListOf()
    private val shownKeys = mutableSetOf<String>()
    private var cachedFeed: List<GeneratedTrack> = emptyList()

    fun getCachedFeed(): List<GeneratedTrack> = cachedFeed

    private suspend fun refillQueue() = coroutineScope {
        val profile = tasteProfileProvider.get()
        val pool = Collections.synchronizedList(mutableListOf<GeneratedTrack>())

        val jobs = mutableListOf<kotlinx.coroutines.Deferred<*>>()

        for (seed in profile.recentTracksRaw.shuffled().take(5)) {
            if (seed.name.isBlank() || seed.artist.isBlank()) continue
            jobs += async(Dispatchers.IO) {
                try {
                    val tracks = generateRepository.fetchSimilarTracks(seed.name, seed.artist, 15)
                    pool.addAll(tracks)
                } catch (e: Exception) { Log.d(TAG, "refillQueue similar-tracks miss", e) }
            }
        }

        for (artistName in profile.topArtistNames.shuffled().take(4)) {
            jobs += async(Dispatchers.IO) {
                try {
                    val tracks = generateRepository.fetchSimilarArtistTracks(artistName, 10)
                    pool.addAll(tracks)
                } catch (e: Exception) { Log.d(TAG, "refillQueue similar-artists miss", e) }
            }
        }

        for (tag in profile.topTags.shuffled().take(2)) {
            jobs += async(Dispatchers.IO) {
                try {
                    val tracks = generateRepository.fetchTagTracks(tag, 15)
                    pool.addAll(tracks)
                } catch (e: Exception) { Log.d(TAG, "refillQueue tag miss", e) }
            }
        }

        jobs.awaitAll()

        val deduped = generateRepository.deduplicate(pool.toList()).filterNot { it.key in shownKeys }
        queue.addAll(deduped.shuffled())
    }

    /** Chunk-shuffled batch of [count] tracks for the feed — refills the
     *  underlying pool transparently when running low. */
    suspend fun nextBatch(count: Int = 8): List<GeneratedTrack> {
        if (queue.size < count) refillQueue()
        val batch = queue.take(count)
        queue = queue.drop(count).toMutableList()
        shownKeys.addAll(batch.map { it.key })
        cachedFeed = (cachedFeed + batch).takeLast(50)
        return batch
    }

    /** "Surprise Me" — one genuinely random track from a fresh pull,
     *  distinct from the passive infinite-scroll batches. */
    suspend fun surpriseMe(): GeneratedTrack? {
        if (queue.isEmpty()) refillQueue()
        return queue.randomOrNull()?.also {
            queue.remove(it)
            shownKeys.add(it.key)
        }
    }

    fun reset() {
        queue = mutableListOf()
        shownKeys.clear()
        cachedFeed = emptyList()
    }
}
