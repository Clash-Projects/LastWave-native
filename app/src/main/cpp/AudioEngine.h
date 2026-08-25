#pragma once

#include "DspProcessor.h"
#include "LockFreeRingBuffer.h"
#include "PcmConverter.h"

#include <oboe/Oboe.h>

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

namespace lastwave::audio {

class AudioEngine final : public oboe::AudioStreamDataCallback,
                          public oboe::AudioStreamErrorCallback {
public:
    AudioEngine() = default;
    ~AudioEngine() override;

    AudioEngine(const AudioEngine&) = delete;
    AudioEngine& operator=(const AudioEngine&) = delete;

    [[nodiscard]] bool start(std::int32_t preferredOutputSampleRate = 0);
    void stop() noexcept;

    // Producer-side PCM ingress. One producer thread may call this method.
    // Mono input is duplicated to stereo; stereo remains interleaved.
    std::size_t writePcm(
        const void* pcm,
        std::size_t frameCount,
        PcmFormat format,
        std::int32_t inputSampleRate,
        std::int32_t inputChannelCount);
    void flushResampler();

    void setStudioMasterClarity(bool enabled) noexcept;
    void setVolumeBoost(bool enabled, std::int32_t percent) noexcept;
    void setEqualizer(bool enabled, const float* gainsDb, std::size_t gainCount) noexcept;

    [[nodiscard]] bool configureMediaProcessor(
        std::int32_t inputSampleRate,
        std::int32_t outputSampleRate,
        std::int32_t channelCount);
    [[nodiscard]] bool processMediaPcm(
        const void* pcm,
        std::size_t frameCount,
        PcmFormat format,
        float* output,
        std::size_t outputCapacityFrames,
        std::size_t& outputFrameCount);
    [[nodiscard]] bool flushMediaProcessor(
        float* output,
        std::size_t outputCapacityFrames,
        std::size_t& outputFrameCount);
    void resetMediaProcessor();

    [[nodiscard]] std::int32_t outputSampleRate() const noexcept {
        return outputSampleRate_.load(std::memory_order_acquire);
    }
    [[nodiscard]] std::size_t bufferedFrames() const noexcept;

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* audioStream,
        void* audioData,
        std::int32_t numFrames) override;
    void onErrorAfterClose(
        oboe::AudioStream* audioStream,
        oboe::Result error) override;

private:
    [[nodiscard]] bool startLocked(std::int32_t preferredOutputSampleRate);
    void releaseProducerStateLocked() noexcept;
    [[nodiscard]] bool configureResamplerLocked(std::int32_t inputSampleRate);
    [[nodiscard]] bool configureMediaResamplerLocked();
    void buildFadeCurve(std::int32_t sampleRate);

    static constexpr std::int32_t kOutputChannels = 2;
    static constexpr std::int32_t kRingSeconds = 3;
    static constexpr std::size_t kProducerChunkFrames = 2048;
    static constexpr std::size_t kMaxResampledFrames = 32768;

    std::mutex controlMutex_;
    mutable std::mutex producerMutex_;
    std::mutex mediaProcessorMutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::unique_ptr<LockFreeRingBuffer<float>> ringBuffer_;
    std::atomic<LockFreeRingBuffer<float>*> activeRingBuffer_{nullptr};
    std::atomic<std::int32_t> outputSampleRate_{0};
    std::atomic<bool> restartAllowed_{false};
    std::int32_t requestedOutputSampleRate_{0};

    // libsoxr is kept opaque in the public header.
    void* resampler_{nullptr};
    std::int32_t resamplerInputRate_{0};
    bool resamplerFlushing_{false};
    std::vector<float> rawScratch_;
    std::vector<float> stereoScratch_;
    std::vector<float> resampledScratch_;

    DspProcessor oboeDsp_;
    DspProcessor mediaDsp_;
    void* mediaResampler_{nullptr};
    std::int32_t mediaInputSampleRate_{0};
    std::int32_t mediaOutputSampleRate_{0};
    std::int32_t mediaChannelCount_{0};
    std::vector<float> mediaScratch_;

    // Callback-only state. Storage is built before requestStart(), so the
    // real-time callback performs no allocation or locking.
    std::vector<float> fadeInCurve_;
    std::size_t underrunFadePosition_{0};
    std::size_t recoveryFadePosition_{0};
    bool underrunActive_{false};
    bool recoveryFading_{false};
    std::array<float, kOutputChannels> underrunAnchor_{};
    std::array<float, kOutputChannels> lastOutput_{};
};

}  // namespace lastwave::audio
