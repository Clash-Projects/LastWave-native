package com.lastwave.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A live, in-app, in-memory log of exactly what MediaScrobbleListenerService
 * is doing — added after three separate rounds of blind hypothesis-fixes
 * (crash guarding, duration=0 fallback, MediaSession.Token identity) didn't
 * resolve a still-reported "never scrobbles" issue with no way to see WHY
 * from either side of the conversation. This is the actual fix for THAT:
 * visibility. Open Settings' "Scrobbler debug log" while a track plays and
 * watch it live — every track detection, threshold calculation, reset, and
 * scrobble attempt (success or the exact failure reason) is recorded here
 * as it happens, in the same process the service runs in, no adb/logcat
 * needed. In-memory only (resets on process death) — sufficient for
 * watching a live test session, not meant as a permanent history.
 */
@Singleton
class ScrobbleDebugLog @Inject constructor() {
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(message: String) {
        val stamped = "${timeFormat.format(Date())}  $message"
        _entries.update { (listOf(stamped) + it).take(80) }
    }

    fun clear() {
        _entries.update { emptyList() }
    }
}
