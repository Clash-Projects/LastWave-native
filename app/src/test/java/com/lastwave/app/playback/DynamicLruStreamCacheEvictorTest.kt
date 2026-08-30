package com.lastwave.app.playback

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for DynamicLruStreamCacheEvictor.
 */
class DynamicLruStreamCacheEvictorTest {

    private lateinit var cache: Cache
    private var maxBytes: Long = 100L
    private lateinit var evictor: DynamicLruStreamCacheEvictor

    private fun createSpan(key: String, length: Long, lastTouchTimestamp: Long): CacheSpan {
        return CacheSpan(key, 0L, length, lastTouchTimestamp, null)
    }

    @Before
    fun setUp() {
        maxBytes = 100L
        evictor = DynamicLruStreamCacheEvictor { maxBytes }
        cache = mockk(relaxed = true)
        every { cache.removeSpan(any()) } answers {
            val span = firstArg<CacheSpan>()
            evictor.onSpanRemoved(cache, span)
        }
    }

    @Test
    fun testEvictionWhenExceedingDynamicLimit() {
        val span1 = createSpan("span1", 60L, 1000L)
        val span2 = createSpan("span2", 50L, 2000L)

        evictor.onSpanAdded(cache, span1)
        assertThat(evictor.currentSizeBytes).isEqualTo(60L)

        // Adding span2 brings total to 110L > 100L maxBytes
        evictor.onSpanAdded(cache, span2)

        // Oldest span (span1) should be evicted
        verify(exactly = 1) { cache.removeSpan(span1) }
        assertThat(evictor.currentSizeBytes).isEqualTo(50L)
    }

    @Test
    fun testEvictIfNeededTrimsWhenLimitReduced() {
        val span1 = createSpan("span1", 40L, 1000L)
        val span2 = createSpan("span2", 40L, 2000L)

        evictor.onSpanAdded(cache, span1)
        evictor.onSpanAdded(cache, span2)
        assertThat(evictor.currentSizeBytes).isEqualTo(80L)

        // Reduce limit dynamically to 50L
        maxBytes = 50L
        evictor.evictIfNeeded(cache)

        // Oldest span (span1) must be evicted to fit within 50L
        verify(exactly = 1) { cache.removeSpan(span1) }
        assertThat(evictor.currentSizeBytes).isEqualTo(40L)
    }

    @Test
    fun testZeroLimitEvictsAll() {
        val span1 = createSpan("span1", 30L, 1000L)
        val span2 = createSpan("span2", 40L, 2000L)

        evictor.onSpanAdded(cache, span1)
        evictor.onSpanAdded(cache, span2)

        // Disabled cache limit (0 bytes)
        maxBytes = 0L
        evictor.evictIfNeeded(cache)

        verify(exactly = 1) { cache.removeSpan(span1) }
        verify(exactly = 1) { cache.removeSpan(span2) }
        assertThat(evictor.currentSizeBytes).isEqualTo(0L)
    }

    @Test
    fun testTouchingSpanUpdatesLruOrder() {
        val span1 = createSpan("span1", 40L, 1000L)
        val span2 = createSpan("span2", 40L, 2000L)

        evictor.onSpanAdded(cache, span1)
        evictor.onSpanAdded(cache, span2)

        // Touch span1 with a new timestamp > span2's timestamp
        val touchedSpan1 = createSpan("span1", 40L, 3000L)
        evictor.onSpanTouched(cache, span1, touchedSpan1)

        // Now add span3 (40L) which exceeds 100L limit (total 120L)
        val span3 = createSpan("span3", 40L, 4000L)
        evictor.onSpanAdded(cache, span3)

        // Since span1 was touched, span2 is now the oldest and should be evicted
        verify(exactly = 1) { cache.removeSpan(span2) }
        verify(exactly = 0) { cache.removeSpan(span1) }
    }
}
