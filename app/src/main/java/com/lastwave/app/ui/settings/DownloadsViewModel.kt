package com.lastwave.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.download.TrackDownloadManager
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private fun <T> Flow<T>.withDownloadsFallback(fallback: T): Flow<T> =
    catch { error ->
        android.util.Log.e("DownloadsViewModel", "Downloads storage unavailable", error)
        emit(fallback)
    }

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadedTrackDao: DownloadedTrackDao,
    private val downloadManager: TrackDownloadManager,
    private val musicPlayer: MusicPlayer,
) : ViewModel() {

    val downloadedTracks: StateFlow<List<DownloadedTrackEntity>> =
        downloadedTrackDao.getAll().withDownloadsFallback(emptyList()).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    val totalBytes: StateFlow<Long?> =
        downloadedTrackDao.totalBytes().withDownloadsFallback(0L).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            0L,
        )

    val activeDownloads: StateFlow<Map<String, com.lastwave.app.data.download.DownloadProgress>> =
        downloadManager.downloads

    init {
        launchDownloadAction("sync downloads from storage") {
            downloadManager.syncDownloadsFromStorage()
        }
    }

    private fun launchDownloadAction(action: String, block: suspend () -> Unit) =
        viewModelScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                android.util.Log.e("DownloadsViewModel", "Failed to $action", error)
            } catch (error: LinkageError) {
                android.util.Log.e("DownloadsViewModel", "Unsupported platform action: $action", error)
            }
        }

    fun cancelDownload(key: String) {
        try {
            downloadManager.cancelDownload(key)
        } catch (error: Exception) {
            android.util.Log.e("DownloadsViewModel", "Failed to cancel download", error)
        } catch (error: LinkageError) {
            android.util.Log.e("DownloadsViewModel", "Download cancellation unsupported", error)
        }
    }

    fun deleteTrack(track: DownloadedTrackEntity) {
        launchDownloadAction("delete download") {
            downloadManager.deleteDownloadedTrack(track)
        }
    }

    fun deleteHistoryRecordOnly(track: DownloadedTrackEntity) {
        launchDownloadAction("delete download history") {
            downloadedTrackDao.delete(track)
        }
    }

    fun clearAll() {
        launchDownloadAction("clear downloads") {
            downloadManager.clearAllDownloads()
        }
    }

    fun clearHistoryOnly() {
        launchDownloadAction("clear download history") {
            downloadedTrackDao.clearAll()
        }
    }

    fun playTrack(track: DownloadedTrackEntity) {
        val playable = PlayableTrack(
            title = track.title,
            artist = track.artist,
            album = track.album,
            artworkUrl = track.artworkUrl,
            playbackUrl = track.mediaStoreUri ?: track.filePath,
        )
        try {
            musicPlayer.play(playable, sourceLabel = "Downloads")
        } catch (error: Exception) {
            android.util.Log.e("DownloadsViewModel", "Could not play download", error)
        } catch (error: LinkageError) {
            android.util.Log.e("DownloadsViewModel", "Playback unsupported on this device", error)
        }
    }

    fun openInFileManager() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).path + "/LastWave"), "resource/folder")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(Intent.createChooser(intent, "Open Music/LastWave").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // Fallback
        } catch (error: LinkageError) {
            // Some custom ROMs omit the expected storage activity.
        }
    }
}
