package com.lastwave.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class AccentMode(val storageValue: String) {
    MANUAL("manual"),
    DYNAMIC("dynamic"),
    MONOCHROME("monochrome");

    companion object {
        fun fromStorage(value: String?): AccentMode =
            entries.firstOrNull { it.storageValue == value } ?: MANUAL
    }
}

data class ThemePrefs(
    val accentColor: String = "#E03030",
    val accentLight: String = "#FF6060",
    val accentMode: AccentMode = AccentMode.MANUAL,
    val amoled: Boolean = false,
    /** Experimental iOS-style liquid-glass materials. Off by default — the
     *  classic opaque look stays untouched until the user opts in. */
    val liquidGlass: Boolean = false,
)

@Singleton
class ThemePreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val ACCENT_COLOR = stringPreferencesKey("lw_accent")
        val ACCENT_LIGHT = stringPreferencesKey("lw_accentLight")
        val ACCENT_MODE = stringPreferencesKey("lw_accentMode")
        val AMOLED = booleanPreferencesKey("lw_amoled")
        val LIQUID_GLASS = booleanPreferencesKey("lw_liquidGlass")
    }

    val prefs: Flow<ThemePrefs> = dataStore.data.map { p ->
        ThemePrefs(
            accentColor = p[Keys.ACCENT_COLOR] ?: "#E03030",
            accentLight = p[Keys.ACCENT_LIGHT] ?: "#FF6060",
            accentMode = AccentMode.fromStorage(p[Keys.ACCENT_MODE]),
            amoled = p[Keys.AMOLED] ?: false,
            liquidGlass = p[Keys.LIQUID_GLASS] ?: false,
        )
    }

    suspend fun setManualAccent(color: String, light: String) {
        dataStore.edit {
            it[Keys.ACCENT_COLOR] = color
            it[Keys.ACCENT_LIGHT] = light
            it[Keys.ACCENT_MODE] = AccentMode.MANUAL.storageValue
        }
    }

    suspend fun setMode(mode: AccentMode) {
        dataStore.edit { it[Keys.ACCENT_MODE] = mode.storageValue }
    }

    suspend fun setAmoled(enabled: Boolean) {
        dataStore.edit { it[Keys.AMOLED] = enabled }
    }

    suspend fun setLiquidGlass(enabled: Boolean) {
        dataStore.edit { it[Keys.LIQUID_GLASS] = enabled }
    }
}
