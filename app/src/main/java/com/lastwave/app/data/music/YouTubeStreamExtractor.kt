package com.lastwave.app.data.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the signed/ciphered YouTube media URLs that raw InnerTube player
 * responses no longer reliably expose. Extraction happens locally; no proxy
 * or account is used.
 */
@Singleton
class YouTubeStreamExtractor @Inject constructor(
    http: OkHttpClient,
) {
    private val downloader = OkHttpNewPipeDownloader(http)

    @Volatile
    private var initialized = false

    private val streamCache = ConcurrentHashMap<String, Pair<Long, YouTubeAudioStream>>()

    fun invalidateCache(videoId: String) {
        streamCache.remove(videoId)
    }

    suspend fun resolveAudioStream(videoId: String, preferM4a: Boolean = false): YouTubeAudioStream = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!preferM4a) {
            streamCache[videoId]?.let { (cachedAt, stream) ->
                if (now - cachedAt < CACHE_EXPIRY_MS) {
                    return@withContext stream
                }
            }
        }

        initialize()
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        } catch (error: Exception) {
            throw IOException("YouTube stream extraction failed for $videoId", error)
        }
        val stream = if (preferM4a) {
            info.audioStreams
                .filter { it.format?.mimeType?.contains("mp4") == true || it.format?.mimeType?.contains("m4a") == true }
                .maxByOrNull { maxOf(it.averageBitrate, it.bitrate) }
                ?: info.audioStreams.maxByOrNull { maxOf(it.averageBitrate, it.bitrate) }
        } else {
            info.audioStreams.maxByOrNull { maxOf(it.averageBitrate, it.bitrate) }
        } ?: throw IOException("YouTube returned no playable audio stream for $videoId")
        val reportedBitrate = maxOf(stream.averageBitrate, stream.bitrate)
        val result = YouTubeAudioStream(
            url = stream.content,
            mimeType = stream.format?.mimeType,
            // NewPipe reports kbps while raw InnerTube formats report bps;
            // normalize both providers to bps for one truthful UI value.
            bitrate = if (reportedBitrate in 1..9_999) reportedBitrate * 1_000 else reportedBitrate,
        )
        if (!preferM4a) {
            pruneStreamCache(now)
            streamCache[videoId] = Pair(now, result)
        }
        result
    }

    /** Expired entries used to linger forever (expiry was only checked on
     *  read), so every distinct video ever played stayed resident. Drop them
     *  opportunistically once the cache outgrows its bound. */
    private fun pruneStreamCache(now: Long) {
        if (streamCache.size <= MAX_STREAM_CACHE_ENTRIES) return
        streamCache.entries.removeIf { now - it.value.first >= CACHE_EXPIRY_MS }
        if (streamCache.size > MAX_STREAM_CACHE_ENTRIES) {
            streamCache.entries
                .sortedBy { it.value.first }
                .take(streamCache.size - MAX_STREAM_CACHE_ENTRIES)
                .forEach { streamCache.remove(it.key) }
        }
    }

    fun preWarm() {
        initialize()
    }

    private fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            NewPipe.init(downloader)
            initialized = true
        }
    }

    companion object {
        private const val CACHE_EXPIRY_MS = 4 * 60 * 60 * 1000L // 4 hours
        private const val MAX_STREAM_CACHE_ENTRIES = 64
    }
}

private class OkHttpNewPipeDownloader(
    private val http: OkHttpClient,
) : Downloader() {
    override fun execute(request: Request): Response {
        val headers = request.headers()
        val requestBuilder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())

        headers.forEach { (name, values) ->
            values.forEach { value -> requestBuilder.addHeader(name, value) }
        }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            requestBuilder.header("User-Agent", YOUTUBE_WEB_USER_AGENT)
        }

        return http.newCall(requestBuilder.build()).execute().use { response ->
            Response(
                response.code,
                response.message,
                response.headers.names().associateWith(response.headers::values),
                response.body?.string().orEmpty(),
                response.request.url.toString(),
            )
        }
    }
}

internal const val YOUTUBE_WEB_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
