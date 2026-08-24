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
 *  • **Zero Phase Distortion**: No fake 3D virtualizers, phase comb-filtering, or bloated bass boosters.
 *  • **Safe Audio Processing**: Dynamic pre-attenuation and brickwall peak limiting (Android 9+) to
 *    prevent inter-sample digital clipping even at maximum volume on 0 dBFS masters.
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

        // Prefer modern DynamicsProcessing with a brickwall limiter on Android 9+.
        // The gain is applied before the limiter, so 200% does not silently
        // become a clipped digital signal on supported devices.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            applyDynamicsProcessing(
                gainsDb = activeGains,
                requestedOutputGainDb = boostDb,
                toneProcessingActive = needsToneProcessing,
            )
        } else {
            if (needsToneProcessing) applyEqualizer(activeGains) else releaseToneProcessingInternal()
            if (volumeBoostActive) applyLegacyVolumeBoost(boostDb) else releaseLoudnessInternal()
        }
    }

    // ── DynamicsProcessing (API 28+ Studio Precision) ──

    private fun applyDynamicsProcessing(
        gainsDb: List<Float>,
        requestedOutputGainDb: Float,
        toneProcessingActive: Boolean,
    ) {
        runCatching {
            // Leave at most a small bounded boost before the safety limiter.
            // This retains perceived energy while avoiding constant gain
            // reduction on already-loud masters.
            val toneHeadroomDb = if (toneProcessingActive) {
                calculateHeadroomDb(gainsDb)
            } else {
                0f
            }
            val stagedInputGainDb = requestedOutputGainDb + toneHeadroomDb

            var dp = dynamicsProcessor
            if (dp == null) {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    /* channelCount = */ 2,
                    /* enablePreEq = */ true,
                    /* preEqBandCount = */ EQ_BAND_FREQS_HZ.size,
                    /* enableMbc = */ false,
                    /* mbcBandCount = */ 0,
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

            // Fast safety ceiling with recovery slow enough to avoid audible
            // pumping. It should stay idle during normal clarity playback and
            // engage mainly for hot masters or an explicit volume boost.
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
            // Never leave two independent tone/gain effects active together.
            releaseEqualizerInternal()
            releaseLoudnessInternal()
        }.onFailure { e ->
            Log.w(TAG, "DynamicsProcessing unavailable, falling back to legacy Equalizer: ${e.message}")
            releaseDynamicsProcessorInternal()
            applyEqualizer(gainsDb)
            if (requestedOutputGainDb > 0f) {
                applyLegacyVolumeBoost(requestedOutputGainDb)
            } else {
                releaseLoudnessInternal()
            }
        }
    }

    /** Pre-Android 9 fallback for the same bounded gain control. */
    private fun applyLegacyVolumeBoost(boostDb: Float) {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        runCatching {
            val enhancer = loudnessEnhancer ?: LoudnessEnhancer(EFFECT_PRIORITY, sessionId).also {
                loudnessEnhancer = it
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
    }
}
