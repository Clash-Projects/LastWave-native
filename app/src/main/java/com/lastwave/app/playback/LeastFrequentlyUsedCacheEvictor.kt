package com.lastwave.app.playback

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.LinkedList
import java.util.HashMap

/**
 * Least Frequently Used (LFU) cache evictor.
 * Evicts the least frequently used cache entries when the cache exceeds its size limit.
 */
class LeastFrequentlyUsedCacheEvictor(
    private val maxSizeBytes: Long
) : CacheEvictor {

    /** Maps cache keys to their access frequency */
    private val frequencyMap: HashMap<String, Int> = HashMap()

    /** Queue to maintain access order for LRU tie-breaking */
    private val accessQueue = LinkedList<String>()

    /** Current size of the cache in bytes */
    private var currentSize = 0L

    override fun onCacheInitialized() {
        // No initialization needed
    }

    override fun onStartFile(cache: Cache, fileId: String, position: Long, length: Long) {
        // No action needed for file start
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        currentSize += span.length
        frequencyMap[span.key] = 1
        accessQueue.add(span.key)
        evictIfNeeded(cache)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        currentSize -= span.length
        frequencyMap.remove(span.key)
        accessQueue.remove(span.key)
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        val key = oldSpan.key
        // Increment frequency
        frequencyMap[key] = frequencyMap.getOrDefault(key, 0) + 1
        // Move to end of queue (most recently used)
        accessQueue.remove(key)
        accessQueue.add(key)
    }

    override fun requiresCacheSpanTouches(): Boolean = true

    /** Evict entries if cache size exceeds the maximum allowed size */
    private fun evictIfNeeded(cache: Cache) {
        while (currentSize > maxSizeBytes && accessQueue.isNotEmpty()) {
            val leastFrequentKey = findLeastFrequentlyUsed()
            leastFrequentKey?.let { key ->
                // Find the CacheSpan for this key and remove it
                val spans = cache.getCachedSpans(key)
                spans.forEach { cache.removeSpan(it) }
            }
        }
    }

    /**
     * Finds the least frequently used key.
     * If multiple keys have the same frequency, returns the least recently used one.
     */
    private fun findLeastFrequentlyUsed(): String? {
        return accessQueue.minByOrNull { frequencyMap[it] ?: Int.MAX_VALUE }
    }
}