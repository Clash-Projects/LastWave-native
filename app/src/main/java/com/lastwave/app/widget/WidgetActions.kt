package com.lastwave.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

/**
 * Toggles play/pause on whichever session [ActiveMediaSessionHolder] is
 * currently pointing at. If no session is available (e.g. the scrobbler
 * hasn't been granted Notification Listener access, or nothing has played
 * since the last reboot), this is a no-op — there's deliberately no error
 * state shown on the widget itself, matching how the platform's own media
 * controls behave when a session disappears mid-interaction.
 */
class TogglePlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val controller = ActiveMediaSessionHolder.controller ?: return
        val playing = controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
        runCatching {
            if (playing) controller.transportControls.pause() else controller.transportControls.play()
        }
    }
}

class SkipNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val controller = ActiveMediaSessionHolder.controller ?: return
        runCatching { controller.transportControls.skipToNext() }
    }
}
