package com.lastwave.app.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import androidx.media3.common.C
import com.lastwave.app.data.local.EQ_BAND_FREQS_HZ
import com.lastwave.app.data.local.EqualizerPreferences
import com.lastwave.app.data.local.EqualizerSettings
import com.lastwave.app.data.local.SettingsPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide audio-effects chain attached to the music player's audio
 * session, hosting the two Experimental audio features:
 *
 *  • **15-band Equalizer** — Android's stock [Equalizer] effect exposes a
 *    device-dependent number of bands (often just 5), so our fixed 15-point
 *    curve is linearly interpolated onto whatever hardware bands exist.
 *    The UI always shows the full 15-band curve; the sound matches it on
 *    every device regardless of what the OEM exposes.
 *
 *  • **Music Enhancer** — not an equalizer: a gentle mastering-style chain
 *    ([BassBoost] warmth + [Virtualizer] width + a subtle [LoudnessEnhancer]
 *    lift) that makes tracks feel fuller and more alive without reshaping
 *    their tonal balance.
 *
 * All effect objects are created lazily per audio session (the session id
 * changes whenever the platform audio server restarts) and released when
 * disabled so we never hold onto the device's limited global effect slots.
 */
@Singleton
class AudioEffectsEngine @Inject constructor(
    equalizerPreferences: EqualizerPreferences,
    settingsPreferences: SettingsPreferences,
    private val applicationScope: CoroutineScope,
) {
    /** Active audio session id; [C.AUDIO_SESSION_ID_UNSET] == none attached yet. */
    private var sessionId: Int = C.AUDIO_SESSION_ID_UNSET

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudness: LoudnessEnhancer? = null

    @Volatile private var eqSettings = EqualizerSettings()
    @Volatile private var enhancerEnabled = false

    init {
        applicationScope.launch(Dispatchers.Main.immediate) {
            equalizerPreferences.settings.collect { settings ->
                eqSettings = settings
                applyEqualizer()
            }
        }
        applicationScope.launch(Dispatchers.Main.immediate) {
            settingsPreferences.settings.collect { misc ->
                enhancerEnabled = misc.musicEnhancerEnabled
                applyEnhancer()
            }
        }
    }

    /** Called by [MusicPlayer] whenever ExoPlayer binds to a new audio session. */
    fun attach(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId == sessionId) return
        applicationScope.launch(Dispatchers.Main.immediate) {
            releaseAll()
            sessionId = audioSessionId
            applyEqualizer()
            applyEnhancer()
        }
    }

    // ── Equalizer ──

    private fun ensureEqualizer(): Equalizer? {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return null
        return equalizer ?: runCatching { Equalizer(EFFECT_PRIORITY, sessionId) }
            .onSuccess { equalizer = it }
            .getOrNull()
    }

    private fun applyEqualizer() {
        val eq = if (eqSettings.enabled) ensureEqualizer() else equalizer
        if (eq == null) {
            if (!eqSettings.enabled) releaseEqualizer()
            return
        }
        runCatching {
            if (!eqSettings.enabled) {
                eq.enabled = false
                releaseEqualizer()
                return
            }
            val range = eq.bandLevelRange
            val minMb = range.first().toInt()
            val maxMb = range.last().toInt()
            for (band in 0 until eq.numberOfBands.toInt()) {
                val centerHz = eq.getCenterFreq(band.toShort()) / MILLIHERTZ_PER_HZ
                val gainDb = interpolateCurve(centerHz)
                val millibels = (gainDb * MB_PER_DB).toInt().coerceIn(minMb, maxMb)
                eq.setBandLevel(band.toShort(), millibels.toShort())
            }
            eq.enabled = true
        }.onFailure {
            android.util.Log.w(TAG, "Equalizer apply failed", it)
        }
    }

    /** Linear interpolation of our fixed 15-point curve at an arbitrary
     *  hardware band center frequency; clamps outside the curve's span. */
    private fun interpolateCurve(hz: Int): Float {
        val freqs = EQ_BAND_FREQS_HZ
        val gains = eqSettings.gainsDb
        if (gains.size != freqs.size) return 0f
        if (hz <= freqs.first()) return gains.first()
        if (hz >= freqs.last()) return gains.last()
        for (i in 0 until freqs.lastIndex) {
            val lo = freqs[i]
            val hi = freqs[i + 1]
            if (hz in lo..hi) {
                val t = (hz - lo).toFloat() / (hi - lo)
                return gains[i] + (gains[i + 1] - gains[i]) * t
            }
        }
        return 0f
    }

    // ── Music Enhancer ──

    private fun applyEnhancer() {
        if (!enhancerEnabled || sessionId == C.AUDIO_SESSION_ID_UNSET) {
            releaseEnhancer()
            return
        }
        runCatching {
            val bass = bassBoost ?: BassBoost(EFFECT_PRIORITY, sessionId).also { bassBoost = it }
            if (bass.strengthSupported) bass.setStrength(ENHANCER_BASS_STRENGTH)
            bass.enabled = true
        }
        runCatching {
            val virt = virtualizer ?: Virtualizer(EFFECT_PRIORITY, sessionId).also { virtualizer = it }
            if (virt.strengthSupported) virt.setStrength(ENHANCER_VIRTUALIZER_STRENGTH)
            virt.enabled = true
        }
        runCatching {
            val loud = loudness ?: LoudnessEnhancer(sessionId).also { loudness = it }
            loud.setTargetGain(ENHANCER_LOUDNESS_MB)
            loud.enabled = true
        }
    }

    // ── Lifecycle ──

    private fun releaseEqualizer() {
        runCatching { equalizer?.enabled = false }
        runCatching { equalizer?.release() }
        equalizer = null
    }

    private fun releaseEnhancer() {
        listOf(bassBoost, virtualizer).forEach { fx ->
            runCatching { fx?.enabled = false }
            runCatching { fx?.release() }
        }
        runCatching { loudness?.enabled = false }
        runCatching { loudness?.release() }
        bassBoost = null
        virtualizer = null
        loudness = null
    }

    private fun releaseAll() {
        releaseEqualizer()
        releaseEnhancer()
    }

    private companion object {
        const val TAG = "AudioEffects"
        /** Priority 0: normal priority for app-internal effects. */
        const val EFFECT_PRIORITY = 0
        /** getCenterFreq returns millihertz. */
        const val MILLIHERTZ_PER_HZ = 1000
        /** Equalizer band levels are in millibels. */
        const val MB_PER_DB = 100f

        // Music Enhancer tuning: strong enough to feel, low enough to stay
        // clean. Strength scales are device-defined 0..1000.
        const val ENHANCER_BASS_STRENGTH: Short = 650
        const val ENHANCER_VIRTUALIZER_STRENGTH: Short = 500
        const val ENHANCER_LOUDNESS_MB = 1500 // +1.5 dB
    }
}
