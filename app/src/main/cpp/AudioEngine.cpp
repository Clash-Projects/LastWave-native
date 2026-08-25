#include "AudioEngine.h"

#include <soxr.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace lastwave::audio {
namespace {

constexpr double kPi = 3.1415926535897932384626433832795;

soxr_t asSoxr(void* handle) noexcept {
    return static_cast<soxr_t>(handle);
}

}  // namespace

AudioEngine::~AudioEngine() {
    stop();
    std::lock_guard processorLock(mediaProcessorMutex_);
    if (mediaResampler_ != nullptr) soxr_delete(asSoxr(mediaResampler_));
}

bool AudioEngine::start(std::int32_t preferredOutputSampleRate) {
    std::lock_guard controlLock(controlMutex_);
    restartAllowed_.store(true, std::memory_order_release);
    return startLocked(preferredOutputSampleRate);
}

bool AudioEngine::startLocked(std::int32_t preferredOutputSampleRate) {
    if (stream_ != nullptr) return true;

    requestedOutputSampleRate_ = std::max(preferredOutputSampleRate, 0);
    std::shared_ptr<oboe::AudioStream> openedStream;
    const auto openWithSharingMode = [this, &openedStream](oboe::SharingMode sharingMode) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(sharingMode)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(kOutputChannels)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        if (requestedOutputSampleRate_ > 0) {
            builder.setSampleRate(requestedOutputSampleRate_);
        }
        return builder.openStream(openedStream);
    };
    auto openResult = openWithSharingMode(oboe::SharingMode::Exclusive);
    if (openResult != oboe::Result::OK || openedStream == nullptr) {
        // Exclusive streams are routinely denied by OEM policy, Bluetooth,
        // calls, accessibility services, and other active media apps.
        // Shared low-latency output is the portable fallback.
        if (openedStream != nullptr) openedStream->close();
        openedStream.reset();
        openResult = openWithSharingMode(oboe::SharingMode::Shared);
    }
    if (openResult != oboe::Result::OK || openedStream == nullptr) {
        restartAllowed_.store(false, std::memory_order_release);
        return false;
    }

    const std::int32_t sampleRate = openedStream->getSampleRate();
    if (sampleRate <= 0 || openedStream->getChannelCount() != kOutputChannels ||
        openedStream->getFormat() != oboe::AudioFormat::Float) {
        openedStream->close();
        restartAllowed_.store(false, std::memory_order_release);
        return false;
    }

    {
        std::lock_guard producerLock(producerMutex_);
        releaseProducerStateLocked();
        const auto capacitySamples = static_cast<std::size_t>(sampleRate) *
            kRingSeconds * kOutputChannels;
        ringBuffer_ = std::make_unique<LockFreeRingBuffer<float>>(capacitySamples);
        rawScratch_.resize(kProducerChunkFrames * kOutputChannels);
        stereoScratch_.resize(kProducerChunkFrames * kOutputChannels);
        resampledScratch_.resize(kMaxResampledFrames * kOutputChannels);
    }

    oboeDsp_.configure(sampleRate);
    buildFadeCurve(sampleRate);
    underrunFadePosition_ = fadeInCurve_.size();
    recoveryFadePosition_ = fadeInCurve_.size();
    underrunActive_ = false;
    recoveryFading_ = false;
    underrunAnchor_.fill(0.0F);
    lastOutput_.fill(0.0F);

    outputSampleRate_.store(sampleRate, std::memory_order_release);
    activeRingBuffer_.store(ringBuffer_.get(), std::memory_order_release);
    stream_ = std::move(openedStream);
    if (stream_->requestStart() != oboe::Result::OK) {
        activeRingBuffer_.store(nullptr, std::memory_order_release);
        stream_->close();
        stream_.reset();
        outputSampleRate_.store(0, std::memory_order_release);
        std::lock_guard producerLock(producerMutex_);
        releaseProducerStateLocked();
        restartAllowed_.store(false, std::memory_order_release);
        return false;
    }
    return true;
}

void AudioEngine::stop() noexcept {
    std::lock_guard controlLock(controlMutex_);
    restartAllowed_.store(false, std::memory_order_release);
    activeRingBuffer_.store(nullptr, std::memory_order_release);
    if (stream_ != nullptr) {
        stream_->requestStop();
        stream_->close();
        stream_.reset();
    }
    outputSampleRate_.store(0, std::memory_order_release);
    std::lock_guard producerLock(producerMutex_);
    releaseProducerStateLocked();
}

