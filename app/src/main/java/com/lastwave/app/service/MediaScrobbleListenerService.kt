package com.lastwave.app.service

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import com.lastwave.app.data.local.ScrobblerPreferences
import com.lastwave.app.data.repository.ScrobbleRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "MediaScrobbleListener"

/**
 * LastWave's own local scrobbler, built after the reference Pano-Scrobbler
 * screenshots — but without needing any accompanying Spotify/YouTube-style
 * "reveals metadata" SDK: Android's own MediaSessionManager already
 * exposes whatever any app publishes as its active media session (title/
 * artist/album/duration/position), which is exactly the same source every
 * local scrobbler (Pano included) actually reads from.
 *
 * Reliability history on this class, in order:
 * 1) Every call into MediaSessionManager/MediaController is wrapped in
 *    runCatching — a SecurityException from getActiveSessions()/
 *    registerCallback() (happens on some OEM ROMs, sometimes even briefly
 *    right after access is freshly granted) used to propagate straight out
 *    of onListenerConnected(), and since this service shares the app's
 *    process, that crashed the whole app on every single launch.
 * 2) A notification-post listener was added as a fast nudge to re-check
 *    active sessions, since MediaSessionManager's own
 *    OnActiveSessionsChangedListener only fires on a SET change (session
 *    created/destroyed) and can lag on some OEMs.
 * 3) This pass: two remaining real reliability gaps, found from an actual
 *    reported repro (3 different player apps, switching between them):
 *    - RESUMING a track that was already playing before (same track, no
 *      metadata change — e.g. switching back to a paused player) never
 *      re-announced "now playing", because that submission previously only
 *      fired from onMetadataChanged. Now onPlaybackStateChanged ALSO
 *      triggers a now-playing announcement whenever a session transitions
 *      into STATE_PLAYING, gated by a service-wide `lastAnnouncedKey` so
 *      switching between several simultaneously-open players correctly
 *      re-announces whichever one just actually started playing, instead
 *      of leaving a stale announcement from whichever one played first.
 *    - Push-based discovery (notification posts, active-sessions-changed)
 *      isn't guaranteed on every OEM — added an unconditional 4s poll of
 *      getActiveSessions() as a standing safety net underneath the push
 *      signals, so a session is discovered within a few seconds worst-case
 *      even if this device's push signals are unreliable, rather than only
 *      on a best-effort push and otherwise possibly never.
 *    - YouTube/YT Music's auto-generated "Topic" channels put " - Topic"
 *      on the end of the artist field (e.g. "JVNLIII - Topic") — stripped
 *      before submitting, matching Pano Scrobbler's own behavior, instead
 *      of scrobbling the channel suffix as if it were part of the artist
 *      name.
 * 4) The actual explanation for "now-playing shows up fine but a track
 *    never scrobbles even after listening to the whole thing, only when
 *    using this app's own scrobbler and not a third-party one": `watched`
 *    was keyed by the raw MediaController object, which uses default
 *    reference-identity equals()/hashCode() — nothing guarantees Android
 *    returns the same object across repeated getActiveSessions() calls,
 *    only that it refers to the same session. Under the 4s standing poll,
 *    every currently-playing session could look "stale" on every single
 *    poll tick, get unbound (cancelling its scrobble timer) and instantly
 *    rebound as brand new (accumulated play time reset to 0) — forever,
 *    well before ever reaching the scrobble threshold, while now-playing
 *    (a one-shot call needing no sustained state) kept working. Re-keyed
 *    on MediaSession.Token, which has real value-based equality.
 */
@AndroidEntryPoint
class MediaScrobbleListenerService : NotificationListenerService() {

    @Inject lateinit var scrobblerPreferences: ScrobblerPreferences
    @Inject lateinit var scrobbleRepository: ScrobbleRepository
    @Inject lateinit var debugLog: ScrobbleDebugLog

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var pollJob: Job? = null
    private val watched = mutableMapOf<android.media.session.MediaSession.Token, WatchedSession>()

