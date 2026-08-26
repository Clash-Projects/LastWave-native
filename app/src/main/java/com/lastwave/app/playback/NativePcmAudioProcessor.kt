package com.lastwave.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts decoded Media3 PCM to Float32 and runs the C++ DSP in place.
 * The platform fallback preserves source rates through 192 kHz. Oboe output
 * instead targets its actual device rate through the native libsoxr HQ path.
 */
class NativePcmAudioProcessor(
    private val engine: NativeAudioEngine,
    private val maxOutputSampleRateHz: Int = MAX_OUTPUT_SAMPLE_RATE_HZ,
) : BaseAudioProcessor() {
    var nativeOutputSampleRate: Int = DEFAULT_OUTPUT_SAMPLE_RATE_HZ
        private set

    val isAvailable: Boolean
        get() = engine.isAvailable

    private var nativeEncoding = NativePcmEncoding.PCM_FLOAT
    private var copyBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var trimCombineBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var retainedEndBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var configuredStartTrimFrames = 0
    private var configuredEndTrimFrames = 0
    private var startTrimFramesRemaining = 0
    private var retainedEndBytes = 0
    private var outputSampleRateOverrideHz: Int? = null

    fun setTrimFrameCount(startFrames: Int, endFrames: Int) {
        configuredStartTrimFrames = startFrames.coerceIn(0, MAX_TRIM_FRAMES)
        configuredEndTrimFrames = endFrames.coerceIn(0, MAX_TRIM_FRAMES)
    }

    /**
     * Begins a new input stream for gapless playback without tearing down
     * the native pipeline. Only per-stream trim accounting is reset; the
     * libsoxr resampler and every DSP filter keep their state so consecutive
     * same-format tracks join sample-exactly, exactly like one continuous
     * stream. Must only be called when the core audio format is unchanged.
     */
    fun beginStream(startFrames: Int, endFrames: Int) {
        setTrimFrameCount(startFrames, endFrames)
        startTrimFramesRemaining = configuredStartTrimFrames
        retainedEndBytes = 0
        retainedEndBuffer.clear()
    }

    fun setOutputSampleRateOverride(sampleRateHz: Int?) {
        require(sampleRateHz == null || sampleRateHz in MIN_OUTPUT_SAMPLE_RATE_HZ..MAX_OUTPUT_SAMPLE_RATE_HZ)
        outputSampleRateOverrideHz = sampleRateHz
    }

    fun outputSampleRateFor(inputSampleRate: Int): Int =
        outputSampleRateOverrideHz
            ?: inputSampleRate.coerceIn(MIN_OUTPUT_SAMPLE_RATE_HZ, maxOutputSampleRateHz)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.channelCount !in 1..2) {
            throw AudioProcessor.UnhandledAudioFormatException(
                "Native DSP supports mono/stereo only",
                inputAudioFormat,
            )
        }
        nativeEncoding = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> NativePcmEncoding.PCM_I16
            C.ENCODING_PCM_24BIT -> NativePcmEncoding.PCM_I24_PACKED
            C.ENCODING_PCM_32BIT -> NativePcmEncoding.PCM_I32
            C.ENCODING_PCM_FLOAT -> NativePcmEncoding.PCM_FLOAT
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        nativeOutputSampleRate = outputSampleRateFor(inputAudioFormat.sampleRate)
        val endTrimBytes = Math.multiplyExact(
            configuredEndTrimFrames,
            inputAudioFormat.bytesPerFrame,
        )
        retainedEndBuffer = if (endTrimBytes > 0) {
            ByteBuffer.allocateDirect(endTrimBytes).order(ByteOrder.LITTLE_ENDIAN)
        } else {
            AudioProcessor.EMPTY_BUFFER
        }
        if (!engine.configureMediaProcessor(
                inputSampleRate = inputAudioFormat.sampleRate,
                outputSampleRate = nativeOutputSampleRate,
                channelCount = inputAudioFormat.channelCount,
            )
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(
                "Native DSP/resampler configuration failed",
                inputAudioFormat,
            )
        }
        return AudioProcessor.AudioFormat(
            nativeOutputSampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_FLOAT,
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        check(!hasPendingOutput()) { "Native PCM output must be drained before more input" }

        val bytesPerFrame = inputAudioFormat.bytesPerFrame
        val frameCount = inputBuffer.remaining() / bytesPerFrame
        if (frameCount == 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val originalPosition = inputBuffer.position()
        val inputByteCount = frameCount * bytesPerFrame
        val source = inputBuffer.duplicate().apply {
            limit(position() + inputByteCount)
        }
        val startTrimFrames = minOf(startTrimFramesRemaining, frameCount)
        source.position(source.position() + startTrimFrames * bytesPerFrame)
        startTrimFramesRemaining -= startTrimFrames

        if (configuredEndTrimFrames == 0) {
            processFrames(source, source.remaining() / bytesPerFrame)
            inputBuffer.position(originalPosition + inputByteCount)
            return
        }

        val totalBytes = Math.addExact(retainedEndBytes, source.remaining())
        if (trimCombineBuffer.capacity() < totalBytes) {
            trimCombineBuffer = ByteBuffer.allocateDirect(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
        }
        trimCombineBuffer.clear()
        if (retainedEndBytes > 0) {
            val retained = retainedEndBuffer.duplicate().apply {
                clear()
                limit(retainedEndBytes)
            }
            trimCombineBuffer.put(retained)
        }
        trimCombineBuffer.put(source).flip()

        val endTrimBytes = configuredEndTrimFrames * bytesPerFrame
        val processByteCount = (totalBytes - endTrimBytes).coerceAtLeast(0)
        if (processByteCount > 0) {
            val processInput = trimCombineBuffer.duplicate().apply {
                clear()
                limit(processByteCount)
            }
            processFrames(processInput, processByteCount / bytesPerFrame)
        }

        retainedEndBytes = totalBytes - processByteCount
        retainedEndBuffer.clear()
        if (retainedEndBytes > 0) {
            val tail = trimCombineBuffer.duplicate().apply {
                position(processByteCount)
                limit(totalBytes)
            }
            retainedEndBuffer.put(tail)
        }
        inputBuffer.position(originalPosition + inputByteCount)
    }

    private fun processFrames(inputBuffer: ByteBuffer, frameCount: Int) {
        if (frameCount <= 0) return
        val inputByteCount = frameCount * inputAudioFormat.bytesPerFrame
        val expectedOutputFrames = if (inputAudioFormat.sampleRate == nativeOutputSampleRate) {
            frameCount
        } else {
            ((frameCount.toLong() * nativeOutputSampleRate + inputAudioFormat.sampleRate - 1L) /
                inputAudioFormat.sampleRate + RESAMPLER_OUTPUT_HEADROOM_FRAMES).toInt()
        }
        val outputByteCount = Math.multiplyExact(expectedOutputFrames, outputAudioFormat.bytesPerFrame)
        val output = replaceOutputBuffer(outputByteCount).order(ByteOrder.nativeOrder())

        val nativeInput: ByteBuffer
        val inputOffset: Int
        if (inputBuffer.isDirect) {
            nativeInput = inputBuffer
            inputOffset = inputBuffer.position()
        } else {
            if (copyBuffer.capacity() < inputByteCount) {
                copyBuffer = ByteBuffer.allocateDirect(inputByteCount).order(ByteOrder.LITTLE_ENDIAN)
            }
            copyBuffer.clear()
            val copySource = inputBuffer.duplicate()
            copySource.limit(copySource.position() + inputByteCount)
            copyBuffer.put(copySource).flip()
            nativeInput = copyBuffer
            inputOffset = 0
        }

        val processedFrames = engine.processMediaPcm(
            input = nativeInput,
            inputByteOffset = inputOffset,
            output = output,
            outputByteOffset = 0,
            frameCount = frameCount,
            encoding = nativeEncoding,
            channelCount = inputAudioFormat.channelCount,
        )
        if (processedFrames < 0) {
            // A native failure must not take down Media3's playback thread.
            // Preserve the stream clock with silence; the next configure will
            // select the platform sink if native processing was disabled.
            output.clear()
            val silenceFrames = if (inputAudioFormat.sampleRate == nativeOutputSampleRate) {
                frameCount
            } else {
                ((frameCount.toLong() * nativeOutputSampleRate + inputAudioFormat.sampleRate - 1L) /
                    inputAudioFormat.sampleRate).toInt()
            }
            repeat(silenceFrames * inputAudioFormat.channelCount) {
                output.putFloat(0f)
            }
            output.flip()
            return
        }
        output.position(processedFrames * outputAudioFormat.bytesPerFrame)
        output.flip()
    }

    override fun onQueueEndOfStream() {
        if (outputAudioFormat == AudioProcessor.AudioFormat.NOT_SET) return
        // The retained input frames are encoder padding and are intentionally discarded.
        retainedEndBytes = 0
        val output = replaceOutputBuffer(
            RESAMPLER_FLUSH_CAPACITY_FRAMES * outputAudioFormat.bytesPerFrame,
        ).order(ByteOrder.nativeOrder())
        val outputFrames = engine.flushMediaProcessor(output, outputAudioFormat.channelCount)
        output.position(outputFrames.coerceAtLeast(0) * outputAudioFormat.bytesPerFrame)
        output.flip()
    }

    override fun onFlush() {
        startTrimFramesRemaining = configuredStartTrimFrames
        retainedEndBytes = 0
        retainedEndBuffer.clear()
        engine.resetMediaProcessor()
    }

    override fun onReset() {
        engine.resetMediaProcessor()
        copyBuffer = AudioProcessor.EMPTY_BUFFER
        trimCombineBuffer = AudioProcessor.EMPTY_BUFFER
        retainedEndBuffer = AudioProcessor.EMPTY_BUFFER
        configuredStartTrimFrames = 0
        configuredEndTrimFrames = 0
        startTrimFramesRemaining = 0
        retainedEndBytes = 0
    }

    private companion object {
        const val MIN_OUTPUT_SAMPLE_RATE_HZ = 8_000
        const val MAX_OUTPUT_SAMPLE_RATE_HZ = 192_000
        const val DEFAULT_OUTPUT_SAMPLE_RATE_HZ = 48_000
        const val MAX_TRIM_FRAMES = 1_000_000
        const val RESAMPLER_OUTPUT_HEADROOM_FRAMES = 4_096
        const val RESAMPLER_FLUSH_CAPACITY_FRAMES = 65_536
    }
}
