package com.lastwave.app.data.repository

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.lastwave.app.data.local.AccentMode
import com.lastwave.app.data.local.ThemePreferences
import com.lastwave.app.data.local.ThemePrefs
import com.lastwave.app.ui.theme.Md3SchemeBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

data class ThemeUiState(
    val colorScheme: ColorScheme,
    val amoled: Boolean,
    val mode: AccentMode,
)

@Singleton
class ThemeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val themePreferences: ThemePreferences,
    applicationScope: CoroutineScope,
) {
    /** Hex captured from the device wallpaper, when accentMode == DYNAMIC and
     *  a wallpaper color is available. Null falls back to the manual accent,
     *  same as _applyAccent()'s "dynamic saved but unavailable -> manual" rule. */
    private val dynamicHex = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ThemeUiState> = combine(
        themePreferences.prefs,
        dynamicHex,
    ) { prefs: ThemePrefs, dynamic: String? ->
        val scheme = when {
            prefs.accentMode == AccentMode.MONOCHROME ->
                Md3SchemeBuilder.buildMonochromeScheme(prefs.amoled)
            prefs.accentMode == AccentMode.DYNAMIC && dynamic != null ->
                Md3SchemeBuilder.buildScheme(dynamic, prefs.amoled)
            else ->
                Md3SchemeBuilder.buildScheme(prefs.accentColor, prefs.amoled)
        }
        ThemeUiState(colorScheme = scheme, amoled = prefs.amoled, mode = prefs.accentMode)
    }.stateIn(
        applicationScope,
        SharingStarted.Eagerly,
        ThemeUiState(Md3SchemeBuilder.buildScheme("#E03030", false), amoled = false, mode = AccentMode.MANUAL),
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

    /** Reads the system wallpaper's primary color via WallpaperManager's
     *  color-extraction API (no storage permission required, unlike parsing
     *  the wallpaper bitmap by hand the way a WebView bridge would have to). */
    fun refreshWallpaperAccent() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            dynamicHex.value = null
            return
        }
        val colors: WallpaperColors? = try {
            WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        } catch (e: SecurityException) {
            null
        }
        val primary = colors?.primaryColor
        dynamicHex.value = primary?.let {
            "#%02X%02X%02X".format(
                (it.red() * 255).toInt(),
                (it.green() * 255).toInt(),
                (it.blue() * 255).toInt(),
            )
        }
    }

    private fun Color.toHex(): String = "#%02X%02X%02X".format(
        (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
    )
}
