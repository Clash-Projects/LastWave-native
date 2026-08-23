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
        if (intent?.action == TrackDownloadManager.ACTION_CANCEL_DOWNLOAD) {
            val key = intent.getStringExtra(TrackDownloadManager.EXTRA_DOWNLOAD_KEY)
            if (!key.isNullOrBlank()) {
                downloadManager.cancelDownload(key)
            }
        }
    }
}
