package com.lastwave.app.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
    private val lrclibLyricsApi: LrclibLyricsApi,
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

            try {
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
                    val ytStream = innerTube.resolveAudioStream(videoId)
                    resolvedUrl = ytStream.url
                    val rawMime = ytStream.mimeType.orEmpty()
                    if (rawMime.contains("mp4") || rawMime.contains("m4a") || rawMime.contains("aac")) {
                        extension = "m4a"
                        mimeType = "audio/mp4"
                        formatBadge = "M4A AAC"
                    } else {
                        extension = "webm"
                        mimeType = "audio/webm"
                        formatBadge = "OPUS"
                    }
                    isQobuz = false
                }

                val safeFilename = sanitizeFilename("$artist - $title") + ".$extension"

                // 2. Open output stream in public storage (Music/LastWave)
                val (initialStream, uri, file) = openPublicOutputStream(safeFilename, mimeType, title, artist, album)
                destinationUri = uri
                destinationFile = file
                if (uri != null) activeUris[key] = uri
                if (file != null) activeFiles[key] = file

                showDownloadNotification(notifId, key, title, artist, 0, false, formatBadge)

                // 3. Download bytes with progress, retry on truncation
                var bytesReadTotal = 0L
                var totalLength = -1L
                var downloadAttempt = 0
                var downloadSuccess = false
                var currentOutputStream: java.io.OutputStream? = initialStream

                while (downloadAttempt <= MAX_DOWNLOAD_RETRIES && !downloadSuccess) {
                    val requestBuilder = Request.Builder().url(resolvedUrl!!)
                    if (!isQobuz) {
                        requestBuilder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36")
                        requestBuilder.header("Origin", "https://music.youtube.com")
                        requestBuilder.header("Referer", "https://music.youtube.com/")
                    }
                    val request = requestBuilder.build()
                    val response = downloadClient.newCall(request).execute()

                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading track")

                    // Validate response Content-Type is audio
                    val contentType = response.header("Content-Type").orEmpty()
                    if (contentType.isNotBlank() && !contentType.contains("audio") && !contentType.contains("octet-stream")) {
                        response.close()
                        throw IOException("Invalid content type: $contentType (expected audio)")
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
                        // Chunked transfer — show indeterminate progress
                        updateProgress(
                            DownloadProgress(
                                key = key, title = title, artist = artist,
                                progressPercent = 0, formatBadge = formatBadge,
                            ),
                        )
                        showDownloadNotification(notifId, key, title, artist, 0, true, formatBadge)
                    }

                    val out = currentOutputStream ?: run {
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri, "wt")
                                ?: context.contentResolver.openOutputStream(uri)
                                ?: throw IOException("Could not re-open stream for $uri")
                        } else if (file != null) {
                            FileOutputStream(file)
                        } else {
                            throw IOException("No output target available")
                        }
                    }

                    out.use { fos ->
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
                                // Update byte count for chunked transfers
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
                    currentOutputStream = null

                    // Verify Content-Length match (when known)
                    if (totalLength > 0 && bytesReadTotal != totalLength) {
                        downloadAttempt++
                        if (downloadAttempt > MAX_DOWNLOAD_RETRIES) {
                            throw IOException(
                                "Download truncated: received $bytesReadTotal of $totalLength bytes"
                            )
                        }
                        continue
                    }

                    downloadSuccess = true
                }

                // 4. Mark public MediaStore file as finished (IS_PENDING = 0)
                finalizePublicFile(uri)

                val finalPath = file?.absolutePath ?: uri?.toString() ?: safeFilename

                // 5. Fetch lyrics from LRCLIB and write sidecar .lrc file in Music/LastWave
                var hasLyrics = false
                var syncedLyrics: String? = null
                var plainLyrics: String? = null
                var lrcPath: String? = null

                val lyricsRecord = runCatching {
                    lrclibLyricsApi.fetchLyrics(
                        title = title,
                        artist = artist,
                        album = album,
                        durationSeconds = if (durationMs > 0) (durationMs / 1000).toInt() else null,
                    )
                }.getOrNull()

                if (lyricsRecord != null) {
                    syncedLyrics = lyricsRecord.syncedLyrics
                    plainLyrics = lyricsRecord.plainLyrics
                    val lyricsText = syncedLyrics ?: plainLyrics
                    if (!lyricsText.isNullOrBlank()) {
                        hasLyrics = true
                        val lrcFilename = sanitizeFilename("$artist - $title") + ".lrc"
                        lrcPath = writePublicCompanionFile(lrcFilename, lyricsText, "text/plain")
                    }
                }

                // 6. Persist to Room database
                val entity = DownloadedTrackEntity(
                    title = title,
                    artist = artist,
                    album = album.orEmpty(),
                    artworkUrl = artworkUrl,
                    filePath = finalPath,
                    mediaStoreUri = uri?.toString(),
                    fileSizeBytes = bytesReadTotal,
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
    ): Triple<java.io.OutputStream, Uri?, File?> {
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, filename)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$PUBLIC_DIR_NAME")
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                if (!album.isNullOrBlank()) put(MediaStore.Audio.Media.ALBUM, album)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("Could not create MediaStore entry for $filename")

            val stream = resolver.openOutputStream(uri, "wt")
                ?: resolver.openOutputStream(uri)
                ?: throw IOException("Could not open output stream for $uri")

            return Triple(stream, uri, null)
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
                put(MediaStore.Audio.Media.IS_PENDING, 0)
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
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$PUBLIC_DIR_NAME")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri, "wt")?.use { os ->
                        os.write(content.toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                    uri.toString()
                } else null
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
