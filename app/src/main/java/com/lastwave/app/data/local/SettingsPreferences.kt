package com.lastwave.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class LyricsAnimation(val id: String, val title: String, val description: String) {
    APPLE_FLUID("apple_fluid", "Apple Fluid", "Smooth spring scaling with dynamic focal tracking"),
    KARAOKE_PULSE("karaoke_pulse", "Karaoke Pulse", "Rhythmic scale pop with energetic spring bounce"),
    KINETIC_SLIDE("kinetic_slide", "Kinetic Slide", "Active line glides smoothly from leading edge"),
    CINEMATIC_BLUR("cinematic_blur", "Cinematic Focus", "Soft background blur & vertical drift on past lines"),
    LOSSLESS_GLOW("lossless_glow", "Lossless Glow", "Vibrant gradient text with frosted glass reflection"),
    CARD_POP("card_pop", "Glass Elevation", "3D floating glass card lift with specular highlights"),
    APPLE_ZOOM("apple_zoom", "Dynamic Focus Zoom", "Expanded focal magnification with fluid spring push"),
    MINIMAL_WAVE("minimal_wave", "Minimal Clean", "Pure low-latency opacity transitions without distortion");

    companion object {
        fun fromId(id: String?): LyricsAnimation =
            entries.firstOrNull { it.id == id } ?: APPLE_FLUID
    }
}

data class MiscSettings(
    /** When on, the app's accent color follows the dominant color of the
     *  currently-scrobbling track's artwork (Home's "now playing" track),
     *  updating live as that track changes. Falls back to the user's
     *  regular selected accent whenever nothing is playing or artwork
     *  colors can't be extracted — see ThemeRepository.updateNowPlayingArtwork. */
    val dynamicNowPlayingEnabled: Boolean = false,
    /** "Use Application Font" — on: the bundled Google Sans Flex variable
     *  font (see ui/theme/Type.kt); off: the device's own system font.
     *  Defaults on so the app ships with its own identity out of the box. */
    val useCustomFont: Boolean = true,
    /** Last.fm usernames pinned to the top of Home's friend-switcher sheet
     *  (long-press a friend row to toggle). Order among pinned friends
     *  follows whatever order user.getfriends itself returns them in —
     *  just filtered to the front, not independently reorderable. */
    val pinnedFriends: Set<String> = emptySet(),
    /** When true, the player attempts to resolve and stream lossless / Hi-Res audio
     *  directly from Qobuz CDN when a high-confidence match exists. Falls back to YouTube Music. */
    val preferQobuzStreaming: Boolean = true,
    /** Preferred quality preset for Qobuz streaming (27: 24/192, 7: 24/96, 6: 16/44.1, 5: 320k).
     *  If a track does not support the requested quality, the worker automatically selects the highest available. */
    val qobuzQuality: Int = 27,
    /** Optional studio-clarity curve. Disabled by default because fixed tone
     *  shaping cannot be neutral on every speaker, headset and OEM spatializer. */
    val isStudioMasterClarityEnabled: Boolean = false,
    /** Experimental lyrics animation style (Settings -> Experimental -> Lyrics Animation). */
    val lyricsAnimation: LyricsAnimation = LyricsAnimation.APPLE_FLUID,
    /** Blend the end of one queued track into the beginning of the next. */
    val crossfadeEnabled: Boolean = false,
    /** Crossfade length in seconds; kept within the native settings slider range. */
    val crossfadeSeconds: Int = 5,
    /** When true (default), uses the multi-layer dynamic wavy seekbar.
     *  When false, uses the classic standard progress slider in the player tab. */
    val wavySeekbarEnabled: Boolean = true,
    /** When true (default), downloads fetch and save synced lyrics (.lrc companion files and embedded tags). */
    val downloadLyrics: Boolean = true,
    /** When true (default), caches streamed songs locally for instant playback and data savings. */
    val streamCacheEnabled: Boolean = true,
    /** Maximum number of streamed songs to retain in local LRU stream cache (25, 50, 100, 200, 500). */
    val streamCacheSongLimit: Int = 50,
    /** When true, automatically syncs liked/favorited songs to YouTube Music. */
    val autoSyncLikedSongsToYouTube: Boolean = false,
    /** When true, optimizes the app for low-end devices by disabling expensive rendering. */
    val performanceModeEnabled: Boolean = false,
) {
    /** Effective wavy seekbar status: returns true only if enabled and performance mode is OFF. */
    val effectiveWavySeekbar: Boolean
        get() = wavySeekbarEnabled && !performanceModeEnabled
}

/** Small dedicated prefs object for settings that don't fit ThemePreferences
 *  or SessionPreferences semantically — shares the app's single DataStore. */
