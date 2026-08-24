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

/** The 15 standard ISO 2/3-octave center frequencies (Hz) the equalizer's
 *  band sliders map onto. Low end is bass body, mid range carries vocals,
 *  the top end is air and sparkle. */
val EQ_BAND_FREQS_HZ = intArrayOf(25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000)

/** Compact frequency label under each band slider ("63", "1K", "16K"...). */
fun eqBandLabel(hz: Int): String = if (hz >= 1000) "${hz / 1000}K" else "$hz"

data class EqPreset(
    val name: String,
    /** Gain in dB per band, same order as [EQ_BAND_FREQS_HZ]. */
    val gainsDb: List<Float>,
)

/**
 * Curated, listening-tested 15-band curves. Values are deliberately kept in
 * the ±8 dB range — beyond that Android's audio fx chain starts clipping and
 * smearing transients, so these are the strongest settings that still sound
 * clean at high volume.
 */
object EqualizerPresets {
    const val CUSTOM_NAME = "Custom"

    val FLAT = EqPreset("Default", List(EQ_BAND_FREQS_HZ.size) { 0f })
    /**
     * Balanced house curve used by Studio Master Clarity. The restrained
     * sub-bass lift adds weight, the low-mid cuts remove masking, and the
     * broad presence/air shelf exposes detail without a narrow, harsh peak.
     * AudioEffectsEngine supplies the required gain staging and peak ceiling.
     */
    val STUDIO_MASTER = EqPreset(
        "Studio Master",
        listOf(
            1.2f, 1.1f, 0.8f, 0.2f, -0.7f,
            -0.9f, -0.4f, 0.2f, 0.8f, 1.4f,
            2.1f, 2.3f, 1.7f, 2.0f, 2.2f,
        ),
    )

    val ALL: List<EqPreset> = listOf(
        FLAT,
        STUDIO_MASTER,
        EqPreset("Acoustic", listOf(3.0f, 2.5f, 2.0f, 1.5f, 0.5f, 1.0f, 1.5f, 2.0f, 2.0f, 1.5f, 1.0f, 1.0f, 1.0f, 1.5f, 1.5f)),
        EqPreset("Vocal Clarity", listOf(-1.5f, -1.0f, -0.5f, 0f, 0.5f, 1.0f, 2.0f, 3.0f, 3.5f, 3.0f, 2.5f, 2.0f, 1.5f, 1.5f, 1.0f)),
        EqPreset("Classical", listOf(3.0f, 2.5f, 2.0f, 1.5f, 0.5f, 0f, 0f, 0f, 0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.0f)),
        EqPreset("Jazz", listOf(2.5f, 2.0f, 1.5f, 1.0f, 0f, -0.5f, 0f, 0.5f, 1.0f, 1.5f, 2.0f, 2.0f, 2.5f, 2.5f, 2.5f)),
        EqPreset("Rock", listOf(4.0f, 3.5f, 3.0f, 1.5f, 0f, -1.0f, -1.0f, -0.5f, 0.5f, 1.5f, 2.5f, 3.0f, 3.5f, 4.0f, 4.0f)),
        EqPreset("Pop", listOf(-1.0f, -0.5f, 0.0f, 1.0f, 1.5f, 2.0f, 2.0f, 1.5f, 1.0f, 0.5f, 0.5f, 1.0f, 1.5f, 2.0f, 2.5f)),
        EqPreset("Treble Air", listOf(0f, 0f, 0f, -0.5f, -0.5f, -0.5f, 0f, 0.5f, 1.0f, 1.5f, 2.0f, 3.0f, 3.5f, 4.5f, 5.0f)),
        EqPreset("Deep Bass Clean", listOf(5.0f, 4.5f, 3.5f, 2.5f, 1.0f, 0f, -0.5f, -0.5f, 0f, 0f, 0f, 0f, 0.5f, 0.5f, 0.5f)),
        EqPreset("Electronic", listOf(4.5f, 4.0f, 3.0f, 2.0f, 0.5f, -0.5f, 0f, 1.0f, 2.0f, 2.5f, 2.5f, 2.5f, 3.0f, 3.5f, 4.0f)),
        EqPreset("R&B", listOf(5.0f, 4.5f, 3.5f, 2.0f, 1.0f, -0.5f, -1.0f, -0.5f, 0.5f, 1.5f, 2.0f, 2.5f, 3.0f, 3.0f, 3.0f)),
    )

    fun byName(name: String): EqPreset? = ALL.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

data class EqualizerSettings(
    val enabled: Boolean = false,
    val presetName: String = EqualizerPresets.FLAT.name,
    /** Per-band gain in dB, index-aligned with [EQ_BAND_FREQS_HZ]. */
    val gainsDb: List<Float> = EqualizerPresets.FLAT.gainsDb,
)

@Singleton
class EqualizerPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("lw_eq_enabled")
        val PRESET_NAME = stringPreferencesKey("lw_eq_preset")
        val GAINS_DB = stringPreferencesKey("lw_eq_gains")
    }

    val settings: Flow<EqualizerSettings> = dataStore.data.map { p ->
        val storedGains = p[Keys.GAINS_DB]?.split(',')?.mapNotNull(String::toFloatOrNull)
            ?.takeIf { it.size == EQ_BAND_FREQS_HZ.size }
        // The stored name is trusted as-is: every writer of GAINS_DB also
        // updates PRESET_NAME (Custom on manual band edits), so the pair can't
        // drift apart. Unknown names fall back to Default.
        val resolvedName = p[Keys.PRESET_NAME]
            ?.let { name ->
                if (name == EqualizerPresets.CUSTOM_NAME || EqualizerPresets.byName(name) != null) name else null
            }
            ?: EqualizerPresets.FLAT.name
        val gains = storedGains
            ?: EqualizerPresets.byName(resolvedName)?.gainsDb
            ?: EqualizerPresets.FLAT.gainsDb
        EqualizerSettings(
            enabled = p[Keys.ENABLED] ?: false,
            presetName = resolvedName,
            gainsDb = gains,
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ENABLED] = enabled }
    }

    suspend fun applyPreset(preset: EqPreset) {
        dataStore.edit {
            it[Keys.PRESET_NAME] = preset.name
            it[Keys.GAINS_DB] = encodeGains(preset.gainsDb)
            it[Keys.ENABLED] = true
        }
    }

    suspend fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in EQ_BAND_FREQS_HZ.indices) return
        dataStore.edit {
            val current = it[Keys.GAINS_DB]?.split(',')?.mapNotNull { v -> v.toFloatOrNull() }
                ?.takeIf { v -> v.size == EQ_BAND_FREQS_HZ.size }
                ?: EqualizerPresets.FLAT.gainsDb
            val next = current.toMutableList().also { list -> list[bandIndex] = gainDb }
            it[Keys.PRESET_NAME] = EqualizerPresets.CUSTOM_NAME
            it[Keys.GAINS_DB] = encodeGains(next)
        }
    }

    suspend fun reset() {
        applyPreset(EqualizerPresets.FLAT)
    }

    private fun encodeGains(gains: List<Float>): String =
        gains.joinToString(",") { "%.1f".format(it) }
}
