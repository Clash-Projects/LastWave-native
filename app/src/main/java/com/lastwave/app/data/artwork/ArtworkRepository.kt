package com.lastwave.app.data.artwork

import android.util.Log
import com.lastwave.app.data.local.db.ArtworkCacheDao
import com.lastwave.app.data.local.db.ArtworkCacheEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ArtworkPipeline"
private const val CRASH_TAG = "ArtworkCrash"

/** 30 days — same TTL as the original's _ART_DISK_TTL. */
private const val DISK_CACHE_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
private const val MAX_ARTWORK_DB_ENTRIES = 1_000
private const val ARTWORK_DB_CLEANUP_INTERVAL = 100
private const val MAX_ARTWORK_MEMORY_ENTRIES = 600

/** 30s per-track cooldown for the "Refresh Cover Art" force-refresh action —
 *  matches §1.7's spec exactly. */
private const val FORCE_REFRESH_COOLDOWN_MILLIS = 30_000L

/**
 * Faithful port of _resolveTrackArt(): memory cache -> disk (Room) cache ->
 * Last.fm track.getInfo -> iTunes. No provider chain beyond what the
 * original app actually has.
 *
 * Every entry point here is wrapped so a failure anywhere in this pipeline —
 * a Room error, a network exception, a malformed response — is caught,
 * logged, and treated as "no artwork yet" (falls through to the fallback
 * icon). It NEVER rethrows: an artwork lookup failing must never crash the
 * screen it's running on.
 */
