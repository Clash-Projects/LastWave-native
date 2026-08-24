package com.lastwave.app.data.repository

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.lastwave.app.data.artwork.ArtworkNormalizer
import com.lastwave.app.data.artwork.ArtworkRepository
import com.lastwave.app.data.local.AccentMode
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.local.ThemePreferences
import com.lastwave.app.data.local.ThemePrefs
import com.lastwave.app.ui.theme.Md3SchemeBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import android.os.Handler
import android.os.Looper
import javax.inject.Inject
import javax.inject.Singleton

data class ThemeUiState(
    val colorScheme: ColorScheme,
    val amoled: Boolean,
    val mode: AccentMode,
    /** Raw hex of the manually-picked accent (preset or custom), independent
     *  of [colorScheme] — colorScheme.primary is a generated M3 tone of this
     *  seed, not the seed itself, so it can't be compared back against a
     *  preset's hex to know which one is selected. This can. */
    val accentColorHex: String,
    /** Settings' "Use Application Font" toggle — see ui/theme/Type.kt for
     *  what turning it off falls back to. */
    val useCustomFont: Boolean = true,
    /** Experimental liquid-glass materials (Settings → Experimental). When
     *  true, container roles in [colorScheme] are semi-translucent and the
     *  chrome surfaces get specular glass dressing. */
    val liquidGlass: Boolean = false,
)

