package com.lastwave.app.playback

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide playback audio diagnostics.
 *
 * Every notable engine event (stream open/restart, route rebuilds, native
 * fallbacks, underrun storms, decoder failures) is appended to a small
 * bounded in-memory ring that is cheap enough to record unconditionally —
 * comparing a perfectly working phone against a problematic one then needs
 * no reproduction steps, just [snapshot]. Logcat output stays gated behind
 * debuggable builds so production logging remains clean and lightweight.
 */
object PlaybackDiagnostics {
    private const val TAG = "LastWaveAudio"
    private const val MAX_EVENTS = 96
    private const val MAX_MESSAGE_LENGTH = 220

    val streamOpens = AtomicLong()
    val streamRestarts = AtomicLong()
    val routeRebuilds = AtomicLong()
    val nativeFallbacks = AtomicLong()
    val underrunFallbacks = AtomicLong()
    val gaplessReuses = AtomicLong()

    private class Event(val atRealtimeMs: Long, val source: String, val message: String) {
        override fun toString(): String =
            "+${(atRealtimeMs - bootAnchorMs) / 1000.0}s [$source] $message"
    }

    private val lock = Any()
    private val events = ArrayDeque<Event>(MAX_EVENTS)
    private val bootAnchorMs = SystemClock.elapsedRealtime()

    @Volatile
    private var debuggable: Boolean? = null

    /** Called once from [com.lastwave.app.LastWaveApplication]. Safe to skip; logging stays off. */
    fun install(context: Context) {
        debuggable = try {
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Throwable) {
            false
        }
    }

    fun event(source: String, message: String) {
        val truncated = if (message.length <= MAX_MESSAGE_LENGTH) {
            message
        } else {
            message.substring(0, MAX_MESSAGE_LENGTH) + "…"
        }
        synchronized(lock) {
            if (events.size >= MAX_EVENTS) events.pollFirst()
            events.addLast(Event(SystemClock.elapsedRealtime(), source, truncated))
        }
        if (debuggable == true) Log.d(TAG, "[$source] $truncated")
    }

    fun counter(counter: AtomicLong, source: String, message: String) {
        counter.incrementAndGet()
        event(source, message)
    }

    /** Newest-last human-readable journal for developer diagnostics screens. */
    fun snapshot(): List<String> = synchronized(lock) {
        events.map(Event::toString)
    }

    fun clear() = synchronized(lock) { events.clear() }
}