std::size_t AudioEngine::writePcm(
    const void* pcm,
    std::size_t frameCount,
    PcmFormat format,
    std::int32_t inputSampleRate,
    std::int32_t inputChannelCount) {
    if (pcm == nullptr || frameCount == 0 || inputSampleRate <= 0 ||
        (inputChannelCount != 1 && inputChannelCount != kOutputChannels) ||
        PcmConverter::bytesPerSample(format) == 0) {
        return 0;
    }

    std::lock_guard producerLock(producerMutex_);
    auto* ring = activeRingBuffer_.load(std::memory_order_acquire);
    const std::int32_t destinationRate = outputSampleRate_.load(std::memory_order_acquire);
    if (ring == nullptr || destinationRate <= 0 ||
        !configureResamplerLocked(inputSampleRate)) {
        return 0;
    }

    const auto* inputBytes = static_cast<const std::uint8_t*>(pcm);
    const std::size_t bytesPerFrame = PcmConverter::bytesPerSample(format) *
        static_cast<std::size_t>(inputChannelCount);
    const double ratio = static_cast<double>(destinationRate) / inputSampleRate;
    const std::size_t ratioSafeChunk = std::max<std::size_t>(
        1,
        static_cast<std::size_t>((kMaxResampledFrames - 256) /
            std::max(1.0, ratio)));

    std::size_t consumedFrames = 0;
    while (consumedFrames < frameCount) {
        const std::size_t chunkFrames = std::min({
            frameCount - consumedFrames,
            kProducerChunkFrames,
            ratioSafeChunk,
        });
        const void* chunkInput = inputBytes + consumedFrames * bytesPerFrame;
        const std::size_t inputSamples = chunkFrames *
            static_cast<std::size_t>(inputChannelCount);
        if (!PcmConverter::toFloat(chunkInput, inputSamples, format, rawScratch_.data())) {
            break;
        }

        if (inputChannelCount == 1) {
            for (std::size_t frame = 0; frame < chunkFrames; ++frame) {
                stereoScratch_[frame * 2U] = rawScratch_[frame];
                stereoScratch_[frame * 2U + 1U] = rawScratch_[frame];
            }
        } else {
            std::memcpy(
                stereoScratch_.data(),
                rawScratch_.data(),
                chunkFrames * kOutputChannels * sizeof(float));
        }

        if (resampler_ == nullptr) {
            const std::size_t writtenSamples = ring->write(
                stereoScratch_.data(),
                chunkFrames * kOutputChannels);
            const std::size_t writtenFrames = writtenSamples / kOutputChannels;
            consumedFrames += writtenFrames;
            if (writtenFrames != chunkFrames) break;
            continue;
        }

        const std::size_t availableOutputFrames = std::min(
            kMaxResampledFrames,
            ring->availableToWrite() / kOutputChannels);
        if (availableOutputFrames == 0) break;

        std::size_t inputDone = 0;
        std::size_t outputDone = 0;
        const soxr_error_t error = soxr_process(
            asSoxr(resampler_),
            stereoScratch_.data(),
            chunkFrames,
            &inputDone,
            resampledScratch_.data(),
            availableOutputFrames,
            &outputDone);
        if (error != nullptr) break;

        const std::size_t writtenSamples = ring->write(
            resampledScratch_.data(),
            outputDone * kOutputChannels);
        if (writtenSamples != outputDone * kOutputChannels) break;
        consumedFrames += inputDone;
        if (inputDone == 0) break;
    }
    return consumedFrames;
}

void AudioEngine::flushResampler() {
    std::lock_guard producerLock(producerMutex_);
    auto* ring = activeRingBuffer_.load(std::memory_order_acquire);
    if (ring == nullptr || resampler_ == nullptr) return;
    resamplerFlushing_ = true;

    while (true) {
        const std::size_t outputCapacity = std::min(
            kMaxResampledFrames,
            ring->availableToWrite() / kOutputChannels);
        if (outputCapacity == 0) return;
        std::size_t outputDone = 0;
        const soxr_error_t error = soxr_process(
            asSoxr(resampler_),
            nullptr,
            0,
            nullptr,
            resampledScratch_.data(),
            outputCapacity,
            &outputDone);
        if (error != nullptr || outputDone == 0) {
            soxr_delete(asSoxr(resampler_));
            resampler_ = nullptr;
            resamplerInputRate_ = 0;
            resamplerFlushing_ = false;
            return;
        }
        if (ring->write(resampledScratch_.data(), outputDone * kOutputChannels) !=
            outputDone * kOutputChannels) {
            return;
        }
    }
}

