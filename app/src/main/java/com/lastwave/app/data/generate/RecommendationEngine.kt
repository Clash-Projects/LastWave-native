package com.lastwave.app.data.generate

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random

private const val RTAG = "RecommendationEngine"

/** Core recommendation constants plus a bounded API-refill guard. */
private const val RECO_MAX_CYCLES = 5
private const val RECO_REFILL_ATTEMPTS = 24
private const val RECO_ARTIST_CAP = 2
private const val RECO_ALBUM_CAP = 2
private const val RECO_GENRE_CAP_RATIO = 0.35

/** Port of _RECO_GENRE_NEIGHBORS — widens genre discovery without drifting
 *  away from the user's taste. */
private val RECO_GENRE_NEIGHBORS = mapOf(
    "indie rock" to "alternative rock", "alternative rock" to "indie rock",
    "house" to "deep house", "deep house" to "tech house", "tech house" to "techno", "techno" to "trance",
    "hip hop" to "rap", "hip-hop" to "boom bap", "rap" to "hip hop",
    "metal" to "heavy metal", "heavy metal" to "metal", "black metal" to "doom metal", "death metal" to "doom metal",
    "pop" to "indie pop", "indie pop" to "dream pop", "dream pop" to "shoegaze", "shoegaze" to "dream pop",
    "jazz" to "soul", "soul" to "r&b", "r&b" to "neo-soul", "funk" to "soul",
    "electronic" to "experimental", "ambient" to "chillwave", "lo-fi" to "chillwave", "chillwave" to "synthwave",
    "punk" to "post-punk", "post-punk" to "new wave", "new wave" to "synth-pop",
    "folk" to "indie folk", "indie folk" to "folk", "country" to "folk",
    "trap" to "drill", "drill" to "trap", "drum and bass" to "jungle", "jungle" to "drum and bass",
)

/** One pooled candidate — port of the pool Map's `{ track, weight, tags, isPrimaryGenre }`. */
private data class Candidate(
    var track: GeneratedTrack,
    var weight: Int,
    val tags: MutableSet<String> = mutableSetOf(),
    var isPrimaryGenre: Boolean = false,
)

private data class Scored(
    val track: GeneratedTrack,
    val weight: Int,
    val tags: Set<String>,
    val fresh: Boolean,
    val score: Double,
)

/** Mutable per-run context threaded through the source pipeline — port of `ctx` in app.js. */
private class RecoContext(
    val total: Int,
    val profile: TasteProfile,
    val blacklist: Set<String>,
) {
    val pool = LinkedHashMap<String, Candidate>()
    val exploredArtists = mutableSetOf<String>()
    val exploredTags = mutableSetOf<String>()
    val pendingArtists = mutableListOf<String>()
    private val lock = Any()

    fun addAll(tracks: List<GeneratedTrack>, weight: Int, tag: String, isPrimaryGenre: Boolean = false) {
        synchronized(lock) {
            for (t in tracks) {
                if (t.name.isBlank() || t.artist.isBlank()) continue
                val key = t.key
                if (key in blacklist) continue
                val cur = pool[key]
                if (cur != null) {
                    if (weight > cur.weight) cur.weight = weight
                    cur.tags.add(tag)
                } else {
                    pool[key] = Candidate(t, weight, mutableSetOf(tag), isPrimaryGenre)
                }
            }
        }
    }
}

/**
 * Port of app.js's scoring/interleave machinery and 7-source pipeline.
 * Selection is intentionally stricter here: history freshness never
 * relaxes, and the source pipeline refills instead of using heard tracks.
 *
 * [rawCall] is GenerateRepository's authenticated Last.fm call primitive —
 * injected rather than duplicated so this engine and the simple fetch
 * modes share one signing/error path.
 */
