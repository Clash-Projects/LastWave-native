package com.lastwave.app.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
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
import com.lastwave.app.data.qobuz.QobuzMusicApi
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.artwork.ArtworkNormalizer
import com.lastwave.app.data.artwork.ArtworkRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadProgress(
    val key: String,
    val title: String,
    val artist: String,
    val progressPercent: Int = 0,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val formatBadge: String = "AUDIO",
    val isFinished: Boolean = false,
    val error: String? = null,
)

@Singleton
class TrackDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val qobuzMusicApi: QobuzMusicApi,
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
        const val EXTRA_DOWNLOAD_KEY = "download_key"
        private const val PUBLIC_DIR_NAME = "LastWave"
        private const val DOWNLOAD_BUFFER_SIZE = 512 * 1024 // 512 KB
        private const val MAX_DOWNLOAD_RETRIES = 1
    }

    // Dedicated HTTP client with extended timeouts and high-throughput connection pooling
    private val downloadClient = okHttpClient.newBuilder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 16
        })
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeUris = ConcurrentHashMap<String, Uri>()
    private val activeFiles = ConcurrentHashMap<String, File>()

    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Download progress and status notifications"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun makeDownloadKey(title: String, artist: String): String =
        "${artist.trim().lowercase()}_${title.trim().lowercase()}"

    fun isDownloading(title: String, artist: String): Boolean {
        val key = makeDownloadKey(title, artist)
        val progress = _downloads.value[key]
        return progress != null && !progress.isFinished && progress.error == null
    }

    fun cancelDownload(key: String) {
        val job = activeJobs.remove(key)
        job?.cancel()

        // Clean up partial media entry/file
        activeUris.remove(key)?.let { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        activeFiles.remove(key)?.let { file ->
            runCatching { if (file.exists()) file.delete() }
        }

        notificationManager.cancel(key.hashCode())
        _downloads.update { it - key }
    }

    fun downloadTrack(
        title: String,
        artist: String,
        album: String? = null,
        artworkUrl: String? = null,
    ) {
        val key = makeDownloadKey(title, artist)
        if (activeJobs.containsKey(key)) return

        val job = applicationScope.launch(Dispatchers.IO) {
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

                if (resolvedArtworkUrl == null || resolvedAlbum == null) {
                    val best = runCatching { innerTube.findBestMatch(title, artist) }.getOrNull()
                    if (resolvedArtworkUrl == null) {
                        resolvedArtworkUrl = best?.artworkUrl?.takeIf { ArtworkNormalizer.isRealImage(it) }
                    }
                    if (resolvedAlbum == null) {
                        resolvedAlbum = best?.album?.takeIf { it.isNotBlank() }
                    }
                }

                if (resolvedArtworkUrl == null) {
                    artworkRepository.resolve(title, artist)
                    val cacheKey = ArtworkNormalizer.cacheKey(title, artist)
                    resolvedArtworkUrl = artworkRepository.resolved.value[cacheKey]
                        ?.takeIf { ArtworkNormalizer.isRealImage(it) }
                        ?: kotlinx.coroutines.withTimeoutOrNull(3_500L) {
                            artworkRepository.resolved.first { it.containsKey(cacheKey) }[cacheKey]
                        }?.takeIf { ArtworkNormalizer.isRealImage(it) }
                }

                // 1. Resolve source — respect user's Qobuz preference for downloads too
                val misc = runCatching { settingsPreferences.settings.first() }.getOrDefault(MiscSettings())
                var resolvedUrl: String? = null
                var mimeType = "audio/flac"
                var extension = "flac"
                var formatBadge = "24-BIT FLAC"
                var isQobuz = false
                var durationMs = 0L

                if (misc.preferQobuzStreaming) {
                    val qobuzStream = kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                        runCatching {
                            qobuzMusicApi.resolveStream(
                                title = title,
                                artist = artist,
                                preferredQuality = QobuzMusicApi.QUALITY_MAX_HI_RES,
                            )
                        }.getOrNull()
                    }

                    if (qobuzStream != null) {
                        resolvedUrl = qobuzStream.url
                        mimeType = qobuzStream.mimeType
                        extension = if (qobuzStream.formatId == QobuzMusicApi.QUALITY_MP3_320) "mp3" else "flac"
                        formatBadge = when {
                            qobuzStream.bitDepth > 16 || qobuzStream.samplingRate > 48.0 -> "HI-RES FLAC"
                            qobuzStream.formatId == QobuzMusicApi.QUALITY_CD_LOSSLESS -> "LOSSLESS FLAC"
                            qobuzStream.formatId == QobuzMusicApi.QUALITY_MP3_320 -> "320k MP3"
                            else -> "FLAC"
                        }
                        isQobuz = true
                        durationMs = 0L
                    }
                }

                if (resolvedUrl == null) {
                    // Fallback to YouTube Music
                    val bestMatch = innerTube.findBestMatch(title, artist)
                    val videoId = bestMatch.videoId ?: error("No audio source found for $title")
                    if (resolvedArtworkUrl == null) {
                        resolvedArtworkUrl = bestMatch.artworkUrl?.takeIf { ArtworkNormalizer.isRealImage(it) }
                    }
                    if (resolvedAlbum == null) resolvedAlbum = bestMatch.album
                    val ytStream = innerTube.resolveAudioStream(videoId)
                    resolvedUrl = ytStream.url
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
                    } else {
                        extension = "m4a"
                        mimeType = "audio/mp4"
                        formatBadge = "AUDIO"
                    }
                    isQobuz = false
                }

                // 2. Download raw stream to local temp cache file
                val rawFile = File.createTempFile("dl_raw_", ".$extension", context.cacheDir)
                tempDownloadFile = rawFile

                    var bytesReadTotal = 0L
                    var totalLength = -1L
                    var downloadAttempt = 0
                    var downloadSuccess = false

                    while (downloadAttempt <= MAX_DOWNLOAD_RETRIES && !downloadSuccess) {
                        val requestBuilder = Request.Builder().url(resolvedUrl!!)
                        requestBuilder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36")
                        requestBuilder.header("Accept", "*/*")
                        val request = requestBuilder.build()
                        val response = downloadClient.newCall(request).execute()

                        if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading track")

                        // Validate response is actual media payload and not an HTML/JSON error page
                        val contentType = response.header("Content-Type").orEmpty().lowercase()
                        if (contentType.contains("text/html") || contentType.contains("application/json")) {
                            response.close()
                            throw IOException("Invalid download payload ($contentType)")
                        }
                        if (contentType.contains("webm")) {
                            extension = "webm"
                            mimeType = "audio/webm"
                            formatBadge = "WEBM OPUS"
                        } else if (contentType.contains("ogg") || contentType.contains("opus")) {
                            extension = "opus"
                            mimeType = "audio/ogg"
                            formatBadge = "OPUS"
                        }

                        val body = response.body ?: throw IOException("Empty response body")
                        totalLength = body.contentLength()
                        val source = body.byteStream()

                        bytesReadTotal = 0L
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        var bytesRead: Int
                        var lastProgress = 0
                        val isChunked = totalLength <= 0

                        if (isChunked) {
                            updateProgress(
                                DownloadProgress(
                                    key = key, title = title, artist = artist,
                                    progressPercent = 0, formatBadge = formatBadge,
                                ),
                            )
                            showDownloadNotification(notifId, key, title, artist, 0, true, formatBadge)
                        }

                        FileOutputStream(tempDownloadFile).use { fos ->
                            while (source.read(buffer).also { bytesRead = it } != -1) {
                                fos.write(buffer, 0, bytesRead)
                                bytesReadTotal += bytesRead

                                if (!isChunked && totalLength > 0) {
                                    val progress = ((bytesReadTotal * 100) / totalLength).toInt().coerceIn(0, 100)
                                    if (progress != lastProgress) {
                                        lastProgress = progress
                                        updateProgress(
                                            DownloadProgress(
                                                key = key, title = title, artist = artist,
                                                progressPercent = progress,
                                                bytesDownloaded = bytesReadTotal,
                                                totalBytes = totalLength,
                                                formatBadge = formatBadge,
                                            ),
                                        )
                                        showDownloadNotification(notifId, key, title, artist, progress, false, formatBadge)
                                    }
                                } else if (isChunked) {
                                    val mbDown = String.format("%.1f MB", bytesReadTotal / (1024.0 * 1024.0))
                                    updateProgress(
                                        DownloadProgress(
                                            key = key, title = title, artist = artist,
                                            progressPercent = 0,
                                            bytesDownloaded = bytesReadTotal,
                                            totalBytes = -1L,
                                            formatBadge = "$formatBadge • $mbDown",
                                        ),
                                    )
                                }
                            }
                            fos.flush()
                        }

                        if (totalLength > 0 && bytesReadTotal != totalLength) {
                            downloadAttempt++
                            if (downloadAttempt > MAX_DOWNLOAD_RETRIES) {
                                throw IOException("Download truncated: received $bytesReadTotal of $totalLength bytes")
                            }
                            continue
                        }

                        downloadSuccess = true
                    }

                    val safeFilename = sanitizeFilename("$artist - $title") + ".$extension"

                    // 3. Fetch lyrics BEFORE tagging so they can be embedded
                    // INTO the audio file (a sidecar alone lands in a folder
                    // most players never associate with the track).
                    var hasLyrics = false
                    var syncedLyrics: String? = null
                    var plainLyrics: String? = null
                    var lrcPath: String? = null

                    val lyricsRecord = runCatching {
                        lrclibLyricsApi.fetchLyrics(
                            title = title,
                            artist = artist,
                            album = resolvedAlbum,
                            durationSeconds = if (durationMs > 0) (durationMs / 1000).toInt() else null,
                        )
                    }.getOrNull()

                    if (lyricsRecord != null) {
                        syncedLyrics = lyricsRecord.syncedLyrics
                        plainLyrics = lyricsRecord.plainLyrics
                        hasLyrics = !(syncedLyrics.isNullOrBlank() && plainLyrics.isNullOrBlank())
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
                        lyrics = syncedLyrics ?: plainLyrics,
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
                        durationMs = durationMs,
                    )
                    destinationUri = uri
                    destinationFile = file
                    if (uri != null) activeUris[key] = uri
                    if (file != null) activeFiles[key] = file

                    tempDownloadFile.inputStream().use { input ->
                        destStream.use { output ->
                            input.copyTo(output)
                            output.flush()
                        }
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
                val lyricsText = syncedLyrics ?: plainLyrics
                if (!lyricsText.isNullOrBlank()) {
                    val lrcFilename = sanitizeFilename("$artist - $title") + ".lrc"
                    lrcPath = writePublicCompanionFile(lrcFilename, lyricsText, "text/plain")
                }

                // 6. Persist to Room database
                val entity = DownloadedTrackEntity(
                    title = title,
                    artist = artist,
                    album = resolvedAlbum.orEmpty(),
                    artworkUrl = resolvedArtworkUrl,
                    filePath = finalPath,
                    mediaStoreUri = uri?.toString(),
                    fileSizeBytes = tempDownloadFile.length(),
                    formatBadge = formatBadge,
                    durationMs = durationMs,
                    isQobuz = isQobuz,
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
                        totalBytes = bytesReadTotal,
                        formatBadge = formatBadge,
                        isFinished = true,
                    ),
                )

                showCompletedNotification(notifId, title, artist, formatBadge)
            } catch (cancelled: CancellationException) {
                // Cancelled by user — clean up partial file
                destinationUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                destinationFile?.let { runCatching { if (it.exists()) it.delete() } }
                notificationManager.cancel(notifId)
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
                showErrorNotification(notifId, title, artist, error.localizedMessage ?: "Failed")
            } finally {
                activeJobs.remove(key)
                activeUris.remove(key)
                activeFiles.remove(key)
                tempDownloadFile?.let { runCatching { if (it.exists()) it.delete() } }
            }

        }
        activeJobs[key] = job
    }

    private fun updateProgress(progress: DownloadProgress) {
        _downloads.update { it + (progress.key to progress) }
    }

    private fun openPublicOutputStream(
        filename: String,
        mimeType: String,
        title: String,
        artist: String,
        album: String?,
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

            val audioContentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, filename)
                put(MediaStore.Audio.Media.MIME_TYPE, audioMime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$PUBLIC_DIR_NAME")
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                if (!album.isNullOrBlank()) put(MediaStore.Audio.Media.ALBUM, album)
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
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_DIR_NAME")
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

        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading \"$title\"")
            .setContentText("$artist \u2022 $badgeText ($progress%)")
            .setProgress(100, progress, isIndeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    private fun showCompletedNotification(
        notificationId: Int,
        title: String,
        artist: String,
        badgeText: String,
    ) {
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download Complete")
            .setContentText("\"$title\" by $artist ($badgeText)")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    private fun showErrorNotification(
        notificationId: Int,
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
            .build()

        notificationManager.notify(notificationId, notification)
    }

    private fun writePublicCompanionFile(
        filename: String,
        content: String,
        mimeType: String,
    ): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_DIR_NAME")
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


    suspend fun deleteDownloadedTrack(track: DownloadedTrackEntity) = withContext(Dispatchers.IO) {
        downloadedTrackDao.delete(track)
        // Delete physical audio file
        if (!track.mediaStoreUri.isNullOrBlank()) {
            runCatching { context.contentResolver.delete(Uri.parse(track.mediaStoreUri), null, null) }
        }
        if (track.filePath.isNotBlank()) {
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

    suspend fun clearAllDownloads() = withContext(Dispatchers.IO) {
        val all = downloadedTrackDao.getAllList()
        all.forEach { track ->
            if (!track.mediaStoreUri.isNullOrBlank()) {
                runCatching { context.contentResolver.delete(Uri.parse(track.mediaStoreUri), null, null) }
            }
            if (track.filePath.isNotBlank()) {
                runCatching {
                    val f = File(track.filePath)
                    if (f.exists()) f.delete()
                }
            }
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
        downloadedTrackDao.clearAll()
    }

    private fun sanitizeFilename(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "track" }
}
