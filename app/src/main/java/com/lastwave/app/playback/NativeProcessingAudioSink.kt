@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lastwave.app.playback

import android.media.AudioDeviceInfo
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer

/**
 * Runs the optional native Float32 DSP in front of Media3 while keeping an
 * independent, conservative [DefaultAudioSink] ready as the fail-open path.
 * Devices that reject Float32 AudioTrack still receive the native DSP result;
 * Media3 performs only the final Float32-to-PCM16 conversion. If native/JNI
 * processing itself fails, the untouched source buffer is retried through the
 * plain platform PCM path.
 */
class NativeProcessingAudioSink(
    private val enhancedDelegate: DefaultAudioSink,
    private val fallbackDelegate: DefaultAudioSink,
    private val processor: NativePcmAudioProcessor,
    private val onPlatformEffectsRequired: (Boolean) -> Unit = {},
) : AudioSink {
    private var activeDelegate: DefaultAudioSink = fallbackDelegate
    private var processingActive = false
    private var floatOutputDisabled = false
    private var nativePathDisabled = false
    private var playing = false

    private var configuredFormat: Format? = null
    private var configuredBufferSize = 0
    private var configuredOutputChannels: IntArray? = null
    private var processedFormat: Format? = null
    private var outputChannelCount = 2

    private var pendingInputLimit = 0
    private var pendingOutput: ByteBuffer? = null
    private var pendingPresentationTimeUs = 0L
    private var pendingAccessUnitCount = 0
    private var pendingOutputFrameCount = 0
    private var nextOutputPresentationTimeUs = 0L

    private var endOfStreamQueued = false
    private var endOfStreamOutput: ByteBuffer? = null

    override fun setListener(listener: AudioSink.Listener) {
        enhancedDelegate.setListener(listener)
        fallbackDelegate.setListener(listener)
    }

    override fun setClock(clock: Clock) {
        enhancedDelegate.setClock(clock)
        fallbackDelegate.setClock(clock)
    }

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int {
        val fallbackSupport = fallbackDelegate.getFormatSupport(format)
        if (nativePathDisabled || !processor.isAvailable || !canProcess(format)) {
            return fallbackSupport
        }
        val floatSupport = enhancedDelegate.getFormatSupport(asFloatProbeFormat(format))
        if (!floatOutputDisabled && floatSupport != AudioSink.SINK_FORMAT_UNSUPPORTED) {
            return floatSupport
        }
        val processedPcm16Support = fallbackDelegate.getFormatSupport(asFloatProbeFormat(format))
        return if (processedPcm16Support != AudioSink.SINK_FORMAT_UNSUPPORTED) {
            processedPcm16Support
        } else fallbackSupport
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        AudioOffloadSupport.DEFAULT_UNSUPPORTED

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        activeDelegate.getCurrentPositionUs(sourceEnded)

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        configuredFormat = format
        configuredBufferSize = specifiedBufferSize
        configuredOutputChannels = outputChannels?.clone()
        clearPending()
        clearEndOfStream()
        processingActive = false
        processedFormat = null

        if (!nativePathDisabled && processor.isAvailable && canProcess(format) &&
            tryConfigureNativePath(format, outputChannels)
        ) {
            return
        }
        configureFallback(format, specifiedBufferSize, outputChannels)
    }

    private fun tryConfigureNativePath(format: Format, outputChannels: IntArray?): Boolean {
        val outputFormat = try {
            processor.reset()
            processor.setTrimFrameCount(format.encoderDelay, format.encoderPadding)
            processor.configure(AudioProcessor.AudioFormat(format))
        } catch (error: Exception) {
            disableNativePath("Native DSP configuration failed", error)
            return false
        } catch (error: LinkageError) {
            disableNativePath("Native DSP configuration linkage failed", error)
            return false
        }

        if (outputFormat == AudioProcessor.AudioFormat.NOT_SET ||
            outputFormat.sampleRate <= 0 ||
            outputFormat.channelCount !in 1..2 ||
            outputFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            disableNativePath("Native DSP rejected decoded PCM", null)
            return false
        }

        val floatFormat = asFloatFormat(format, outputFormat)
        processedFormat = floatFormat
        outputChannelCount = outputFormat.channelCount
        try {
            processor.flush()
        } catch (error: Exception) {
            disableNativePath("Native DSP activation failed", error)
            return false
        } catch (error: LinkageError) {
            disableNativePath("Native DSP activation linkage failed", error)
            return false
        }

        if (!floatOutputDisabled &&
            enhancedDelegate.getFormatSupport(floatFormat) != AudioSink.SINK_FORMAT_UNSUPPORTED
        ) {
            try {
                enhancedDelegate.configure(floatFormat, 0, outputChannels)
                if (activeDelegate !== enhancedDelegate) safeFlush(fallbackDelegate)
                activeDelegate = enhancedDelegate
                processingActive = true
                notifyPlatformEffectsRequired(false)
                if (playing) enhancedDelegate.play()
                return true
            } catch (error: Exception) {
                disableFloatOutput("Float32 AudioTrack configuration failed", error)
            } catch (error: LinkageError) {
                disableFloatOutput("Float32 AudioTrack configuration linkage failed", error)
            }
        }

        return try {
            // Float output is deliberately fed into a float-disabled sink.
            // Media3's ToInt16PcmAudioProcessor performs the final conversion,
            // while all native DSP remains 32-bit floating point.
            fallbackDelegate.configure(floatFormat, 0, outputChannels)
            if (activeDelegate !== fallbackDelegate) safeFlush(enhancedDelegate)
            activeDelegate = fallbackDelegate
            processingActive = true
            notifyPlatformEffectsRequired(false)
            if (playing) fallbackDelegate.play()
            Log.i(TAG, "Using native Float32 DSP with PCM16 AudioTrack compatibility output")
            true
        } catch (error: Exception) {
            disableNativePath("Processed PCM16 compatibility configuration failed", error)
            false
        } catch (error: LinkageError) {
            disableNativePath("Processed PCM16 compatibility linkage failed", error)
            false
        }
    }

    private fun configureFallback(
        format: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        processingActive = false
        clearPending()
        clearEndOfStream()
        processedFormat = null
        safeResetProcessor()
        fallbackDelegate.configure(format, specifiedBufferSize, outputChannels)
        if (activeDelegate !== fallbackDelegate) safeFlush(enhancedDelegate)
        activeDelegate = fallbackDelegate
        notifyPlatformEffectsRequired(true)
        if (playing) fallbackDelegate.play()
    }

    override fun play() {
        playing = true
        if (!processingActive) {
            activeDelegate.play()
            return
        }
        try {
            activeDelegate.play()
        } catch (error: Exception) {
            if (!recoverProcessingPath("Processed output play failed", error)) throw error
            activeDelegate.play()
        } catch (error: LinkageError) {
            if (!recoverProcessingPath("Processed output play linkage failed", error)) throw error
            activeDelegate.play()
        }
    }

    override fun handleDiscontinuity() {
        clearPending()
        clearEndOfStream()
        if (!processingActive) {
            activeDelegate.handleDiscontinuity()
            return
        }
        try {
            processor.flush()
            activeDelegate.handleDiscontinuity()
        } catch (error: Exception) {
            if (!recoverProcessingPath("Processed discontinuity handling failed", error)) throw error
            activeDelegate.handleDiscontinuity()
        } catch (error: LinkageError) {
            if (!recoverProcessingPath("Processed discontinuity linkage failed", error)) throw error
            activeDelegate.handleDiscontinuity()
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (!processingActive) {
            return activeDelegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        try {
            return handleProcessedBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        } catch (error: Exception) {
            if (!recoverProcessingPath("Native/processed buffer failed", error)) throw error
        } catch (error: LinkageError) {
            if (!recoverProcessingPath("Native/processed buffer linkage failed", error)) throw error
        }
        if (!processingActive) {
            return fallbackDelegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        return try {
            handleProcessedBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        } catch (retryError: Exception) {
            if (!switchToPlatformFallback("Processed PCM16 retry failed", retryError)) throw retryError
            fallbackDelegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        } catch (retryError: LinkageError) {
            if (!switchToPlatformFallback("Processed PCM16 retry linkage failed", retryError)) throw retryError
            fallbackDelegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
    }

    private fun handleProcessedBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (pendingOutput == null) {
            if (!buffer.hasRemaining()) return true
            processor.queueInput(buffer.duplicate())
            pendingInputLimit = buffer.limit()
            val output = processor.getOutput()
            pendingOutput = output
            pendingPresentationTimeUs = presentationTimeUs
            pendingAccessUnitCount = encodedAccessUnitCount
            pendingOutputFrameCount = output.remaining() /
                (Float.SIZE_BYTES * outputChannelCount)
        }

        val output = pendingOutput ?: return true
        if (output.hasRemaining()) {
            activeDelegate.handleBuffer(output, pendingPresentationTimeUs, pendingAccessUnitCount)
        }
        if (output.hasRemaining()) return false

        nextOutputPresentationTimeUs = pendingPresentationTimeUs +
            pendingOutputFrameCount * MICROS_PER_SECOND / processor.nativeOutputSampleRate
        if (buffer.position() < pendingInputLimit) {
            buffer.position(pendingInputLimit)
        }
        clearPending()
        return true
    }

    override fun playToEndOfStream() {
        if (!processingActive) {
            activeDelegate.playToEndOfStream()
            return
        }
        try {
            if (!endOfStreamQueued) {
                processor.queueEndOfStream()
                endOfStreamOutput = processor.getOutput()
                endOfStreamQueued = true
            }
            val tail = endOfStreamOutput
            if (tail?.hasRemaining() == true) {
                activeDelegate.handleBuffer(tail, nextOutputPresentationTimeUs, 1)
                if (tail.hasRemaining()) return
            }
            activeDelegate.playToEndOfStream()
        } catch (error: Exception) {
            if (!recoverProcessingPath("Processed end-of-stream failed", error)) throw error
            activeDelegate.playToEndOfStream()
        } catch (error: LinkageError) {
            if (!recoverProcessingPath("Processed end-of-stream linkage failed", error)) throw error
            activeDelegate.playToEndOfStream()
        }
    }

    override fun isEnded(): Boolean =
        activeDelegate.isEnded() &&
            pendingOutput?.hasRemaining() != true &&
            endOfStreamOutput?.hasRemaining() != true

    override fun hasPendingData(): Boolean =
        pendingOutput?.hasRemaining() == true ||
            endOfStreamOutput?.hasRemaining() == true ||
            activeDelegate.hasPendingData()

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        enhancedDelegate.setPlaybackParameters(playbackParameters)
        fallbackDelegate.setPlaybackParameters(playbackParameters)
    }

    override fun getPlaybackParameters(): PlaybackParameters =
        activeDelegate.getPlaybackParameters()

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        enhancedDelegate.setSkipSilenceEnabled(skipSilenceEnabled)
        fallbackDelegate.setSkipSilenceEnabled(skipSilenceEnabled)
    }

    override fun getSkipSilenceEnabled(): Boolean =
        activeDelegate.getSkipSilenceEnabled()

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        enhancedDelegate.setAudioAttributes(audioAttributes)
        fallbackDelegate.setAudioAttributes(audioAttributes)
    }

    override fun getAudioAttributes(): AudioAttributes? =
        activeDelegate.getAudioAttributes()

    override fun setAudioSessionId(audioSessionId: Int) {
        enhancedDelegate.setAudioSessionId(audioSessionId)
        fallbackDelegate.setAudioSessionId(audioSessionId)
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        enhancedDelegate.setAuxEffectInfo(auxEffectInfo)
        fallbackDelegate.setAuxEffectInfo(auxEffectInfo)
    }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        enhancedDelegate.setPreferredDevice(audioDeviceInfo)
        fallbackDelegate.setPreferredDevice(audioDeviceInfo)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        enhancedDelegate.setOutputStreamOffsetUs(outputStreamOffsetUs)
        fallbackDelegate.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun enableTunnelingV21() {
        enhancedDelegate.enableTunnelingV21()
        fallbackDelegate.enableTunnelingV21()
    }

    override fun disableTunneling() {
        enhancedDelegate.disableTunneling()
        fallbackDelegate.disableTunneling()
    }

    override fun setOffloadMode(offloadMode: Int) {
        enhancedDelegate.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
        fallbackDelegate.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {
        enhancedDelegate.setOffloadDelayPadding(delayInFrames, paddingInFrames)
        fallbackDelegate.setOffloadDelayPadding(delayInFrames, paddingInFrames)
    }

    override fun setVolume(volume: Float) {
        enhancedDelegate.setVolume(volume)
        fallbackDelegate.setVolume(volume)
    }

    override fun pause() {
        playing = false
        activeDelegate.pause()
    }

    override fun flush() {
        clearPending()
        clearEndOfStream()
        if (processingActive) {
            try {
                processor.flush()
                activeDelegate.flush()
                return
            } catch (error: Exception) {
                if (!recoverProcessingPath("Processed flush failed", error)) throw error
            } catch (error: LinkageError) {
                if (!recoverProcessingPath("Processed flush linkage failed", error)) throw error
            }
        }
        activeDelegate.flush()
    }

    override fun reset() {
        clearPending()
        clearEndOfStream()
        configuredFormat = null
        configuredBufferSize = 0
        configuredOutputChannels = null
        processedFormat = null
        processingActive = false
        playing = false
        notifyPlatformEffectsRequired(false)
        safeResetProcessor()
        safeReset(enhancedDelegate)
        fallbackDelegate.reset()
        activeDelegate = fallbackDelegate
    }

    override fun release() {
        clearPending()
        clearEndOfStream()
        processedFormat = null
        processingActive = false
        playing = false
        notifyPlatformEffectsRequired(false)
        safeResetProcessor()
        try {
            enhancedDelegate.release()
        } catch (error: Exception) {
            Log.w(TAG, "Enhanced sink release failed", error)
        } catch (error: LinkageError) {
            Log.w(TAG, "Enhanced sink release linkage failed", error)
        }
        fallbackDelegate.release()
    }

    private fun recoverProcessingPath(reason: String, error: Throwable): Boolean =
        if (activeDelegate === enhancedDelegate && switchToProcessedPcm16(reason, error)) {
            true
        } else {
            switchToPlatformFallback(reason, error)
        }

    private fun switchToProcessedPcm16(reason: String, error: Throwable): Boolean {
        val format = configuredFormat ?: return false
        val floatFormat = processedFormat ?: return false
        disableFloatOutput(reason, error)
        clearPending()
        clearEndOfStream()
        safeFlush(enhancedDelegate)
        return try {
            processor.flush()
            // A runtime output recovery retries the current untouched source
            // buffer, so encoder delay must not be removed a second time.
            processor.beginStream(0, format.encoderPadding)
            fallbackDelegate.flush()
            fallbackDelegate.configure(floatFormat, 0, configuredOutputChannels)
            activeDelegate = fallbackDelegate
            processingActive = true
            notifyPlatformEffectsRequired(false)
            if (playing) fallbackDelegate.play()
            Log.w(TAG, "Recovered with native Float32 DSP and PCM16 AudioTrack output")
            true
        } catch (fallbackError: Exception) {
            Log.w(TAG, "Processed PCM16 recovery failed", fallbackError)
            false
        } catch (fallbackError: LinkageError) {
            Log.w(TAG, "Processed PCM16 recovery linkage failed", fallbackError)
            false
        }
    }

    private fun switchToPlatformFallback(reason: String, error: Throwable): Boolean {
        val format = configuredFormat ?: return false
        disableNativePath(reason, error)
        processingActive = false
        clearPending()
        clearEndOfStream()
        processedFormat = null
        safeResetProcessor()
        safeFlush(enhancedDelegate)
        return try {
            fallbackDelegate.flush()
            fallbackDelegate.configure(
                format,
                configuredBufferSize,
                configuredOutputChannels,
            )
            activeDelegate = fallbackDelegate
            notifyPlatformEffectsRequired(true)
            if (playing) fallbackDelegate.play()
            true
        } catch (fallbackError: Exception) {
            Log.e(TAG, "PCM16 fallback configuration failed", fallbackError)
            false
        } catch (fallbackError: LinkageError) {
            Log.e(TAG, "PCM16 fallback linkage failed", fallbackError)
            false
        }
    }

    private fun disableFloatOutput(reason: String, error: Throwable) {
        floatOutputDisabled = true
        Log.w(TAG, "$reason; keeping native DSP through PCM16 compatibility output", error)
        PlaybackDiagnostics.counter(PlaybackDiagnostics.nativeFallbacks, TAG, reason)
    }

    private fun disableNativePath(reason: String, error: Throwable?) {
        nativePathDisabled = true
        if (error != null) {
            Log.w(TAG, "$reason; locking this player to conservative platform PCM", error)
        } else {
            Log.w(TAG, "$reason; locking this player to conservative platform PCM")
        }
        PlaybackDiagnostics.counter(PlaybackDiagnostics.nativeFallbacks, TAG, reason)
        safeResetProcessor()
    }

    private fun notifyPlatformEffectsRequired(required: Boolean) {
        try {
            onPlatformEffectsRequired(required)
        } catch (error: Exception) {
            Log.w(TAG, "Platform effect routing notification failed", error)
        } catch (error: LinkageError) {
            Log.w(TAG, "Platform effect routing linkage failed", error)
        }
    }

    private fun safeResetProcessor() {
        try {
            processor.reset()
        } catch (error: Exception) {
            Log.w(TAG, "Native processor reset failed", error)
        } catch (error: LinkageError) {
            Log.w(TAG, "Native processor reset linkage failed", error)
        }
    }

    private fun safeFlush(delegate: DefaultAudioSink) {
        try {
            delegate.flush()
        } catch (error: Exception) {
            Log.w(TAG, "Inactive audio sink flush failed", error)
        } catch (error: LinkageError) {
            Log.w(TAG, "Inactive audio sink flush linkage failed", error)
        }
    }

    private fun safeReset(delegate: DefaultAudioSink) {
        try {
            delegate.reset()
        } catch (error: Exception) {
            Log.w(TAG, "Enhanced audio sink reset failed", error)
        } catch (error: LinkageError) {
            Log.w(TAG, "Enhanced audio sink reset linkage failed", error)
        }
    }

    private fun canProcess(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.AUDIO_RAW &&
            format.sampleRate > 0 &&
            format.channelCount in 1..2 &&
            format.pcmEncoding in SUPPORTED_ENCODINGS

    private fun asFloatProbeFormat(format: Format): Format =
        format.buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setSampleRate(processor.outputSampleRateFor(format.sampleRate))
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setEncoderDelay(0)
            .setEncoderPadding(0)
            .build()

    private fun asFloatFormat(
        format: Format,
        outputFormat: AudioProcessor.AudioFormat,
    ): Format = format.buildUpon()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setSampleRate(outputFormat.sampleRate)
        .setChannelCount(outputFormat.channelCount)
        .setPcmEncoding(outputFormat.encoding)
        .setEncoderDelay(0)
        .setEncoderPadding(0)
        .build()

    private fun clearPending() {
        pendingInputLimit = 0
        pendingOutput = null
        pendingPresentationTimeUs = 0L
        pendingAccessUnitCount = 0
        pendingOutputFrameCount = 0
    }

    private fun clearEndOfStream() {
        endOfStreamQueued = false
        endOfStreamOutput = null
        nextOutputPresentationTimeUs = 0L
    }

    private companion object {
        const val TAG = "NativeAudioSink"
        const val MICROS_PER_SECOND = 1_000_000L
        val SUPPORTED_ENCODINGS = setOf(
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT,
        )
    }
}
