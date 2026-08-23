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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadedTrackDao: DownloadedTrackDao,
    private val downloadManager: TrackDownloadManager,
    private val musicPlayer: MusicPlayer,
) : ViewModel() {

    val downloadedTracks: StateFlow<List<DownloadedTrackEntity>> =
        downloadedTrackDao.getAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totalBytes: StateFlow<Long?> =
        downloadedTrackDao.totalBytes().stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val activeDownloads: StateFlow<Map<String, com.lastwave.app.data.download.DownloadProgress>> =
        downloadManager.downloads

    fun cancelDownload(key: String) {
        downloadManager.cancelDownload(key)
    }

    fun deleteTrack(track: DownloadedTrackEntity) {
        viewModelScope.launch {
            downloadManager.deleteDownloadedTrack(track)
        }
    }

    fun deleteHistoryRecordOnly(track: DownloadedTrackEntity) {
        viewModelScope.launch {
            downloadedTrackDao.delete(track)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            downloadManager.clearAllDownloads()
        }
    }

    fun clearHistoryOnly() {
        viewModelScope.launch {
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
        musicPlayer.play(playable, sourceLabel = "Downloads")
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
        }
    }
}
