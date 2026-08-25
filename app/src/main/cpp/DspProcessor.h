#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>

namespace lastwave::audio {

class DspProcessor final {
public:
    static constexpr std::size_t kEqualizerBandCount = 15;

    DspProcessor() noexcept;

    void configure(double sampleRate) noexcept;
    void reset() noexcept;
    void setStudioMasterClarity(bool enabled) noexcept;
    void setVolumeBoost(bool enabled, std::int32_t percent) noexcept;
    void setEqualizer(
        bool enabled,
        const float* gainsDb,
        std::size_t gainCount) noexcept;
    void process(
        float* interleaved,
        std::int32_t frameCount,
        std::int32_t channelCount = 2) noexcept;

    [[nodiscard]] bool isStudioMasterClarityEnabled() const noexcept {
        return targetEnabled_.load(std::memory_order_acquire);
    }

private:
    struct Biquad final {
        double b0{1.0};
        double b1{0.0};
        double b2{0.0};
        double a1{0.0};
        double a2{0.0};
        std::array<double, 2> z1{};
        std::array<double, 2> z2{};

        static Biquad highPass(double sampleRate, double frequency, double q) noexcept;
        static Biquad peaking(
            double sampleRate,
            double frequency,
            double q,
            double gainDb) noexcept;
        void setPeaking(
            double sampleRate,
            double frequency,
            double q,
            double gainDb) noexcept;
        float tick(float input, std::size_t channel) noexcept;
        [[nodiscard]] double magnitude(double sampleRate, double frequency) const noexcept;
        void clear() noexcept;
    };

    struct Crossfeed final {
        double a0Low{0.0};
        double b1Low{0.0};
        double a0High{1.0};
        double a1High{0.0};
        double b1High{0.0};
        double gain{1.0};
        std::array<double, 2> low{};
        std::array<double, 2> high{};
        std::array<double, 2> previousInput{};

        void configure(double sampleRate, double cutoffHz, double levelDb) noexcept;
        void process(float& left, float& right) noexcept;
        void clear() noexcept;
    };

    double sampleRate_{48000.0};
    float currentWet_{1.0F};
    float rampPerFrame_{1.0F / 2400.0F};
    std::atomic<bool> targetEnabled_{true};
    std::atomic<bool> targetEqualizerEnabled_{false};
    std::atomic<float> targetOutputGain_{1.0F};
    std::array<std::atomic<float>, kEqualizerBandCount> targetEqGainsDb_{};
    std::array<float, kEqualizerBandCount> currentEqGainsDb_{};
    std::array<Biquad, kEqualizerBandCount> equalizerBands_{};
    std::int32_t equalizerUpdateCountdown_{0};
    float currentPreampDb_{-1.0F};
    float currentPreampGain_{0.891250938F};
    float equalizerMaximumBoostDb_{0.0F};
    float limiterGain_{1.0F};
    float currentOutputGain_{1.0F};
    float outputGainSmoothing_{0.001F};
    std::int32_t microFadeFrameCount_{96};
    std::int32_t microFadePosition_{0};
    Biquad subBassHighPass_{};
    Biquad lowMidSeparation_{};
    Biquad airDetail_{};
    Crossfeed crossfeed_{};
};

}  // namespace lastwave::audio
