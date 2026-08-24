package com.lastwave.app.playback

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log
import androidx.media3.common.C
import com.lastwave.app.data.local.EQ_BAND_FREQS_HZ
import com.lastwave.app.data.local.EqualizerPreferences
import com.lastwave.app.data.local.EqualizerPresets
import com.lastwave.app.data.local.EqualizerSettings
import com.lastwave.app.data.local.SettingsPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide audiophile DSP audio-effects engine attached to the music player's audio session.
 *
 * Designed around pristine studio-grade playback:
 *  • **Clarity First**: Open vocal presence (1.5k–4kHz) and sparkling air (10k–16kHz) without harshness.
 *  • **Natural Imaging**: No fake 3D virtualizers, comb filtering, or bloated bass effects.
 *  • **Controlled Dynamics**: Gentle five-zone control adds separation and punch without crushing transients.
 *  • **Safe Output**: Dynamic gain staging and peak limiting (Android 9+) protect dense masters.
 *  • **Memory & HAL Safe**: Thread-safe lifecycle using [Mutex] and background dispatchers so vendor
 *    AudioFX IPC calls never stutter playback or block the main thread.
 */
@Singleton
class AudioEffectsEngine @Inject constructor(
    equalizerPreferences: EqualizerPreferences,
    settingsPreferences: SettingsPreferences,
    private val applicationScope: CoroutineScope,
) {
    private var sessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private val effectMutex = Mutex()

    private var equalizer: Equalizer? = null
    private var dynamicsProcessor: DynamicsProcessing? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    @Volatile private var eqSettings = EqualizerSettings()
    @Volatile private var enhancerEnabled = false
    @Volatile private var volumeBoostEnabled = false
    @Volatile private var volumeBoostPercent = 100

    init {
        applicationScope.launch(Dispatchers.Default) {
            equalizerPreferences.settings.collect { settings ->
                eqSettings = settings
                applyProcessing()
            }
        }
        applicationScope.launch(Dispatchers.Default) {
            settingsPreferences.settings.collect { misc ->
                enhancerEnabled = misc.musicEnhancerEnabled
                volumeBoostEnabled = misc.volumeBoostEnabled
                volumeBoostPercent = misc.volumeBoostPercent.coerceIn(100, 200)
                applyProcessing()
            }
        }
    }

    /** Called by [MusicPlayer] whenever ExoPlayer binds to a new audio session. */
    fun attach(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId == sessionId) return
        applicationScope.launch(Dispatchers.Default) {
            effectMutex.withLock {
                releaseAllInternal()
                sessionId = audioSessionId
                applyProcessingInternal()
            }
        }
    }

    /** Detaches and releases all effect instances. */
    fun detach() {
        applicationScope.launch(Dispatchers.Default) {
            effectMutex.withLock {
                releaseAllInternal()
                sessionId = C.AUDIO_SESSION_ID_UNSET
            }
        }
    }

    private fun applyProcessing() {
        applicationScope.launch(Dispatchers.Default) {
            effectMutex.withLock {
                applyProcessingInternal()
            }
        }
    }

    private fun applyProcessingInternal() {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return

        val activeGains: List<Float> = when {
            eqSettings.enabled -> eqSettings.gainsDb
            enhancerEnabled -> EqualizerPresets.STUDIO_MASTER.gainsDb
            else -> List(EQ_BAND_FREQS_HZ.size) { 0f }
        }

        val volumeBoostActive = volumeBoostEnabled && volumeBoostPercent > 100
        val needsToneProcessing = eqSettings.enabled || enhancerEnabled
        val needsProcessing = needsToneProcessing || volumeBoostActive

        if (!needsProcessing) {
            releaseAllInternal()
            return
        }

        val boostDb = if (volumeBoostActive) {
            (20.0 * log10(volumeBoostPercent / 100.0)).toFloat().coerceIn(0f, 6.1f)
        } else 0f

        // Keep tone shaping and loudness as separate stages. Feeding +6 dB
        // into the tone limiter made the old 200% control collapse back toward
        // the original peaks and sound almost unchanged on modern Android.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (needsToneProcessing) applyDynamicsProcessing(activeGains) else releaseToneProcessingInternal()
        } else {
            if (needsToneProcessing) applyEqualizer(activeGains) else releaseToneProcessingInternal()
        }
        if (volumeBoostActive) applyVolumeBoost(boostDb) else releaseLoudnessInternal()
    }

    // ── DynamicsProcessing (API 28+ Studio Precision) ──

    private fun applyDynamicsProcessing(gainsDb: List<Float>) {
        runCatching {
            // Leave at most a small bounded boost before the safety limiter.
            // This retains perceived energy while avoiding constant gain
            // reduction on already-loud masters.
            val stagedInputGainDb = calculateHeadroomDb(gainsDb)

            var dp = dynamicsProcessor
            if (dp == null) {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    /* channelCount = */ 2,
                    /* enablePreEq = */ true,
                    /* preEqBandCount = */ EQ_BAND_FREQS_HZ.size,
                    /* enableMbc = */ true,
                    /* mbcBandCount = */ STUDIO_MBC_BAND_COUNT,
                    /* enablePostEq = */ false,
                    /* postEqBandCount = */ 0,
                    /* enableLimiter = */ true
                )
                    .setInputGainAllChannelsTo(stagedInputGainDb)
                    .build()

                dp = DynamicsProcessing(EFFECT_PRIORITY, sessionId, config).also { dynamicsProcessor = it }
            }

            // Update the existing processor when only the slider changed.
            dp.setInputGainAllChannelsTo(stagedInputGainDb)

            // Apply the shared 15-band Studio Master / user EQ curve.
            val preEq = DynamicsProcessing.Eq(true, true, EQ_BAND_FREQS_HZ.size)
            for (i in EQ_BAND_FREQS_HZ.indices) {
                val band = DynamicsProcessing.EqBand(
                    true,
                    EQ_BAND_FREQS_HZ[i].toFloat(),
                    gainsDb.getOrElse(i) { 0f }
                )
                preEq.setBand(i, band)
            }
            dp.setPreEqAllChannelsTo(preEq)

            // Studio Master only: five broad, gently controlled zones keep
            // sub-bass firm, vocals present and treble calm. Ratios stay very
            // low so micro-detail and natural musical dynamics remain intact.
            dp.setMbcAllChannelsTo(createStudioMasterDynamics(enhancerEnabled))

            // Fast safety ceiling with recovery slow enough to avoid audible
            // pumping. It should stay idle during normal clarity playback and
            // engage mainly for hot masters and rare combined-band peaks.
            val limiter = DynamicsProcessing.Limiter(
                /* inUse = */ true,
                /* enabled = */ true,
                /* linkGroup = */ 0,
                /* attackTime = */ 1.0f,
                /* releaseTime = */ 80.0f,
                /* ratio = */ 20.0f,
                /* threshold = */ -0.8f,
                /* postGain = */ 0.0f
            )
            dp.setLimiterAllChannelsTo(limiter)
            dp.enabled = true
            // A prior vendor-DSP failure may have created the legacy fallbacks.
            // Never leave two independent tone effects active together.
            releaseEqualizerInternal()
        }.onFailure { e ->
            Log.w(TAG, "DynamicsProcessing unavailable, falling back to legacy Equalizer: ${e.message}")
            releaseDynamicsProcessorInternal()
            applyEqualizer(gainsDb)
        }
    }

    private fun createStudioMasterDynamics(enabled: Boolean): DynamicsProcessing.Mbc {
        val stage = DynamicsProcessing.Mbc(enabled, true, STUDIO_MBC_BAND_COUNT)
        for (index in 0 until STUDIO_MBC_BAND_COUNT) {
            stage.setBand(
                index,
                DynamicsProcessing.MbcBand(
                    /* enabled = */ true,
                    /* cutoffFrequency = */ STUDIO_MBC_CUTOFF_HZ[index],
                    /* attackTime = */ STUDIO_MBC_ATTACK_MS[index],
                    /* releaseTime = */ STUDIO_MBC_RELEASE_MS[index],
                    /* ratio = */ STUDIO_MBC_RATIO[index],
                    /* threshold = */ STUDIO_MBC_THRESHOLD_DB[index],
                    /* kneeWidth = */ 8.0f,
                    /* noiseGateThreshold = */ -90.0f,
                    /* expanderRatio = */ 1.0f,
                    /* preGain = */ 0.0f,
                    /* postGain = */ STUDIO_MBC_POST_GAIN_DB[index],
                ),
            )
        }
        return stage
    }

    /**
     * Dedicated audible loudness stage for every supported Android version.
     * LoudnessEnhancer applies the requested gain while compressing only peaks
     * that would clip, which is exactly the behavior a 100–200% control needs.
     */
    private fun applyVolumeBoost(boostDb: Float) {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        runCatching {
            val current = loudnessEnhancer
            val enhancer = if (current != null && runCatching { current.hasControl() }.getOrDefault(false)) {
                current
            } else {
                releaseLoudnessInternal()
                LoudnessEnhancer(sessionId).also {
                    loudnessEnhancer = it
                }
            }
            enhancer.setTargetGain((boostDb * MB_PER_DB).roundToInt().coerceIn(0, 610))
            enhancer.enabled = true
        }.onFailure {
            Log.w(TAG, "LoudnessEnhancer unavailable; volume boost was not applied", it)
        }
    }

    // ── Legacy Equalizer Fallback ──

    private fun ensureEqualizer(): Equalizer? {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return null
        return equalizer ?: runCatching { Equalizer(EFFECT_PRIORITY, sessionId) }
            .onSuccess { equalizer = it }
            .getOrNull()
    }

    private fun applyEqualizer(gainsDb: List<Float>) {
        val eq = ensureEqualizer() ?: return
        runCatching {
            val range = eq.bandLevelRange
            val minMb = range.first().toInt()
            val maxMb = range.last().toInt()

            // Use the same clean gain staging as the modern path. Older
            // Equalizer implementations do not provide a reliable limiter.
            val headroomOffsetDb = calculateHeadroomDb(gainsDb)

            for (band in 0 until eq.numberOfBands.toInt()) {
                val centerHz = eq.getCenterFreq(band.toShort()) / MILLIHERTZ_PER_HZ
                val rawGainDb = interpolateCurve(centerHz, gainsDb)
                val safeGainDb = rawGainDb + headroomOffsetDb
                val millibels = (safeGainDb * MB_PER_DB).toInt().coerceIn(minMb, maxMb)
                eq.setBandLevel(band.toShort(), millibels.toShort())
            }
            eq.enabled = true
        }.onFailure {
            Log.w(TAG, "Equalizer apply failed", it)
        }
    }

    private fun interpolateCurve(hz: Int, gains: List<Float>): Float {
        val freqs = EQ_BAND_FREQS_HZ
        if (gains.size != freqs.size) return 0f
        if (hz <= freqs.first()) return gains.first()
        if (hz >= freqs.last()) return gains.last()
        for (i in 0 until freqs.lastIndex) {
            val lo = freqs[i]
            val hi = freqs[i + 1]
            if (hz in lo..hi) {
                // EQ centers are logarithmically spaced; interpolating in log
                // frequency preserves the intended curve on 5/7/10-band OEMs.
                val t = (ln(hz.toFloat()) - ln(lo.toFloat())) /
                    (ln(hi.toFloat()) - ln(lo.toFloat()))
                return gains[i] + (gains[i + 1] - gains[i]) * t
            }
        }
        return 0f
    }

    private fun calculateHeadroomDb(gainsDb: List<Float>): Float {
        val maxBoostDb = (gainsDb.maxOrNull() ?: 0f).coerceAtLeast(0f)
        return -(maxBoostDb - MAX_PRE_LIMITER_BOOST_DB).coerceAtLeast(0f)
    }

    // ── Lifecycle Cleanup ──

    private fun releaseAllInternal() {
        releaseToneProcessingInternal()
        releaseLoudnessInternal()
    }

    private fun releaseToneProcessingInternal() {
        releaseEqualizerInternal()
        releaseDynamicsProcessorInternal()
    }

    private fun releaseEqualizerInternal() {
        runCatching {
            equalizer?.enabled = false
            equalizer?.release()
        }
        equalizer = null
    }

    private fun releaseDynamicsProcessorInternal() {
        runCatching {
            dynamicsProcessor?.enabled = false
            dynamicsProcessor?.release()
        }
        dynamicsProcessor = null
    }

    private fun releaseLoudnessInternal() {
        runCatching {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
        }
        loudnessEnhancer = null
    }

    private companion object {
        const val TAG = "AudiophileAudioFX"
        const val EFFECT_PRIORITY = 0
        const val MILLIHERTZ_PER_HZ = 1000
        const val MB_PER_DB = 100f
        const val MAX_PRE_LIMITER_BOOST_DB = 0.6f
        const val STUDIO_MBC_BAND_COUNT = 5

        val STUDIO_MBC_CUTOFF_HZ = floatArrayOf(120f, 500f, 2_200f, 6_500f, 20_000f)
        val STUDIO_MBC_ATTACK_MS = floatArrayOf(18f, 13f, 8f, 4f, 3f)
        val STUDIO_MBC_RELEASE_MS = floatArrayOf(170f, 140f, 115f, 100f, 90f)
        val STUDIO_MBC_RATIO = floatArrayOf(1.16f, 1.12f, 1.14f, 1.20f, 1.12f)
        val STUDIO_MBC_THRESHOLD_DB = floatArrayOf(-18f, -20f, -22f, -20f, -18f)
        val STUDIO_MBC_POST_GAIN_DB = floatArrayOf(0.30f, 0.05f, 0.25f, 0.05f, 0.15f)
    }
}