@Singleton
class ArtworkRepository @Inject constructor(
    private val cacheDao: ArtworkCacheDao,
    private val lastFm: LastFmTrackInfoProvider,
    private val itunes: ITunesArtworkProvider,
    private val innerTube: com.lastwave.app.data.music.InnerTubeMusicApi,
    private val http: okhttp3.OkHttpClient,
) {
    private val _resolved = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolved: StateFlow<Map<String, String>> = _resolved.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val savesSinceTrim = AtomicInteger(0)

    init {
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                // Only prewarm what the in-memory cache can retain. Reading
                // all 1,000 rows just to discard 400 on the first new cover
                // inflated startup DB work and the first map copy.
                val cachedEntities = cacheDao.getNewest(MAX_ARTWORK_MEMORY_ENTRIES).asReversed()
                val validMap = cachedEntities
                    .filter { it.url.isNotBlank() && now - it.timestampMillis < DISK_CACHE_TTL_MILLIS }
                    .associate { it.cacheKey to it.url }
                _resolved.update { validMap + it }
                Log.d(TAG, "Pre-warmed memory cache with ${validMap.size} artwork entries")
                cacheDao.deleteOlderThan(now - DISK_CACHE_TTL_MILLIS)
                cacheDao.trimToNewest(MAX_ARTWORK_DB_ENTRIES)
            } catch (e: Exception) {
                Log.e(CRASH_TAG, "Error pre-warming artwork cache from DB", e)
            }
        }
    }

    // Avoids firing a second lookup for a track that's already mid-resolve.
    private val inFlight = mutableSetOf<String>()
    private val inFlightMutex = Mutex()
    // One visible list can request dozens of missing covers at once. Each
    // cover races three providers, so stay within the shared client's
    // 24-request ceiling while filling more than four visible rows at once.
    private val networkRaceSemaphore = Semaphore(8)

    /** Public entry point. Deliberately catches Throwable, not just
     *  Exception — an artwork miss must never take the app down, full stop. */
    suspend fun resolve(name: String, artist: String) {
        val key = ArtworkNormalizer.cacheKey(name, artist)
        try {
            resolveInternal(key, name, artist)
        } catch (cancellation: CancellationException) {
            inFlightMutex.withLock { inFlight.remove(key) }
            throw cancellation
        } catch (t: Throwable) {
            Log.e(CRASH_TAG, "Artwork lookup crashed and was suppressed | Track: $name | Artist: $artist | Cache key: $key", t)
            inFlightMutex.withLock { inFlight.remove(key) }
        }
    }

    private suspend fun resolveInternal(key: String, name: String, artist: String) {
        // 1. Memory cache — instant, 0ms
        _resolved.value[key]?.let {
            if (it.isNotBlank()) return
        }

        val alreadyRunning = inFlightMutex.withLock {
            if (key in inFlight) true else { inFlight.add(key); false }
        }
        if (alreadyRunning) return

        try {
            // 2. Disk (Room) cache — persists across relaunches.
            val cached = try {
                cacheDao.get(key)
            } catch (e: Exception) {
                null
            }
            if (cached != null && cached.url.isNotBlank() && System.currentTimeMillis() - cached.timestampMillis < DISK_CACHE_TTL_MILLIS) {
                publish(key, cached.url)
                return
            }

            // 3. Multi-provider race, bounded across all visible rows.
            val winner = networkRaceSemaphore.withPermit {
                val channel = kotlinx.coroutines.channels.Channel<Pair<String, String?>>(3)
                val jobs = mutableListOf<kotlinx.coroutines.Job>()
                jobs += scope.launch(Dispatchers.IO) {
                    channel.trySend("deezer" to fetchDeezer(name, artist))
                }
                jobs += scope.launch(Dispatchers.IO) {
                    channel.trySend("itunes" to safeFetch("iTunes", name, artist) {
                        itunes.fetchArtworkUrl(name, artist)
                    })
                }
                jobs += scope.launch(Dispatchers.IO) {
                    channel.trySend("youtube" to fetchYouTubeMusic(name, artist))
                }
                try {
                    kotlinx.coroutines.withTimeoutOrNull(3_000L) {
                        repeat(3) {
                            val (provider, url) = channel.receive()
                            if (!url.isNullOrBlank()) return@withTimeoutOrNull provider to url
                        }
                        null
                    }
                } finally {
                    channel.close()
                    jobs.forEach { it.cancel() }
                }
            }

            if (winner != null) {
                save(key, winner.first, winner.second)
            } else {
                publish(key, "")
            }
        } finally {
            inFlightMutex.withLock { inFlight.remove(key) }
        }
    }

    private suspend fun fetchDeezer(name: String, artist: String): String? = withContext(Dispatchers.IO) {
        val query = if (artist.isNotBlank()) "$name $artist" else name
        val url = "https://api.deezer.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=1"
        try {
            val req = okhttp3.Request.Builder().url(url).build()
            val body = http.newCall(req).awaitSuccessfulBodyOrNull() ?: return@withContext null
            val jsonEl = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
            val data = jsonEl?.get("data") as? kotlinx.serialization.json.JsonArray
            val first = data?.firstOrNull() as? kotlinx.serialization.json.JsonObject
            val album = first?.get("album") as? kotlinx.serialization.json.JsonObject
            (album?.get("cover_big") as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: (album?.get("cover_xl") as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: ((first?.get("artist") as? kotlinx.serialization.json.JsonObject)?.get("picture_xl") as? kotlinx.serialization.json.JsonPrimitive)?.content
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchYouTubeMusic(name: String, artist: String): String? = withContext(Dispatchers.IO) {
        try {
            val query = if (artist.isNotBlank()) "$name $artist" else name
            val results = innerTube.searchSongs(query, limit = 2, prefetchStreams = false)
            results.firstOrNull()?.artworkUrl
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    /** Runs one provider call with its own try/catch */
    private suspend fun safeFetch(providerName: String, name: String, artist: String, block: suspend () -> String?): String? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.e(CRASH_TAG, "Provider threw and was suppressed | Provider: $providerName | Track: $name | Artist: $artist", e)
            null
        }

    private suspend fun save(key: String, provider: String, url: String) {
        publish(key, url)
        try {
            cacheDao.upsert(ArtworkCacheEntity(key, url, provider, System.currentTimeMillis()))
            if (savesSinceTrim.incrementAndGet() >= ARTWORK_DB_CLEANUP_INTERVAL &&
                savesSinceTrim.getAndSet(0) >= ARTWORK_DB_CLEANUP_INTERVAL
            ) {
                cacheDao.deleteOlderThan(System.currentTimeMillis() - DISK_CACHE_TTL_MILLIS)
                cacheDao.trimToNewest(MAX_ARTWORK_DB_ENTRIES)
            }
        } catch (e: Exception) {
            Log.e(CRASH_TAG, "Room write failed | Cache key: $key | Provider: $provider", e)
        }
    }

    private fun publish(key: String, url: String) {
        _resolved.update { current ->
            val next = current + (key to url)
            if (next.size <= MAX_ARTWORK_MEMORY_ENTRIES) next
            else next.entries.drop(next.size - MAX_ARTWORK_MEMORY_ENTRIES).associate { it.key to it.value }
        }
    }

    // ── Additions for §1.7 "Refresh Cover Art" + §4.2/§4.7 batch pre-warm ──

    private val lastForceRefresh = mutableMapOf<String, Long>()
    private val forceRefreshMutex = Mutex()

    /** Port of the "Refresh Cover Art" menu action: bypasses the memory +
     *  disk cache entirely and re-fetches from network, with a 30s per-
     *  track cooldown to prevent hammering both providers on repeat taps. */
    suspend fun forceRefresh(name: String, artist: String) {
        val key = ArtworkNormalizer.cacheKey(name, artist)
        val now = System.currentTimeMillis()
        val allowed = forceRefreshMutex.withLock {
            val last = lastForceRefresh[key] ?: 0L
            if (now - last < FORCE_REFRESH_COOLDOWN_MILLIS) false else {
                lastForceRefresh[key] = now
                true
            }
        }
        if (!allowed) return

        try {
            val fromLastFm = safeFetch("Last.fm track.getInfo (force)", name, artist) { lastFm.fetchArtworkUrl(name, artist) }
            val resolved = if (!fromLastFm.isNullOrBlank()) {
                save(key, "lastfm", fromLastFm)
                fromLastFm
            } else {
                val fromItunes = safeFetch("iTunes (force)", name, artist) { itunes.fetchArtworkUrl(name, artist) }
                if (!fromItunes.isNullOrBlank()) {
                    save(key, "itunes", fromItunes)
                    fromItunes
                } else {
                    save(key, "none", "")
                    ""
                }
            }
            Log.d(TAG, "Force-refreshed artwork | Track: $name | Artist: $artist | Result: $resolved")
        } catch (t: Throwable) {
            Log.e(CRASH_TAG, "Force-refresh crashed and was suppressed | Track: $name | Artist: $artist", t)
        }
    }

    /** Fire-and-forget batch warm — used to pre-enrich the first few tracks
     *  of a newly-saved playlist so its cover grid isn't empty on first
     *  render (§4.2, §4.7). Each item runs through the normal cached
     *  resolve() path, not forceRefresh(). */
    suspend fun enrichBatch(items: List<Pair<String, String>>) {
        // Chunk size matched to the OkHttp client's now-higher
        // maxRequestsPerHost (see NetworkModule) — the old chunk of 5 was
        // sized for the previous default limit, artificially throttling
        // batch pre-warms even after the client itself could handle more.
        items.chunked(10).forEach { batch ->
            coroutineScope {
                batch.map { (name, artist) -> launch { resolve(name, artist) } }.forEach { it.join() }
            }
        }
    }
}
