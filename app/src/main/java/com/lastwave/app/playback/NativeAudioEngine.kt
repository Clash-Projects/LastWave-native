package com.lastwave.app.playback

import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.local.EqualizerPreferences
import java.io.Closeable
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

enum class NativePcmEncoding(internal val nativeValue: Int, internal val bytesPerSample: Int) {
    PCM_I16(0, 2),
    PCM_I24_PACKED(1, 3),
    PCM_I32(2, 4),
    PCM_FLOAT(3, 4),
}

/**
 * Process-wide owner of the C++17 DSP/Oboe engine. Normal app playback enters
 * through [NativeProcessingAudioSink], preserving Media3's one AudioTrack
 * clock; [start]/[writePcm] remain available for a direct Oboe producer.
 */
@Singleton
class NativeAudioEngine @Inject constructor(
    settingsPreferences: SettingsPreferences,
    equalizerPreferences: EqualizerPreferences,
    applicationScope: CoroutineScope,
) : Closeable {
    private val handleLock = Any()
    private var nativeHandle = nativeCreate()

    init {
        applicationScope.launch(Dispatchers.Default) {
            settingsPreferences.settings
                .map { it.isStudioMasterClarityEnabled }
                .distinctUntilChanged()
                .collect(::setStudioMasterClarity)
        }
        applicationScope.launch(Dispatchers.Default) {
            settingsPreferences.settings
                .map { it.volumeBoostEnabled to it.volumeBoostPercent.coerceIn(100, 200) }
                .distinctUntilChanged()
                .collect { (enabled, percent) -> setVolumeBoost(enabled, percent) }
        }
        applicationScope.launch(Dispatchers.Default) {
            equalizerPreferences.settings.collect { settings ->
                setEqualizer(settings.enabled, settings.gainsDb.toFloatArray())
            }
        }
    }

    /** Opens a stereo Float32 Oboe output stream. Zero asks Android for its native rate. */
    fun start(preferredOutputSampleRate: Int = 0): Boolean =
        withHandle(false) { nativeStart(it, preferredOutputSampleRate.coerceAtLeast(0)) }

    fun stop() {
        withHandle(Unit) { nativeStop(it) }
    }

    /** Thread-safe; native DSP crossfades wet/dry over exactly 50 ms. */
    fun setStudioMasterClarity(enabled: Boolean) {
        withHandle(Unit) { nativeSetStudioMasterClarity(it, enabled) }
    }

    /** Native adaptive gain; ramps smoothly and never exceeds the -1 dBFS ceiling. */
    fun setVolumeBoost(enabled: Boolean, percent: Int) {
        withHandle(Unit) { nativeSetVolumeBoost(it, enabled, percent.coerceIn(100, 200)) }
    }

    /** Updates the native 15-band EQ; its gains are smoothed in C++. */
    fun setEqualizer(enabled: Boolean, gainsDb: FloatArray) {
        require(gainsDb.size == EQUALIZER_BAND_COUNT) { "Expected 15 equalizer bands" }
        withHandle(Unit) { nativeSetEqualizer(it, enabled, gainsDb) }
    }

    internal fun configureMediaProcessor(
        inputSampleRate: Int,
        outputSampleRate: Int,
        channelCount: Int,
    ): Boolean {
        require(inputSampleRate > 0 && outputSampleRate > 0)
        require(channelCount in 1..2)
        return withHandle(false) {
            nativeConfigureMediaProcessor(it, inputSampleRate, outputSampleRate, channelCount)
        }
    }

    /** Processes decoded Media3 PCM into an interleaved direct Float32 buffer. */
    internal fun processMediaPcm(
        input: ByteBuffer,
        inputByteOffset: Int,
        output: ByteBuffer,
        outputByteOffset: Int,
        frameCount: Int,
        encoding: NativePcmEncoding,
        channelCount: Int,
    ): Int {
        require(input.isDirect && output.isDirect) { "Native PCM buffers must be direct" }
        return withHandle(0) { handle ->
            nativeProcessMediaPcm(
                handle,
                input,
                inputByteOffset,
                output,
                outputByteOffset,
                frameCount,
                encoding.nativeValue,
                channelCount,
            )
        }
    }

    internal fun flushMediaProcessor(output: ByteBuffer, channelCount: Int): Int {
        require(output.isDirect)
        return withHandle(-1) {
            nativeFlushMediaProcessor(it, output, output.position(), channelCount)
        }
    }

    internal fun resetMediaProcessor() {
        withHandle(Unit) { nativeResetMediaProcessor(it) }
    }

    /**
     * Enqueues little-endian mono/stereo PCM from a direct [ByteBuffer]. Returns
     * input frames accepted. A short return means the producer must retry the
     * unconsumed frames after Oboe frees ring-buffer space.
     */
    fun writePcm(
        buffer: ByteBuffer,
        frameCount: Int,
        encoding: NativePcmEncoding,
        inputSampleRate: Int,
        inputChannelCount: Int,
    ): Int {
        require(buffer.isDirect) { "NativeAudioEngine requires a direct ByteBuffer" }
        require(inputChannelCount == 1 || inputChannelCount == 2) {
            "Only mono or stereo PCM is supported"
        }
        require(inputSampleRate > 0) { "inputSampleRate must be positive" }
        require(frameCount >= 0) { "frameCount must not be negative" }
        val requiredBytes = frameCount.toLong() * inputChannelCount * encoding.bytesPerSample
        require(requiredBytes <= buffer.remaining().toLong()) { "PCM buffer is too small" }
        if (frameCount == 0) return 0

        val accepted = withHandle(0) { handle ->
            nativeWritePcm(
                handle,
                buffer,
                buffer.position(),
                frameCount,
                encoding.nativeValue,
                inputSampleRate,
                inputChannelCount,
            )
        }
        if (accepted > 0) {
            buffer.position(buffer.position() + accepted * inputChannelCount * encoding.bytesPerSample)
        }
        return accepted
    }

    /** Drains libsoxr's delayed sinc tail at end-of-stream. */
    fun flushResampler() {
        withHandle(Unit) { nativeFlushResampler(it) }
    }

    val outputSampleRate: Int
        get() = withHandle(0, ::nativeOutputSampleRate)

    val bufferedFrames: Long
        get() = withHandle(0L, ::nativeBufferedFrames)

    override fun close() {
        synchronized(handleLock) {
            val handle = nativeHandle
            nativeHandle = 0L
            if (handle != 0L) nativeDestroy(handle)
        }
    }

    private inline fun <T> withHandle(fallback: T, block: (Long) -> T): T {
        return synchronized(handleLock) {
            val handle = nativeHandle
            if (handle == 0L) fallback else block(handle)
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeStart(handle: Long, preferredOutputSampleRate: Int): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeSetStudioMasterClarity(handle: Long, enabled: Boolean)
    private external fun nativeSetVolumeBoost(handle: Long, enabled: Boolean, percent: Int)
    private external fun nativeSetEqualizer(handle: Long, enabled: Boolean, gainsDb: FloatArray)
    private external fun nativeConfigureMediaProcessor(
        handle: Long,
        inputSampleRate: Int,
        outputSampleRate: Int,
        channelCount: Int,
    ): Boolean
    private external fun nativeProcessMediaPcm(
        handle: Long,
        input: ByteBuffer,
        inputByteOffset: Int,
        output: ByteBuffer,
        outputByteOffset: Int,
        frameCount: Int,
        encoding: Int,
        channelCount: Int,
    ): Int
    private external fun nativeFlushMediaProcessor(
        handle: Long,
        output: ByteBuffer,
        outputByteOffset: Int,
        channelCount: Int,
    ): Int
    private external fun nativeResetMediaProcessor(handle: Long)
    private external fun nativeWritePcm(
        handle: Long,
        buffer: ByteBuffer,
        byteOffset: Int,
        frameCount: Int,
        encoding: Int,
        inputSampleRate: Int,
        inputChannelCount: Int,
    ): Int
    private external fun nativeFlushResampler(handle: Long)
    private external fun nativeOutputSampleRate(handle: Long): Int
    private external fun nativeBufferedFrames(handle: Long): Long

    private companion object {
        const val EQUALIZER_BAND_COUNT = 15

        init {
            System.loadLibrary("lastwave_audio")
        }
    }
}
