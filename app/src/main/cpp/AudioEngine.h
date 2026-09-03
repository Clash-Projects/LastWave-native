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
    // True while an Oboe stream is open and rendering. Allows callers to
    // reuse a healthy stream instead of reopening AAudio per track.
    [[nodiscard]] bool isRunning() const noexcept;
    // Drops all queued audio and resets callback-side fade/prebuffer state
    // without closing the stream. Used for seeks and track transitions so
    // OEM audio stacks never hear a teardown/reopen pop.
    void flushOutput() noexcept;

    // Producer-side PCM ingress. One producer thread may call this method.
    // Mono input is duplicated to stereo; stereo remains interleaved.
    std::size_t writePcm(
        const void* pcm,
        std::size_t frameCount,
        PcmFormat format,
        std::int32_t inputSampleRate,
        std::int32_t inputChannelCount);
    // Enqueues Float32 PCM that has already passed through mediaDsp_. The
    // sample rate must match the active Oboe stream, minimizing any additional
    // rate conversion after libsoxr.
    std::size_t writeProcessedPcm(
        const float* pcm,
        std::size_t frameCount,
        std::int32_t inputSampleRate,
        std::int32_t inputChannelCount);
    void flushResampler();
    void setPlaying(bool playing) noexcept;
    void setOutputVolume(float volume) noexcept;

    void setStudioMasterClarity(bool enabled) noexcept;
    void setBitPerfect(bool enabled) noexcept;
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
    // The sample rate that was actually requested for the current stream.
    // Zero means "let Android choose its native rate".
    [[nodiscard]] std::int32_t requestedSampleRate() const noexcept {
        return requestedOutputSampleRate_.load(std::memory_order_acquire);
    }
    [[nodiscard]] std::size_t bufferedFrames() const noexcept;
    [[nodiscard]] std::uint64_t underrunCount() const noexcept {
        return underrunCount_.load(std::memory_order_acquire);
    }
    [[nodiscard]] std::uint64_t renderedFrames() const noexcept {
        return renderedFrames_.load(std::memory_order_acquire);
    }
    // Diagnostics: how many streams were opened, how many were reopened after
    // a fatal stream error, and how often the device's accepted rate differed
    // from what the app asked for.
    [[nodiscard]] std::uint64_t streamOpenCount() const noexcept {
        return streamOpenCount_.load(std::memory_order_acquire);
    }
    [[nodiscard]] std::uint64_t streamRestartCount() const noexcept {
        return streamRestartCount_.load(std::memory_order_acquire);
    }
    [[nodiscard]] std::uint64_t rateAdaptationCount() const noexcept {
        return rateAdaptationCount_.load(std::memory_order_acquire);
    }

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
    static constexpr std::int32_t kRingMilliseconds = 750;
    // Device buffer target and prebuffer are deliberately medium-sized: music
    // stability under OEM throttling beats minimal latency.
    static constexpr std::int32_t kTargetDeviceBufferMilliseconds = 60;
    static constexpr std::int32_t kPrebufferMilliseconds = 120;
    static constexpr std::int32_t kPrebufferMaxCallbacks = 24;
    // Glitch-free playback time that removes one counted underrun episode.
    static constexpr std::int32_t kUnderrunDecaySeconds = 5;
    static constexpr std::size_t kProducerChunkFrames = 2048;
    static constexpr std::size_t kMaxResampledFrames = 32768;

    // Mutable so const probes (isRunning) can serialize against control
    // paths; locking a mutex never mutates its logical state.
    mutable std::mutex controlMutex_;
    mutable std::mutex producerMutex_;
    std::mutex mediaProcessorMutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::unique_ptr<LockFreeRingBuffer<float>> ringBuffer_;
    std::atomic<LockFreeRingBuffer<float>*> activeRingBuffer_{nullptr};
    std::atomic<std::int32_t> outputSampleRate_{0};
    std::atomic<bool> restartAllowed_{false};
    std::atomic<bool> playbackEnabled_{false};
    std::atomic<bool> inputAlreadyProcessed_{false};
    std::atomic<float> targetOutputVolume_{1.0F};
    std::atomic<std::uint64_t> renderedFrames_{0};
    std::atomic<std::uint64_t> underrunCount_{0};
    std::atomic<std::uint64_t> streamOpenCount_{0};
    std::atomic<std::uint64_t> streamRestartCount_{0};
    std::atomic<std::uint64_t> rateAdaptationCount_{0};
    std::atomic<std::int32_t> requestedOutputSampleRate_{0};

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
    bool prebuffering_{true};
    std::size_t prebufferFrames_{0};
    std::size_t prebufferBaseFrames_{0};
    std::int32_t prebufferWaitCallbacks_{kPrebufferMaxCallbacks};
    std::array<float, kOutputChannels> underrunAnchor_{};
    std::array<float, kOutputChannels> lastOutput_{};
    float currentOutputVolume_{1.0F};
    float outputVolumeRampStep_{1.0F};
    std::size_t cleanFrames_{0};
    std::size_t underrunDecayIntervalFrames_{0};
};

}  // namespace lastwave::audio
