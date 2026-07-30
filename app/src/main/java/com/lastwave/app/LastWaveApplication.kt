package com.lastwave.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class LastWaveApplication : Application(), ImageLoaderFactory {

    /**
     * App-wide Coil configuration (purely a performance concern — request
     * semantics are unchanged):
     *  - respectCacheHeaders(false): Last.fm / iTunes artwork URLs are
     *    immutable, but their CDNs send conservative cache headers; honoring
     *    them meant already-seen artwork could be re-fetched over the
     *    network on later scroll-bys. Ignoring the headers makes the disk
     *    cache authoritative, so each artwork downloads at most once.
     *  - Bounded, explicit memory/disk caches so scroll-bys of previously
     *    seen rows are pure in-memory hits.
     *  - crossfade stays disabled: cached images appear instantly instead
     *    of re-animating on every bind while flinging through the list.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.35)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(false)
            .build()
}
