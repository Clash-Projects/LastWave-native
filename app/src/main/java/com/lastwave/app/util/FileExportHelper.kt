package com.lastwave.app.util

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.lastwave.app.data.generate.GeneratedTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native equivalent of bridge.js's saveFile()/shareFileContent(): writes an
 * export file to the app's external Documents dir (survives uninstall on
 * API<29's scoped-storage-exempt path, and is user-visible via a file
 * manager either way) and/or opens a system share sheet for it via
 * FileProvider — matching the original's Save + Share export actions.
 */
@Singleton
class FileExportHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val PUBLIC_EXPORT_SUBDIR = "LastWave"
    }

    private fun documentsDir(): File {
        val dir = context.getExternalFilesDir("Documents") ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Writes [content] to a file named [filename] under app-external
     *  Documents/. Returns the written File on success. */
    fun saveToDocuments(filename: String, content: String): File {
        val file = File(documentsDir(), filename)
        file.writeText(content)
        return file
    }

    /** Writes [content] to a Uri the user picked via Storage Access
     *  Framework (ACTION_CREATE_DOCUMENT / CreateDocument contract).
     *
     *  Used for Backup export instead of [saveToDocuments]: files written
     *  under getExternalFilesDir() live in the app-private
     *  Android/data/<package>/ tree, which the system's document picker
     *  (the same picker Restore uses to open a file) does not browse into
     *  on modern Android — the backup would exist but be unreachable from
     *  Restore's file picker. Writing through a SAF Uri the user chose
     *  themselves guarantees the file is somewhere they can navigate back
     *  to later. */
    fun writeTextToUri(uri: Uri, content: String) {
        val resolver = context.contentResolver
        resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            ?: throw IOException("Couldn't open output stream for $uri")
    }

    /** Opens the system share sheet for [filename]/[content] via
     *  FileProvider — mirrors shareFileContent()'s mime-typed file share. */
    fun shareFile(filename: String, content: String, mimeType: String) {
        val file = saveToDocuments(filename, content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share playlist").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Plain-text share fallback (used if file sharing isn't available for
     *  some reason) — mirrors shareText(). */
    fun shareText(text: String, subject: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun sanitizeFilename(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "playlist" }

    /**
     * Auto-saves a generated playlist as an M3U file to the public
     * Download/LastWave/ folder — separate from [saveToDocuments]'s
     * app-private export copy, and visible outside the app / after
     * uninstall. Always runs off the main thread. Never throws: genuine
     * failures come back as [Result.failure] for the caller to decide
     * whether to surface; an already-existing file (same playlist saved
     * before) is treated as success, not a failure, so callers don't
     * need their own duplicate-save tracking.
     */
    suspend fun savePlaylistToPublicDownloads(title: String, tracks: List<GeneratedTrack>): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val filename = "${sanitizeFilename(title)}.m3u"
                val content = PlaylistExportFormat.toM3u(title, tracks)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveViaMediaStore(filename, content)
                } else {
                    saveViaLegacyFile(filename, content)
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** API 29+: MediaStore's Downloads collection — no storage permission
     *  needed for an app writing its own files here under scoped storage. */
    private fun saveViaMediaStore(filename: String, content: String) {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_EXPORT_SUBDIR"

        val alreadyExists = resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(filename, "$relativePath/"),
            null,
        )?.use { it.moveToFirst() } ?: false
        if (alreadyExists) return

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "audio/x-mpegurl")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore refused to create $filename")
        resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            ?: throw IOException("Couldn't open output stream for $filename")
    }

    /** API 24-28: legacy public-storage File access, gated on the
     *  (dangerous, runtime-requested) WRITE_EXTERNAL_STORAGE permission.
     *  There's no Activity/UI context available from this layer to prompt
     *  for that permission, so if it isn't already granted this is a
     *  silent no-op rather than a crash or a confusing background error —
     *  the playlist is already safely stored in Room either way. */
    private fun saveViaLegacyFile(filename: String, content: String) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PUBLIC_EXPORT_SUBDIR)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Couldn't create ${dir.path}")
        val file = File(dir, filename)
        if (file.exists()) return
        file.writeText(content)
    }
}

/** Port of playlist.js's exportAsCsv()/exportAsM3u() content builders — pure
 *  functions kept outside the DI class for easy testing. */
object PlaylistExportFormat {
    private fun csvEscape(value: String): String {
        val needsQuoting = value.contains(',') || value.contains('"') || value.contains('\n')
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuoting) "\"$escaped\"" else escaped
    }

    fun toCsv(tracks: List<GeneratedTrack>): String {
        val header = "Track,Artist"
        val rows = tracks.joinToString("\n") { "${csvEscape(it.name)},${csvEscape(it.artist)}" }
        return "$header\n$rows"
    }

    /** [templateLabel] matches the mode->label mapping used in the M3U
     *  filename, e.g. "AiMix", "MyRecommendation", "ByGenre",
     *  "SimilarTracks", "MyMix". */
    fun templateLabelFor(mode: String): String = when (mode) {
        "recommendations" -> "MyRecommendation"
        "tag" -> "ByGenre"
        "similar-tracks", "start-mix" -> "SimilarTracks"
        "mix" -> "MyMix"
        "discover" -> "AiMix"
        else -> "AiMix"
    }

    fun toM3u(title: String, tracks: List<GeneratedTrack>): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#PLAYLIST:$title\n")
        for (t in tracks) {
            sb.append("#EXTINF:-1,${t.artist} - ${t.name}\n")
            sb.append(if (t.url.isNotBlank()) t.url else "").append('\n')
        }
        return sb.toString()
    }
}