void AudioEngine::setStudioMasterClarity(bool enabled) noexcept {
    oboeDsp_.setStudioMasterClarity(enabled);
    mediaDsp_.setStudioMasterClarity(enabled);
}

void AudioEngine::setVolumeBoost(bool enabled, std::int32_t percent) noexcept {
    oboeDsp_.setVolumeBoost(enabled, percent);
    mediaDsp_.setVolumeBoost(enabled, percent);
}

void AudioEngine::setEqualizer(
    bool enabled,
    const float* gainsDb,
    std::size_t gainCount) noexcept {
    oboeDsp_.setEqualizer(enabled, gainsDb, gainCount);
    mediaDsp_.setEqualizer(enabled, gainsDb, gainCount);
}

bool AudioEngine::configureMediaProcessor(
    std::int32_t inputSampleRate,
    std::int32_t outputSampleRate,
    std::int32_t channelCount) {
    if (inputSampleRate <= 0 || outputSampleRate <= 0 ||
        (channelCount != 1 && channelCount != 2)) {
        return false;
    }
    std::lock_guard processorLock(mediaProcessorMutex_);
    if (mediaResampler_ != nullptr) {
        soxr_delete(asSoxr(mediaResampler_));
        mediaResampler_ = nullptr;
    }
    mediaInputSampleRate_ = inputSampleRate;
    mediaOutputSampleRate_ = outputSampleRate;
    mediaChannelCount_ = channelCount;
    mediaScratch_.resize(kProducerChunkFrames * static_cast<std::size_t>(channelCount));
    // Resample first, then run tone/peak processing at the actual AudioTrack
    // rate so sinc overshoot is included in the final -1 dBFS protection.
    mediaDsp_.configure(outputSampleRate);
    return configureMediaResamplerLocked();
}

bool AudioEngine::processMediaPcm(
    const void* pcm,
    std::size_t frameCount,
    PcmFormat format,
    float* output,
    std::size_t outputCapacityFrames,
    std::size_t& outputFrameCount) {
    outputFrameCount = 0;
    if (pcm == nullptr || output == nullptr || frameCount == 0 ||
        outputCapacityFrames == 0) {
        return false;
    }
    std::lock_guard processorLock(mediaProcessorMutex_);
    if (mediaInputSampleRate_ <= 0 || mediaOutputSampleRate_ <= 0 ||
        mediaChannelCount_ <= 0 || mediaScratch_.empty()) {
        return false;
    }
    const std::size_t channels = static_cast<std::size_t>(mediaChannelCount_);
    const std::size_t bytesPerFrame = PcmConverter::bytesPerSample(format) * channels;
    if (bytesPerFrame == 0) return false;

    if (mediaResampler_ == nullptr && mediaInputSampleRate_ == mediaOutputSampleRate_) {
        if (outputCapacityFrames < frameCount ||
            !PcmConverter::toFloat(pcm, frameCount * channels, format, output)) {
            return false;
        }
        mediaDsp_.process(output, static_cast<std::int32_t>(frameCount), mediaChannelCount_);
        outputFrameCount = frameCount;
        return true;
    }
    if (mediaResampler_ == nullptr) return false;

    const auto* inputBytes = static_cast<const std::uint8_t*>(pcm);
    std::size_t consumedFrames = 0;
    while (consumedFrames < frameCount) {
        const std::size_t chunkFrames = std::min(
            frameCount - consumedFrames,
            kProducerChunkFrames);
        if (!PcmConverter::toFloat(
                inputBytes + consumedFrames * bytesPerFrame,
                chunkFrames * channels,
                format,
                mediaScratch_.data())) {
            return false;
        }
        std::size_t inputDone = 0;
        std::size_t outputDone = 0;
        const std::size_t remainingOutput = outputCapacityFrames - outputFrameCount;
        if (remainingOutput == 0) return false;
        const soxr_error_t error = soxr_process(
            asSoxr(mediaResampler_),
            mediaScratch_.data(),
            chunkFrames,
            &inputDone,
            output + outputFrameCount * channels,
            remainingOutput,
            &outputDone);
        if (error != nullptr || inputDone != chunkFrames) return false;
        mediaDsp_.process(
            output + outputFrameCount * channels,
            static_cast<std::int32_t>(outputDone),
            mediaChannelCount_);
        consumedFrames += inputDone;
        outputFrameCount += outputDone;
    }
    return true;
}

