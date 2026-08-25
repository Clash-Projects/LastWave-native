@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lastwave.app.playback

import android.media.AudioDeviceInfo
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
import java.nio.ByteBuffer

/**
 * Keeps ExoPlayer's AudioTrack clock/timestamps while inserting native DSP
 * immediately after decoding. Encoded passthrough/offload is deliberately
 * disabled so FLAC, Opus and AAC all reach the same Float32 signal path.
 */
class NativeProcessingAudioSink(
    private val delegate: AudioSink,
    private val processor: NativePcmAudioProcessor,
) : AudioSink {
    private var processingActive = false
    private var outputChannelCount = 2
    private var pendingInput: ByteBuffer? = null
    private var pendingInputLimit = 0
    private var pendingOutput: ByteBuffer? = null
    private var pendingPresentationTimeUs = 0L
    private var pendingAccessUnitCount = 0
    private var pendingOutputFrameCount = 0
    private var endOfStreamQueued = false
    private var endOfStreamOutput: ByteBuffer? = null
    private var nextOutputPresentationTimeUs = 0L

    override fun setListener(listener: AudioSink.Listener) = delegate.setListener(listener)

    override fun setClock(clock: Clock) = delegate.setClock(clock)

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int {
        if (!processor.isAvailable) return delegate.getFormatSupport(format)
        if (canProcess(format)) {
            val floatSupport = delegate.getFormatSupport(asFloatFormat(format))
            if (floatSupport != AudioSink.SINK_FORMAT_UNSUPPORTED) return floatSupport
            return delegate.getFormatSupport(format)
        }
        // Reporting compressed formats unsupported here forces Media3 to
        // decode them; otherwise device passthrough would bypass native DSP.
        return if (format.sampleMimeType == MimeTypes.AUDIO_RAW) {
            delegate.getFormatSupport(format)
        } else AudioSink.SINK_FORMAT_UNSUPPORTED
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        AudioOffloadSupport.DEFAULT_UNSUPPORTED

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        delegate.getCurrentPositionUs(sourceEnded)

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        clearPending()
        clearEndOfStream()
        processor.reset()
        val floatFormat = asFloatFormat(format)
        val shouldProcess = processor.isAvailable &&
            canProcess(format) &&
            delegate.getFormatSupport(floatFormat) != AudioSink.SINK_FORMAT_UNSUPPORTED
        if (shouldProcess) {
            outputChannelCount = format.channelCount
            processor.setTrimFrameCount(format.encoderDelay, format.encoderPadding)
            processingActive = try {
                processor.configure(AudioProcessor.AudioFormat(format))
                processor.flush()
                true
            } catch (error: RuntimeException) {
                android.util.Log.e(TAG, "Native DSP configuration failed; using platform audio", error)
                processor.reset()
                false
            } catch (error: LinkageError) {
                android.util.Log.e(TAG, "Native DSP unavailable; using platform audio", error)
                processor.reset()
                false
            }
        }
        if (processingActive) {
            delegate.configure(floatFormat, 0, outputChannels)
            return
        }
        delegate.configure(format, specifiedBufferSize, outputChannels)
    }

    override fun play() = delegate.play()

    override fun handleDiscontinuity() {
        clearPending()
        clearEndOfStream()
        if (processingActive) processor.flush()
        delegate.handleDiscontinuity()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (!processingActive) {
            return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        if (!buffer.hasRemaining()) return true

        if (pendingInput == null) {
            val processorInput = buffer.duplicate()
            processor.queueInput(processorInput)
            pendingInput = buffer
            pendingInputLimit = buffer.limit()
            pendingOutput = processor.getOutput()
            pendingPresentationTimeUs = presentationTimeUs
            pendingAccessUnitCount = encodedAccessUnitCount
            pendingOutputFrameCount = pendingOutput!!.remaining() /
                (Float.SIZE_BYTES * outputChannelCount)
        } else {
            check(pendingInput === buffer) { "AudioSink input changed before native output drained" }
        }

        val output = checkNotNull(pendingOutput)
        if (output.hasRemaining()) {
            delegate.handleBuffer(output, pendingPresentationTimeUs, pendingAccessUnitCount)
        }
        if (output.hasRemaining()) return false

        nextOutputPresentationTimeUs = pendingPresentationTimeUs +
            pendingOutputFrameCount * MICROS_PER_SECOND / processor.nativeOutputSampleRate
        buffer.position(pendingInputLimit)
        clearPending()
        return true
    }

    override fun playToEndOfStream() {
        if (!processingActive) {
            delegate.playToEndOfStream()
            return
        }
        if (!endOfStreamQueued) {
            processor.queueEndOfStream()
            endOfStreamOutput = processor.getOutput()
            endOfStreamQueued = true
        }
        val tail = endOfStreamOutput
        if (tail?.hasRemaining() == true) {
            delegate.handleBuffer(tail, nextOutputPresentationTimeUs, 1)
            if (tail.hasRemaining()) return
        }
        delegate.playToEndOfStream()
    }

    override fun isEnded(): Boolean = delegate.isEnded()

    override fun hasPendingData(): Boolean =
        pendingOutput?.hasRemaining() == true ||
            endOfStreamOutput?.hasRemaining() == true ||
            delegate.hasPendingData()

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) =
        delegate.setPlaybackParameters(playbackParameters)

    override fun getPlaybackParameters(): PlaybackParameters = delegate.getPlaybackParameters()

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) =
        delegate.setSkipSilenceEnabled(skipSilenceEnabled)

    override fun getSkipSilenceEnabled(): Boolean = delegate.getSkipSilenceEnabled()

    override fun setAudioAttributes(audioAttributes: AudioAttributes) =
        delegate.setAudioAttributes(audioAttributes)

    override fun getAudioAttributes(): AudioAttributes? = delegate.getAudioAttributes()

    override fun setAudioSessionId(audioSessionId: Int) = delegate.setAudioSessionId(audioSessionId)

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = delegate.setAuxEffectInfo(auxEffectInfo)

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) =
        delegate.setPreferredDevice(audioDeviceInfo)

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) =
        delegate.setOutputStreamOffsetUs(outputStreamOffsetUs)

    override fun enableTunnelingV21() = delegate.enableTunnelingV21()

    override fun disableTunneling() = delegate.disableTunneling()

    override fun setOffloadMode(offloadMode: Int) = delegate.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) =
        delegate.setOffloadDelayPadding(delayInFrames, paddingInFrames)

    override fun setVolume(volume: Float) = delegate.setVolume(volume)

    override fun pause() = delegate.pause()

    override fun flush() {
        clearPending()
        clearEndOfStream()
        processor.flush()
        delegate.flush()
    }

    override fun reset() {
        clearPending()
        clearEndOfStream()
        processingActive = false
        processor.reset()
        delegate.reset()
    }

    override fun release() {
        reset()
        delegate.release()
    }

    private fun canProcess(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.AUDIO_RAW &&
            format.channelCount in 1..2 &&
            format.pcmEncoding in SUPPORTED_ENCODINGS

    private fun asFloatFormat(format: Format): Format =
        format.buildUpon()
            .setSampleRate(processor.nativeOutputSampleRate)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setEncoderDelay(0)
            .setEncoderPadding(0)
            .build()

    private fun clearPending() {
        pendingInput = null
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