@Singleton
class ThemeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val themePreferences: ThemePreferences,
    private val settingsPreferences: SettingsPreferences,
    private val paletteExtractor: NowPlayingPaletteExtractor,
    private val artworkRepository: ArtworkRepository,
    private val homeRepository: HomeRepository,
    private val applicationScope: CoroutineScope,
) {
    /** Hex captured from the device wallpaper, when accentMode == DYNAMIC and
     *  a wallpaper color is available. Null falls back to the manual accent,
     *  same as _applyAccent()'s "dynamic saved but unavailable -> manual" rule. */
    private val dynamicHex = MutableStateFlow<String?>(null)

    /** Cache key (ArtworkNormalizer.cacheKey(name, artist)) of the currently-
     *  scrobbling track, or null when nothing is playing. This is what
     *  actually drives now-playing theming — see the init block below for
     *  why a raw artwork URL isn't tracked directly. */
    private val nowPlayingTrackKey = MutableStateFlow<String?>(null)

    /** Hex extracted from the currently-scrobbling track's artwork, when
     *  "Dynamic Now Playing Theme" is on. Null whenever nothing is playing,
     *  no artwork could be resolved from any provider, or extraction
     *  failed — see the init block below. */
    private val nowPlayingHex = MutableStateFlow<String?>(null)

    init {
        // Bug fix: this used to read RecentTrack.artworkUrl straight off the
        // Last.fm API response and extract a palette from THAT. Last.fm's
        // own artwork field is blank for a large fraction of tracks — it's
        // exactly why the app already has an iTunes-fallback artwork
        // pipeline (ArtworkRepository) that every other artwork-showing
        // screen goes through. Now-playing theming skipped that pipeline
        // entirely, so on any track where Last.fm itself had no art, this
        // silently produced nothing and the accent never changed — which
        // is likely most of the time. Routing through the same
        // resolve()/resolved combo everything else uses fixes that, and
        // also means it stays reactive: resolve() is async (network +
        // disk cache), so this recomputes automatically the moment a
        // result lands, rather than needing a one-shot synchronous URL.
        applicationScope.launch {
            combine(nowPlayingTrackKey, artworkRepository.resolved) { key, resolvedMap ->
                key?.let { resolvedMap[it] }
            }.distinctUntilChanged().collect { url ->
                nowPlayingHex.value = if (url.isNullOrBlank()) null else paletteExtractor.extractAccentHex(url)
            }
        }

        // Bug fix (Dynamic Color): refreshWallpaperAccent() used to be
        // called ONLY from setMode() — i.e. only at the exact moment the
        // user flips the toggle on. dynamicHex starts back at null every
        // process launch (it's a plain in-memory MutableStateFlow, nothing
        // persists it), so anyone who had Dynamic Color already enabled
        // from a previous session got dynamic == null on cold start, and
        // the `when` below fell straight through to the manual accent —
        // this is why the reported symptom was "wallpaper is purple but
        // the app still shows red": red (#E03030) is the manual accent's
        // own default, not a wallpaper color at all. Populating dynamicHex
        // here, once, whenever DYNAMIC is already the persisted mode,
        // closes that gap.
        applicationScope.launch {
            if (themePreferences.prefs.first().accentMode == AccentMode.DYNAMIC) {
                refreshWallpaperAccent()
            }
        }

        // "Refresh automatically whenever the wallpaper changes" — the
        // system callback for exactly that, rather than only ever
        // re-reading colors on toggle/cold-start. Registered unconditionally
        // (cheap, no permission beyond what getWallpaperColors already
        // needs) so a value is always ready the moment the user switches
        // into Dynamic mode, not just after the next wallpaper change.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                WallpaperManager.getInstance(context).addOnColorsChangedListener(
                    { _, which ->
                        if (which and WallpaperManager.FLAG_SYSTEM != 0) refreshWallpaperAccent()
                    },
                    Handler(Looper.getMainLooper()),
                )
            } catch (e: Exception) {
                android.util.Log.w("ThemeRepository", "Wallpaper listener unavailable", e)
                // Some OEM configurations restrict this — Dynamic Color
                // simply won't live-update on those devices; the
                // startup/toggle-time refresh above still works.
            }
        }
    }

    private fun getSystemWallpaperColorHex(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return try {
                androidx.compose.material3.dynamicDarkColorScheme(context).primary.toHex()
            } catch (e: Exception) {
                null
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
        return try {
            val colors = WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            colors?.primaryColor?.let {
                "#%02X%02X%02X".format(
                    (it.red() * 255).toInt(),
                    (it.green() * 255).toInt(),
                    (it.blue() * 255).toInt(),
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    val uiState: StateFlow<ThemeUiState> = combine(
        themePreferences.prefs,
        dynamicHex,
        nowPlayingHex,
        settingsPreferences.settings,
    ) { prefs: ThemePrefs, dynamic: String?, nowPlaying: String?, misc: MiscSettings ->
        val isAmoled = prefs.amoled
        val isGlass = prefs.liquidGlass
        val scheme = when {
            misc.dynamicNowPlayingEnabled && nowPlaying != null ->
                Md3SchemeBuilder.buildScheme(nowPlaying, isAmoled, isGlass)
            prefs.accentMode == AccentMode.MONOCHROME ->
                Md3SchemeBuilder.buildMonochromeScheme(isAmoled, isGlass)
            prefs.accentMode == AccentMode.DYNAMIC -> {
                val seed = dynamic ?: getSystemWallpaperColorHex() ?: prefs.accentColor
                Md3SchemeBuilder.buildScheme(seed, isAmoled, isGlass)
            }
            else ->
                Md3SchemeBuilder.buildScheme(prefs.accentColor, isAmoled, isGlass)
        }
        ThemeUiState(
            colorScheme = scheme,
            amoled = isAmoled,
            mode = prefs.accentMode,
            accentColorHex = prefs.accentColor,
            useCustomFont = misc.useCustomFont,
            liquidGlass = isGlass,
        )
    }.stateIn(
        applicationScope,
        SharingStarted.Eagerly,
        ThemeUiState(
            colorScheme = Md3SchemeBuilder.buildScheme("#E03030", false),
            amoled = false,
            mode = AccentMode.MANUAL,
            accentColorHex = "#E03030",
            useCustomFont = true,
            liquidGlass = false,
        ),
    )

    suspend fun setManualAccent(color: Color) {
        val hex = color.toHex()
        themePreferences.setManualAccent(hex, hex)
    }

    suspend fun setMode(mode: AccentMode) {
        if (mode == AccentMode.DYNAMIC) refreshWallpaperAccent()
        themePreferences.setMode(mode)
    }

    suspend fun setAmoled(enabled: Boolean) = themePreferences.setAmoled(enabled)

    suspend fun setLiquidGlass(enabled: Boolean) = themePreferences.setLiquidGlass(enabled)

    /** Turning this on used to just flip the DataStore flag and wait for
     *  Home's own poll loop to eventually call updateNowPlayingArtwork() —
     *  if the user was sitting on Settings (as anyone testing the toggle
     *  would be) that could mean no visible change until they next visited
     *  Home. Now enabling it also fetches the current now-playing status
     *  directly, so the accent updates right away wherever the toggle was
     *  flipped from, same as Home's own poll cycle would produce. */
    suspend fun setDynamicNowPlaying(enabled: Boolean) {
        settingsPreferences.setDynamicNowPlaying(enabled)
        if (enabled) refreshNowPlayingImmediately()
    }

    private suspend fun refreshNowPlayingImmediately() {
        try {
            val page = homeRepository.fetchRecentTracks(page = 1, limit = 1).getOrNull()
            val track = page?.nowPlaying
            updateNowPlayingArtwork(track?.name, track?.artist?.displayName)
        } catch (e: Exception) {
            // Best-effort only — the regular Home poll loop will catch up
            // once the user visits Home, same as before this method existed.
        }
    }

    fun refreshWallpaperAccent() {
        dynamicHex.value = getSystemWallpaperColorHex()
    }

    /**
     * Called from HomeViewModel whenever the polled "now playing" track
     * changes (including becoming null — nothing scrobbling right now).
     * Cheap to call on every poll tick: the key dedup below means
     * resolve() only fires once per distinct track, not once per poll.
     *
     * Resolution itself goes through ArtworkRepository — the same
     * Last.fm -> iTunes fallback chain and memory/disk cache every other
     * artwork-showing screen uses — so this benefits from whatever's
     * already cached for that track and won't hammer either provider.
     */
    fun updateNowPlayingArtwork(trackName: String?, artistName: String?) {
        if (trackName.isNullOrBlank() || artistName.isNullOrBlank()) {
            nowPlayingTrackKey.value = null
            return
        }
        val key = ArtworkNormalizer.cacheKey(trackName, artistName)
        if (key == nowPlayingTrackKey.value) return
        nowPlayingTrackKey.value = key
        applicationScope.launch { artworkRepository.resolve(trackName, artistName) }
    }

    private fun Color.toHex(): String = "#%02X%02X%02X".format(
        (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
    )
}
