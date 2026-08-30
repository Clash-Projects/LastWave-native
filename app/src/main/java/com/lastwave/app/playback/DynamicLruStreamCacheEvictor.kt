package com.lastwave.app.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * Dynamic Least Recently Used (LRU) Cache Evictor for Media3 audio stream caching.
 * Adapts its capacity in real-time according to the user's configured song limit in settings.
 */
@UnstableApi
class DynamicLruStreamCacheEvictor(
    private val maxBytesProvider: () -> Long,
) : CacheEvictor, Comparator<CacheSpan> {

    private val leastRecentlyUsed = TreeSet<CacheSpan>(this)
    private var currentSize: Long = 0

    val currentSizeBytes: Long get() = currentSize

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() {}

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {}

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.add(span)
        currentSize += span.length
        evictIfNeeded(cache)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.remove(span)
        currentSize -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    fun evictIfNeeded(cache: Cache) {
        val maxBytes = maxBytesProvider()
        while (currentSize > maxBytes && leastRecentlyUsed.isNotEmpty()) {
            val oldest = leastRecentlyUsed.first()
            cache.removeSpan(oldest)
        }
    }

    override fun compare(lhs: CacheSpan, rhs: CacheSpan): Int {
        if (lhs.lastTouchTimestamp - rhs.lastTouchTimestamp == 0L) {
            return lhs.compareTo(rhs)
        }
        return if (lhs.lastTouchTimestamp < rhs.lastTouchTimestamp) -1 else 1
    }
}
