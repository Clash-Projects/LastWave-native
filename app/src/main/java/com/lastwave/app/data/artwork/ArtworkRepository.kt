package com.lastwave.app.data.artwork

import android.util.Log
import com.lastwave.app.data.local.db.ArtworkCacheDao
import com.lastwave.app.data.local.db.ArtworkCacheEntity
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ArtworkPipeline"
private const val CRASH_TAG = "ArtworkCrash"

/** 30 days — same TTL as the original's _ART_DISK_TTL. */
private const val DISK_CACHE_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000

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
) {
    private val _resolved = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolved: StateFlow<Map<String, String>> = _resolved.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            try {
                val cachedEntities = cacheDao.getAll()
                val now = System.currentTimeMillis()
                val validMap = cachedEntities
                    .filter { it.url.isNotBlank() && now - it.timestampMillis < DISK_CACHE_TTL_MILLIS }
                    .associate { it.cacheKey to it.url }
                _resolved.update { validMap + it }
                Log.d(TAG, "Pre-warmed memory cache with ${validMap.size} artwork entries")
            } catch (e: Exception) {
                Log.e(CRASH_TAG, "Error pre-warming artwork cache from DB", e)
            }
        }
    }

    // Avoids firing a second lookup for a track that's already mid-resolve.
    private val inFlight = mutableSetOf<String>()
    private val inFlightMutex = Mutex()

    /** Public entry point. Deliberately catches Throwable, not just
     *  Exception — an artwork miss must never take the app down, full stop,
     *  including on an Error subtype from a misbehaving library. */
    suspend fun resolve(name: String, artist: String) {
        val key = ArtworkNormalizer.cacheKey(name, artist)
        try {
            resolveInternal(key, name, artist)
        } catch (t: Throwable) {
            Log.e(CRASH_TAG, "Artwork lookup crashed and was suppressed | Track: $name | Artist: $artist | Cache key: $key", t)
            inFlightMutex.withLock { inFlight.remove(key) }
        }
    }

    private suspend fun resolveInternal(key: String, name: String, artist: String) {
        Log.d(TAG, "Artwork lookup: | Artist: $artist | Track: $name | Cache key: $key")

        // 1. Memory cache — instant, no I/O.
        _resolved.value[key]?.let {
            if (it.isNotBlank()) {
                Log.d(TAG, "Cache hit (memory) | Track: $name | Artist: $artist")
                return
            }
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
                Log.e(CRASH_TAG, "Room read failed, treating as cache miss | Track: $name | Artist: $artist", e)
                null
            }
            if (cached != null && cached.url.isNotBlank() && System.currentTimeMillis() - cached.timestampMillis < DISK_CACHE_TTL_MILLIS) {
                Log.d(TAG, "Cache hit (disk) | Track: $name | Artist: $artist | Provider: ${cached.provider} | Downloaded artwork URL: ${cached.url}")
                publish(key, cached.url)
                return
            }
            Log.d(TAG, "Cache miss | Track: $name | Artist: $artist")

            // 3. Last.fm track.getInfo — first real network tier.
            val fromLastFm = safeFetch("Last.fm track.getInfo", name, artist) { lastFm.fetchArtworkUrl(name, artist) }
            if (!fromLastFm.isNullOrBlank()) {
                Log.d(TAG, "Image loaded successfully | Provider: lastfm | Track: $name | Artist: $artist | Downloaded artwork URL: $fromLastFm")
                save(key, "lastfm", fromLastFm)
                return
            }

            // 4. iTunes fallback
            val fromItunes = safeFetch("iTunes", name, artist) { itunes.fetchArtworkUrl(name, artist) }
            if (!fromItunes.isNullOrBlank()) {
                Log.d(TAG, "Image loaded successfully | Provider: itunes | Track: $name | Artist: $artist | Downloaded artwork URL: $fromItunes")
                save(key, "itunes", fromItunes)
                return
            }

            // 5. YouTube Music catalog artwork fallback (universal coverage)
            val fromInnerTube = safeFetch("YouTube Music", name, artist) {
                innerTube.findBestMatch(name, artist).artworkUrl
            }
            if (!fromInnerTube.isNullOrBlank()) {
                Log.d(TAG, "Image loaded successfully | Provider: youtube | Track: $name | Artist: $artist | Downloaded artwork URL: $fromInnerTube")
                save(key, "youtube", fromInnerTube)
                return
            }

            // 6. In-memory temporary placeholder (don't permanently save empty to DB)
            publish(key, "")
        } finally {
            inFlightMutex.withLock { inFlight.remove(key) }
        }
    }

    /** Runs one provider call with its own try/catch, so a provider that
     *  throws instead of returning null (a bug in that provider, a library
     *  exception, anything) still can't propagate past this point. */
    private suspend fun safeFetch(providerName: String, name: String, artist: String, block: suspend () -> String?): String? =
        try {
            block()
        } catch (e: Exception) {
            Log.e(CRASH_TAG, "Provider threw and was suppressed | Provider: $providerName | Track: $name | Artist: $artist", e)
            null
        }

    private suspend fun save(key: String, provider: String, url: String) {
        try {
            cacheDao.upsert(ArtworkCacheEntity(key, url, provider, System.currentTimeMillis()))
        } catch (e: Exception) {
            Log.e(CRASH_TAG, "Room write failed | Cache key: $key | Provider: $provider", e)
            // Still publish to the in-memory tier even if the disk write
            // failed — the UI should show the art this session even if it
            // won't be cached for next launch.
        }
        publish(key, url)
    }

    private fun publish(key: String, url: String) {
        _resolved.update { it + (key to url) }
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
