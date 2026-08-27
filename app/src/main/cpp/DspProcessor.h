#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
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
    void setPeakProtectionEnabled(bool enabled) noexcept;
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
        static Biquad highShelf(
            double sampleRate,
            double frequency,
            double slope,
            double gainDb) noexcept;
        void setPeaking(
            double sampleRate,
            double frequency,
            double q,
            double gainDb) noexcept;

        [[nodiscard]] inline float tick(float input, std::size_t channel) noexcept {
            const double value = static_cast<double>(input);
            const double output = b0 * value + z1[channel];
            z1[channel] = b1 * value - a1 * output + z2[channel];
            z2[channel] = b2 * value - a2 * output;
            if (!std::isfinite(output) || !std::isfinite(z1[channel]) || !std::isfinite(z2[channel])) {
                z1[channel] = 0.0;
                z2[channel] = 0.0;
                return 0.0F;
            }
            return static_cast<float>(output);
        }

        [[nodiscard]] double magnitude(double sampleRate, double frequency) const noexcept;

        inline void clear() noexcept {
            z1.fill(0.0);
            z2.fill(0.0);
        }
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

        inline void process(float& left, float& right) noexcept {
            const double inputLeft = left;
            const double inputRight = right;
            low[0] = a0Low * inputLeft + b1Low * low[0];
            low[1] = a0Low * inputRight + b1Low * low[1];
            high[0] = a0High * inputLeft + a1High * previousInput[0] + b1High * high[0];
            high[1] = a0High * inputRight + a1High * previousInput[1] + b1High * high[1];
            previousInput[0] = inputLeft;
            previousInput[1] = inputRight;
            left = static_cast<float>((high[0] + low[1]) * gain);
            right = static_cast<float>((high[1] + low[0]) * gain);
        }

        inline void clear() noexcept {
            low.fill(0.0);
            high.fill(0.0);
            previousInput.fill(0.0);
        }
    };

    double sampleRate_{48000.0};
    float currentWet_{0.0F};
    float rampPerFrame_{1.0F / 2400.0F};
    std::atomic<bool> targetEnabled_{false};
    std::atomic<bool> peakProtectionEnabled_{false};
    std::atomic<bool> targetEqualizerEnabled_{false};
    std::atomic<std::uint32_t> targetEqualizerRevision_{0};
    std::array<std::atomic<float>, kEqualizerBandCount> targetEqGainsDb_{};
    std::array<float, kEqualizerBandCount> currentEqGainsDb_{};
    std::array<Biquad, kEqualizerBandCount> equalizerBands_{};
    std::int32_t equalizerUpdateCountdown_{0};
    std::int32_t equalizerHeadroomCountdown_{0};
    std::uint32_t appliedEqualizerRevision_{0};
    std::uint16_t activeEqualizerBands_{0};
    float currentPreampDb_{0.0F};
    float currentPreampGain_{1.0F};
    float equalizerMaximumBoostDb_{0.0F};
    float limiterGain_{1.0F};
    float equalizerGainSmoothing_{0.1F};
    float limiterRelease_{0.001F};
    // One-pole DC blocker (10 Hz). Removes stream DC offset so peaks keep the
    // full symmetric headroom; transparent for DC-free program material.
    float dcBlockerR_{0.999F};
    std::array<double, 2> dcXPrev_{};
    std::array<double, 2> dcYPrev_{};
    std::int32_t microFadeFrameCount_{96};
    std::int32_t microFadePosition_{0};
    bool clarityChainActive_{false};
    Biquad subBassHighPass_{};
    Biquad bassFoundation_{};
    Biquad lowMidSeparation_{};
    Biquad boxinessControl_{};
    Biquad presenceDetail_{};
    Biquad airDetail_{};
    Crossfeed crossfeed_{};
};

}  // namespace lastwave::audio