    /** The track key (artist|title) LastWave most recently told Last.fm is
     *  "now playing", across ALL watched sessions — not per-session. Last.fm
     *  only has one now-playing slot per account, so this exists purely to
     *  avoid redundant re-submission of the exact same still-playing track
     *  on every poll tick; any different session actually starting playback
     *  always overrides it. */
    @Volatile private var lastAnnouncedKey: String = ""

    @Volatile private var enabled: Boolean = false
    @Volatile private var submitNowPlaying: Boolean = true
    @Volatile private var scrobblePercent: Int = 50
    @Volatile private var selectedPackages: Set<String> = emptySet()

    private inner class WatchedSession(val controller: MediaController) {
        var callback: MediaController.Callback? = null
        var trackKey: String = ""
        var accumulatedMs: Long = 0L
        var playingSinceElapsed: Long? = null
        var scrobbledForKey: String = ""
        var startedAtEpochSec: Long = 0L
        var scrobbleJob: Job? = null
        // See onTrackChanged's doc — tracks whether the LAST scrobble
        // threshold we scheduled for the current trackKey was computed
        // from a real known duration, or the unknown-duration fallback.
        var durationKnown: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()
        runCatching {
            serviceScope.launch {
                scrobblerPreferences.settings.collect { s ->
                    val wasEnabled = enabled
                    enabled = s.enabled
                    submitNowPlaying = s.submitNowPlaying
                    scrobblePercent = s.scrobblePercent
                    val changedPackages = selectedPackages != s.selectedPackages
                    selectedPackages = s.selectedPackages
                    if (wasEnabled != enabled || changedPackages) {
                        debugLog.log("Settings: enabled=$enabled, nowPlaying=$submitNowPlaying, percent=$scrobblePercent%, watching=${selectedPackages.size} app(s)")
                    }
                    if (changedPackages) refreshActiveSessions()
                }
            }
        }.onFailure { Log.w(TAG, "onCreate settings collector failed to start", it) }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        debugLog.log("Notification listener connected")
        runCatching {
            val manager = getSystemService(MediaSessionManager::class.java) ?: return
            val component = ComponentName(this, MediaScrobbleListenerService::class.java)
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                runCatching { bindControllers(controllers.orEmpty()) }
                    .onFailure { Log.w(TAG, "bindControllers (session change) failed", it) }
            }
            sessionsListener = listener
            manager.addOnActiveSessionsChangedListener(listener, component)
            bindControllers(manager.getActiveSessions(component))
        }.onFailure {
            debugLog.log("onListenerConnected FAILED: ${it.message}")
            Log.w(TAG, "onListenerConnected failed — scrobbling unavailable this session", it)
        }