class RecommendationEngine(
    private val rawCall: suspend (Map<String, String>) -> kotlinx.serialization.json.JsonObject,
    private val isFresh: suspend (List<GeneratedTrack>) -> List<GeneratedTrack>,
    private val onProgress: (String) -> Unit,
) {
    private fun jsonTracks(obj: kotlinx.serialization.json.JsonObject, vararg path: String): List<GeneratedTrack> {
        var el: kotlinx.serialization.json.JsonElement? = obj
        for (p in path) el = (el as? kotlinx.serialization.json.JsonObject)?.get(p)
        return GenerateJson.normalise(el)
    }

    private fun jsonNames(obj: kotlinx.serialization.json.JsonObject, vararg path: String): List<String> {
        var el: kotlinx.serialization.json.JsonElement? = obj
        for (p in path) el = (el as? kotlinx.serialization.json.JsonObject)?.get(p)
        return GenerateJson.namesOf(el)
    }

    // ── Scoring — exact port of _recoGemScore / _recoCommunityScore / _recoMatchBonus / _recoScore ──

    private fun gemScore(track: GeneratedTrack): Int {
        val n = track.listeners ?: return 10
        return when {
            n < 3000 -> 36
            n < 10000 -> 30
            n < 30000 -> 22
            n < 100000 -> 12
            n < 400000 -> 0
            n < 1500000 -> -14
            else -> -28
        }
    }

    private fun communityScore(track: GeneratedTrack): Int {
        val listeners = track.listeners ?: return 0
        val playcount = track.playcount ?: return 0
        if (listeners == 0L) return 0
        val ratio = playcount.toDouble() / listeners.toDouble()
        return when {
            ratio >= 6 -> 14
            ratio >= 3 -> 8
            ratio >= 1.5 -> 3
            else -> 0
        }
    }

    private fun matchBonus(track: GeneratedTrack): Int =
        track.match?.let { (it * 26).roundToInt() } ?: 0

    private fun recoScore(track: GeneratedTrack, profile: TasteProfile, bucketWeight: Int, isPrimaryGenre: Boolean, sourceCount: Int, fresh: Boolean): Double {
        var score = 0.0
        val bw = if (bucketWeight <= 0) 1 else bucketWeight

        if (bw >= 4) score += 34 else if (bw >= 3) score += 26 else if (bw >= 2) score += 18

        val artistKey = track.artist.lowercase()
        val knownArtist = profile.topArtistNames.contains(artistKey) || profile.recentArtists.contains(artistKey)
        if (profile.topArtistNames.contains(artistKey)) score += 22
        if (profile.recentArtists.contains(artistKey)) score += 10
        if (!knownArtist) score += 9
        if (isPrimaryGenre) score += 6

        if (sourceCount >= 3) score += 18 else if (sourceCount == 2) score += 9

        score += matchBonus(track)
        score += gemScore(track)
        score += communityScore(track)

        score += if (fresh) 6 else -20
        score += Random.nextDouble() * 6

        return score
    }

    // ── Candidate sources — parallel ports matching Promise.allSettled in app.js ──

    private suspend fun srcSimilarTracks(ctx: RecoContext, cycle: Int) = coroutineScope {
        val pool = if (cycle == 1) ctx.profile.recentTracksRaw else ctx.profile.topTracksRaw
        val seeds = pool.shuffled().drop((cycle - 1) * 3).take(3)
        val deferreds = seeds.map { s ->
            async {
                if (s.name.isNotBlank() && s.artist.isNotBlank()) {
                    try {
                        val d = rawCall(mapOf("method" to "track.getsimilar", "track" to s.name, "artist" to s.artist, "limit" to "30"))
                        jsonTracks(d, "similartracks", "track")
                    } catch (e: Exception) {
                        Log.d(RTAG, "srcSimilarTracks miss for ${s.name}", e)
                        emptyList()
                    }
                } else emptyList()
            }
        }
        val results = deferreds.awaitAll()
        for (tracks in results) {
            if (tracks.isNotEmpty()) {
                ctx.addAll(tracks, if (cycle == 1) 4 else 3, if (cycle == 1) "mood" else "taste")
            }
        }
    }

    private suspend fun srcSimilarArtists(ctx: RecoContext, cycle: Int) = coroutineScope {
        val artists = ctx.profile.topArtistNames.shuffled().drop((cycle - 1) * 3).take(3)
        val deferreds = artists.map { artistName ->
            async {
                try {
                    val d = rawCall(mapOf("method" to "artist.getsimilar", "artist" to artistName, "limit" to "20"))
                    jsonNames(d, "similarartists", "artist").shuffled()
                } catch (e: Exception) {
                    Log.d(RTAG, "srcSimilarArtists miss for $artistName", e)
                    emptyList()
                }
            }
        }
        val results = deferreds.awaitAll()
        for (sims in results) {
            for (sa in sims.take(5)) {
                val ak = sa.lowercase()
                if (ctx.exploredArtists.contains(ak)) continue
                ctx.exploredArtists.add(ak)
                ctx.pendingArtists.add(sa)
            }
        }
    }

    private suspend fun srcArtistTopTracks(ctx: RecoContext) = coroutineScope {
        val batch = ctx.pendingArtists.take(4)
        repeat(batch.size) { ctx.pendingArtists.removeAt(0) }
        val deferreds = batch.map { artistName ->
            async {
                try {
                    val page = ceil(Random.nextDouble() * 6).toInt().coerceAtLeast(1)
                    val d = rawCall(mapOf("method" to "artist.gettoptracks", "artist" to artistName, "limit" to "10", "page" to page.toString()))

                    artistName to jsonTracks(d, "toptracks", "track")
                } catch (e: Exception) {
                    Log.d(RTAG, "srcArtistTopTracks miss for $artistName", e)
                    artistName to emptyList()
                }
            }
        }
        val results = deferreds.awaitAll()
        for ((artistName, tracks) in results) {
            if (tracks.isNotEmpty()) {
                ctx.addAll(tracks, 2, "artist:${artistName.lowercase()}")
            }
        }
    }

    private suspend fun srcGenreMatches(ctx: RecoContext, cycle: Int) = coroutineScope {
        val tags = ctx.profile.topTags.toList().shuffled().drop((cycle - 1) * 4).take(4)
        for (tag in tags) ctx.exploredTags.add(tag)
        val limit = ceil(ctx.total * 0.4).toInt().coerceAtLeast(1)
        val deferreds = tags.map { tag ->
            async {
                try {
                    val page = (Random.nextDouble() * 10).toInt() + 2
                    val d = rawCall(mapOf("method" to "tag.gettoptracks", "tag" to tag, "limit" to limit.toString(), "page" to page.toString()))
                    tag to jsonTracks(d, "tracks", "track")
                } catch (e: Exception) {
                    Log.d(RTAG, "srcGenreMatches miss for $tag", e)
                    tag to emptyList()
                }
            }
        }
        val results = deferreds.awaitAll()
        for ((tag, tracks) in results) {
            if (tracks.isNotEmpty()) {
                ctx.addAll(tracks, 1, "tag:$tag", isPrimaryGenre = true)
            }
        }
    }

    private suspend fun srcTagMatches(ctx: RecoContext) = coroutineScope {
        val neighbors = ctx.exploredTags.mapNotNull { RECO_GENRE_NEIGHBORS[it] }
        val unique = neighbors.distinct().filter { !ctx.exploredTags.contains(it) }.take(4)
        for (tag in unique) ctx.exploredTags.add(tag)
        val limit = ceil(ctx.total * 0.3).toInt().coerceAtLeast(1)
        val deferreds = unique.map { tag ->
            async {
                try {
                    val page = (Random.nextDouble() * 6).toInt() + 1
                    val d = rawCall(mapOf("method" to "tag.gettoptracks", "tag" to tag, "limit" to limit.toString(), "page" to page.toString()))
                    tag to jsonTracks(d, "tracks", "track")
                } catch (e: Exception) {
                    Log.d(RTAG, "srcTagMatches miss for $tag", e)
                    tag to emptyList()
                }
            }
        }
        val results = deferreds.awaitAll()
        for ((tag, tracks) in results) {
            if (tracks.isNotEmpty()) {
                ctx.addAll(tracks, 1, "tag:$tag", isPrimaryGenre = false)
            }
        }
    }

    private suspend fun srcRelatedArtists(ctx: RecoContext) = coroutineScope {
        val seedArtists = ctx.exploredArtists.shuffled().take(4)
        val deferreds = seedArtists.map { artistName ->
            async {
                try {
                    val d = rawCall(mapOf("method" to "artist.getsimilar", "artist" to artistName, "limit" to "15"))
                    jsonNames(d, "similarartists", "artist").shuffled()
                } catch (e: Exception) {
                    Log.d(RTAG, "srcRelatedArtists miss for $artistName", e)
                    emptyList()
                }
            }
        }
        val results = deferreds.awaitAll()
        for (sims in results) {
            for (sa in sims) {
                val ak = sa.lowercase()
                if (ctx.exploredArtists.contains(ak)) continue
                ctx.exploredArtists.add(ak)
                ctx.pendingArtists.add(sa)
            }
        }
        srcArtistTopTracks(ctx)
    }

    private suspend fun srcDiscoveryPool(ctx: RecoContext) = coroutineScope {
        val wide = ctx.profile.topArtistNames.shuffled().take(3)
        val deferreds = wide.map { artistName ->

            async {
                try {
                    val page = ceil(Random.nextDouble() * 8).toInt().coerceAtLeast(1)
                    val d = rawCall(mapOf("method" to "artist.gettoptracks", "artist" to artistName, "limit" to "15", "page" to page.toString()))
                    artistName to jsonTracks(d, "toptracks", "track")
                } catch (e: Exception) {
                    Log.d(RTAG, "srcDiscoveryPool miss for $artistName", e)
                    artistName to emptyList()
                }
            }
        }
        val results = deferreds.awaitAll()
        for ((artistName, tracks) in results) {
            if (tracks.isNotEmpty()) {
                ctx.addAll(tracks, 2, "artist:${artistName.lowercase()}")
            }
        }
    }

    /** Targeted fallback used only while fewer than [RecoContext.total]
     *  eligible tracks exist. Random pages expand the pool without ever
     *  bypassing [RecoContext.blacklist]. */
    private suspend fun srcFreshRefill(ctx: RecoContext, attempt: Int) {
        val limit = maxOf(50, ctx.total * 2).coerceAtMost(200).toString()
        val tag = ctx.profile.topTags.randomOrNull()
        val artist = ctx.profile.topArtistNames.randomOrNull()
        val useTag = tag != null && (attempt % 2 == 0 || artist == null)

        if (useTag) {
            try {
                val page = 2 + ((attempt * 7) % 48)
                val data = rawCall(
                    mapOf(
                        "method" to "tag.gettoptracks",
                        "tag" to tag!!,
                        "limit" to limit,
                        "page" to page.toString(),
                    ),
                )
                ctx.addAll(jsonTracks(data, "tracks", "track"), 1, "tag:$tag", isPrimaryGenre = true)
            } catch (e: Exception) {
                Log.d(RTAG, "fresh tag refill miss for $tag", e)
            }
            return
        }

        if (artist != null) {
            try {
                val similar = rawCall(
                    mapOf("method" to "artist.getsimilar", "artist" to artist, "limit" to "30"),
                )
                val candidateArtist = jsonNames(similar, "similarartists", "artist").shuffled().firstOrNull()
                    ?: return
                val page = 1 + ((attempt * 5) % 12)
                val data = rawCall(
                    mapOf(
                        "method" to "artist.gettoptracks",
                        "artist" to candidateArtist,
                        "limit" to limit,
                        "page" to page.toString(),
                    ),
                )
                ctx.addAll(tracks = jsonTracks(data, "toptracks", "track"), weight = 2, tag = "artist:${candidateArtist.lowercase()}")
            } catch (e: Exception) {
                Log.d(RTAG, "fresh artist refill miss for $artist", e)
            }
        }
    }

    // ── Selection — progressive diversity relaxation, strict freshness ──

    private fun selectFinal(scored: List<Scored>, total: Int): List<GeneratedTrack> {
        val genreCap = maxOf(2, ceil(total * RECO_GENRE_CAP_RATIO).toInt())

        fun attempt(artistCap: Int, albumCapOn: Boolean, genreCapOn: Boolean, freshOnly: Boolean): List<GeneratedTrack> {
            val artistCount = mutableMapOf<String, Int>()
            val albumCount = mutableMapOf<String, Int>()
            val genreCount = mutableMapOf<String, Int>()
            val pickedKeys = mutableSetOf<String>()
            val picked = mutableListOf<GeneratedTrack>()
            for (c in scored) {
                val key = c.track.key
                if (key in pickedKeys) continue
                if (freshOnly && !c.fresh) continue
                val ak = c.track.artist.lowercase()
                if ((artistCount[ak] ?: 0) >= artistCap) continue
                if (albumCapOn && !c.track.album.isNullOrBlank()) {
                    val alK = c.track.album.lowercase()
                    if ((albumCount[alK] ?: 0) >= RECO_ALBUM_CAP) continue
                }
                if (genreCapOn) {
                    val genreTag = c.tags.firstOrNull { it.startsWith("tag:") }
                    if (genreTag != null) {
                        if ((genreCount[genreTag] ?: 0) >= genreCap) continue
                        genreCount[genreTag] = (genreCount[genreTag] ?: 0) + 1
                    }
                }
                artistCount[ak] = (artistCount[ak] ?: 0) + 1
                if (!c.track.album.isNullOrBlank()) {
                    val alK = c.track.album.lowercase()
                    albumCount[alK] = (albumCount[alK] ?: 0) + 1
                }
                pickedKeys.add(key)
                picked.add(c.track)
                if (picked.size >= total) break
            }
            return picked
        }

        // Diversity may relax, freshness never does. A recommendation track
        // already present in Discovery History must never fill a thin pool.
        var result = attempt(RECO_ARTIST_CAP, true, true, true)
        if (result.size < total) result = attempt(3, true, true, true)
        if (result.size < total) result = attempt(3, true, false, true)
        if (result.size < total) result = attempt(3, false, false, true)
        if (result.size < total) result = attempt(99, false, false, true)
        return result
    }

    /** Port of _recoInterleave: round-robin re-order by genre bucket so the
     *  same tag doesn't play twice in a row when it can be avoided. */
    private fun interleave(tracks: List<GeneratedTrack>, tagsByKey: Map<String, Set<String>>): List<GeneratedTrack> {
        val buckets = LinkedHashMap<String, ArrayDeque<GeneratedTrack>>()
        for (t in tracks) {
            val tags = tagsByKey[t.key]
            val genreTag = tags?.firstOrNull { it.startsWith("tag:") }
            val label = genreTag ?: "other"
            buckets.getOrPut(label) { ArrayDeque() }.add(t)
        }
        val labels = buckets.keys.toList()
        val out = mutableListOf<GeneratedTrack>()
        var lastLabel: String? = null
        while (out.size < tracks.size) {
            var pickedIdx = labels.indexOfFirst { l -> buckets[l]!!.isNotEmpty() && l != lastLabel }
            if (pickedIdx == -1) pickedIdx = labels.indexOfFirst { l -> buckets[l]!!.isNotEmpty() }
            if (pickedIdx == -1) break
            val label = labels[pickedIdx]
            val track = buckets[label]!!.removeFirst()
            out.add(track)
            lastLabel = label
        }
        return out
    }

    /** Main entry point for a complete, strictly fresh recommendation set. */
    suspend fun run(total: Int, profile: TasteProfile, blacklist: Set<String>): List<GeneratedTrack> {
        onProgress("Reading your listening mood\u2026")
        val ctx = RecoContext(total, profile, blacklist)

        var cycle = 1
        while (cycle <= RECO_MAX_CYCLES && ctx.pool.size < total) {
            onProgress(if (cycle == 1) "Reading your listening mood\u2026" else "Digging deeper for more discoveries (round $cycle)\u2026")

            srcSimilarTracks(ctx, cycle)
            if (ctx.pool.size < total) {
                srcSimilarArtists(ctx, cycle)
                srcArtistTopTracks(ctx)
            }
            if (ctx.pool.size < total) srcGenreMatches(ctx, cycle)
            if (ctx.pool.size < total) srcTagMatches(ctx)
            if (ctx.pool.size < total) srcRelatedArtists(ctx)
            if (ctx.pool.size < total) srcDiscoveryPool(ctx)

            val freshKeys = isFresh(ctx.pool.values.map { it.track }).map { it.key }.toSet()
            val freshCount = ctx.pool.keys.count { it in freshKeys }
            Log.d(RTAG, "cycle $cycle: pool=${ctx.pool.size} unique, fresh=$freshCount, need=$total")

            cycle++
        }

        var refillAttempt = 0
        while (ctx.pool.size < total && refillAttempt < RECO_REFILL_ATTEMPTS) {
            onProgress("Finding fresh tracks\u2026 ${ctx.pool.size}/$total")
            srcFreshRefill(ctx, refillAttempt)
            refillAttempt++
        }

        onProgress("Curating your personal recommendations\u2026")

        val allTracks = ctx.pool.values.map { it.track }
        val freshKeysFinal = isFresh(allTracks).map { it.key }.toSet()

        val scored = ctx.pool.values.map { c ->
            val fresh = c.track.key in freshKeysFinal
            Scored(
                track = c.track,
                weight = c.weight,
                tags = c.tags,
                fresh = fresh,
                score = recoScore(c.track, profile, c.weight, c.isPrimaryGenre, c.tags.size, fresh),
            )
        }.sortedWith(compareByDescending<Scored> { it.fresh }.thenByDescending { it.score })

        var final = selectFinal(scored, total)
        if (final.isEmpty() && ctx.pool.isNotEmpty()) {
            final = ctx.pool.values.map { it.track }.take(total)
        }
        Log.d(RTAG, "selected ${final.size}/$total from candidate pool")

        // Dedup + cap
        val seen = mutableSetOf<String>()
        final = final.filter { seen.add(it.key) }.take(total)

        val tagsByKey = scored.associate { it.track.key to it.tags }
        final = interleave(final, tagsByKey)

        // Playlist flow: strongest track opens, second-strongest closes.
        if (final.size >= 3) {
            val scoreByKey = scored.associate { it.track.key to it.score }
            val order = final.sortedByDescending { scoreByKey[it.key] ?: 0.0 }
            val opener = order[0]
            val closer = order.firstOrNull { it.key != opener.key } ?: order[1]
            final = final.filter { it.key != opener.key && it.key != closer.key }
            final = listOf(opener) + final + listOf(closer)
        }

        Log.d(RTAG, "FINAL: ${final.size}/$total tracks returned")
        return final
    }
}
