package com.lastwave.app.widget

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.lastwave.app.service.MediaScrobbleListenerService

abstract class LastWaveWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // If Notification Access is already granted, ask Android to reconnect
        // the listener immediately when the first widget is placed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(context, MediaScrobbleListenerService::class.java),
                )
            }
        }
    }
}

class NowPlayingWidgetReceiver : LastWaveWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}

class CompactNowPlayingWidgetReceiver : LastWaveWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CompactNowPlayingWidget()
}

class ArtworkNowPlayingWidgetReceiver : LastWaveWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ArtworkNowPlayingWidget()
}

class GlassNowPlayingWidgetReceiver : LastWaveWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GlassNowPlayingWidget()
}
