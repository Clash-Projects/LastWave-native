package com.lastwave.app

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.lastwave.app.data.repository.ThemeRepository
import com.lastwave.app.widget.WidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LastWaveApplication : Application(), ImageLoaderFactory {

    @Inject lateinit var themeRepository: dagger.Lazy<ThemeRepository>
    @Inject lateinit var applicationScope: CoroutineScope
    @Inject lateinit var okHttpClient: dagger.Lazy<okhttp3.OkHttpClient>
    @Inject lateinit var streamExtractor: dagger.Lazy<com.lastwave.app.data.music.YouTubeStreamExtractor>
    @Inject lateinit var ytMusicSyncManager: dagger.Lazy<com.lastwave.app.data.ytmusic.YtMusicSyncManager>
    @Inject lateinit var likedSongsManager: dagger.Lazy<com.lastwave.app.data.playlist.LikedSongsManager>

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Content providers (including AndroidX startup/profile components)
        // are created before Application.onCreate(). Install diagnostics here
        // so failures in that earlier device-dependent phase are not lost.
        CrashGuard.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { com.lastwave.app.playback.PlaybackDiagnostics.install(this) }
        runCatching { com.lastwave.app.data.music.potoken.BotGuardTokenGenerator.initialize(this) }
        applicationScope.launch(Dispatchers.IO) {
            delay(OPTIONAL_STARTUP_DELAY_MS)
            // A process kill can bypass TrackDownloadManager's finally block
            // and strand a full lossless track in cache. Remove only old temp
            // files so cleanup cannot race a newly started download.
            runCatching {
                val orphanCutoff = System.currentTimeMillis() - ORPHAN_TEMP_MAX_AGE_MS
                cacheDir.listFiles { file ->
                    file.isFile &&
                        file.name.startsWith("dl_raw_") &&
                        file.lastModified() < orphanCutoff
                }?.forEach { file -> runCatching { file.delete() } }
            }
            // NewPipe is optional fallback infrastructure. A broken extractor
            // install must not escape an application-scope coroutine.
            runCatching { streamExtractor.get().preWarm() }
            runCatching { likedSongsManager.get().start() }
                .onFailure { android.util.Log.e("LastWaveStartup", "Liked Songs startup disabled", it) }
        }
        // Never create BotGuard's headless WebView during app launch. Some
        // Android 11 OEM devices have a missing/updating WebView provider,
        // which can terminate the process. Playback initializes it on demand.
        // YouTube Music playlist sync heartbeat (no-ops until an account is
        // connected AND sync is enabled in Settings).
        applicationScope.launch {
            delay(OPTIONAL_STARTUP_DELAY_MS)
            runCatching { ytMusicSyncManager.get().start() }
                .onFailure { android.util.Log.e("LastWaveStartup", "YT sync startup disabled", it) }
        }
        // A widget is a separate RemoteViews surface, so it needs an explicit
        // refresh whenever LastWave's live theme changes. The widget's palette
        // only consumes primary/onPrimary (every other role is fixed), so
        // dedupe on those — otherwise ANY DataStore settings change (pins,
        // toggles, font) rebuilt every placed widget.
        applicationScope.launch(Dispatchers.IO) {
            delay(OPTIONAL_STARTUP_DELAY_MS)
            try {
                themeRepository.get().uiState
                    .map { it.colorScheme.primary to it.colorScheme.onPrimary }
                    .distinctUntilChanged()
                    .collect {
                        WidgetUpdater.refreshTheme(this@LastWaveApplication)
                    }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                android.util.Log.e("LastWaveStartup", "Widget theme observer disabled", error)
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
    override fun newImageLoader(): ImageLoader {
        val lowRamDevice = runCatching {
            (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
        }.getOrDefault(false)
        val imageClient = try {
            okHttpClient.get().newBuilder()
            .dispatcher(okhttp3.Dispatcher().apply {
                // Bound decode/network bursts: artwork hosts are shared by
                // many visible rows, and 128 simultaneous responses can turn
                // into a GC/decode storm on mobile CPUs.
                maxRequests = if (lowRamDevice) 16 else 48
                maxRequestsPerHost = if (lowRamDevice) 4 else 8
            })
            .connectionPool(okhttp3.ConnectionPool(if (lowRamDevice) 8 else 16, 5, java.util.concurrent.TimeUnit.MINUTES))
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        } catch (error: Exception) {
            android.util.Log.e("LastWaveStartup", "Shared artwork client unavailable; using isolated client", error)
            okhttp3.OkHttpClient.Builder().build()
        } catch (error: LinkageError) {
            android.util.Log.e("LastWaveStartup", "Shared artwork client unsupported; using isolated client", error)
            okhttp3.OkHttpClient.Builder().build()
        }

        return ImageLoader.Builder(this)
            .okHttpClient(imageClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(if (lowRamDevice) 0.08 else 0.18)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    // Enough for hundreds of compressed covers without
                    // allowing artwork to dominate the app's storage usage.
                    .maxSizeBytes(if (lowRamDevice) LOW_RAM_IMAGE_DISK_CACHE_BYTES else IMAGE_DISK_CACHE_BYTES)
                    .build()
            }
            .respectCacheHeaders(false)
            .allowHardware(true)
            .crossfade(150)
            .build()

    }

    private companion object {
        const val IMAGE_DISK_CACHE_BYTES = 32L * 1024 * 1024
        const val LOW_RAM_IMAGE_DISK_CACHE_BYTES = 16L * 1024 * 1024
        const val ORPHAN_TEMP_MAX_AGE_MS = 6L * 60 * 60 * 1000
        const val OPTIONAL_STARTUP_DELAY_MS = 2_500L
    }
}