@Singleton
class SettingsPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val DYNAMIC_NOW_PLAYING = booleanPreferencesKey("lw_dynamic_now_playing")
        val USE_CUSTOM_FONT = booleanPreferencesKey("lw_use_custom_font")
        val PINNED_FRIENDS = stringSetPreferencesKey("lw_pinned_friends")
        val PREFER_QOBUZ_STREAMING = booleanPreferencesKey("lw_prefer_qobuz_streaming")
        val QOBUZ_QUALITY = intPreferencesKey("lw_qobuz_quality")
        val MUSIC_ENHANCER = booleanPreferencesKey("lw_music_enhancer")
        val LYRICS_ANIMATION = stringPreferencesKey("lw_lyrics_animation")
        val CROSSFADE_ENABLED = booleanPreferencesKey("lw_crossfade_enabled")
        val CROSSFADE_SECONDS = intPreferencesKey("lw_crossfade_seconds")
        val WAVY_SEEKBAR_ENABLED = booleanPreferencesKey("lw_wavy_seekbar_enabled")
        val DOWNLOAD_LYRICS = booleanPreferencesKey("lw_download_lyrics")
        val STREAM_CACHE_ENABLED = booleanPreferencesKey("lw_stream_cache_enabled")
        val STREAM_CACHE_SONG_LIMIT = intPreferencesKey("lw_stream_cache_song_limit")
        val AUTO_SYNC_LIKED_TO_YOUTUBE = booleanPreferencesKey("lw_auto_sync_liked_to_youtube")
        val PERFORMANCE_MODE_ENABLED = booleanPreferencesKey("lw_performance_mode_enabled")
    }

    val settings: Flow<MiscSettings> = dataStore.data
        .recoverPreferences("SettingsPreferences")
        .map { p ->
            MiscSettings(
                dynamicNowPlayingEnabled = p.readSafely(Keys.DYNAMIC_NOW_PLAYING) ?: false,
                useCustomFont = p.readSafely(Keys.USE_CUSTOM_FONT) ?: true,
                pinnedFriends = p.readSafely(Keys.PINNED_FRIENDS) ?: emptySet(),
                preferQobuzStreaming = p.readSafely(Keys.PREFER_QOBUZ_STREAMING) ?: true,
                qobuzQuality = p.readSafely(Keys.QOBUZ_QUALITY)?.takeIf { it in QOBUZ_QUALITIES } ?: 27,
                isStudioMasterClarityEnabled = p.readSafely(Keys.MUSIC_ENHANCER) ?: false,
                lyricsAnimation = LyricsAnimation.fromId(p.readSafely(Keys.LYRICS_ANIMATION)),
                crossfadeEnabled = p.readSafely(Keys.CROSSFADE_ENABLED) ?: false,
                crossfadeSeconds = (p.readSafely(Keys.CROSSFADE_SECONDS) ?: 5).coerceIn(1, 10),
                wavySeekbarEnabled = p.readSafely(Keys.WAVY_SEEKBAR_ENABLED) ?: true,
                downloadLyrics = p.readSafely(Keys.DOWNLOAD_LYRICS) ?: true,
                streamCacheEnabled = p.readSafely(Keys.STREAM_CACHE_ENABLED) ?: true,
                streamCacheSongLimit = p.readSafely(Keys.STREAM_CACHE_SONG_LIMIT) ?: 50,
                autoSyncLikedSongsToYouTube = p.readSafely(Keys.AUTO_SYNC_LIKED_TO_YOUTUBE) ?: false,
                performanceModeEnabled = p.readSafely(Keys.PERFORMANCE_MODE_ENABLED) ?: false,
            )
        }

    suspend fun setDynamicNowPlaying(enabled: Boolean) {
        dataStore.edit { it[Keys.DYNAMIC_NOW_PLAYING] = enabled }
    }

    suspend fun setUseCustomFont(enabled: Boolean) {
        dataStore.edit { it[Keys.USE_CUSTOM_FONT] = enabled }
    }

    suspend fun setPreferQobuzStreaming(enabled: Boolean) {
        dataStore.edit { it[Keys.PREFER_QOBUZ_STREAMING] = enabled }
    }

    suspend fun setQobuzQuality(quality: Int) {
        dataStore.edit { it[Keys.QOBUZ_QUALITY] = quality.takeIf { it in QOBUZ_QUALITIES } ?: 27 }
    }

    suspend fun setStudioMasterClarity(enabled: Boolean) {
        dataStore.edit { it[Keys.MUSIC_ENHANCER] = enabled }
    }

    suspend fun setLyricsAnimation(animation: LyricsAnimation) {
        dataStore.edit { it[Keys.LYRICS_ANIMATION] = animation.id }
    }

    suspend fun setCrossfadeEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CROSSFADE_ENABLED] = enabled }
    }

    suspend fun setCrossfadeSeconds(seconds: Int) {
        dataStore.edit { it[Keys.CROSSFADE_SECONDS] = seconds.coerceIn(1, 10) }
    }

    suspend fun setWavySeekbarEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.WAVY_SEEKBAR_ENABLED] = enabled }
    }

    suspend fun setDownloadLyrics(enabled: Boolean) {
        dataStore.edit { it[Keys.DOWNLOAD_LYRICS] = enabled }
    }

    suspend fun setStreamCacheEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.STREAM_CACHE_ENABLED] = enabled }
    }

    suspend fun setStreamCacheSongLimit(limit: Int) {
        dataStore.edit { it[Keys.STREAM_CACHE_SONG_LIMIT] = limit.coerceIn(10, 1000) }
    }

    suspend fun setAutoSyncLikedSongsToYouTube(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_SYNC_LIKED_TO_YOUTUBE] = enabled }
    }

    suspend fun setPerformanceModeEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.PERFORMANCE_MODE_ENABLED] = enabled }
    }

    suspend fun toggleFriendPinned(username: String) {
        dataStore.edit { prefs ->
            val current = prefs.readSafely(Keys.PINNED_FRIENDS) ?: emptySet()
            prefs[Keys.PINNED_FRIENDS] = if (username in current) current - username else current + username
        }
    }

    private companion object {
        val QOBUZ_QUALITIES = setOf(5, 6, 7, 27)
    }
}
