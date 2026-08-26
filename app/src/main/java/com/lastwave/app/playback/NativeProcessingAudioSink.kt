@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lastwave.app.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Media3 PCM sink backed by Oboe Float32 output. The native stage performs
 * gapless trim, libsoxr HQ rate conversion and optional DSP before the Oboe
 * ring. Android's AudioTrack remains an automatic output fallback whenever
 * native output cannot open, keeps underrunning or cannot follow an audio
 * device change — and automatically comes back after a probation window.
 *
 * Sample-rate contract: the sink only ever writes PCM at the rate the device
 * actually accepted (`engine.outputSampleRate`, read back after every stream
 * open). Source material at any other rate is converted by libsoxr HQ inside
 * [NativePcmAudioProcessor] before it reaches the ring, so 44.1 kHz files are
 * never pushed through a 48 kHz path as though the rates matched.
 */
class NativeProcessingAudioSink(
    context: Context,
    private val delegate: AudioSink,
    private val engine: NativeAudioEngine,
    private val processor: NativePcmAudioProcessor,
) : AudioSink {
    private val speedProcessor = FloatSonicProcessor()
    private val appAudioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: AudioSink.Listener? = null
    private var nativeActive = false
    /** Native Float32 DSP feeding the platform AudioTrack when Oboe cannot run. */
    private var platformDspActive = false

    /** Written from the render thread, read from the main thread — see [nativeOnProbation]. */
    @Volatile
    private var nativeProbationUntilMs = 0L
    private var nativeOpenFailures = 0
    private var playing = false
    private var configuredFormat: Format? = null
    private var configuredBufferSize = 0
    private var configuredOutputChannels: IntArray? = null
    private var processorOutputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputChannelCount = 2
    private var outputSampleRate = 48_000
    private var volume = 1f
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var skipSilenceEnabled = false
    private var pendingInput: ByteBuffer? = null
    private var pendingInputLimit = 0
    private var pendingOutput: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var pendingPresentationTimeUs = C.TIME_UNSET
    private var pendingEncodedAccessUnitCount = 0
    private var lastPresentationTimeUs = 0L
    private var endOfStreamOutput: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var nativeEndOfStreamQueued = false
    private var platformEndOfStreamQueued = false
    private var platformEndOfStreamDeferred = false
    private var speedEndOfStreamQueued = false
    private var endOfStreamComplete = false

    /** Set when EOS completion had to bail (ring full / route rebuild) and
     *  must be re-driven from the [isEnded] poll instead of being lost. */
    @Volatile
    private var endOfStreamDeferred = false
    @Volatile
    private var outputStreamOffsetUs = 0L
    @Volatile
    private var positionAnchorMediaUs = C.TIME_UNSET
    @Volatile
    private var positionAnchorRenderedFrames = 0L
    private var positionAdvancingNotified = false

    private var lastRouteRebuildAtMs = 0L

    /**
     * Set while the main thread is rebuilding the native stream for a device
     * change. The render thread defers all sink work for the window instead of
     * racing the rebuild with its own recovery paths.
     */
    @Volatile
    private var routeRebuildInProgress = false

    /**
     * Serializes control-path reconfiguration (configure/flush/fallback and
     * device-change rebuilds) between Media3's render thread and the main
     * thread. Per-buffer hot paths deliberately never take this lock; they
     * observe [routeRebuildInProgress] instead, whose clearing after the lock
     * is released gives them a happens-before view of the new configuration.
     */
    private val configurationLock = Any()

    /**
     * Output hardware changes (BT connect/disconnect, wired plug, dock, USB)
     * normally reroute silently inside AAudio. When they happen we rebuild
     * the stream once, debounced, so the pipeline re-verifies the rate the
     * NEW device actually accepted instead of feeding it the old stream's
     * rate through Android's own converter.
     */
    private val deviceChangedCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.any { it.isSink }) scheduleRouteRebuild("device added")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.isSink }) scheduleRouteRebuild("device removed")
        }
    }

    private val routeRebuildRunnable = Runnable { rebuildForRouteChange() }

    init {
        runCatching {
            appAudioManager?.registerAudioDeviceCallback(deviceChangedCallback, mainHandler)
        }.onFailure { error ->
            PlaybackDiagnostics.event(TAG, "AudioDeviceCallback unavailable: ${error.message}")
        }
    }

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        delegate.setListener(listener)
    }

    override fun setClock(clock: Clock) = delegate.setClock(clock)

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int {
        if (!processor.isAvailable) return delegate.getFormatSupport(format)
        if (processor.isAvailable && canProcess(format)) {
            return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }
        // Compressed passthrough would bypass FFmpeg/native PCM processing.
        return if (format.sampleMimeType == MimeTypes.AUDIO_RAW) {
            delegate.getFormatSupport(format)
        } else {
            AudioSink.SINK_FORMAT_UNSUPPORTED
        }
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        AudioOffloadSupport.DEFAULT_UNSUPPORTED

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!nativeActive) return delegate.getCurrentPositionUs(sourceEnded)
        if (positionAnchorMediaUs == C.TIME_UNSET || outputSampleRate <= 0) {
            return AudioSink.CURRENT_POSITION_NOT_SET
        }
        val renderedFrames = engine.renderedFrames
        val renderedDeltaFrames = (renderedFrames - positionAnchorRenderedFrames).coerceAtLeast(0L)
        val outputDurationUs = renderedDeltaFrames * MICROS_PER_SECOND / outputSampleRate
        val mediaDurationUs = (outputDurationUs * playbackParameters.speed).toLong()
        return outputStreamOffsetUs + positionAnchorMediaUs + mediaDurationUs
    }

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        synchronized(configurationLock) {
            // Gapless fast path: consecutive tracks whose core format matches
            // the live native configuration join sample-exactly. The Oboe
            // stream, the ring buffer, the libsoxr resampler and all DSP
            // filter states stay untouched; only per-stream trim accounting
            // is restarted. Dropping and reopening here used to punch an
            // audible hole into continuous albums and discard up to hundreds
            // of milliseconds of buffered audio.
            if (canReuseNativeConfiguration(format)) {
                configuredFormat = format
                configuredBufferSize = specifiedBufferSize
                configuredOutputChannels = outputChannels?.clone()
                processor.beginStream(format.encoderDelay, format.encoderPadding)
                // Re-anchor position for the new stream: the first buffer of
                // the next track re-captures the anchor against the still-
                // running frame counter.
                clearPosition()
                PlaybackDiagnostics.counter(
                    PlaybackDiagnostics.gaplessReuses,
                    TAG,
                    "Gapless continuation at $outputSampleRate Hz",
                )
                return
            }

            clearPending()
            clearEndOfStream()
            clearPosition()
            configuredFormat = format
            configuredBufferSize = specifiedBufferSize
            configuredOutputChannels = outputChannels?.clone()
            outputChannelCount = format.channelCount
            processorOutputFormat = AudioProcessor.AudioFormat.NOT_SET
            platformDspActive = false
            delegate.flush()
            // A healthy Oboe stream is reused across track changes and seeks;
            // only its queued audio is dropped. Tearing down and reopening
            // AAudio here used to inject a click into every transition on OEM
            // audio stacks.
            processor.reset()
            processor.setOutputSampleRateOverride(null)

            val nativeEligible = !nativeOnProbation() && processor.isAvailable && canProcess(format)
            nativeActive = nativeEligible && configureNative(format)
            if (nativeActive) {
                nativeOpenFailures = 0
                return
            }
            if (nativeEligible) {
                nativeOpenFailures++
                if (nativeOpenFailures >= NATIVE_OPEN_FAILURES_BEFORE_PROBATION) {
                    putNativeOnProbation(
                        NATIVE_OPEN_PROBATION_MS,
                        "Oboe open failed $nativeOpenFailures times consecutively",
                    )
                }
            }

            // Oboe is an output optimization, not a requirement for sound
            // processing. Keep the same native Float32 DSP in front of the
            // platform AudioTrack fallback so clarity/EQ/boost never become
            // silent no-op switches on devices whose AAudio stream cannot run.
            if (processor.isAvailable && canProcess(format) &&
                configurePlatformDsp(format, applyGaplessTrim = true)
            ) {
                platformDspActive = true
                return
            }

            processor.reset()
            processor.setOutputSampleRateOverride(null)
            delegate.setPlaybackParameters(playbackParameters)
            delegate.setVolume(volume)
            delegate.configure(format, specifiedBufferSize, outputChannels)
            if (playing) delegate.play()
        }
    }

    /** True when the running native pipeline can absorb [format] without any rebuild. */
    private fun canReuseNativeConfiguration(format: Format): Boolean {
        if (!nativeActive || nativeOnProbation()) return false
        // End-of-stream flushed the terminal resampler state; the next stream
        // needs the full reconfiguration path to recreate it cleanly.
        if (nativeEndOfStreamQueued || speedEndOfStreamQueued) return false
        val current = configuredFormat ?: return false
        // Rate equality is mandatory: mixing source rates through a reused
        // resampler would shift pitch and speed. Everything else (encoder
        // delay/padding metadata differences) is handled per-stream by trims.
        return current.sampleRate == format.sampleRate &&
            current.channelCount == format.channelCount &&
            current.pcmEncoding == format.pcmEncoding
    }

    override fun play() {
        playing = true
        if (nativeActive) engine.setPlaying(true) else delegate.play()
    }

    override fun handleDiscontinuity() {
        synchronized(configurationLock) {
            clearPending()
            clearEndOfStream()
            clearPosition()
            if (nativeActive) {
                restartNativeOutput()
                listener?.onPositionDiscontinuity()
            } else {
                delegate.handleDiscontinuity()
            }
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (!nativeActive && !platformDspActive) {
            return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        // The lock is uncontended on this hot path (control paths hold it only
        // during rare reconfiguration/rebuild windows). It guarantees the
        // main-thread route rebuild can never reset the processor chain while
        // a buffer is being fed through it.
        synchronized(configurationLock) {
            if (platformDspActive) {
                if (!buffer.hasRemaining()) return true
                if (pendingInput == null) {
                    processor.queueInput(buffer.duplicate())
                    pendingInput = buffer
                    pendingInputLimit = buffer.limit()
                    pendingPresentationTimeUs = presentationTimeUs
                    lastPresentationTimeUs = presentationTimeUs
                    pendingEncodedAccessUnitCount = encodedAccessUnitCount
                    pendingOutput = processor.getOutput()
                } else {
                    check(pendingInput === buffer) {
                        "AudioSink input changed before platform DSP output drained"
                    }
                }
                if (pendingOutput.hasRemaining() &&
                    !delegate.handleBuffer(
                        pendingOutput,
                        pendingPresentationTimeUs,
                        pendingEncodedAccessUnitCount,
                    )
                ) {
                    return false
                }
                buffer.position(pendingInputLimit)
                clearPending()
                return true
            }
            if (routeRebuildInProgress || !nativeActive) return false
            if (engine.underrunCount >= MAX_NATIVE_UNDERRUN_EPISODES) {
                putNativeOnProbation(
                    UNDERRUN_PROBATION_MS,
                    "Sustained Oboe underruns (${engine.underrunCount} episodes)",
                )
                switchToPlatformOutput("sustained Oboe underruns (${engine.underrunCount})")
                listener?.onPositionDiscontinuity()
                return handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            }
            if (!buffer.hasRemaining()) return true

            if (positionAnchorMediaUs == C.TIME_UNSET && presentationTimeUs != C.TIME_UNSET) {
                // Match DefaultAudioSink: handleBuffer's presentation time is
                // already in the renderer clock's coordinate system.
                positionAnchorMediaUs = presentationTimeUs.coerceAtLeast(0L)
                positionAnchorRenderedFrames = engine.renderedFrames
            }
            if (pendingInput == null) {
                val nativeInput = buffer.duplicate()
                processor.queueInput(nativeInput)
                pendingInput = buffer
                pendingInputLimit = buffer.limit()
                pendingOutput = speedProcessor.queueInput(processor.getOutput())
            } else {
                check(pendingInput === buffer) {
                    "AudioSink input changed before Oboe output drained"
                }
            }

            if (!drainToOboe(pendingOutput)) return false
            buffer.position(pendingInputLimit)
            clearPending()
            return true
        }
    }

    override fun playToEndOfStream() {
        if (platformDspActive) {
            synchronized(configurationLock) {
                tryCompletePlatformEndOfStreamLocked()
            }
            return
        }
        if (!nativeActive) {
            delegate.playToEndOfStream()
            return
        }
        synchronized(configurationLock) {
            tryCompleteEndOfStreamLocked()
        }
    }

    private fun tryCompletePlatformEndOfStreamLocked() {
        if (!platformDspActive) return
        if (pendingOutput.hasRemaining() &&
            !delegate.handleBuffer(
                pendingOutput,
                pendingPresentationTimeUs,
                pendingEncodedAccessUnitCount,
            )
        ) {
            platformEndOfStreamDeferred = true
            return
        }
        if (!platformEndOfStreamQueued) {
            processor.queueEndOfStream()
            endOfStreamOutput = processor.getOutput()
            platformEndOfStreamQueued = true
        }
        if (endOfStreamOutput.hasRemaining() &&
            !delegate.handleBuffer(
                endOfStreamOutput,
                lastPresentationTimeUs,
                1,
            )
        ) {
            platformEndOfStreamDeferred = true
            return
        }
        platformEndOfStreamDeferred = false
        delegate.playToEndOfStream()
    }

    /**
     * Advances the end-of-stream handoff one step. Every stage is idempotent
     * and resumable: Media3 invokes [playToEndOfStream] exactly once per
     * stream, but the Oboe ring can be momentarily full or a route rebuild
     * may own the stream at that instant. Bailing out there used to swallow
     * EOS forever — the renderer polled [isEnded], got false eternally, and
     * the player sat frozen at the final position without ever advancing.
     * Deferring and re-driving completion from the [isEnded] poll (which the
     * renderer performs continuously) removes that lost-EOS window.
     */
    private fun tryCompleteEndOfStreamLocked() {
        if (endOfStreamComplete || !nativeActive) return
        if (routeRebuildInProgress) {
            endOfStreamDeferred = true
            return
        }
        if (pendingOutput.hasRemaining() && !drainToOboe(pendingOutput)) {
            endOfStreamDeferred = true
            return
        }

        if (!nativeEndOfStreamQueued) {
            processor.queueEndOfStream()
            endOfStreamOutput = speedProcessor.queueInput(processor.getOutput())
            nativeEndOfStreamQueued = true
        }
        if (endOfStreamOutput.hasRemaining() && !drainToOboe(endOfStreamOutput)) {
            endOfStreamDeferred = true
            return
        }

        if (!speedEndOfStreamQueued) {
            speedProcessor.queueEndOfStream()
            speedEndOfStreamQueued = true
        }
        endOfStreamOutput = speedProcessor.getOutput()
        if (endOfStreamOutput.hasRemaining() && !drainToOboe(endOfStreamOutput)) {
            endOfStreamDeferred = true
            return
        }
        endOfStreamDeferred = false
        endOfStreamComplete = speedProcessor.isEnded
    }

    override fun isEnded(): Boolean {
        return when {
            nativeActive -> {
                // Renderer polls this every frame while output is ending; use it to
                // drive any previously deferred EOS completion to the finish line.
                synchronized(configurationLock) {
                    if (endOfStreamDeferred || (!endOfStreamComplete && nativeEndOfStreamQueued)) {
                        tryCompleteEndOfStreamLocked()
                    }
                }
                endOfStreamComplete && !hasPendingData()
            }
            platformDspActive -> {
                synchronized(configurationLock) {
                    if (platformEndOfStreamDeferred) tryCompletePlatformEndOfStreamLocked()
                }
                delegate.isEnded() && !pendingOutput.hasRemaining() && !endOfStreamOutput.hasRemaining()
            }
            else -> delegate.isEnded()
        }
    }

    override fun hasPendingData(): Boolean {
        return when {
            nativeActive -> pendingOutput.hasRemaining() ||
                endOfStreamOutput.hasRemaining() ||
                engine.bufferedFrames > 0L
            platformDspActive -> pendingOutput.hasRemaining() ||
                endOfStreamOutput.hasRemaining() ||
                delegate.hasPendingData()
            else -> delegate.hasPendingData()
        }
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        delegate.setPlaybackParameters(playbackParameters)
        if (this.playbackParameters == playbackParameters) return
        synchronized(configurationLock) {
            if (nativeActive && positionAnchorMediaUs != C.TIME_UNSET) {
                val currentPositionUs = getCurrentPositionUs(false)
                positionAnchorMediaUs = (currentPositionUs - outputStreamOffsetUs).coerceAtLeast(0L)
                positionAnchorRenderedFrames = engine.renderedFrames
            }
            this.playbackParameters = playbackParameters
            if (nativeActive && processorOutputFormat != AudioProcessor.AudioFormat.NOT_SET) {
                speedProcessor.configure(processorOutputFormat, playbackParameters)
            }
        }
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        this.skipSilenceEnabled = skipSilenceEnabled
        // LastWave does not expose skip-silence. Keep the delegate synchronized
        // for the compatibility path without adding destructive music edits.
        delegate.setSkipSilenceEnabled(skipSilenceEnabled)
    }

    override fun getSkipSilenceEnabled(): Boolean = skipSilenceEnabled

    override fun setAudioAttributes(audioAttributes: AudioAttributes) =
        delegate.setAudioAttributes(audioAttributes)

    override fun getAudioAttributes(): AudioAttributes? = delegate.getAudioAttributes()

    override fun setAudioSessionId(audioSessionId: Int) = delegate.setAudioSessionId(audioSessionId)

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = delegate.setAuxEffectInfo(auxEffectInfo)

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) =
        delegate.setPreferredDevice(audioDeviceInfo)

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        this.outputStreamOffsetUs = outputStreamOffsetUs
        delegate.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun enableTunnelingV21() = delegate.enableTunnelingV21()

    override fun disableTunneling() = delegate.disableTunneling()

    override fun setOffloadMode(offloadMode: Int) =
        delegate.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) =
        delegate.setOffloadDelayPadding(delayInFrames, paddingInFrames)

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        delegate.setVolume(this.volume)
        if (nativeActive) engine.setOutputVolume(this.volume)
    }

    override fun pause() {
        playing = false
        if (nativeActive) engine.setPlaying(false) else delegate.pause()
    }

    override fun flush() {
        synchronized(configurationLock) {
            clearPending()
            clearEndOfStream()
            clearPosition()
            if (nativeActive) {
                restartNativeOutput()
                listener?.onPositionDiscontinuity()
            } else {
                if (platformDspActive) processor.flush()
                delegate.flush()
            }
        }
    }

    override fun reset() {
        synchronized(configurationLock) {
            clearPending()
            clearEndOfStream()
            clearPosition()
            outputStreamOffsetUs = 0L
            nativeActive = false
            platformDspActive = false
            playing = false
            configuredFormat = null
            processorOutputFormat = AudioProcessor.AudioFormat.NOT_SET
            engine.stop()
            processor.reset()
            processor.setOutputSampleRateOverride(null)
            speedProcessor.reset()
            delegate.reset()
        }
    }

    override fun release() {
        synchronized(configurationLock) {
            mainHandler.removeCallbacks(routeRebuildRunnable)
            runCatching {
                appAudioManager?.unregisterAudioDeviceCallback(deviceChangedCallback)
            }
            nativeActive = false
            platformDspActive = false
            engine.stop()
            processor.reset()
            speedProcessor.reset()
            delegate.release()
        }
    }

    /**
     * Keeps the native Float32 media processor active while AudioTrack owns the
     * final output. This is the compatibility path for devices where Oboe is
     * unavailable or on probation; decoded Opus, FLAC, AAC and MP3 still pass
     * through clarity, EQ, volume boost and peak protection.
     */
    private fun configurePlatformDsp(format: Format, applyGaplessTrim: Boolean): Boolean {
        return try {
            engine.setPlaying(false)
            nativeActive = false
            platformDspActive = false
            engine.stop()
            processor.reset()
            processor.setOutputSampleRateOverride(null)
            processor.setTrimFrameCount(
                if (applyGaplessTrim) format.encoderDelay else 0,
                if (applyGaplessTrim) format.encoderPadding else 0,
            )
            processorOutputFormat = processor.configure(AudioProcessor.AudioFormat(format))
            processor.flush()

            val processedFormat = format.buildUpon()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setSampleRate(processorOutputFormat.sampleRate)
                .setChannelCount(processorOutputFormat.channelCount)
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .setEncoderDelay(0)
                .setEncoderPadding(0)
                .build()
            delegate.flush()
            delegate.setPlaybackParameters(playbackParameters)
            delegate.setVolume(volume)
            // The source buffer size describes its original PCM encoding;
            // AudioTrack should size itself again for processed Float32 output.
            delegate.configure(processedFormat, 0, configuredOutputChannels)
            if (playing) delegate.play()
            PlaybackDiagnostics.event(
                TAG,
                "Native Float32 DSP active through platform AudioTrack at ${processedFormat.sampleRate} Hz",
            )
            true
        } catch (error: Exception) {
            Log.e(TAG, "Platform DSP configuration failed; using unprocessed Android audio", error)
            PlaybackDiagnostics.event(TAG, "Platform DSP configure failed: ${error.message}")
            processor.reset()
            processor.setOutputSampleRateOverride(null)
            processorOutputFormat = AudioProcessor.AudioFormat.NOT_SET
            false
        } catch (error: LinkageError) {
            Log.e(TAG, "Platform DSP symbols unavailable; using unprocessed Android audio", error)
            processorOutputFormat = AudioProcessor.AudioFormat.NOT_SET
            false
        }
    }

    /**
     * Opens or reuses the native stream and configures the processing chain
     * around the rate the device ACTUALLY accepted — never the rate we asked
     * for. Returns false (and leaves the engine stopped) when no trustworthy
     * native configuration could be established.
     */
    private fun configureNative(format: Format): Boolean {
        return try {
            // Reuse a healthy Oboe stream across tracks and seeks: only the ring
            // content is dropped. A full reopen is reserved for a dead stream.
            val reused = engine.isRunning &&
                engine.outputSampleRate in MIN_OUTPUT_SAMPLE_RATE_HZ..MAX_OUTPUT_SAMPLE_RATE_HZ
            if (reused) {
                engine.flushOutput()
            } else {
                if (!engine.start(0)) return false
            }
            // Read-back verification: this is the single source of truth for what
            // the device will clock out. A Samsung path reporting 48 kHz while we
            // hinted something else lands here as 48 kHz — everything downstream
            // (resampler override, speed processor, position math) keys off it.
            val nativeRate = engine.outputSampleRate
            if (nativeRate !in MIN_OUTPUT_SAMPLE_RATE_HZ..MAX_OUTPUT_SAMPLE_RATE_HZ) {
                engine.stop()
                return false
            }
            if (!reused || nativeRate != outputSampleRate) {
                PlaybackDiagnostics.event(
                    TAG,
                    "Native output ${if (reused) "reused" else "open"}: actual=$nativeRate Hz" +
                        " (requested=${engine.requestedSampleRate}, opens=${engine.streamOpenCount}," +
                        " restarts=${engine.streamRestartCount}," +
                        " adapted=${engine.rateAdaptationCount})",
                )
            }
            outputSampleRate = nativeRate
            processor.setOutputSampleRateOverride(nativeRate)
            processor.setTrimFrameCount(format.encoderDelay, format.encoderPadding)
            processorOutputFormat = processor.configure(AudioProcessor.AudioFormat(format))
            processor.flush()
            speedProcessor.configure(processorOutputFormat, playbackParameters)
            engine.setOutputVolume(volume)
            engine.setPlaying(playing)
            true
        } catch (error: Exception) {
            Log.e(TAG, "Native Oboe configuration failed; using Android audio", error)
            PlaybackDiagnostics.event(TAG, "Native configure failed: ${error.message}")
            engine.stop()
            false
        } catch (error: LinkageError) {
            Log.e(TAG, "Native Oboe symbols unavailable; using Android audio", error)
            engine.stop()
            false
        }
    }

    private fun restartNativeOutput() {
        synchronized(configurationLock) {
            val format = configuredFormat ?: return
            processor.reset()
            speedProcessor.reset()
            if (routeRebuildInProgress) {
                // A device-change rebuild is already reopening the stream and
                // will drop queued audio there; avoid two concurrent rebuilds.
                return
            }
            // configureNative prefers flushing the existing stream; a full
            // restart happens only when the stream actually died.
            if (configureNative(format)) return

            switchToPlatformOutput("Oboe restart failed")
        }
    }

    private fun switchToPlatformOutput(reason: String) {
        synchronized(configurationLock) {
            val format = configuredFormat ?: return
            Log.w(TAG, "$reason; temporarily using Android audio output")
            PlaybackDiagnostics.counter(PlaybackDiagnostics.nativeFallbacks, TAG, reason)
            nativeActive = false
            platformDspActive = false
            engine.setPlaying(false)
            engine.stop()
            clearPending()
            clearEndOfStream()
            clearPosition()
            processor.reset()
            processor.setOutputSampleRateOverride(null)
            processorOutputFormat = AudioProcessor.AudioFormat.NOT_SET
            speedProcessor.reset()
            if (processor.isAvailable && canProcess(format) &&
                configurePlatformDsp(format, applyGaplessTrim = false)
            ) {
                platformDspActive = true
                return
            }
            delegate.flush()
            delegate.setPlaybackParameters(playbackParameters)
            delegate.setVolume(volume)
            delegate.configure(format, configuredBufferSize, configuredOutputChannels)
            if (playing) delegate.play()
        }
    }

    private fun drainToOboe(output: ByteBuffer): Boolean {
        while (output.hasRemaining()) {
            val frames = output.remaining() / (Float.SIZE_BYTES * outputChannelCount)
            if (frames <= 0) {
                output.position(output.limit())
                break
            }
            val accepted = engine.writeProcessedPcm(
                output,
                frames,
                outputSampleRate,
                outputChannelCount,
            )
            if (accepted == 0) {
                if (routeRebuildInProgress) return false
                val actualRate = engine.outputSampleRate
                if (actualRate != outputSampleRate) {
                    // The stream died and rebuilt underneath us (fatal AAudio
                    // error, OEM audio-server restart, real device switch) and
                    // came back at a DIFFERENT rate. Writing the old-rate
                    // buffer now would shift pitch; rebuilding the pipeline
                    // around the new actual rate preserves correctness. The
                    // input was never consumed, so returning false makes the
                    // renderer re-offer it once the chain is rebuilt.
                    if (recoverFromOutputRateDrift(actualRate)) return false
                    putNativeOnProbation(
                        RATE_DRIFT_PROBATION_MS,
                        "Unrecoverable output rate drift ($outputSampleRate -> $actualRate Hz)",
                    )
                    switchToPlatformOutput(
                        "output rate changed $outputSampleRate -> $actualRate Hz",
                    )
                    listener?.onPositionDiscontinuity()
                    return false
                }
                return false
            }
            if (!positionAdvancingNotified && playing) {
                positionAdvancingNotified = true
                listener?.onPositionAdvancing(System.currentTimeMillis())
            }
        }
        return true
    }

    /**
     * Transparently rebuilds the native chain after the underlying stream
     * started reporting a different sample rate. Buffered audio for the old
     * rate is discarded (at most one processing chunk); the renderer replays
     * the unconsumed input buffer, so no samples are lost.
     */
    private fun recoverFromOutputRateDrift(actualRate: Int): Boolean {
        PlaybackDiagnostics.event(
            TAG,
            "Recovering: stream now runs at $actualRate Hz (was $outputSampleRate Hz)",
        )
        val format = configuredFormat ?: return false
        clearPending()
        clearEndOfStream()
        val capturedUs = if (positionAnchorMediaUs != C.TIME_UNSET) {
            getCurrentPositionUs(false)
        } else {
            C.TIME_UNSET
        }
        clearPosition()
        processor.reset()
        speedProcessor.reset()
        if (!configureNative(format)) return false
        if (capturedUs != C.TIME_UNSET) {
            positionAnchorMediaUs = (capturedUs - outputStreamOffsetUs).coerceAtLeast(0L)
            positionAnchorRenderedFrames = engine.renderedFrames
        }
        listener?.onPositionDiscontinuity()
        return nativeActive
    }

    private fun canProcess(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.AUDIO_RAW &&
            format.channelCount in 1..2 &&
            format.pcmEncoding in SUPPORTED_ENCODINGS

    // ---- Audio-device-change handling ----------------------------------

    private fun scheduleRouteRebuild(trigger: String) {
        if (!nativeActive || nativeOnProbation()) return
        if (configuredFormat == null) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRouteRebuildAtMs < ROUTE_REBUILD_MIN_INTERVAL_MS) return
        PlaybackDiagnostics.event(TAG, "Audio route change scheduled ($trigger)")
        mainHandler.removeCallbacks(routeRebuildRunnable)
        mainHandler.postDelayed(routeRebuildRunnable, ROUTE_REBUILD_DEBOUNCE_MS)
    }

    /**
     * Graceful stream rebuild after an output device appeared or vanished.
     * Fades the old stream silent, reopens AAudio so Android hands us the new
     * device's actual rate, reconfigures the conversion chain around it and
     * continues playback. Any failure falls back to the platform sink rather
     * than risking broken audio.
     */
    private fun rebuildForRouteChange() {
        if (!nativeActive || nativeOnProbation() || routeRebuildInProgress) return
        val format = configuredFormat ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRouteRebuildAtMs < ROUTE_REBUILD_MIN_INTERVAL_MS) return
        lastRouteRebuildAtMs = now
        routeRebuildInProgress = true
        try {
            synchronized(configurationLock) {
                PlaybackDiagnostics.counter(
                    PlaybackDiagnostics.routeRebuilds,
                    TAG,
                    "Rebuilding native stream for audio device change",
                )

                val capturedUs = if (positionAnchorMediaUs != C.TIME_UNSET) {
                    getCurrentPositionUs(false)
                } else {
                    C.TIME_UNSET
                }
                // 5 ms fade-out inside the engine callback prevents the close
                // click; the reopen fades back in from the prebuffer gate.
                engine.setPlaying(false)
                engine.stop()
                clearPending()
                clearEndOfStream()

                processor.reset()
                speedProcessor.reset()
                if (configureNative(format)) {
                    if (capturedUs != C.TIME_UNSET) {
                        positionAnchorMediaUs = (capturedUs - outputStreamOffsetUs).coerceAtLeast(0L)
                        positionAnchorRenderedFrames = engine.renderedFrames
                    }
                    PlaybackDiagnostics.event(
                        TAG,
                        "Route rebuild complete at ${engine.outputSampleRate} Hz",
                    )
                } else {
                    switchToPlatformOutput("native stream rebuild after device change failed")
                    listener?.onPositionDiscontinuity()
                }
            }
        } catch (error: Throwable) {
            // A route rebuild must never take the app down; degrade to the
            // platform sink which is configured independently of native state.
            PlaybackDiagnostics.event(TAG, "Route rebuild crashed: ${error.message}")
            runCatching { switchToPlatformOutput("route rebuild failure") }
            runCatching { listener?.onPositionDiscontinuity() }
        } finally {
            routeRebuildInProgress = false
        }
    }

    // ---- Probation ------------------------------------------------------

    private fun nativeOnProbation(): Boolean =
        SystemClock.elapsedRealtime() < nativeProbationUntilMs

    private fun putNativeOnProbation(durationMs: Long, reason: String) {
        nativeProbationUntilMs = SystemClock.elapsedRealtime() + durationMs
        PlaybackDiagnostics.event(TAG, "Native output probation ${durationMs}ms: $reason")
    }

    private fun clearPending() {
        pendingInput = null
        pendingInputLimit = 0
        pendingOutput = AudioProcessor.EMPTY_BUFFER
        pendingPresentationTimeUs = C.TIME_UNSET
        pendingEncodedAccessUnitCount = 0
    }

    private fun clearEndOfStream() {
        endOfStreamOutput = AudioProcessor.EMPTY_BUFFER
        nativeEndOfStreamQueued = false
        platformEndOfStreamQueued = false
        platformEndOfStreamDeferred = false
        speedEndOfStreamQueued = false
        endOfStreamComplete = false
        endOfStreamDeferred = false
    }

    private fun clearPosition() {
        positionAnchorMediaUs = C.TIME_UNSET
        positionAnchorRenderedFrames = 0L
        positionAdvancingNotified = false
        lastPresentationTimeUs = 0L
        listener?.onPositionDiscontinuity()
    }

    /** Float32 -> TPDF-dithered -> Media3 Sonic (Int16) -> Float32, active only away from 1x. */
    private class FloatSonicProcessor {
        private val toInt16 = ToInt16PcmAudioProcessor()
        private val sonic = SonicAudioProcessor()
        private val ditherRandom = java.util.Random()
        private var active = false
        private var ditherBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
        private var floatOutput: ByteBuffer = AudioProcessor.EMPTY_BUFFER

        fun configure(format: AudioProcessor.AudioFormat, parameters: PlaybackParameters) {
            reset()
            active = abs(parameters.speed - 1f) >= SPEED_EPSILON ||
                abs(parameters.pitch - 1f) >= SPEED_EPSILON
            if (!active) return
            val int16Format = toInt16.configure(format)
            toInt16.flush()
            sonic.setSpeed(parameters.speed)
            sonic.setPitch(parameters.pitch)
            sonic.configure(int16Format)
            sonic.flush()
        }

        fun queueInput(input: ByteBuffer): ByteBuffer {
            if (!active) return input
            if (input.hasRemaining()) {
                toInt16.queueInput(applyTpdfDither(input))
                val int16 = toInt16.getOutput()
                if (int16.hasRemaining()) sonic.queueInput(int16)
            }
            return getOutput()
        }

        fun getOutput(): ByteBuffer {
            if (!active) return AudioProcessor.EMPTY_BUFFER
            return convertToFloat(sonic.getOutput())
        }

        fun queueEndOfStream() {
            if (!active) return
            toInt16.queueEndOfStream()
            val int16Tail = toInt16.getOutput()
            if (int16Tail.hasRemaining()) sonic.queueInput(int16Tail)
            sonic.queueEndOfStream()
        }

        val isEnded: Boolean
            get() = !active || sonic.isEnded

        fun reset() {
            toInt16.reset()
            sonic.reset()
            active = false
            ditherBuffer = AudioProcessor.EMPTY_BUFFER
            floatOutput = AudioProcessor.EMPTY_BUFFER
        }

        /**
         * Adds ±0.5 LSB triangular dither before Int16 quantization. Without
         * it, quiet passages and reverb tails show correlated quantization
         * distortion at non-1x speeds; with it the error becomes benign,
         * decorrelated noise.
         */
        private fun applyTpdfDither(input: ByteBuffer): ByteBuffer {
            val byteCount = input.remaining()
            if (ditherBuffer.capacity() < byteCount) {
                ditherBuffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
            }
            ditherBuffer.clear()
            val source = input.duplicate().order(ByteOrder.nativeOrder())
            val lsb = 1f / PCM_16_SCALE
            while (source.hasRemaining()) {
                val noise = (ditherRandom.nextFloat() - ditherRandom.nextFloat()) * 0.5f * lsb
                ditherBuffer.putFloat(source.float + noise)
            }
            source.position(source.limit())
            input.position(input.limit())
            ditherBuffer.flip()
            return ditherBuffer
        }

        private fun convertToFloat(input: ByteBuffer): ByteBuffer {
            if (!input.hasRemaining()) return AudioProcessor.EMPTY_BUFFER
            val sampleCount = input.remaining() / Short.SIZE_BYTES
            val byteCount = sampleCount * Float.SIZE_BYTES
            if (floatOutput.capacity() < byteCount) {
                floatOutput = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
            } else {
                floatOutput.clear()
            }
            val source = input.duplicate().order(ByteOrder.nativeOrder())
            repeat(sampleCount) {
                floatOutput.putFloat(source.short / PCM_16_SCALE)
            }
            input.position(input.limit())
            floatOutput.flip()
            return floatOutput
        }
    }

    private companion object {
        const val TAG = "NativeOboeSink"
        const val MICROS_PER_SECOND = 1_000_000L
        const val MIN_OUTPUT_SAMPLE_RATE_HZ = 8_000
        const val MAX_OUTPUT_SAMPLE_RATE_HZ = 192_000
        // The engine forgives one counted underrun per 5s of clean playback,
        // so only a genuinely sustained problem reaches this fallback.
        const val MAX_NATIVE_UNDERRUN_EPISODES = 8L
        const val NATIVE_OPEN_FAILURES_BEFORE_PROBATION = 2
        // Probation replaces the old permanent session kill-switch: transient
        // CPU storms, audio-server hiccups and route flaps self-heal instead
        // of silently disabling native quality (and the EQ/DSP) forever.
        const val UNDERRUN_PROBATION_MS = 90_000L
        const val RATE_DRIFT_PROBATION_MS = 90_000L
        const val NATIVE_OPEN_PROBATION_MS = 5 * 60_000L
        const val ROUTE_REBUILD_DEBOUNCE_MS = 300L
        const val ROUTE_REBUILD_MIN_INTERVAL_MS = 1_500L
        const val SPEED_EPSILON = 0.0001f
        const val PCM_16_SCALE = 32768f
        val SUPPORTED_ENCODINGS = setOf(
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT,
        )
    }
}