bool AudioEngine::flushMediaProcessor(
    float* output,
    std::size_t outputCapacityFrames,
    std::size_t& outputFrameCount) {
    outputFrameCount = 0;
    if (output == nullptr || outputCapacityFrames == 0) return false;
    std::lock_guard processorLock(mediaProcessorMutex_);
    if (mediaResampler_ == nullptr) return true;
    const std::size_t channels = static_cast<std::size_t>(mediaChannelCount_);
    while (outputFrameCount < outputCapacityFrames) {
        std::size_t outputDone = 0;
        const soxr_error_t error = soxr_process(
            asSoxr(mediaResampler_),
            nullptr,
            0,
            nullptr,
            output + outputFrameCount * channels,
            outputCapacityFrames - outputFrameCount,
            &outputDone);
        if (error != nullptr) return false;
        mediaDsp_.process(
            output + outputFrameCount * channels,
            static_cast<std::int32_t>(outputDone),
            mediaChannelCount_);
        outputFrameCount += outputDone;
        if (outputDone == 0) break;
    }
    return true;
}

void AudioEngine::resetMediaProcessor() {
    std::lock_guard processorLock(mediaProcessorMutex_);
    mediaDsp_.reset();
    if (mediaResampler_ != nullptr) {
        soxr_delete(asSoxr(mediaResampler_));
        mediaResampler_ = nullptr;
    }
    (void) configureMediaResamplerLocked();
}

bool AudioEngine::configureMediaResamplerLocked() {
    if (mediaInputSampleRate_ <= 0 || mediaOutputSampleRate_ <= 0 ||
        (mediaChannelCount_ != 1 && mediaChannelCount_ != 2)) {
        return false;
    }
    if (mediaInputSampleRate_ == mediaOutputSampleRate_) return true;
    soxr_error_t error = nullptr;
    const soxr_io_spec_t ioSpec = soxr_io_spec(SOXR_FLOAT32_I, SOXR_FLOAT32_I);
    // HQ is transparent for playback while avoiding the large CPU spikes of
    // VHQ on low-end ARM cores. VHQ's extra offline-grade precision is not
    // worth real-time underruns on mobile hardware.
    const soxr_quality_spec_t qualitySpec = soxr_quality_spec(SOXR_HQ, 0);
    const soxr_runtime_spec_t runtimeSpec = soxr_runtime_spec(1);
    mediaResampler_ = soxr_create(
        static_cast<double>(mediaInputSampleRate_),
        static_cast<double>(mediaOutputSampleRate_),
        static_cast<unsigned>(mediaChannelCount_),
        &error,
        &ioSpec,
        &qualitySpec,
        &runtimeSpec);
    if (error != nullptr || mediaResampler_ == nullptr) {
        if (mediaResampler_ != nullptr) soxr_delete(asSoxr(mediaResampler_));
        mediaResampler_ = nullptr;
        return false;
    }
    return true;
}