        // Standing safety net — see class doc, reliability point 3. Doesn't
        // depend on any push signal at all; worst case, a session that
        // somehow slipped past every push trigger is still picked up
        // within ~4s of this loop running.
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            while (true) {
                delay(4_000)
                refreshActiveSessions()
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        runCatching {
            val manager = getSystemService(MediaSessionManager::class.java)
            sessionsListener?.let { manager?.removeOnActiveSessionsChangedListener(it) }
        }.onFailure { Log.w(TAG, "onListenerDisconnected cleanup failed", it) }
        pollJob?.cancel()
        runCatching { unbindAll() }.onFailure { Log.w(TAG, "unbindAll on disconnect failed", it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
        runCatching { unbindAll() }.onFailure { Log.w(TAG, "unbindAll on destroy failed", it) }
        serviceScope.cancel()
    }

    private fun refreshActiveSessions() {
        runCatching {
            val manager = getSystemService(MediaSessionManager::class.java) ?: return
            val component = ComponentName(this, MediaScrobbleListenerService::class.java)
            bindControllers(manager.getActiveSessions(component))
        }.onFailure { Log.w(TAG, "refreshActiveSessions failed", it) }
    }

    /** Every notification post from a watched package is used as an
     *  immediate nudge to re-check active sessions, on top of the standing
     *  poll above — a notification post (basically every music app posts
     *  one the moment a track starts) is often faster than waiting for the
     *  next poll tick or the active-sessions-changed callback. */
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg !in selectedPackages) return
        runCatching { refreshActiveSessions() }.onFailure { Log.w(TAG, "onNotificationPosted refresh failed", it) }
    }
    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) {}

    private fun bindControllers(controllers: List<MediaController>) {
        // THE actual reason a track could survive a full listen and still
        // never scrobble: this used to be keyed by the raw MediaController
        // object itself. MediaController does NOT override equals()/
        // hashCode() — it's reference identity only — and there's no
        // guarantee Android hands back the SAME object on every
        // getActiveSessions() call, only that it refers to the same
        // underlying session. Under the 4s standing poll, if the platform
        // (varies by OEM/version) returns a fresh wrapper object each time,
        // EVERY currently-watched, actually-still-playing session looked
        // "stale" on every single poll tick — got unbound (cancelling its
        // in-progress scrobble timer) and immediately rebound as if brand
        // new (resetting accumulated play time back to 0) — over and over,
        // every ~4 seconds, forever. That makes reaching the scrobble
        // threshold nearly impossible no matter how long the track
        // actually played, while now-playing (a single one-shot call, not
        // something that needs sustained tracked state) kept working fine
        // — exactly the split behavior reported. MediaSession.Token is
        // Parcelable and DOES implement real value-based equals()/
        // hashCode() specifically for comparisons like this, so it's the
        // correct stable identity for the same session across calls,
        // regardless of whether the MediaController wrapper is reused.
        val liveTokens = controllers.mapNotNull { c -> runCatching { c.sessionToken }.getOrNull() }.toSet()
        val stale = watched.keys - liveTokens
        if (stale.isNotEmpty()) {
            stale.forEach { token ->
                val pkg = runCatching { watched[token]?.controller?.packageName }.getOrNull() ?: "?"
                debugLog.log("Session gone: $pkg (was tracking ${watched[token]?.trackKey.orEmpty()})")
            }
        }
        stale.forEach { unbindToken(it) }

        controllers.forEach { controller ->
            runCatching {
                if (controller.packageName !in selectedPackages) return@forEach
                val token = controller.sessionToken
                if (watched.containsKey(token)) return@forEach
                debugLog.log("New session bound: ${controller.packageName}")
                val session = WatchedSession(controller)
                val callback = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        runCatching { onTrackChanged(session, metadata) }.onFailure { Log.w(TAG, "onMetadataChanged failed", it) }
                    }
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        runCatching { onStateChanged(session, state) }.onFailure { Log.w(TAG, "onPlaybackStateChanged failed", it) }
                    }
                    override fun onSessionDestroyed() {
                        runCatching { unbindToken(token) }.onFailure { Log.w(TAG, "unbind on session destroyed failed", it) }
                    }
                }
                session.callback = callback
                controller.registerCallback(callback)
                watched[token] = session
                onTrackChanged(session, controller.metadata)
                onStateChanged(session, controller.playbackState)
            }.onFailure { Log.w(TAG, "Failed to bind controller for ${runCatching { controller.packageName }.getOrDefault("?")}", it) }
        }
    }

    private fun unbindToken(token: android.media.session.MediaSession.Token) {
        val session = watched.remove(token) ?: return
        session.callback?.let { runCatching { session.controller.unregisterCallback(it) } }
        session.scrobbleJob?.cancel()
    }

    private fun unbindAll() {
        watched.keys.toList().forEach { unbindToken(it) }
    }

    /** Strips YouTube/YT Music auto-generated "Topic" channel suffixes
     *  (e.g. "JVNLIII - Topic" -> "JVNLIII") so the artist scrobbled is the
     *  actual artist name, not the channel display name — same cleanup
     *  Pano Scrobbler does. Only strips an exact trailing " - Topic" /
     *  " Topic" (case-insensitive) so it can't accidentally mangle a real
     *  artist whose name happens to contain "topic" elsewhere. */
    private fun cleanArtist(raw: String): String {
        val trimmed = raw.trim()
        val suffixes = listOf(" - Topic", " Topic")
        for (suffix in suffixes) {
            if (trimmed.endsWith(suffix, ignoreCase = true)) {
                return trimmed.substring(0, trimmed.length - suffix.length).trim()
            }
        }
        return trimmed
    }

    private fun onTrackChanged(session: WatchedSession, metadata: MediaMetadata?) {
        if (metadata == null) return
        val rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (rawArtist.isNullOrBlank() || title.isNullOrBlank()) return
        val artist = cleanArtist(rawArtist)
        val key = "$artist|$title"
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        if (key == session.trackKey) {
            // Same track — but a very real, common case: plenty of apps
            // fire onMetadataChanged in stages, title/artist first with
            // duration still 0, then a follow-up call fills the real
            // duration in moments later. Since the key is unchanged, this
            // used to just return here and keep whatever threshold was
            // first scheduled — permanently stuck on the unknown-duration
            // 4-minute fallback if that's what the FIRST call had, even
            // once the real (likely shorter) duration became available.
            // For any song under 4 minutes, that means the track finishes
            // and the NEXT track's onTrackChanged cancels this one's
            // scrobbleJob before the fallback threshold is ever reached —
            // it silently never scrobbles, no matter how long it actually
            // played. Re-schedule with the accurate threshold the moment
            // a real duration shows up.
            if (!session.durationKnown && durationMs > 0L) {
                debugLog.log("Duration became known late for \"$title\" — was using 4min fallback, now ${durationMs}ms; rescheduling")
                session.durationKnown = true
                session.scrobbleJob?.cancel()
                scheduleScrobbleCheck(session, key, artist, title, album, durationMs)
            }
            return
        }

        debugLog.log("Track detected: \"$title\" — $artist (${session.controller.packageName}), duration=${if (durationMs > 0L) "${durationMs}ms" else "unknown"}")

        session.trackKey = key
        session.accumulatedMs = 0L
        session.durationKnown = durationMs > 0L
        session.playingSinceElapsed = if (session.controller.playbackState?.state == PlaybackState.STATE_PLAYING) SystemClock.elapsedRealtime() else null
        session.startedAtEpochSec = System.currentTimeMillis() / 1000
        session.scrobbleJob?.cancel()

        if (session.playingSinceElapsed != null) {
            announceNowPlaying(key, artist, title, album)
        }
        scheduleScrobbleCheck(session, key, artist, title, album, durationMs)
    }

    /** Fires on EVERY playback-state transition, not just track changes —
     *  this is what makes resuming an already-playing track (same
     *  metadata, no onMetadataChanged at all) correctly re-announce
     *  now-playing, and what makes switching back to a different app's
     *  paused session take over the now-playing slot again immediately. */
    private fun onStateChanged(session: WatchedSession, state: PlaybackState?) {
        val playing = state?.state == PlaybackState.STATE_PLAYING
        if (playing) {
            if (session.playingSinceElapsed == null) {
                session.playingSinceElapsed = SystemClock.elapsedRealtime()
                if (session.trackKey.isNotBlank()) {
                    val metadata = session.controller.metadata
                    val rawArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    if (!rawArtist.isNullOrBlank() && !title.isNullOrBlank()) {
                        announceNowPlaying(session.trackKey, cleanArtist(rawArtist), title, metadata.getString(MediaMetadata.METADATA_KEY_ALBUM))
                    }
                }
            }
        } else {
            session.playingSinceElapsed?.let { since ->
                session.accumulatedMs += SystemClock.elapsedRealtime() - since
            }
            session.playingSinceElapsed = null
        }
    }

    private fun announceNowPlaying(key: String, artist: String, title: String, album: String?) {
        if (!enabled || !submitNowPlaying) return
        if (key == lastAnnouncedKey) return
        lastAnnouncedKey = key
        serviceScope.launch {
            runCatching { scrobbleRepository.updateNowPlaying(artist, title, album) }
                .onFailure { Log.w(TAG, "updateNowPlaying failed", it) }
        }
    }

    private fun scheduleScrobbleCheck(session: WatchedSession, key: String, artist: String, title: String, album: String?, durationMs: Long) {
        if (!enabled) {
            debugLog.log("Scrobbling disabled — not scheduling \"$title\"")
            return
        }
        // Last.fm's own scrobble rule: a track must be longer than 30s, and
        // needs to have played to 50% (or whatever percent is configured)
        // of its length, capped at 4 minutes of playback either way,
        // whichever comes first.
        //
        // The real bug this replaced: MediaMetadata's duration field is 0
        // (or missing entirely) from plenty of real apps — plenty of
        // streaming/browser-based players never populate it, or only fill
        // it in a moment after playback starts. The old `durationMs <=
        // 30_000L` check treated that unknown-duration case exactly like
        // "this track is too short to scrobble" and silently gave up
        // FOREVER for that track, on every app that doesn't reliably
        // report duration — which is exactly what "listened to the whole
        // song and it still never showed up" looks like. When duration is
        // unknown, this now falls back to a fixed 4-minute ACCUMULATED
        // PLAYBACK TIME threshold instead of refusing outright — the same
        // percent-of-duration math simply can't apply without a real
        // duration, but there's no reason to abandon scrobbling entirely
        // just because one field was missing.
        val hasKnownDuration = durationMs > 0L
        if (hasKnownDuration && durationMs <= 30_000L) {
            debugLog.log("\"$title\" is ${durationMs}ms (<=30s) — Last.fm's own rule excludes it from scrobbling")
            return
        }
        val thresholdMs = if (hasKnownDuration) {
            minOf((durationMs * scrobblePercent) / 100, 4 * 60_000L)
        } else {
            4 * 60_000L
        }
        debugLog.log("Scrobble threshold for \"$title\": ${thresholdMs / 1000}s of playback" + if (!hasKnownDuration) " (unknown-duration fallback)" else "")
        session.scrobbleJob = serviceScope.launch {
            while (true) {
                delay(3_000)
                if (session.trackKey != key) {
                    debugLog.log("\"$title\" changed/stopped before reaching its ${thresholdMs / 1000}s threshold — not scrobbled")
                    return@launch
                }
                val playedMs = session.accumulatedMs + (session.playingSinceElapsed?.let { SystemClock.elapsedRealtime() - it } ?: 0L)
                if (playedMs >= thresholdMs) {
                    if (session.scrobbledForKey != key) {
                        session.scrobbledForKey = key
                        debugLog.log("Threshold reached for \"$title\" — submitting scrobble\u2026")
                        runCatching { scrobbleRepository.scrobble(artist, title, album, session.startedAtEpochSec) }
                            .onSuccess { result ->
                                debugLog.log(
                                    when (result) {
                                        ScrobbleRepository.Result.Success -> "Scrobble SUCCEEDED for \"$title\""
                                        ScrobbleRepository.Result.NoSessionKey -> "Scrobble FAILED for \"$title\": no session key — sign out and sign in again with Connect with Last.fm"
                                        is ScrobbleRepository.Result.Failed -> "Scrobble FAILED for \"$title\": ${result.message}"
                                    },
                                )
                            }
                            .onFailure {
                                debugLog.log("Scrobble THREW for \"$title\": ${it.message}")
                                Log.w(TAG, "scrobble failed", it)
                            }
                    }
                    return@launch
                }
            }
        }
    }
}
