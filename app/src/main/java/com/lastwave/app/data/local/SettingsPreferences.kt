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

enum class LyricsUiVersion(val id: String, val title: String) {
    CLASSIC("classic", "Classic"),
    MODERN("modern", "Modern");

    companion object {
        fun fromId(id: String?): LyricsUiVersion =
            entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}

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
     *  directly from lossless CDN when a high-confidence match exists. Falls back to YouTube Music. */
    val preferLosslessStreaming: Boolean = true,
    /** Preferred quality preset for lossless streaming (27: 24/192, 7: 24/96, 6: 16/44.1, 5: 320k).
     *  If a track does not support the requested quality, the worker automatically selects the highest available. */
    val losslessQuality: Int = 27,
    /** Preferred quality preset for downloads (27: 24/192, 7: 24/96, 6: 16/44.1, 5: 320k, -1: YouTube Music). */
    val downloadQuality: Int = 27,
    /** Optional studio-clarity curve. Disabled by default because fixed tone
     *  shaping cannot be neutral on every speaker, headset and OEM spatializer. */
    val isStudioMasterClarityEnabled: Boolean = false,
    /** When true, completely bypasses DSP, EQ, tone effects, and software volume ducking for bit-exact audio. */
    val isBitPerfectEnabled: Boolean = false,
    /** Lyrics UI layout version (Classic or Modern). */
    val lyricsUiVersion: LyricsUiVersion = LyricsUiVersion.CLASSIC,
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
    /** YouTube-Music-style endless playback: when a finite queue runs out, keep
     *  going with tracks similar to what just played instead of stopping. */
    val autoplayEnabled: Boolean = true,
    /** Include every audio file on the device in the local library, not just
     *  the app's own Music/LastWave downloads folder. Imported device files are
     *  never deleted by the app — see TrackDownloadManager.isAppOwned. */
    val scanFullDeviceAudio: Boolean = true,
)

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
        val PREFER_LOSSLESS_STREAMING = booleanPreferencesKey("lw_prefer_lossless_streaming")
        val LOSSLESS_QUALITY = intPreferencesKey("lw_lossless_quality")
        val DOWNLOAD_QUALITY = intPreferencesKey("lw_download_quality")
        val MUSIC_ENHANCER = booleanPreferencesKey("lw_music_enhancer")
        val BIT_PERFECT_ENABLED = booleanPreferencesKey("lw_bit_perfect_enabled")
        val LYRICS_UI_VERSION = stringPreferencesKey("lw_lyrics_ui_version")
        val LYRICS_ANIMATION = stringPreferencesKey("lw_lyrics_animation")
        val CROSSFADE_ENABLED = booleanPreferencesKey("lw_crossfade_enabled")
        val CROSSFADE_SECONDS = intPreferencesKey("lw_crossfade_seconds")
        val WAVY_SEEKBAR_ENABLED = booleanPreferencesKey("lw_wavy_seekbar_enabled")
        val DOWNLOAD_LYRICS = booleanPreferencesKey("lw_download_lyrics")
        val AUTOPLAY_ENABLED = booleanPreferencesKey("lw_autoplay_enabled")
        val SCAN_FULL_DEVICE_AUDIO = booleanPreferencesKey("lw_scan_full_device_audio")
    }

    val settings: Flow<MiscSettings> = dataStore.data
        .recoverPreferences("SettingsPreferences")
        .map { p ->
            MiscSettings(
                dynamicNowPlayingEnabled = p.readSafely(Keys.DYNAMIC_NOW_PLAYING) ?: false,
                useCustomFont = p.readSafely(Keys.USE_CUSTOM_FONT) ?: true,
                pinnedFriends = p.readSafely(Keys.PINNED_FRIENDS) ?: emptySet(),
                preferLosslessStreaming = p.readSafely(Keys.PREFER_LOSSLESS_STREAMING) ?: true,
                losslessQuality = p.readSafely(Keys.LOSSLESS_QUALITY)?.takeIf { it in LOSSLESS_QUALITIES } ?: 27,
                downloadQuality = p.readSafely(Keys.DOWNLOAD_QUALITY)?.takeIf { it in DOWNLOAD_QUALITIES } ?: 27,
                isStudioMasterClarityEnabled = p.readSafely(Keys.MUSIC_ENHANCER) ?: false,
                isBitPerfectEnabled = p.readSafely(Keys.BIT_PERFECT_ENABLED) ?: false,
                lyricsUiVersion = LyricsUiVersion.fromId(p.readSafely(Keys.LYRICS_UI_VERSION)),
                lyricsAnimation = LyricsAnimation.fromId(p.readSafely(Keys.LYRICS_ANIMATION)),
                crossfadeEnabled = p.readSafely(Keys.CROSSFADE_ENABLED) ?: false,
                crossfadeSeconds = (p.readSafely(Keys.CROSSFADE_SECONDS) ?: 5).coerceIn(1, 12),
                wavySeekbarEnabled = p.readSafely(Keys.WAVY_SEEKBAR_ENABLED) ?: true,
                downloadLyrics = p.readSafely(Keys.DOWNLOAD_LYRICS) ?: true,
                autoplayEnabled = p.readSafely(Keys.AUTOPLAY_ENABLED) ?: true,
                scanFullDeviceAudio = p.readSafely(Keys.SCAN_FULL_DEVICE_AUDIO) ?: true,
            )
        }

    suspend fun setDynamicNowPlaying(enabled: Boolean) {
        dataStore.edit { it[Keys.DYNAMIC_NOW_PLAYING] = enabled }
    }

    suspend fun setUseCustomFont(enabled: Boolean) {
        dataStore.edit { it[Keys.USE_CUSTOM_FONT] = enabled }
    }

    suspend fun setPreferLosslessStreaming(enabled: Boolean) {
        dataStore.edit {
            it[Keys.PREFER_LOSSLESS_STREAMING] = enabled
        }
    }

    suspend fun setLosslessQuality(quality: Int) {
        dataStore.edit {
            val q = quality.takeIf { it in LOSSLESS_QUALITIES } ?: 27
            it[Keys.LOSSLESS_QUALITY] = q
        }
    }

    suspend fun setDownloadQuality(quality: Int) {
        dataStore.edit {
            val q = quality.takeIf { it in DOWNLOAD_QUALITIES } ?: 27
            it[Keys.DOWNLOAD_QUALITY] = q
        }
    }

    suspend fun setStudioMasterClarity(enabled: Boolean) {
        dataStore.edit { it[Keys.MUSIC_ENHANCER] = enabled }
    }

    suspend fun setLyricsUiVersion(version: LyricsUiVersion) {
        dataStore.edit { it[Keys.LYRICS_UI_VERSION] = version.id }
    }

    suspend fun setBitPerfectEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BIT_PERFECT_ENABLED] = enabled }
    }

    suspend fun setLyricsAnimation(animation: LyricsAnimation) {
        dataStore.edit { it[Keys.LYRICS_ANIMATION] = animation.id }
    }

    suspend fun setCrossfadeEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CROSSFADE_ENABLED] = enabled }
    }

    suspend fun setCrossfadeSeconds(seconds: Int) {
        dataStore.edit { it[Keys.CROSSFADE_SECONDS] = seconds.coerceIn(1, 12) }
    }

    suspend fun setWavySeekbarEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.WAVY_SEEKBAR_ENABLED] = enabled }
    }

    suspend fun setDownloadLyrics(enabled: Boolean) {
        dataStore.edit { it[Keys.DOWNLOAD_LYRICS] = enabled }
    }

    suspend fun setAutoplayEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTOPLAY_ENABLED] = enabled }
    }

    suspend fun setScanFullDeviceAudio(enabled: Boolean) {
        dataStore.edit { it[Keys.SCAN_FULL_DEVICE_AUDIO] = enabled }
    }

    suspend fun toggleFriendPinned(username: String) {
        dataStore.edit { prefs ->
            val current = prefs.readSafely(Keys.PINNED_FRIENDS) ?: emptySet()
            prefs[Keys.PINNED_FRIENDS] = if (username in current) current - username else current + username
        }
    }

    private companion object {
        val LOSSLESS_QUALITIES = setOf(5, 6, 7, 27)
        val DOWNLOAD_QUALITIES = setOf(-1, 5, 6, 7, 27)
    }
}
