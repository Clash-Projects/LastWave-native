package com.lastwave.app.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.lastwave.app.MainActivity
import com.lastwave.app.service.MediaScrobbleListenerService

private fun resolveController(context: Context): MediaController? {
    val held = ActiveMediaSessionHolder.controller
    held?.let {
        val state = runCatching { it.playbackState?.state }.getOrNull()
        if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) return it
    }

    // ActionCallbacks may start in a fresh process. Re-resolve the live
    // controller from Android instead of depending only on an in-memory field.
    val resolved = runCatching {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return@runCatching null
        val listener = ComponentName(context, MediaScrobbleListenerService::class.java)
        manager.getActiveSessions(listener)
            .filter { it.metadata != null }
            .maxByOrNull { controllerRank(it.playbackState?.state) }
            ?.also { ActiveMediaSessionHolder.controller = it }
    }.getOrNull()
    return resolved ?: held
}

private fun controllerRank(state: Int?): Int = when (state) {
    PlaybackState.STATE_PLAYING -> 5
    PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> 4
    PlaybackState.STATE_PAUSED -> 3
    PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> 2
    else -> 1
}

class TogglePlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val controller = resolveController(context) ?: return
        val wasPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        val succeeded = runCatching {
            if (wasPlaying) {
                controller.transportControls.pause()
            } else {
                controller.transportControls.play()
            }
        }.isSuccess
        if (succeeded) WidgetUpdater.setPlaying(context, !wasPlaying)
    }
}

class SkipPreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        resolveController(context)?.let { controller ->
            runCatching { controller.transportControls.skipToPrevious() }
        }
    }
}

class SkipNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        resolveController(context)?.let { controller ->
            runCatching { controller.transportControls.skipToNext() }
        }
    }
}

class OpenMusicAccessAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

class OpenLastWaveAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }
}
