package com.lastwave.app.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.lastwave.app.MainActivity
import com.lastwave.app.R
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import com.lastwave.app.data.lyrics.LrclibLyricsApi
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.lossless.LosslessMusicApi
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.artwork.ArtworkNormalizer
import com.lastwave.app.data.artwork.ArtworkRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.io.IOException
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DownloadProgress(
    val key: String,
    val title: String,
    val artist: String,
    val progressPercent: Int = 0,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val formatBadge: String = "AUDIO",
    val isWaitingForConnection: Boolean = false,
    val isFinished: Boolean = false,
    val error: String? = null,
)

private data class DownloadTransfer(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val contentType: String,
)

private data class DownloadRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1L
}

private data class ParsedContentRange(
    val start: Long,
    val endInclusive: Long,
    val total: Long,
)

private class DownloadHttpException(val statusCode: Int) :
    IOException("HTTP $statusCode downloading track")

private class DownloadProtocolException(message: String) : IOException(message)

private class DownloadInterruptedException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

@Singleton
class TrackDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val losslessMusicApi: LosslessMusicApi,
    private val innerTube: InnerTubeMusicApi,
    private val artworkRepository: ArtworkRepository,
    private val lrclibLyricsApi: LrclibLyricsApi,
    private val audioTagWriter: AudioTagWriter,
    okHttpClient: OkHttpClient,
    private val downloadedTrackDao: DownloadedTrackDao,
    private val settingsPreferences: SettingsPreferences,
    private val applicationScope: CoroutineScope,
) {
    companion object {
        const val CHANNEL_ID = "lastwave_downloads"
        const val ACTION_CANCEL_DOWNLOAD = "com.lastwave.app.ACTION_CANCEL_DOWNLOAD"
        const val ACTION_RECONNECT_DOWNLOAD = "com.lastwave.app.ACTION_RECONNECT_DOWNLOAD"
        const val ACTION_VIEW_DOWNLOADS = "com.lastwave.app.ACTION_VIEW_DOWNLOADS"
        const val EXTRA_DOWNLOAD_KEY = "download_key"
        const val EXTRA_DOWNLOAD_TITLE = "download_title"
        const val EXTRA_DOWNLOAD_ARTIST = "download_artist"
        const val EXTRA_NAVIGATE_TO = "navigate_to"
        private const val PUBLIC_DIR_NAME = "LastWave"
        private const val DOWNLOAD_BUFFER_SIZE = 512 * 1024 // 512 KB
        private const val PARALLEL_YOUTUBE_PARTS = 4
        private const val MIN_PARALLEL_DOWNLOAD_BYTES = 2L * 1024 * 1024
        private const val MIN_VALID_AUDIO_BYTES = 1_024L
        private const val RECONNECT_POLL_INTERVAL_MS = 500L
        private const val RECONNECT_RETRY_BASE_DELAY_MS = 1_000L
        private const val RECONNECT_RETRY_MAX_DELAY_MS = 30_000L
        private const val DOWNLOAD_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
        private val CONTENT_RANGE_PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
        private const val MAX_DOWNLOAD_RETRIES = 1
        /** Device-wide scanning floor. Keeps voice memos, message tones and
         *  other stray short clips out of the music library. */
        private const val MIN_LIBRARY_TRACK_MS = 30_000L
        private const val MEDIASTORE_UNKNOWN = "<unknown>"

        fun makeDownloadKey(title: String, artist: String): String =
            "${artist.trim().lowercase()}_${title.trim().lowercase()}"
    }

    /** Whether the user has granted the read-audio permission this scan needs. */
    private fun hasAudioReadPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    // Dedicated HTTP client with extended timeouts and high-throughput connection pooling
    private val downloadClient = okHttpClient.newBuilder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 24
        })
        .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    // This class is constructed eagerly inside the Hilt graph. A system
    // service lookup that throws or returns null on a modified ROM would
    // otherwise fail every injection and take down app launch, so the
    // manager is resolved lazily and tolerated as absent.
    private val notificationManager: NotificationManager? by lazy {
        runCatching {
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }.getOrNull()
    }
    private val activeKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeUris = ConcurrentHashMap<String, Uri>()
    private val activeFiles = ConcurrentHashMap<String, File>()
    private val reconnectGenerations = ConcurrentHashMap<String, AtomicLong>()

    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager ?: return
        runCatching {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Download progress and status notifications"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }.onFailure { error ->
            android.util.Log.w("TrackDownloadManager", "Notification channel unavailable", error)
        }
    }

    fun makeDownloadKey(title: String, artist: String): String =
        "${artist.trim().lowercase()}_${title.trim().lowercase()}"

    fun isDownloading(title: String, artist: String): Boolean {
        val key = makeDownloadKey(title, artist)
        val progress = _downloads.value[key]
        return activeKeys.contains(key) || (progress != null && !progress.isFinished && progress.error == null)
    }

    suspend fun isTrackDownloaded(title: String, artist: String): Boolean = withContext(Dispatchers.IO) {
        val key = makeDownloadKey(title, artist)
        val existing = runCatching {
            downloadedTrackDao.findByTrackKey(key)
                ?: downloadedTrackDao.findByTitleAndArtist(title.trim(), artist.trim())
        }.getOrNull()

        if (existing != null) {
            val fileStillPresent = when {
                existing.mediaStoreUri != null -> runCatching {
                    context.contentResolver.openInputStream(Uri.parse(existing.mediaStoreUri))?.use { }
                    true
                }.getOrDefault(false)
                else -> runCatching {
                    val f = File(existing.filePath)
                    f.exists() && f.length() > 0
                }.getOrDefault(false)
            }
            if (fileStillPresent) return@withContext true
        }

        // Check if file already exists in public Music/LastWave directory
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), PUBLIC_DIR_NAME)
        if (publicDir.exists() && publicDir.isDirectory) {
            val sanitizedBase = sanitizeFilename("${artist.trim()} - ${title.trim()}")
            val candidateExtensions = listOf("flac", "m4a", "opus", "mp3", "webm")
            if (candidateExtensions.any { ext ->
                    val f = File(publicDir, "$sanitizedBase.$ext")
                    f.exists() && f.length() > 0
                }
            ) {
                return@withContext true
            }
        }

        false
    }

    fun cancelDownload(key: String) {
        activeKeys.remove(key)
        reconnectGenerations.remove(key)?.incrementAndGet()
        val job = activeJobs.remove(key)
        job?.cancel()

        // Clean up partial media entry/file
        activeUris.remove(key)?.let { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        activeFiles.remove(key)?.let { file ->
            runCatching { if (file.exists()) file.delete() }
        }

        notificationManager?.cancel(key.hashCode())
        _downloads.update { it - key }
    }

    fun reconnectDownload(key: String): Boolean {
        if (key !in activeKeys) return false
        reconnectGenerations[key]?.incrementAndGet() ?: return false
        return true
    }

    fun downloadTrack(
        title: String,
        artist: String,
        album: String? = null,
        artworkUrl: String? = null,
        year: String? = null,
    ) {
        val key = makeDownloadKey(title, artist)
        if (!activeKeys.add(key)) return
        reconnectGenerations[key] = AtomicLong()

        val job = applicationScope.launch(Dispatchers.IO) {
            // Already downloaded? Skip re-downloading entirely rather than
            // re-fetching the file and inserting a duplicate DB row or (1).flac file.
            val existing = runCatching {
                downloadedTrackDao.findByTrackKey(key)
                    ?: downloadedTrackDao.findByTitleAndArtist(title.trim(), artist.trim())
            }.getOrNull()
            if (existing != null) {
                val fileStillPresent = when {
                    existing.mediaStoreUri != null -> runCatching {
                        context.contentResolver.openInputStream(Uri.parse(existing.mediaStoreUri))?.use { }
                        true
                    }.getOrDefault(false)
                    else -> runCatching {
                        val f = File(existing.filePath)
                        f.exists() && f.length() > 0
                    }.getOrDefault(false)
                }
                if (fileStillPresent) {
                    activeKeys.remove(key)
                    return@launch
                }
                // Row is stale (file was deleted outside the app) — fall through
                // and re-download; the unique trackKey index means the insert
                // below will REPLACE this row instead of duplicating it.
            } else {
                val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), PUBLIC_DIR_NAME)
                if (publicDir.exists() && publicDir.isDirectory) {
                    val sanitizedBase = sanitizeFilename("${artist.trim()} - ${title.trim()}")
                    val candidateExtensions = listOf("flac", "m4a", "opus", "mp3", "webm")
                    val existingFile = candidateExtensions.map { File(publicDir, "$sanitizedBase.$it") }
                        .firstOrNull { it.exists() && it.length() > 0 }
                    if (existingFile != null) {
                        activeKeys.remove(key)
                        return@launch
                    }
                }
            }
            val notifId = key.hashCode()
            updateProgress(DownloadProgress(key = key, title = title, artist = artist, progressPercent = 0))
            showDownloadNotification(notifId, key, title, artist, 0, false, "Preparing high-res stream...")

            var destinationUri: Uri? = null
            var destinationFile: File? = null
            var tempDownloadFile: File? = null

            try {

                // Resolve missing metadata & cover art proactively
                var resolvedArtworkUrl = artworkUrl?.takeIf { ArtworkNormalizer.isRealImage(it) }
                var resolvedAlbum = album?.takeIf { it.isNotBlank() }
                var preloadedBestMatch: YouTubeMusicTrack? = null

                if (resolvedArtworkUrl == null || resolvedAlbum == null) {
                    preloadedBestMatch = runCatching {
                        innerTube.findBestMatch(title, artist, prefetchStreams = false)
                    }.getOrNull()
                    if (resolvedArtworkUrl == null) {
                        resolvedArtworkUrl = preloadedBestMatch?.artworkUrl?.takeIf { ArtworkNormalizer.isRealImage(it) }
                    }
                    if (resolvedAlbum == null) {
                        resolvedAlbum = preloadedBestMatch?.album?.takeIf { it.isNotBlank() }
                    }
                }

                val artworkFallback = if (resolvedArtworkUrl == null) {
                    async(Dispatchers.IO) {
                        artworkRepository.resolve(title, artist)
                        val cacheKey = ArtworkNormalizer.cacheKey(title, artist)
                        artworkRepository.resolved.value[cacheKey]
                            ?.takeIf { ArtworkNormalizer.isRealImage(it) }
                            ?: kotlinx.coroutines.withTimeoutOrNull(3_500L) {
                                artworkRepository.resolved.first { it.containsKey(cacheKey) }[cacheKey]
                            }?.takeIf { ArtworkNormalizer.isRealImage(it) }
                    }
                } else {
                    null
                }

                // 1. Resolve source — respect user's download quality preference (Lossless tiers or YouTube Music)
                val misc = runCatching { settingsPreferences.settings.first() }.getOrDefault(MiscSettings())
                var resolvedUrl: String? = null
                var mimeType = "audio/flac"
                var extension = "flac"
                var formatBadge = "24-BIT FLAC"
                var isLossless = false
                var durationMs = 0L
                var downloadHeaders = emptyMap<String, String>()
                var expectedContentLength: Long? = null
                var useParallelDownload = false

                val downloadQuality = misc.downloadQuality
                val isYouTubeRequested = downloadQuality == LosslessMusicApi.QUALITY_YOUTUBE

                if (!isYouTubeRequested) {
                    val losslessStream = kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                        runCatching {
                            losslessMusicApi.resolveStream(
                                title = title,
                                artist = artist,
                                preferredQuality = downloadQuality,
                            )
                        }.getOrNull()
                    }

                    if (losslessStream != null) {
                        resolvedUrl = losslessStream.url
                        mimeType = losslessStream.mimeType
                        extension = if (losslessStream.formatId == LosslessMusicApi.QUALITY_MP3_320) "mp3" else "flac"
                        formatBadge = when {
                            losslessStream.bitDepth > 16 || losslessStream.samplingRate > 48.0 -> "HI-RES FLAC"
                            losslessStream.formatId == LosslessMusicApi.QUALITY_CD_LOSSLESS -> "LOSSLESS FLAC"
                            losslessStream.formatId == LosslessMusicApi.QUALITY_MP3_320 -> "320k MP3"
                            else -> "FLAC"
                        }
                        isLossless = true
                        durationMs = 0L
                    }
                }

                if (resolvedUrl == null) {
                    // Fallback to YouTube Music (prefer M4A/AAC for universal media player compatibility)
                    val bestMatch = preloadedBestMatch
                        ?: innerTube.findBestMatch(title, artist, prefetchStreams = false)
                    val videoId = bestMatch.videoId ?: error("No audio source found for $title")
                    if (resolvedArtworkUrl == null) {
                        resolvedArtworkUrl = bestMatch.artworkUrl?.takeIf { ArtworkNormalizer.isRealImage(it) }
                    }
                    if (resolvedAlbum == null) resolvedAlbum = bestMatch.album
                    val ytStream = innerTube.resolveDownloadStream(videoId)
                    resolvedUrl = ytStream.url
                    downloadHeaders = ytStream.requestHeaders
                    expectedContentLength = ytStream.contentLength
                        ?: runCatching { Uri.parse(ytStream.url).getQueryParameter("clen")?.toLongOrNull() }.getOrNull()
                    useParallelDownload = true
                    val rawMime = ytStream.mimeType.orEmpty().lowercase()
                    if (rawMime.contains("mp4") || rawMime.contains("m4a") || rawMime.contains("aac")) {
                        extension = "m4a"
                        mimeType = "audio/mp4"
                        formatBadge = "M4A AAC"
                    } else if (rawMime.contains("webm")) {
                        extension = "webm"
                        mimeType = "audio/webm"
                        formatBadge = "WEBM OPUS"
                    } else if (rawMime.contains("ogg") || rawMime.contains("opus")) {
                        extension = "opus"
                        mimeType = "audio/ogg"
                        formatBadge = "OPUS"
                    } else if (rawMime.contains("mpeg") || rawMime.contains("mp3")) {
                        extension = "mp3"
                        mimeType = "audio/mpeg"
                        formatBadge = "MP3"
                    } else {
                        extension = "m4a"
                        mimeType = "audio/mp4"
                        formatBadge = "AUDIO"
                    }
                    isLossless = false
                }

                // 2. Proactively start lyrics lookup concurrently with the download
                val shouldDownloadLyrics = runCatching {
                    settingsPreferences.settings.first().downloadLyrics
                }.getOrDefault(true)

                val lyricsDeferred = if (shouldDownloadLyrics) {
                    async(Dispatchers.IO) {
                        runCatching {
                            lrclibLyricsApi.fetchLyrics(
                                title = title,
                                artist = artist,
                                album = resolvedAlbum,
                                durationSeconds = null,
                            )
                        }.getOrNull()
                    }
                } else {
                    null
                }

                // 3. Download raw stream to local temp cache file
                val rawFile = File.createTempFile("dl_raw_", ".$extension", context.cacheDir)
                tempDownloadFile = rawFile

                    var lastProgress = 0
                    var lastNotifTime = 0L
                    var lastUnknownProgressBytes = 0L
                    val progressLock = Any()
                    val transfer = downloadToTempFile(
                        downloadKey = key,
                        url = checkNotNull(resolvedUrl),
                        target = rawFile,
                        requestHeaders = downloadHeaders,
                        expectedContentLength = expectedContentLength,
                        useParallelRanges = useParallelDownload,
                        onConnectionStateChanged = { isWaiting ->
                            _downloads.value[key]?.let { current ->
                                val updated = current.copy(isWaitingForConnection = isWaiting)
                                updateProgress(updated)
                                showDownloadNotification(
                                    notificationId = notifId,
                                    downloadKey = key,
                                    title = title,
                                    artist = artist,
                                    progress = updated.progressPercent,
                                    isIndeterminate = updated.totalBytes <= 0L,
                                    badgeText = updated.formatBadge,
                                    isWaitingForConnection = isWaiting,
                                )
                            }
                        },
                    ) { downloadedBytes, totalBytes ->
                        synchronized(progressLock) {
                            val now = android.os.SystemClock.uptimeMillis()
                            if (totalBytes > 0) {
                                val progress = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                if (progress > lastProgress) {
                                    lastProgress = progress
                                    updateProgress(
                                        DownloadProgress(
                                            key = key, title = title, artist = artist,
                                            progressPercent = progress,
                                            bytesDownloaded = downloadedBytes,
                                            totalBytes = totalBytes,
                                            formatBadge = formatBadge,
                                        ),
                                    )
                                    // Throttle notification IPC to avoid Binder lock contention during rapid downloading
                                    if (progress == 100 || now - lastNotifTime >= 250L) {
                                        lastNotifTime = now
                                        showDownloadNotification(notifId, key, title, artist, progress, false, formatBadge)
                                    }
                                }
                            } else if (downloadedBytes - lastUnknownProgressBytes >= 1024 * 1024) {
                                lastUnknownProgressBytes = downloadedBytes
                                val mbDown = String.format("%.1f MB", downloadedBytes / (1024.0 * 1024.0))
                                updateProgress(
                                    DownloadProgress(
                                        key = key, title = title, artist = artist,
                                        progressPercent = 0,
                                        bytesDownloaded = downloadedBytes,
                                        totalBytes = -1L,
                                        formatBadge = "$formatBadge • $mbDown",
                                    ),
                                )
                                if (now - lastNotifTime >= 500L) {
                                    lastNotifTime = now
                                    showDownloadNotification(notifId, key, title, artist, 0, true, formatBadge)
                                }
                            }
                        }
                    }
                    val bytesReadTotal = transfer.bytesDownloaded
                    val contentType = transfer.contentType.lowercase()
                    if (contentType.contains("webm")) {
                        extension = "webm"
                        mimeType = "audio/webm"
                        formatBadge = "WEBM OPUS"
                    } else if (contentType.contains("ogg") || contentType.contains("opus")) {
                        extension = "opus"
                        mimeType = "audio/ogg"
                        formatBadge = "OPUS"
                    } else if (contentType.contains("mpeg") || contentType.contains("mp3")) {
                        extension = "mp3"
                        mimeType = "audio/mpeg"
                        formatBadge = "MP3"
                    } else if (contentType.contains("mp4") || contentType.contains("m4a") || contentType.contains("aac")) {
                        extension = "m4a"
                        mimeType = "audio/mp4"
                        formatBadge = "M4A AAC"
                    }
                    if (useParallelDownload && !hasExpectedContainer(rawFile, extension)) {
                        throw IOException("Downloaded payload is not a valid ${extension.uppercase()} audio file")
                    }

                    // Losslessly remux WebM Opus into standard Ogg Opus for universal player & tag compatibility
                    if (extension == "webm") {
                        val opusFile = File.createTempFile("dl_remux_", ".opus", context.cacheDir)
                        if (WebmOpusRemuxer.remux(rawFile, opusFile)) {
                            rawFile.delete()
                            tempDownloadFile = opusFile
                            extension = "opus"
                            mimeType = "audio/ogg"
                            formatBadge = "OPUS"
                        } else {
                            opusFile.delete()
                        }
                    }

                    val safeFilename = sanitizeFilename("$artist - $title") + ".$extension"

                    // 4. Resolve exact audio duration from downloaded file
                    val durationRetriever = android.media.MediaMetadataRetriever()
                    try {
                        durationRetriever.setDataSource(tempDownloadFile.absolutePath)
                        val durStr = durationRetriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        durStr?.toLongOrNull()?.takeIf { it > 0 }?.let { durationMs = it }
                    } catch (_: Exception) {
                    } finally {
                        runCatching { durationRetriever.release() }
                    }

                    // 5. Complete lyrics resolution (using concurrent result or duration-assisted fallback)
                    var hasLyrics = false
                    var syncedLyrics: String? = null
                    var plainLyrics: String? = null
                    var lrcPath: String? = null

                    if (shouldDownloadLyrics) {
                        var lyricsRecord = lyricsDeferred?.await()
                        if (lyricsRecord == null && durationMs > 0) {
                            lyricsRecord = runCatching {
                                lrclibLyricsApi.fetchLyrics(
                                    title = title,
                                    artist = artist,
                                    album = resolvedAlbum,
                                    durationSeconds = (durationMs / 1000).toInt(),
                                )
                            }.getOrNull()
                        }

                        if (lyricsRecord != null) {
                            syncedLyrics = lyricsRecord.syncedLyrics
                            plainLyrics = lyricsRecord.plainLyrics
                            hasLyrics = !(syncedLyrics.isNullOrBlank() && plainLyrics.isNullOrBlank())
                        }
                    }

                    if (resolvedArtworkUrl == null) {
                        resolvedArtworkUrl = artworkFallback?.await()
                    } else {
                        artworkFallback?.cancel()
                    }

                    // 4. Embed metadata, cover art AND lyrics directly into the
                    // downloaded audio file (container-aware: Vorbis comments +
                    // PICTURE for FLAC/Opus, Matroska tags for WebM,
                    // iTunes atoms for M4A, ID3v2.3 otherwise).
                    val metadataEmbedded = audioTagWriter.embedMetadata(
                        audioFile = tempDownloadFile,
                        title = title,
                        artist = artist,
                        album = resolvedAlbum,
                        artworkUrl = resolvedArtworkUrl,
                        lyrics = if (shouldDownloadLyrics) (syncedLyrics ?: plainLyrics) else null,
                        year = year,
                    )
                    if (!metadataEmbedded) {
                        throw IOException("Could not safely embed audio metadata")
                    }

                    // 5. Transfer tagged file to public storage / MediaStore
                    val (destStream, uri, file) = openPublicOutputStream(
                        filename = safeFilename,
                        mimeType = mimeType,
                        title = title,
                        artist = artist,
                        album = resolvedAlbum,
                        year = year,
                        durationMs = durationMs,
                    )
                    destinationUri = uri
                    destinationFile = file
                    if (uri != null) activeUris[key] = uri
                    if (file != null) activeFiles[key] = file

                    val taggedFileLength = tempDownloadFile.length()
                    val copiedBytes = tempDownloadFile.inputStream().use { input ->
                        destStream.use { output ->
                            val copied = input.copyTo(output, DOWNLOAD_BUFFER_SIZE)
                            output.flush()
                            if (output is FileOutputStream) output.fd.sync()
                            copied
                        }
                    }
                    if (copiedBytes != taggedFileLength) {
                        throw IOException("Public file copy truncated: received $copiedBytes of $taggedFileLength bytes")
                    }

                    // 6. Mark public MediaStore file as finished (IS_PENDING = 0)
                    finalizePublicFile(uri)
                    if (file != null) {
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(file.absolutePath),
                            arrayOf(mimeType),
                            null,
                        )
                    }

                    val finalPath = file?.absolutePath ?: uri?.toString() ?: safeFilename


                // 7. Also write the sidecar .lrc companion file for players that read them
                if (shouldDownloadLyrics) {
                    val lyricsText = syncedLyrics ?: plainLyrics
                    if (!lyricsText.isNullOrBlank()) {
                        val lrcFilename = sanitizeFilename("$artist - $title") + ".lrc"
                        lrcPath = writePublicCompanionFile(lrcFilename, lyricsText, "text/plain")
                    }
                }

                // 6. Persist to Room database
                val entity = DownloadedTrackEntity(
                    trackKey = key,
                    title = title,
                    artist = artist,
                    album = resolvedAlbum.orEmpty(),
                    artworkUrl = resolvedArtworkUrl,
                    filePath = finalPath,
                    mediaStoreUri = uri?.toString(),
                    fileSizeBytes = tempDownloadFile.length(),
                    formatBadge = formatBadge,
                    durationMs = durationMs,
                    isLossless = isLossless,
                    hasLyrics = hasLyrics,
                    syncedLyrics = syncedLyrics,
                    plainLyrics = plainLyrics,
                    lrcFilePath = lrcPath,
                    downloadedAtMillis = System.currentTimeMillis(),
                )
                downloadedTrackDao.insert(entity)

                updateProgress(
                    DownloadProgress(
                        key = key,
                        title = title,
                        artist = artist,
                        progressPercent = 100,
                        bytesDownloaded = bytesReadTotal,
                        totalBytes = transfer.totalBytes,
                        formatBadge = formatBadge,
                        isFinished = true,
                    ),
                )

                showCompletedNotification(notifId, title, artist, formatBadge)
            } catch (cancelled: CancellationException) {
                // Cancelled by user — clean up partial file
                destinationUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                destinationFile?.let { runCatching { if (it.exists()) it.delete() } }
                notificationManager?.cancel(notifId)
                _downloads.update { it - key }
            } catch (error: Exception) {
                destinationUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                destinationFile?.let { runCatching { if (it.exists()) it.delete() } }
                updateProgress(
                    DownloadProgress(
                        key = key,
                        title = title,
                        artist = artist,
                        error = error.localizedMessage ?: error.message ?: "Download failed",
                    ),
                )
                showErrorNotification(notifId, key, title, artist, error.localizedMessage ?: "Failed")
            } finally {
                activeKeys.remove(key)
                activeJobs.remove(key)
                activeUris.remove(key)
                activeFiles.remove(key)
                reconnectGenerations.remove(key)
                tempDownloadFile?.let { runCatching { if (it.exists()) it.delete() } }
            }

        }
        activeJobs[key] = job
    }

    private suspend fun downloadToTempFile(
        downloadKey: String,
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        expectedContentLength: Long?,
        useParallelRanges: Boolean,
        onConnectionStateChanged: (Boolean) -> Unit,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadTransfer {
        val parallelLength = expectedContentLength
            ?.takeIf { useParallelRanges && it >= MIN_PARALLEL_DOWNLOAD_BYTES }
        if (parallelLength != null) {
            try {
                return downloadInParallel(
                    url = url,
                    target = target,
                    requestHeaders = requestHeaders,
                    totalLength = parallelLength,
                    downloadKey = downloadKey,
                    onConnectionStateChanged = onConnectionStateChanged,
                    onProgress = onProgress,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: DownloadProtocolException) {
                android.util.Log.w(
                    "TrackDownloadManager",
                    "Validated range download unavailable; using one stream",
                    error,
                )
                truncateFile(target)
            }
        }

        return downloadSingleStream(
            downloadKey = downloadKey,
            url = url,
            target = target,
            requestHeaders = requestHeaders,
            expectedContentLength = expectedContentLength,
            onConnectionStateChanged = onConnectionStateChanged,
            onProgress = onProgress,
        )
    }

    private suspend fun downloadInParallel(
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        totalLength: Long,
        downloadKey: String,
        onConnectionStateChanged: (Boolean) -> Unit,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadTransfer = coroutineScope {
        val partSize = (totalLength + PARALLEL_YOUTUBE_PARTS - 1L) / PARALLEL_YOUTUBE_PARTS
        val ranges = (0 until PARALLEL_YOUTUBE_PARTS).mapNotNull { index ->
            val start = index * partSize
            if (start >= totalLength) return@mapNotNull null
            DownloadRange(start, minOf(totalLength - 1L, start + partSize - 1L))
        }
        RandomAccessFile(target, "rw").use { it.setLength(totalLength) }

        val downloadedBytes = AtomicLong(0L)
        val waitingRanges = AtomicInteger(0)
        val rangeConnectionStateChanged: (Boolean) -> Unit = { isWaiting ->
            val waitingCount = if (isWaiting) {
                waitingRanges.incrementAndGet()
            } else {
                waitingRanges.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
            }
            onConnectionStateChanged(waitingCount > 0)
        }
        val contentTypes = ranges.map { range ->
            async {
                var downloadedBytesInRange = 0L
                retryInterruptedTransfer(downloadKey, rangeConnectionStateChanged) {
                    downloadRange(
                        url = url,
                        target = target,
                        requestHeaders = requestHeaders,
                        range = DownloadRange(range.start + downloadedBytesInRange, range.endInclusive),
                        expectedTotal = totalLength,
                    ) { delta ->
                        downloadedBytesInRange += delta
                        onProgress(downloadedBytes.addAndGet(delta.toLong()), totalLength)
                    }
                }
            }
        }.awaitAll()

        val finalLength = downloadedBytes.get()
        if (finalLength != totalLength || target.length() != totalLength) {
            throw IOException("Parallel download truncated: received $finalLength of $totalLength bytes")
        }
        RandomAccessFile(target, "rw").use { it.fd.sync() }
        DownloadTransfer(
            bytesDownloaded = finalLength,
            totalBytes = totalLength,
            contentType = contentTypes.firstOrNull { it.isNotBlank() }.orEmpty(),
        )
    }

    private suspend fun downloadRange(
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        range: DownloadRange,
        expectedTotal: Long,
        onBytesRead: (Int) -> Unit,
    ): String = suspendCancellableCoroutine { continuation ->
        val request = buildDownloadRequest(url, requestHeaders, range)
        val call = downloadClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val contentType = response.use { currentResponse ->
                        validateMediaResponse(currentResponse, expectedCode = 206)
                        val parsedRange = parseContentRange(currentResponse.header("Content-Range"))
                            ?: throw DownloadProtocolException("Missing Content-Range for parallel download")
                        if (parsedRange.start != range.start ||
                            parsedRange.endInclusive != range.endInclusive ||
                            parsedRange.total != expectedTotal
                        ) {
                            throw DownloadProtocolException("Mismatched Content-Range ${currentResponse.header("Content-Range")}")
                        }

                        val body = currentResponse.body
                            ?: throw DownloadProtocolException("Empty range response body")
                        val bodyLength = body.contentLength()
                        if (bodyLength > 0L && bodyLength != range.length) {
                            throw DownloadProtocolException("Range length mismatch: received $bodyLength of ${range.length} bytes")
                        }

                        RandomAccessFile(target, "rw").use { output ->
                            output.seek(range.start)
                            body.byteStream().use { input ->
                                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                                var remaining = range.length
                                while (remaining > 0L) {
                                    if (!continuation.isActive) throw CancellationException("Download cancelled")
                                    val read = try {
                                        input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    } catch (error: IOException) {
                                        throw DownloadInterruptedException("Connection interrupted while reading audio", error)
                                    }
                                    if (read < 0) {
                                        throw DownloadInterruptedException("Range truncated with $remaining bytes remaining")
                                    }
                                    output.write(buffer, 0, read)
                                    remaining -= read
                                    onBytesRead(read)
                                }
                            }
                        }
                        currentResponse.header("Content-Type").orEmpty()
                    }
                    if (continuation.isActive) continuation.resume(contentType)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private suspend fun downloadSingleStream(
        downloadKey: String,
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        expectedContentLength: Long?,
        onConnectionStateChanged: (Boolean) -> Unit,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadTransfer {
        val expectedLength = expectedContentLength?.takeIf { it > 0L }
        return retryInterruptedTransfer(downloadKey, onConnectionStateChanged) {
            if (expectedLength != null && target.length() > expectedLength) truncateFile(target)
            if (expectedLength != null && target.length() == expectedLength && expectedLength >= MIN_VALID_AUDIO_BYTES) {
                onProgress(expectedLength, expectedLength)
                return@retryInterruptedTransfer DownloadTransfer(expectedLength, expectedLength, "")
            }
            downloadSingleStreamAttempt(
                url = url,
                target = target,
                requestHeaders = requestHeaders,
                expectedContentLength = expectedLength,
                onProgress = onProgress,
            )
        }
    }

    private suspend fun downloadSingleStreamAttempt(
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        expectedContentLength: Long?,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadTransfer = suspendCancellableCoroutine { continuation ->
        val resumeOffset = target.length().coerceAtLeast(0L)
        val call = downloadClient.newCall(
            buildDownloadRequest(url, requestHeaders, range = null, resumeOffset = resumeOffset),
        )
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val transfer = response.use { currentResponse ->
                        validateMediaResponse(currentResponse)
                        val body = currentResponse.body
                            ?: throw DownloadProtocolException("Empty response body")
                        val responseLength = body.contentLength().takeIf { it > 0L }
                        val partialRange = if (currentResponse.code == 206) {
                            parseContentRange(currentResponse.header("Content-Range"))
                                ?: throw DownloadProtocolException("Missing Content-Range for partial response")
                        } else {
                            null
                        }
                        if (partialRange != null && (partialRange.start != resumeOffset ||
                                partialRange.endInclusive + 1L != partialRange.total)
                        ) {
                            throw DownloadProtocolException("Server returned an unexpected audio range")
                        }
                        if (partialRange != null && expectedContentLength != null &&
                            partialRange.total != expectedContentLength
                        ) {
                            throw DownloadProtocolException(
                                "Download size changed: expected $expectedContentLength, received ${partialRange.total}",
                            )
                        }

                        val totalLength = partialRange?.total
                            ?: responseLength
                            ?: expectedContentLength?.takeIf { it > 0L }
                            ?: -1L
                        if (responseLength != null && expectedContentLength != null &&
                            currentResponse.code == 200 && responseLength != expectedContentLength
                        ) {
                            throw DownloadProtocolException(
                                "Download size changed: expected $expectedContentLength, received $responseLength",
                            )
                        }

                        val append = resumeOffset > 0L && partialRange != null
                        var downloadedBytes = if (append) resumeOffset else 0L
                        FileOutputStream(target, append).use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                                while (true) {
                                    if (!continuation.isActive) throw CancellationException("Download cancelled")
                                    val read = try {
                                        input.read(buffer)
                                    } catch (error: IOException) {
                                        throw DownloadInterruptedException("Connection interrupted while reading audio", error)
                                    }
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    downloadedBytes += read
                                    onProgress(downloadedBytes, totalLength)
                                }
                            }
                            output.flush()
                            output.fd.sync()
                        }
                        if (downloadedBytes < MIN_VALID_AUDIO_BYTES) {
                            throw DownloadProtocolException("Downloaded audio payload is too small")
                        }
                        if (totalLength > 0L && downloadedBytes != totalLength) {
                            if (downloadedBytes < totalLength) {
                                throw DownloadInterruptedException(
                                    "Download truncated: received $downloadedBytes of $totalLength bytes",
                                )
                            }
                            throw DownloadProtocolException(
                                "Download exceeded expected size: received $downloadedBytes of $totalLength bytes",
                            )
                        }

                        DownloadTransfer(
                            bytesDownloaded = downloadedBytes,
                            totalBytes = totalLength.takeIf { it > 0L } ?: downloadedBytes,
                            contentType = currentResponse.header("Content-Type").orEmpty(),
                        )
                    }
                    if (continuation.isActive) continuation.resume(transfer)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private suspend fun <T> retryInterruptedTransfer(
        downloadKey: String,
        onConnectionStateChanged: (Boolean) -> Unit,
        transfer: suspend () -> T,
    ): T {
        var failureCount = 0
        while (true) {
            try {
                return transfer()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                if (!error.isReconnectableTransferFailure()) throw error
                failureCount++
                onConnectionStateChanged(true)
                try {
                    awaitRetryOpportunity(downloadKey, failureCount)
                } finally {
                    onConnectionStateChanged(false)
                }
            }
        }
    }

    private suspend fun awaitRetryOpportunity(downloadKey: String, failureCount: Int) {
        val reconnectGeneration = reconnectGenerations[downloadKey]
            ?: throw CancellationException("Download cancelled")
        val observedGeneration = reconnectGeneration.get()
        val retryDelayMs = (RECONNECT_RETRY_BASE_DELAY_MS *
            (1L shl (failureCount - 1).coerceIn(0, 5)))
            .coerceAtMost(RECONNECT_RETRY_MAX_DELAY_MS)
        var elapsedMs = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            if (reconnectGeneration.get() != observedGeneration) return
            if (elapsedMs >= retryDelayMs && hasUsableNetwork()) return
            delay(RECONNECT_POLL_INTERVAL_MS)
            elapsedMs += RECONNECT_POLL_INTERVAL_MS
        }
    }

    private fun hasUsableNetwork(): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching true
        val network = manager.activeNetwork ?: return@runCatching false
        val capabilities = manager.getNetworkCapabilities(network) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)

    private fun IOException.isReconnectableTransferFailure(): Boolean {
        if (this is DownloadProtocolException) return false
        if (this is DownloadHttpException) {
            return statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in 500..599
        }
        return generateSequence<Throwable>(this) { it.cause }
            .take(8)
            .any {
                it is DownloadInterruptedException ||
                    it is EOFException ||
                    it is ConnectException ||
                    it is NoRouteToHostException ||
                    it is SocketException ||
                    it is UnknownHostException ||
                    it is InterruptedIOException
            }
    }

    private fun buildDownloadRequest(
        url: String,
        requestHeaders: Map<String, String>,
        range: DownloadRange?,
        resumeOffset: Long = 0L,
    ): Request {
        val builder = Request.Builder().url(url)
        requestHeaders.forEach { (name, value) -> builder.header(name, value) }
        if (requestHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            builder.header("User-Agent", DOWNLOAD_USER_AGENT)
        }
        builder.header("Accept", "*/*")
        builder.header("Accept-Encoding", "identity")
        when {
            range != null -> builder.header("Range", "bytes=${range.start}-${range.endInclusive}")
            resumeOffset > 0L -> builder.header("Range", "bytes=$resumeOffset-")
        }
        return builder.build()
    }

    private fun validateMediaResponse(response: Response, expectedCode: Int? = null) {
        if (response.code != 200 && response.code != 206) {
            throw DownloadHttpException(response.code)
        }
        if (expectedCode != null && response.code != expectedCode) {
            throw DownloadProtocolException("Server does not support resumable byte ranges")
        }

        val contentType = response.header("Content-Type").orEmpty().lowercase()
        if (contentType.contains("text/html") ||
            contentType.contains("application/json") ||
            contentType.contains("text/plain")
        ) {
            throw DownloadProtocolException("Invalid download payload ($contentType)")
        }
    }

    private fun parseContentRange(header: String?): ParsedContentRange? {
        val match = header?.trim()?.let(CONTENT_RANGE_PATTERN::matchEntire) ?: return null
        return ParsedContentRange(
            start = match.groupValues[1].toLongOrNull() ?: return null,
            endInclusive = match.groupValues[2].toLongOrNull() ?: return null,
            total = match.groupValues[3].toLongOrNull() ?: return null,
        )
    }

    private fun truncateFile(file: File) {
        RandomAccessFile(file, "rw").use { it.setLength(0L) }
    }

    private fun hasExpectedContainer(file: File, extension: String): Boolean = runCatching {
        if (file.length() < 12L) return@runCatching false
        val header = ByteArray(12)
        RandomAccessFile(file, "r").use { input ->
            if (input.read(header) != header.size) return@runCatching false
        }
        when (extension.lowercase()) {
            "flac" -> header.copyOfRange(0, 4).contentEquals(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))
            "m4a" -> header.copyOfRange(4, 8).contentEquals(byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()))
            "webm" -> header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))
            "opus" -> header.copyOfRange(0, 4).contentEquals(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte()))
            "mp3" -> header.copyOfRange(0, 3).contentEquals(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte())) ||
                ((header[0].toInt() and 0xFF) == 0xFF && (header[1].toInt() and 0xE0) == 0xE0)
            else -> false
        }
    }.getOrDefault(false)

    private fun updateProgress(progress: DownloadProgress) {
        _downloads.update { it + (progress.key to progress) }
    }

    private fun openPublicOutputStream(
        filename: String,
        mimeType: String,
        title: String,
        artist: String,
        album: String?,
        year: String? = null,
        durationMs: Long = 0L,
    ): Triple<java.io.OutputStream, Uri?, File?> {
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android MediaStore Audio only accepts standard audio MIME types
            val audioMime = when {
                mimeType.contains("mp4") || mimeType.contains("m4a") || mimeType.contains("aac") -> "audio/mp4"
                mimeType.contains("flac") -> "audio/flac"
                mimeType.contains("mp3") || mimeType.contains("mpeg") -> "audio/mpeg"
                mimeType.contains("webm") -> "audio/webm"
                mimeType.contains("ogg") || mimeType.contains("opus") -> "audio/ogg"
                mimeType.contains("wav") -> "audio/x-wav"
                else -> "audio/mp4"
            }

            // Remove any pre-existing entry with the same filename to avoid Android appending (1), (2), etc.
            runCatching {
                resolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    "${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
                    arrayOf(filename),
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val oldUri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                        runCatching { resolver.delete(oldUri, null, null) }
                    }
                }
            }

            val audioContentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, filename)
                put(MediaStore.Audio.Media.MIME_TYPE, audioMime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$PUBLIC_DIR_NAME")
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.ALBUM_ARTIST, artist)
                if (!album.isNullOrBlank()) put(MediaStore.Audio.Media.ALBUM, album)
                val yearInt = year?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
                if (yearInt != null && yearInt > 0) {
                    put(MediaStore.Audio.Media.YEAR, yearInt)
                }
                if (durationMs > 0) put(MediaStore.Audio.Media.DURATION, durationMs)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            val audioUri = runCatching { resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audioContentValues) }.getOrNull()
            if (audioUri != null) {
                val stream = resolver.openOutputStream(audioUri, "wt")
                    ?: resolver.openOutputStream(audioUri)
                if (stream != null) return Triple(stream, audioUri, null)
            }

            // Fallback 1: MediaStore.Downloads (pure download columns only)
            val downloadContentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, if (mimeType.isNotBlank()) mimeType else "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_DIR_NAME/Music")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val downloadUri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, downloadContentValues) }.getOrNull()
            if (downloadUri != null) {
                val stream = resolver.openOutputStream(downloadUri, "wt")
                    ?: resolver.openOutputStream(downloadUri)
                if (stream != null) return Triple(stream, downloadUri, null)
            }

            // Fallback 2: Direct public / external app music directory
            val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), PUBLIC_DIR_NAME).apply { if (!exists()) mkdirs() }
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            val fallbackFile = File(fallbackDir, filename)
            val stream = FileOutputStream(fallbackFile)
            return Triple(stream, null, fallbackFile)
        } else {
            // Android 9 and below
            val musicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), PUBLIC_DIR_NAME)
            if (!musicDir.exists()) musicDir.mkdirs()
            val file = File(musicDir, filename)
            val stream = FileOutputStream(file)
            return Triple(stream, null, file)
        }
    }


    private fun finalizePublicFile(uri: Uri?) {
        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            runCatching { context.contentResolver.update(uri, contentValues, null, null) }
        }
    }

    private fun showDownloadNotification(
        notificationId: Int,
        downloadKey: String,
        title: String,
        artist: String,
        progress: Int,
        isIndeterminate: Boolean,
        badgeText: String,
        isWaitingForConnection: Boolean = false,
    ) {
        val cancelIntent = Intent(context, DownloadCancelReceiver::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_KEY, downloadKey)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (isWaitingForConnection) "Waiting for connection" else "Downloading \"$title\"")
            .setContentText(
                if (isWaitingForConnection) "\"$title\" by $artist \u2022 $progress% saved"
                else "$artist \u2022 $badgeText ($progress%)",
            )
            .setProgress(100, progress, isIndeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(viewDownloadsPendingIntent(notificationId))

        if (isWaitingForConnection) {
            builder.addAction(
                android.R.drawable.ic_popup_sync,
                "Reconnect",
                reconnectPendingIntent(notificationId, downloadKey, title, artist),
            )
        }
        val notification = builder
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .build()

        runCatching { notificationManager?.notify(notificationId, notification) }
    }

    private fun showCompletedNotification(
        notificationId: Int,
        title: String,
        artist: String,
        badgeText: String,
    ) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download Complete")
            .setContentText("\"$title\" by $artist ($badgeText)")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(viewDownloadsPendingIntent(notificationId))
            .build()

        runCatching { notificationManager?.notify(notificationId, notification) }
    }

    private fun showErrorNotification(
        notificationId: Int,
        downloadKey: String,
        title: String,
        artist: String,
        error: String,
    ) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download Failed")
            .setContentText("\"$title\" by $artist: $error")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(viewDownloadsPendingIntent(notificationId))
            .addAction(
                android.R.drawable.ic_popup_sync,
                "Reconnect",
                reconnectPendingIntent(notificationId, downloadKey, title, artist),
            )
            .build()

        runCatching { notificationManager?.notify(notificationId, notification) }
    }

    private fun viewDownloadsPendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_VIEW_DOWNLOADS
            putExtra(EXTRA_NAVIGATE_TO, "downloads")
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun reconnectPendingIntent(
        requestCode: Int,
        downloadKey: String,
        title: String,
        artist: String,
    ): PendingIntent {
        val intent = Intent(context, DownloadCancelReceiver::class.java).apply {
            action = ACTION_RECONNECT_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_KEY, downloadKey)
            putExtra(EXTRA_DOWNLOAD_TITLE, title)
            putExtra(EXTRA_DOWNLOAD_ARTIST, artist)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun writePublicCompanionFile(
        filename: String,
        content: String,
        mimeType: String,
    ): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver

                // Remove pre-existing companion file with the same name to prevent (1).lrc duplicates
                runCatching {
                    resolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Downloads._ID),
                        "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                        arrayOf(filename),
                        null,
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndex(MediaStore.Downloads._ID)
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val oldUri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                            runCatching { resolver.delete(oldUri, null, null) }
                        }
                    }
                }

                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_DIR_NAME/Music")
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues) }.getOrNull()
                if (uri != null) {
                    resolver.openOutputStream(uri, "wt")?.use { os ->
                        os.write(content.toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                    uri.toString()
                } else {
                    val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                        ?: File(context.filesDir, "lyrics").apply { if (!exists()) mkdirs() }
                    if (!fallbackDir.exists()) fallbackDir.mkdirs()
                    val file = File(fallbackDir, filename)
                    file.writeText(content, Charsets.UTF_8)
                    file.absolutePath
                }
            } else {
                val musicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), PUBLIC_DIR_NAME)
                if (!musicDir.exists()) musicDir.mkdirs()
                val file = File(musicDir, filename)
                file.writeText(content, Charsets.UTF_8)
                file.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }


    /**
     * True only for files this app downloaded into Music/LastWave.
     *
     * The local library also indexes the user's own music from anywhere on the
     * device (see [syncDownloadsFromStorage]). Those files are borrowed, not
     * owned — removing them from the library must never delete them from the
     * phone, so every deletion path checks this first.
     */
    private fun isAppOwned(track: DownloadedTrackEntity): Boolean {
        val ownedSegment = "${Environment.DIRECTORY_MUSIC}/$PUBLIC_DIR_NAME"
        if (track.filePath.isNotBlank() && !track.filePath.startsWith("content://")) {
            return track.filePath.replace('\\', '/').contains(ownedSegment)
        }
        val uri = track.mediaStoreUri?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            context.contentResolver.query(
                Uri.parse(uri),
                arrayOf(MediaStore.Audio.Media.RELATIVE_PATH),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                val path = cursor.getString(0).orEmpty()
                path.startsWith(ownedSegment, ignoreCase = true)
            } ?: false
        }.getOrDefault(false)
    }

    /** Removes the physical file only when this app put it there. */
    private fun deletePhysicalCopy(track: DownloadedTrackEntity) {
        if (!isAppOwned(track)) return
        if (!track.mediaStoreUri.isNullOrBlank()) {
            runCatching { context.contentResolver.delete(Uri.parse(track.mediaStoreUri), null, null) }
        }
        if (track.filePath.isNotBlank() && !track.filePath.startsWith("content://")) {
            runCatching {
                val f = File(track.filePath)
                if (f.exists()) f.delete()
            }
        }
        // Delete companion .lrc file if present
        if (!track.lrcFilePath.isNullOrBlank()) {
            if (track.lrcFilePath.startsWith("content://")) {
                runCatching { context.contentResolver.delete(Uri.parse(track.lrcFilePath), null, null) }
            } else {
                runCatching {
                    val lf = File(track.lrcFilePath)
                    if (lf.exists()) lf.delete()
                }
            }
        }
    }

    suspend fun deleteDownloadedTrack(track: DownloadedTrackEntity) = withContext(Dispatchers.IO) {
        downloadedTrackDao.delete(track)
        deletePhysicalCopy(track)
    }

    suspend fun clearAllDownloads() = withContext(Dispatchers.IO) {
        downloadedTrackDao.getAllList().forEach(::deletePhysicalCopy)
        downloadedTrackDao.clearAll()
    }

    suspend fun syncDownloadsFromStorage() = withContext(Dispatchers.IO) {
        val existingEntities = downloadedTrackDao.getAllList().toMutableList()
        val existingPaths = existingEntities.map { it.filePath }.toMutableSet()
        val existingUris = existingEntities.mapNotNull { it.mediaStoreUri }.toMutableSet()
        val existingKeys = existingEntities.map { makeDownloadKey(it.title, it.artist) }.toMutableSet()

        // 1. Scan Public Music/LastWave directory on device
        val musicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), PUBLIC_DIR_NAME)
        if (musicDir.exists() && musicDir.isDirectory) {
            val audioFiles = musicDir.listFiles { file ->
                file.isFile && (file.extension.equals("flac", true) ||
                                file.extension.equals("m4a", true) ||
                                file.extension.equals("mp3", true) ||
                                file.extension.equals("opus", true) ||
                                file.extension.equals("ogg", true) ||
                                file.extension.equals("webm", true))
            }.orEmpty()

            for (file in audioFiles) {
                if (file.absolutePath in existingPaths) continue

                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.ifBlank { null } ?: file.nameWithoutExtension.substringAfter(" - ").ifBlank { file.nameWithoutExtension }
                    val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?.ifBlank { null } ?: file.nameWithoutExtension.substringBefore(" - ").ifBlank { "Unknown Artist" }
                    val trackKey = makeDownloadKey(title, artist)
                    if (trackKey in existingKeys) continue

                    val album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
                    val durStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val durMs = durStr?.toLongOrNull() ?: 0L
                    val bitRateStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    val bitrateKbps = bitRateStr?.toIntOrNull()?.let { it / 1000 }

                    val ext = file.extension.lowercase()
                    val badge = when (ext) {
                        "flac" -> "FLAC"
                        "m4a", "mp4", "aac" -> "M4A AAC"
                        "mp3" -> "320k MP3"
                        "opus", "ogg" -> "OPUS"
                        else -> "AUDIO"
                    }

                    val lrcFile = File(musicDir, file.nameWithoutExtension + ".lrc")
                    val hasLyrics = lrcFile.exists() && lrcFile.length() > 0
                    val lrcText = if (hasLyrics) runCatching { lrcFile.readText() }.getOrNull() else null

                    val entity = DownloadedTrackEntity(
                        trackKey = trackKey,
                        title = title,
                        artist = artist,
                        album = album,
                        filePath = file.absolutePath,
                        fileSizeBytes = file.length(),
                        formatBadge = badge,
                        durationMs = durMs,
                        bitrateKbps = bitrateKbps,
                        isLossless = ext == "flac",
                        hasLyrics = hasLyrics,
                        syncedLyrics = if (lrcText?.contains("[") == true) lrcText else null,
                        plainLyrics = if (lrcText?.contains("[") != true) lrcText else null,
                        lrcFilePath = if (hasLyrics) lrcFile.absolutePath else null,
                        downloadedAtMillis = file.lastModified(),
                    )
                    downloadedTrackDao.insert(entity)
                    existingPaths.add(file.absolutePath)
                    existingKeys.add(trackKey)
                } catch (e: Exception) {
                    android.util.Log.e("TrackDownloadManager", "Failed to import file: ${file.name}", e)
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }

        // 2. Query MediaStore for any items in Music/LastWave
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.DATE_ADDED,
            )
            // With full-device scanning on, take every real music file on the
            // phone (IS_MUSIC already excludes ringtones, alarms and
            // notifications); otherwise stay inside the app's own folder.
            val scanWholeDevice = runCatching {
                settingsPreferences.settings.first().scanFullDeviceAudio
            }.getOrDefault(true) && hasAudioReadPermission()

            val selection: String
            val selectionArgs: Array<String>
            if (scanWholeDevice) {
                selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > ?"
                selectionArgs = arrayOf(MIN_LIBRARY_TRACK_MS.toString())
            } else {
                selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
                selectionArgs = arrayOf("${Environment.DIRECTORY_MUSIC}/$PUBLIC_DIR_NAME%")
            }

            runCatching {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                        if (uri.toString() in existingUris) continue

                        // MediaStore writes the literal "<unknown>" for
                        // untagged files rather than leaving the column null.
                        val title = cursor.getString(titleCol)
                            ?.takeUnless { it.isBlank() || it == MEDIASTORE_UNKNOWN }
                            ?: "Unknown Track"
                        val artist = cursor.getString(artistCol)
                            ?.takeUnless { it.isBlank() || it == MEDIASTORE_UNKNOWN }
                            ?: "Unknown Artist"
                        val trackKey = makeDownloadKey(title, artist)
                        if (trackKey in existingKeys) continue

                        val album = cursor.getString(albumCol).orEmpty()
                        val durMs = cursor.getLong(durCol)
                        val size = cursor.getLong(sizeCol)
                        val mime = cursor.getString(mimeCol).orEmpty().lowercase()
                        val date = cursor.getLong(dateCol) * 1000L

                        val isFlac = mime.contains("flac")
                        val isM4a = mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac")
                        val isMp3 = mime.contains("mp3") || mime.contains("mpeg")
                        val isOpus = mime.contains("opus") || mime.contains("ogg")

                        val badge = when {
                            isFlac -> "FLAC"
                            isM4a -> "M4A AAC"
                            isMp3 -> "320k MP3"
                            isOpus -> "OPUS"
                            else -> "AUDIO"
                        }

                        val entity = DownloadedTrackEntity(
                            trackKey = trackKey,
                            title = title,
                            artist = artist,
                            album = album,
                            filePath = uri.toString(),
                            mediaStoreUri = uri.toString(),
                            fileSizeBytes = size,
                            formatBadge = badge,
                            durationMs = durMs,
                            isLossless = isFlac,
                            downloadedAtMillis = if (date > 0) date else System.currentTimeMillis(),
                        )
                        downloadedTrackDao.insert(entity)
                        existingUris.add(uri.toString())
                        existingKeys.add(trackKey)
                    }
                }
            }
        }
    }

    private fun sanitizeFilename(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "track" }
}
