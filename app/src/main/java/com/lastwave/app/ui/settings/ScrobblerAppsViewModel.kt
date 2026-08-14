package com.lastwave.app.ui.settings

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.local.ScrobblerPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class InstalledAppEntry(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    /** True if this package matches LastWave's own curated list of common
     *  music/streaming apps (see KNOWN_MUSIC_PACKAGES below) — shown as a
     *  separate "Detected Music Players" group up top, same idea as the
     *  reference app's own "Music Players" section, rather than making the
     *  user hunt for Spotify/YT Music alphabetically in a list of every
     *  app on their phone. */
    val isKnownMusicPlayer: Boolean,
)

/** Package names LastWave recognizes as music/audio apps out of the box.
 *  Not exhaustive (there's no public "is this a music app" API) — apps not
 *  on this list still show up fine in the full list below, just not
 *  pre-grouped at the top; the user can select any app either way. */
private val KNOWN_MUSIC_PACKAGES = setOf(
    "com.spotify.music",
    "com.google.android.apps.youtube.music",
    "com.google.android.youtube",
    "com.apple.android.music",
    "deezer.android.app",
    "com.aspiro.tidal",
    "com.amazon.mp3",
    "com.soundcloud.android",
    "com.pandora.android",
    "com.jio.media.jiobeats",
    "wynk.com.airtel",
    "com.gaana",
    "com.jiosaavn.android",
    "com.maxmpz.audioplayer", // Poweramp
    "com.tbig.playerpro",
    "com.simplemobiletools.musicplayer",
    "org.videolan.vlc",
    "com.frolo.muse", // Musicolet-adjacent
    "com.github.andreyasadchy.xtra",
    "in.krosbits.musicolet",
    "com.bsplayer.bspandroid.free",
    "com.miui.player",
    "com.samsung.android.app.music.chn",
    "com.sec.android.app.music",
)

@HiltViewModel
class ScrobblerAppsViewModel @Inject constructor(
    private val application: Application,
    private val scrobblerPreferences: ScrobblerPreferences,
) : ViewModel() {

    private val _allApps = MutableStateFlow<List<InstalledAppEntry>>(emptyList())
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val selectedPackages: StateFlow<Set<String>> = scrobblerPreferences.settings
        .map { it.selectedPackages }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Apps filtered by the current search query. Selected apps are always
     *  grouped together at the top (regardless of whether they were
     *  auto-detected or picked manually) — a manually-added app used to
     *  stay wherever it alphabetically fell in "All apps" even after being
     *  selected, which read as if the selection hadn't really "taken".
     *  Within each of the two groups, detected music players still sort
     *  first, then alphabetically. */
    val apps: StateFlow<List<InstalledAppEntry>> = combine(_allApps, _query, selectedPackages) { all, q, selected ->
        val filtered = if (q.isBlank()) all else all.filter { it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
        filtered.sortedWith(
            compareByDescending<InstalledAppEntry> { it.packageName in selected }
                .thenByDescending { it.isKnownMusicPlayer }
                .thenBy { it.label.lowercase() },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        loadApps()
    }

    fun setQuery(q: String) { _query.value = q }

    private fun loadApps() {
        viewModelScope.launch {
            _loading.value = true
            val list = withContext(Dispatchers.IO) {
                val pm = application.packageManager
                // Only apps a user would plausibly pick (has a launcher
                // entry, i.e. shows up in their app drawer, OR isn't a
                // system package) — filters out the hundreds of
                // system/background packages that would otherwise flood
                // this list with things that could never actually have a
                // "now playing" media session.
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .asSequence()
                    .filter { it.packageName != application.packageName }
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                    .distinctBy { it.packageName }
                    .map { info ->
                        val icon = runCatching { pm.getApplicationIcon(info) }.getOrNull()?.let { drawableToBitmap(it) }
                        InstalledAppEntry(
                            packageName = info.packageName,
                            label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName),
                            icon = icon,
                            isKnownMusicPlayer = info.packageName in KNOWN_MUSIC_PACKAGES,
                        )
                    }
                    .sortedBy { it.label.lowercase() }
                    .toList()
            }
            _allApps.value = list
            _loading.value = false
        }
    }

    /** PackageManager hands back a Drawable (often an AdaptiveIconDrawable),
     *  not something Compose's Image() can use directly — rendered into a
     *  fixed-size bitmap once here (off the main thread, cached in state)
     *  rather than re-drawing it on every recomposition. */
    private fun drawableToBitmap(drawable: Drawable): ImageBitmap? = runCatching {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    }.getOrNull()

    fun toggle(packageName: String) {
        viewModelScope.launch { scrobblerPreferences.togglePackage(packageName) }
    }

    /** "Auto-detect installed music players" — select every currently
     *  installed app LastWave recognizes as a music/streaming app in one
     *  tap, instead of the user having to find and check each one by hand. */
    fun selectAllDetectedMusicPlayers() {
        viewModelScope.launch {
            val detected = _allApps.value.filter { it.isKnownMusicPlayer }.map { it.packageName }.toSet()
            if (detected.isEmpty()) return@launch
            val current = selectedPackages.value
            scrobblerPreferences.setSelectedPackages(current + detected)
        }
    }
}
