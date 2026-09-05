package com.lastwave.app.data.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class DownloadCancelReceiver : BroadcastReceiver() {

    @Inject
    lateinit var downloadManager: TrackDownloadManager

    override fun onReceive(context: Context?, intent: Intent?) {
        val downloadIntent = intent ?: return
        val key = downloadIntent.getStringExtra(TrackDownloadManager.EXTRA_DOWNLOAD_KEY)
            ?.takeIf(String::isNotBlank) ?: return
        when (downloadIntent.action) {
            TrackDownloadManager.ACTION_CANCEL_DOWNLOAD -> downloadManager.cancelDownload(key)
            TrackDownloadManager.ACTION_RECONNECT_DOWNLOAD -> {
                if (!downloadManager.reconnectDownload(key)) {
                    val title = downloadIntent.getStringExtra(TrackDownloadManager.EXTRA_DOWNLOAD_TITLE)
                    val artist = downloadIntent.getStringExtra(TrackDownloadManager.EXTRA_DOWNLOAD_ARTIST)
                    if (!title.isNullOrBlank() && !artist.isNullOrBlank()) {
                        downloadManager.downloadTrack(title = title, artist = artist)
                    }
                }
            }
        }
    }
}