std::size_t AudioEngine::bufferedFrames() const noexcept {
    std::lock_guard producerLock(producerMutex_);
    const auto* ring = activeRingBuffer_.load(std::memory_order_acquire);
    return ring == nullptr ? 0 : ring->availableToRead() / kOutputChannels;
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
    oboe::AudioStream* /* audioStream */,
    void* audioData,
    std::int32_t numFrames) {
    auto* output = static_cast<float*>(audioData);
    auto* ring = activeRingBuffer_.load(std::memory_order_acquire);
    if (output == nullptr || numFrames <= 0) return oboe::DataCallbackResult::Continue;

    const std::size_t requestedFrames = static_cast<std::size_t>(numFrames);
    const std::size_t readSamples = ring == nullptr
        ? 0
        : ring->read(output, requestedFrames * kOutputChannels);
    const std::size_t validFrames = readSamples / kOutputChannels;
    const bool wasUnderrun = underrunActive_;

    if (validFrames > 0) {
        oboeDsp_.process(output, static_cast<std::int32_t>(validFrames));
        if (wasUnderrun) {
            recoveryFading_ = true;
            recoveryFadePosition_ = 0;
        }

        for (std::size_t frame = 0; frame < validFrames; ++frame) {
            float recoveryGain = 1.0F;
            if (recoveryFading_) {
                if (recoveryFadePosition_ < fadeInCurve_.size()) {
                    recoveryGain = fadeInCurve_[recoveryFadePosition_++];
                } else {
                    recoveryFading_ = false;
                }
            }
            const std::size_t offset = frame * kOutputChannels;
            output[offset] *= recoveryGain;
            output[offset + 1U] *= recoveryGain;
        }
        lastOutput_[0] = output[(validFrames - 1U) * kOutputChannels];
        lastOutput_[1] = output[(validFrames - 1U) * kOutputChannels + 1U];
    }

    if (validFrames < requestedFrames) {
        if (!wasUnderrun || validFrames > 0) {
            underrunAnchor_ = lastOutput_;
            underrunFadePosition_ = 0;
        }
        recoveryFading_ = false;
        for (std::size_t frame = validFrames; frame < requestedFrames; ++frame) {
            const float fadeOut = underrunFadePosition_ < fadeInCurve_.size()
                ? 1.0F - fadeInCurve_[underrunFadePosition_++]
                : 0.0F;
            const std::size_t offset = frame * kOutputChannels;
            output[offset] = underrunAnchor_[0] * fadeOut;
            output[offset + 1U] = underrunAnchor_[1] * fadeOut;
        }
        underrunActive_ = true;
    } else {
        underrunActive_ = false;
    }

    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(
    oboe::AudioStream* audioStream,
    oboe::Result /* error */) {
    if (!restartAllowed_.load(std::memory_order_acquire)) return;
    std::lock_guard controlLock(controlMutex_);
    if (!restartAllowed_.load(std::memory_order_acquire) ||
        stream_ == nullptr || stream_.get() != audioStream) {
        return;
    }

    activeRingBuffer_.store(nullptr, std::memory_order_release);
    stream_.reset();
    outputSampleRate_.store(0, std::memory_order_release);
    {
        std::lock_guard producerLock(producerMutex_);
        releaseProducerStateLocked();
    }
    try {
        (void) startLocked(requestedOutputSampleRate_);
    } catch (...) {
        // This callback runs on Oboe's recovery thread, outside a JNI frame.
        // Never allow an allocation failure during restart to terminate the
        // whole process.
        restartAllowed_.store(false, std::memory_order_release);
        activeRingBuffer_.store(nullptr, std::memory_order_release);
        outputSampleRate_.store(0, std::memory_order_release);
    }
}

void AudioEngine::releaseProducerStateLocked() noexcept {
    if (resampler_ != nullptr) {
        soxr_delete(asSoxr(resampler_));
        resampler_ = nullptr;
    }
    resamplerInputRate_ = 0;
    resamplerFlushing_ = false;
    ringBuffer_.reset();
    rawScratch_.clear();
    stereoScratch_.clear();
    resampledScratch_.clear();
}

bool AudioEngine::configureResamplerLocked(std::int32_t inputSampleRate) {
    const std::int32_t destinationRate = outputSampleRate_.load(std::memory_order_acquire);
    if (destinationRate <= 0) return false;
    // A NULL-input soxr flush is terminal. A new track/configuration gets a
    // fresh resampler even when its sample rate matches the previous track.
    if (resamplerFlushing_) {
        if (resampler_ != nullptr) soxr_delete(asSoxr(resampler_));
        resampler_ = nullptr;
        resamplerInputRate_ = 0;
        resamplerFlushing_ = false;
    }
    if (resamplerInputRate_ == inputSampleRate) return true;

    if (resampler_ != nullptr) {
        soxr_delete(asSoxr(resampler_));
        resampler_ = nullptr;
    }
    resamplerInputRate_ = inputSampleRate;
    if (inputSampleRate == destinationRate) return true;

    soxr_error_t error = nullptr;
    const soxr_io_spec_t ioSpec = soxr_io_spec(SOXR_FLOAT32_I, SOXR_FLOAT32_I);
    const soxr_quality_spec_t qualitySpec = soxr_quality_spec(SOXR_HQ, 0);
    const soxr_runtime_spec_t runtimeSpec = soxr_runtime_spec(1);
    resampler_ = soxr_create(
        static_cast<double>(inputSampleRate),
        static_cast<double>(destinationRate),
        kOutputChannels,
        &error,
        &ioSpec,
        &qualitySpec,
        &runtimeSpec);
    if (error != nullptr || resampler_ == nullptr) {
        if (resampler_ != nullptr) soxr_delete(asSoxr(resampler_));
        resampler_ = nullptr;
        resamplerInputRate_ = 0;
        return false;
    }
    return true;
}

void AudioEngine::buildFadeCurve(std::int32_t sampleRate) {
    const std::size_t fadeFrames = std::max<std::size_t>(
        1,
        static_cast<std::size_t>(std::llround(sampleRate * 0.002)));
    fadeInCurve_.resize(fadeFrames);
    for (std::size_t frame = 0; frame < fadeFrames; ++frame) {
        const double phase = kPi * static_cast<double>(frame + 1U) /
            static_cast<double>(fadeFrames);
        fadeInCurve_[frame] = static_cast<float>(0.5 * (1.0 - std::cos(phase)));
    }
}

}  // namespace lastwave::audio
