package com.lastwave.app.widget

import android.media.session.MediaController

/**
 * The single most-recently-active [MediaController], shared in-process
 * between [com.lastwave.app.service.MediaScrobbleListenerService] (which
 * already holds real MediaController access for local scrobbling) and the
 * Now Playing widget's tap actions (play/pause, skip).
 *
 * Deliberately NOT persisted anywhere — a MediaController is only valid
 * for the lifetime of the session it points to, so on process death this
 * naturally goes back to null until the service reconnects and rebinds a
 * live session. The widget's own displayed text/art/playing-state DOES
 * persist across process death (see [WidgetUpdater] / Glance's own
 * Preferences-backed widget state) — only the transport-control target
 * lives here.
 */
object ActiveMediaSessionHolder {
    @Volatile var controller: MediaController? = null
    @Volatile var ownToken: android.media.session.MediaSession.Token? = null

    fun clear(expected: MediaController) {
        if (controller?.sessionToken == expected.sessionToken) controller = null
    }
}
