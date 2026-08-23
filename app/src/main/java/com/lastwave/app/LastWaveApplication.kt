package com.lastwave.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.lastwave.app.data.repository.ThemeRepository
import com.lastwave.app.widget.WidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LastWaveApplication : Application(), ImageLoaderFactory {

    @Inject lateinit var themeRepository: ThemeRepository
    @Inject lateinit var applicationScope: CoroutineScope
    @Inject lateinit var okHttpClient: okhttp3.OkHttpClient

    override fun onCreate() {
        super.onCreate()
        com.lastwave.app.data.music.potoken.BotGuardTokenGenerator.initialize(this)
        applicationScope.launch(Dispatchers.IO) {
            com.lastwave.app.data.music.potoken.BotGuardTokenGenerator.preWarm()
        }
        // A widget is a separate RemoteViews surface, so it needs an explicit
        // refresh whenever LastWave's live theme changes.
        applicationScope.launch(Dispatchers.IO) {
            themeRepository.uiState.collect {
                WidgetUpdater.refreshTheme(this@LastWaveApplication)
            }
        }
    }

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
     *  - Hardware acceleration enabled for fast GPU texture uploading.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
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
            .allowHardware(true)
            .build()
}
