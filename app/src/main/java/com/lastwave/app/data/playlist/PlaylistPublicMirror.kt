package com.lastwave.app.data.playlist

import android.util.Log
import com.lastwave.app.data.generate.StoredTrack
import com.lastwave.app.data.local.db.SavedPlaylistDao
import com.lastwave.app.data.local.db.SavedPlaylistEntity
import com.lastwave.app.util.FileExportHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val MIRROR_TYPE = "lastwave-playlists"
private const val MIRROR_SCHEMA = 4
private const val TAG = "PlaylistPublicMirror"

@Serializable
data class PlaylistMirrorEntry(
    val id: Long,
    val title: String,
    val subtitle: String,
    val mode: String,
    val tracks: List<StoredTrack>,
    val createdAtMillis: Long,
    val discoverSignature: String? = null,
    val customCoverUri: String? = null,
    val isCompleted: Boolean = false,
    val isPinned: Boolean = false,
)

@Serializable
data class PlaylistMirrorFile(
    val type: String = MIRROR_TYPE,
    val schemaVersion: Int = MIRROR_SCHEMA,
    val updatedAtMillis: Long,
    val playlists: List<PlaylistMirrorEntry>,
)

/** Public Downloads/LastWave JSON mirror. Room remains the fast runtime
 * source; this file is the uninstall and destructive-migration recovery. */
@Singleton
class PlaylistPublicMirror @Inject constructor(
    private val dao: SavedPlaylistDao,
    private val fileExportHelper: FileExportHelper,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val mutex = Mutex()

    fun playlistCount(content: String): Int? = decode(content)?.playlists?.size

    suspend fun restoreIfDatabaseEmpty(): Int = mutex.withLock {
        val local = dao.getAll()
        if (local.isNotEmpty()) {
            writeLocked(local)
            return@withLock 0
        }

        val primaryRead = fileExportHelper.readPublicPlaylistMirror()
        primaryRead.exceptionOrNull()?.let { Log.w(TAG, "Primary playlist JSON read failed", it) }
        val primaryContent = primaryRead.getOrNull()
        val primaryMirror = primaryContent?.let(::decode)

        val recoveryRead = fileExportHelper.readPublicPlaylistRecovery()
        recoveryRead.exceptionOrNull()?.let { Log.w(TAG, "Recovery playlist JSON read failed", it) }
        val recoveryContent = recoveryRead.getOrNull()
        val recoveryMirror = recoveryContent?.let(::decode)
        val mirror = listOfNotNull(primaryMirror, recoveryMirror).maxByOrNull { it.updatedAtMillis }

        if (mirror == null && primaryContent == null && recoveryContent == null && primaryRead.isSuccess && recoveryRead.isSuccess) {
            writeLocked(emptyList())
            return@withLock 0
        }
        if (mirror == null) {
            Log.e(TAG, "No valid playlist JSON snapshot was readable; Room was left unchanged")
            return@withLock 0
        }

        dao.upsertAll(mirror.playlists.map { it.toEntity() })
        writeLocked(dao.getAll())
        mirror.playlists.size
    }

    suspend fun writeFromDatabase(): Result<Unit> = mutex.withLock {
        writeLocked(dao.getAll())
    }

    suspend fun importAndMerge(content: String): Result<Int> = mutex.withLock {
        val mirror = decode(content)
            ?: return@withLock Result.failure(IllegalArgumentException("Invalid LastWave playlist JSON"))
        try {
            dao.upsertAll(mirror.playlists.map { it.toEntity() })
            writeLocked(dao.getAll()).onFailure { error ->
                Log.e(TAG, "Imported playlists but couldn't refresh public JSON", error)
            }
            Result.success(mirror.playlists.size)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun decode(content: String): PlaylistMirrorFile? = try {
        json.decodeFromString<PlaylistMirrorFile>(content)
            .takeIf { it.type == MIRROR_TYPE && it.schemaVersion <= MIRROR_SCHEMA }
    } catch (e: Exception) {
        null
    }

    private suspend fun writeLocked(entities: List<SavedPlaylistEntity>): Result<Unit> {
        val mirror = PlaylistMirrorFile(
            updatedAtMillis = System.currentTimeMillis(),
            playlists = entities.map { entity ->
                PlaylistMirrorEntry(
                    id = entity.id,
                    title = entity.title,
                    subtitle = entity.subtitle,
                    mode = entity.mode,
                    tracks = runCatching {
                        json.decodeFromString<List<StoredTrack>>(entity.tracksJson)
                    }.getOrDefault(emptyList()),
                    createdAtMillis = entity.createdAtMillis,
                    discoverSignature = entity.discoverSignature,
                    customCoverUri = entity.customCoverUri,
                    isCompleted = entity.isCompleted,
                    isPinned = entity.isPinned,
                )
            },
        )
        return fileExportHelper.writePublicPlaylistMirror(json.encodeToString(mirror))
    }

    private fun PlaylistMirrorEntry.toEntity() = SavedPlaylistEntity(
        id = id,
        title = title,
        subtitle = subtitle,
        mode = mode,
        tracksJson = json.encodeToString(tracks),
        createdAtMillis = createdAtMillis,
        discoverSignature = discoverSignature,
        customCoverUri = customCoverUri,
        isCompleted = isCompleted,
        isPinned = isPinned,
    )
}
