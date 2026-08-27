package com.lastwave.app.playback

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import com.lastwave.app.data.local.EQ_BAND_FREQS_HZ
import com.lastwave.app.data.local.EQ_MAX_GAIN_DB
import com.lastwave.app.data.local.EqualizerPreferences
import com.lastwave.app.data.local.EqualizerPresets
import com.lastwave.app.data.local.EqualizerSettings
import com.lastwave.app.data.local.SettingsPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Last-resort Android AudioFX layer for devices where native decoded-PCM DSP
 * cannot run. It is mutually exclusive with [NativeProcessingAudioSink]: the
 * platform effects are released whenever native Float32 processing is active,
 * preventing doubled EQ, gain, or limiting.
 */
@Singleton
class AudioEffectsEngine @Inject constructor(
    equalizerPreferences: EqualizerPreferences,
    settingsPreferences: SettingsPreferences,
    applicationScope: CoroutineScope,
) {
    private val effectMutex = Mutex()
    private val applyRequests = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var requestedSessionId = C.AUDIO_SESSION_ID_UNSET
    @Volatile private var fallbackRequired = false
    @Volatile private var equalizerSettings = EqualizerSettings()
    @Volatile private var studioClarityEnabled = false
    @Volatile private var volumeBoostEnabled = false
    @Volatile private var volumeBoostPercent = 100

    private var attachedSessionId = C.AUDIO_SESSION_ID_UNSET
    private var toneEffect: ToneEffect? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    init {
        applicationScope.launch(Dispatchers.Default) {
            for (ignored in applyRequests) {
                effectMutex.withLock { applyInternal() }
            }
        }
        applicationScope.launch(Dispatchers.Default) {
            equalizerPreferences.settings.collect { settings ->
                equalizerSettings = settings
                requestApply()
            }
        }
        applicationScope.launch(Dispatchers.Default) {
            settingsPreferences.settings.collect { settings ->
                studioClarityEnabled = settings.isStudioMasterClarityEnabled
                volumeBoostEnabled = settings.volumeBoostEnabled
                volumeBoostPercent = settings.volumeBoostPercent.coerceIn(MIN_BOOST_PERCENT, MAX_BOOST_PERCENT)
                requestApply()
            }
        }
    }

    /** Follows ExoPlayer whenever Android creates or replaces its audio session. */
    fun attach(audioSessionId: Int) {
        requestedSessionId = audioSessionId.takeIf { it != C.AUDIO_SESSION_ID_UNSET }
            ?: C.AUDIO_SESSION_ID_UNSET
        requestApply()
    }

    /** Enables AudioFX only after both native output tiers have failed. */
    fun setFallbackRequired(required: Boolean) {
        if (fallbackRequired == required) return
        fallbackRequired = required
        requestApply()
    }

    fun detach() {
        requestedSessionId = C.AUDIO_SESSION_ID_UNSET
        fallbackRequired = false
        requestApply()
    }

    private fun requestApply() {
        applyRequests.trySend(Unit)
    }

    private fun applyInternal() {
        val targetSessionId = requestedSessionId
        if (targetSessionId != attachedSessionId) {
            releaseAllInternal()
            attachedSessionId = targetSessionId
        }
        if (!fallbackRequired || attachedSessionId == C.AUDIO_SESSION_ID_UNSET) {
            releaseAllInternal()
            return
        }

        val userEqEnabled = equalizerSettings.enabled
        val needsTone = userEqEnabled || studioClarityEnabled
        if (needsTone) {
            applyToneEffect(buildCombinedCurve(userEqEnabled))
        } else {
            releaseToneInternal()
        }

        if (volumeBoostEnabled && volumeBoostPercent > MIN_BOOST_PERCENT) {
            applyVolumeBoost(volumeBoostPercent)
        } else {
            releaseLoudnessInternal()
        }
    }

    private fun buildCombinedCurve(userEqEnabled: Boolean): FloatArray =
        FloatArray(EQ_BAND_FREQS_HZ.size) { index ->
            val studioGain = if (studioClarityEnabled) {
                EqualizerPresets.STUDIO_MASTER.gainsDb.getOrElse(index) { 0f }
            } else 0f
            val userGain = if (userEqEnabled) {
                equalizerSettings.gainsDb.getOrElse(index) { 0f }
            } else 0f
            val combined = studioGain + userGain
            if (combined.isFinite()) combined.coerceIn(-EQ_MAX_GAIN_DB, EQ_MAX_GAIN_DB) else 0f
        }

    private fun applyToneEffect(gainsDb: FloatArray) {
        var effect = toneEffect
        if (effect == null || !effect.hasControl()) {
            releaseToneInternal()
            effect = createToneEffect(attachedSessionId)
            toneEffect = effect
        }
        if (effect?.apply(gainsDb) == true) return

        if (effect is ModernDynamicsEffect) {
            Log.w(TAG, "DynamicsProcessing rejected this device; using legacy Equalizer")
            releaseToneInternal()
            val legacy = LegacyEqualizerEffect.create(attachedSessionId)
            toneEffect = legacy
            if (legacy?.apply(gainsDb) != true) {
                Log.w(TAG, "Platform Equalizer is unavailable on this audio route")
                releaseToneInternal()
            }
        } else {
            Log.w(TAG, "Platform Equalizer failed on this audio route")
            releaseToneInternal()
        }
    }

    private fun createToneEffect(audioSessionId: Int): ToneEffect? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ModernDynamicsEffect.create(audioSessionId)?.let { return it }
        }
        return LegacyEqualizerEffect.create(audioSessionId)
    }

    private fun applyVolumeBoost(percent: Int) {
        val targetGainMb = (20.0 * log10(percent.coerceIn(
            MIN_BOOST_PERCENT,
            MAX_BOOST_PERCENT,
        ) / 100.0) * MILLIBELS_PER_DB.toDouble()).roundToInt().coerceIn(0, MAX_BOOST_MILLIBELS)

        val current = loudnessEnhancer
        val enhancer = if (current != null && runCatching { current.hasControl() }.getOrDefault(false)) {
            current
        } else {
            releaseLoudnessInternal()
            runCatching { LoudnessEnhancer(attachedSessionId) }
                .onFailure { Log.w(TAG, "LoudnessEnhancer unavailable", it) }
                .getOrNull()
                ?.also { loudnessEnhancer = it }
        } ?: return

        runCatching {
            enhancer.setTargetGain(targetGainMb)
            enhancer.enabled = true
        }.onFailure {
            Log.w(TAG, "Volume boost apply failed", it)
            releaseLoudnessInternal()
        }
    }

    private fun releaseAllInternal() {
        releaseToneInternal()
        releaseLoudnessInternal()
    }

    private fun releaseToneInternal() {
        toneEffect?.release()
        toneEffect = null
    }

    private fun releaseLoudnessInternal() {
        runCatching {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
        }
        loudnessEnhancer = null
    }

    private interface ToneEffect {
        fun hasControl(): Boolean
        fun apply(gainsDb: FloatArray): Boolean
        fun release()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private class ModernDynamicsEffect private constructor(
        private val processor: DynamicsProcessing,
    ) : ToneEffect {
        override fun hasControl(): Boolean = runCatching { processor.hasControl() }.getOrDefault(false)

        override fun apply(gainsDb: FloatArray): Boolean = runCatching {
            val headroomDb = calculateHeadroomDb(gainsDb)
            processor.setInputGainAllChannelsTo(headroomDb)
            val preEq = DynamicsProcessing.Eq(true, true, EQ_BAND_FREQS_HZ.size)
            EQ_BAND_FREQS_HZ.forEachIndexed { index, frequency ->
                preEq.setBand(
                    index,
                    DynamicsProcessing.EqBand(true, frequency.toFloat(), gainsDb.getOrElse(index) { 0f }),
                )
            }
            processor.setPreEqAllChannelsTo(preEq)
            processor.setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(
                    true,
                    true,
                    0,
                    1.0f,
                    120.0f,
                    12.0f,
                    -1.0f,
                    0.0f,
                ),
            )
            processor.enabled = true
        }.onFailure { Log.w(TAG, "DynamicsProcessing apply failed", it) }.isSuccess

        override fun release() {
            runCatching {
                processor.enabled = false
                processor.release()
            }
        }

        companion object {
            fun create(audioSessionId: Int): ModernDynamicsEffect? = runCatching {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    true,
                    EQ_BAND_FREQS_HZ.size,
                    false,
                    0,
                    false,
                    0,
                    true,
                ).build()
                ModernDynamicsEffect(DynamicsProcessing(EFFECT_PRIORITY, audioSessionId, config))
            }.onFailure { Log.w(TAG, "DynamicsProcessing creation failed", it) }.getOrNull()
        }
    }

    private class LegacyEqualizerEffect private constructor(
        private val equalizer: Equalizer,
    ) : ToneEffect {
        override fun hasControl(): Boolean = runCatching { equalizer.hasControl() }.getOrDefault(false)

        override fun apply(gainsDb: FloatArray): Boolean = runCatching {
            val range = equalizer.bandLevelRange
            val minimumMb = range.first().toInt()
            val maximumMb = range.last().toInt()
            val headroomDb = calculateHeadroomDb(gainsDb)
            for (band in 0 until equalizer.numberOfBands.toInt()) {
                val centerHz = equalizer.getCenterFreq(band.toShort()) / MILLIHERTZ_PER_HZ
                val gainDb = interpolateCurve(centerHz, gainsDb) + headroomDb
                val levelMb = (gainDb * MILLIBELS_PER_DB).roundToInt().coerceIn(minimumMb, maximumMb)
                equalizer.setBandLevel(band.toShort(), levelMb.toShort())
            }
            equalizer.enabled = true
        }.onFailure { Log.w(TAG, "Legacy Equalizer apply failed", it) }.isSuccess

        override fun release() {
            runCatching {
                equalizer.enabled = false
                equalizer.release()
            }
        }

        companion object {
            fun create(audioSessionId: Int): LegacyEqualizerEffect? = runCatching {
                LegacyEqualizerEffect(Equalizer(EFFECT_PRIORITY, audioSessionId))
            }.onFailure { Log.w(TAG, "Legacy Equalizer creation failed", it) }.getOrNull()
        }
    }

    private companion object {
        const val TAG = "FallbackAudioFX"
        const val EFFECT_PRIORITY = 0
        const val MILLIHERTZ_PER_HZ = 1_000
        const val MILLIBELS_PER_DB = 100f
        const val MIN_BOOST_PERCENT = 100
        const val MAX_BOOST_PERCENT = 200
        const val MAX_BOOST_MILLIBELS = 602
        const val MAX_PRE_LIMITER_BOOST_DB = 1.0f

        fun calculateHeadroomDb(gainsDb: FloatArray): Float {
            val maximumBoost = (gainsDb.maxOrNull() ?: 0f).coerceAtLeast(0f)
            return -(maximumBoost - MAX_PRE_LIMITER_BOOST_DB).coerceAtLeast(0f)
        }

        fun interpolateCurve(hz: Int, gainsDb: FloatArray): Float {
            if (gainsDb.size != EQ_BAND_FREQS_HZ.size) return 0f
            if (hz <= EQ_BAND_FREQS_HZ.first()) return gainsDb.first()
            if (hz >= EQ_BAND_FREQS_HZ.last()) return gainsDb.last()
            for (index in 0 until EQ_BAND_FREQS_HZ.lastIndex) {
                val low = EQ_BAND_FREQS_HZ[index]
                val high = EQ_BAND_FREQS_HZ[index + 1]
                if (hz in low..high) {
                    val position = (ln(hz.toFloat()) - ln(low.toFloat())) /
                        (ln(high.toFloat()) - ln(low.toFloat()))
                    return gainsDb[index] + (gainsDb[index + 1] - gainsDb[index]) * position
                }
            }
            return 0f
        }
    }
}
