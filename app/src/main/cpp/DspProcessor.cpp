#include "DspProcessor.h"

#include <algorithm>
#include <cmath>

namespace lastwave::audio {
namespace {

constexpr double kPi = 3.1415926535897932384626433832795;
constexpr std::array<double, DspProcessor::kEqualizerBandCount> kEqFrequenciesHz{
    25.0, 40.0, 63.0, 100.0, 160.0,
    250.0, 400.0, 630.0, 1000.0, 1600.0,
    2500.0, 4000.0, 6300.0, 10000.0, 16000.0,
};
// Updating 15 biquads every 32 samples was needlessly expensive while a
// preset was smoothing. 128 samples is still below perceptual control
// latency, while headroom analysis can run much less frequently because the
// sample limiter covers the short transition between analyses.
constexpr std::int32_t kEqCoefficientIntervalFrames = 128;
constexpr std::int32_t kEqHeadroomIntervalFrames = 1024;
constexpr double kEqQ = 2.0;
// A -1 dBFS ceiling keeps useful true-peak/OEM headroom without cancelling the
// user-facing 100-150% gain control. The old -2 dBFS ceiling plus a permanent
// 2-3 dB preamp cut made both clarity and boost sound almost bypassed.
constexpr float kOutputCeiling = 0.891250938F;
// The clarity curve is deliberately level-matched within a fraction of a dB:
// enough makeup to replace energy removed from muddy low mids, not a loudness
// trick that makes "on" win only because it is louder.
constexpr float kClarityMakeupGain = 1.047128548F;  // +0.4 dB
constexpr float kClarityStereoWidth = 1.08F;

double safeFrequency(double sampleRate, double frequency) noexcept {
    return std::clamp(frequency, 1.0, sampleRate * 0.45);
}

}  // namespace

DspProcessor::DspProcessor() noexcept {
    for (auto& gain : targetEqGainsDb_) gain.store(0.0F, std::memory_order_relaxed);
    targetOutputGain_.store(1.0F, std::memory_order_relaxed);
}

void DspProcessor::configure(double sampleRate) noexcept {
    sampleRate_ = std::max(sampleRate, 8000.0);
    // A restrained mastering-style contour: remove only subsonic energy,
    // restore bass weight, open congested low mids, add vocal/instrument
    // presence and finish with a broad air shelf. It is intentionally stronger
    // than the old -0.8/+1.2 dB two-band curve, which was effectively inaudible.
    subBassHighPass_ = Biquad::highPass(sampleRate_, 22.0, 0.7071067811865476);
    bassFoundation_ = Biquad::peaking(sampleRate_, 88.0, 0.80, 0.7);
    lowMidSeparation_ = Biquad::peaking(sampleRate_, 285.0, 0.85, -1.6);
    presenceDetail_ = Biquad::peaking(sampleRate_, 3200.0, 0.78, 1.4);
    airDetail_ = Biquad::highShelf(sampleRate_, 9800.0, 0.80, 2.2);
    crossfeed_.configure(sampleRate_, 700.0, 4.5);
    rampPerFrame_ = static_cast<float>(1.0 / (sampleRate_ * 0.050));
    outputGainSmoothing_ = static_cast<float>(
        1.0 - std::exp(-1.0 / (sampleRate_ * 0.050)));
    equalizerGainSmoothing_ = static_cast<float>(1.0 - std::exp(
        -static_cast<double>(kEqCoefficientIntervalFrames) / (sampleRate_ * 0.010)));
    limiterRelease_ = static_cast<float>(
        1.0 - std::exp(-1.0 / (sampleRate_ * 0.150)));
    dcBlockerR_ = static_cast<float>(
        std::exp(-2.0 * kPi * 10.0 / sampleRate_));
    microFadeFrameCount_ = std::max(
        1,
        static_cast<std::int32_t>(std::llround(sampleRate_ * 0.002)));
    currentWet_ = targetEnabled_.load(std::memory_order_acquire) ? 1.0F : 0.0F;
    const bool equalizerEnabled = targetEqualizerEnabled_.load(std::memory_order_acquire);
    activeEqualizerBands_ = 0;
    for (std::size_t band = 0; band < kEqualizerBandCount; ++band) {
        currentEqGainsDb_[band] = equalizerEnabled
            ? targetEqGainsDb_[band].load(std::memory_order_acquire)
            : 0.0F;
        if (std::abs(currentEqGainsDb_[band]) >= 0.0005F) {
            equalizerBands_[band] = Biquad::peaking(
                sampleRate_, kEqFrequenciesHz[band], kEqQ, currentEqGainsDb_[band]);
            activeEqualizerBands_ |= static_cast<std::uint16_t>(1U << band);
        } else {
            equalizerBands_[band] = Biquad{};
        }
    }
    equalizerMaximumBoostDb_ = 0.0F;
    if (activeEqualizerBands_ != 0U) {
        for (std::size_t point = 0; point < kEqualizerBandCount * 2U - 1U; ++point) {
            const double frequency = point % 2U == 0U
                ? kEqFrequenciesHz[point / 2U]
                : std::sqrt(kEqFrequenciesHz[point / 2U] * kEqFrequenciesHz[point / 2U + 1U]);
            double magnitude = 1.0;
            for (const auto& band : equalizerBands_) {
                magnitude *= band.magnitude(sampleRate_, frequency);
            }
            equalizerMaximumBoostDb_ = std::max(
                equalizerMaximumBoostDb_,
                static_cast<float>(20.0 * std::log10(std::max(magnitude, 1.0e-12))));
        }
    }
    // Only explicit EQ boost reserves static headroom. Clarity and peak
    // protection use the linked limiter instead; permanently subtracting
    // 2-3 dB here was the main reason bypass sounded better.
    currentPreampDb_ = activeEqualizerBands_ != 0U
        ? -equalizerMaximumBoostDb_
        : 0.0F;
    currentPreampGain_ = std::pow(10.0F, currentPreampDb_ / 20.0F);
    equalizerUpdateCountdown_ = 0;
    equalizerHeadroomCountdown_ = 0;
    appliedEqualizerRevision_ = targetEqualizerRevision_.load(std::memory_order_acquire);
    limiterGain_ = 1.0F;
    currentOutputGain_ = targetOutputGain_.load(std::memory_order_acquire);
    clarityChainActive_ = currentWet_ > 0.0F;
    reset();
}

void DspProcessor::reset() noexcept {
    subBassHighPass_.clear();
    bassFoundation_.clear();
    lowMidSeparation_.clear();
    presenceDetail_.clear();
    airDetail_.clear();
    crossfeed_.clear();
    for (auto& band : equalizerBands_) band.clear();
    limiterGain_ = 1.0F;
    microFadePosition_ = 0;
    dcXPrev_.fill(0.0);
    dcYPrev_.fill(0.0);
}

void DspProcessor::setStudioMasterClarity(bool enabled) noexcept {
    targetEnabled_.store(enabled, std::memory_order_release);
}

void DspProcessor::setPeakProtectionEnabled(bool enabled) noexcept {
    peakProtectionEnabled_.store(enabled, std::memory_order_release);
}

void DspProcessor::setVolumeBoost(bool enabled, std::int32_t percent) noexcept {
    const float gain = enabled
        ? static_cast<float>(std::clamp(percent, 100, 150)) / 100.0F
        : 1.0F;
    targetOutputGain_.store(gain, std::memory_order_release);
}

void DspProcessor::setEqualizer(
    bool enabled,
    const float* gainsDb,
    std::size_t gainCount) noexcept {
    if (gainsDb != nullptr && gainCount == kEqualizerBandCount) {
        for (std::size_t band = 0; band < kEqualizerBandCount; ++band) {
            targetEqGainsDb_[band].store(
                std::clamp(gainsDb[band], -8.0F, 8.0F),
                std::memory_order_release);
        }
    }
    targetEqualizerEnabled_.store(enabled, std::memory_order_release);
    targetEqualizerRevision_.fetch_add(1, std::memory_order_release);
}

void DspProcessor::process(
    float* samples,
    std::int32_t frameCount,
    std::int32_t channelCount) noexcept {
    if (samples == nullptr || frameCount <= 0 || (channelCount != 1 && channelCount != 2)) return;
    const float target = targetEnabled_.load(std::memory_order_acquire) ? 1.0F : 0.0F;
    const bool peakProtectionEnabled = peakProtectionEnabled_.load(std::memory_order_acquire);
    const float targetOutputGain = targetOutputGain_.load(std::memory_order_acquire);
    const auto equalizerRevision = targetEqualizerRevision_.load(std::memory_order_acquire);
    if (equalizerRevision != appliedEqualizerRevision_) {
        appliedEqualizerRevision_ = equalizerRevision;
        equalizerUpdateCountdown_ = 0;
        equalizerHeadroomCountdown_ = 0;
    }
    if (target > 0.0F) clarityChainActive_ = true;

    bool targetEqualizerHasGain = false;
    if (targetEqualizerEnabled_.load(std::memory_order_acquire)) {
        for (const auto& gain : targetEqGainsDb_) {
            if (std::abs(gain.load(std::memory_order_acquire)) >= 0.0005F) {
                targetEqualizerHasGain = true;
                break;
            }
        }
    }
    const bool controlsBypassed = target == 0.0F &&
        !targetEqualizerHasGain &&
        !peakProtectionEnabled &&
        std::abs(targetOutputGain - 1.0F) < 0.00001F;
    const bool stateBypassed = currentWet_ == 0.0F &&
        activeEqualizerBands_ == 0U &&
        std::abs(currentPreampGain_ - 1.0F) < 0.00001F &&
        std::abs(currentOutputGain_ - 1.0F) < 0.00001F &&
        std::abs(limiterGain_ - 1.0F) < 0.00001F;
    // With every enhancement disabled, decoded PCM stays transparent. This
    // avoids the old unconditional -1 dB attenuation and limiter/clamp pass.
    if (controlsBypassed && stateBypassed) return;

    for (std::int32_t frame = 0; frame < frameCount; ++frame) {
        if (currentWet_ < target) {
            currentWet_ = std::min(target, currentWet_ + rampPerFrame_);
        } else if (currentWet_ > target) {
            currentWet_ = std::max(target, currentWet_ - rampPerFrame_);
        }

        if (equalizerUpdateCountdown_-- <= 0) {
            const bool eqEnabled = targetEqualizerEnabled_.load(std::memory_order_acquire);
            bool coefficientsChanged = false;
            std::uint16_t nextActiveBands = 0;
            if (eqEnabled || activeEqualizerBands_ != 0U) {
                for (std::size_t band = 0; band < kEqualizerBandCount; ++band) {
                    const float targetGain = eqEnabled
                        ? targetEqGainsDb_[band].load(std::memory_order_acquire)
                        : 0.0F;
                    const float previousGain = currentEqGainsDb_[band];
                    currentEqGainsDb_[band] +=
                        (targetGain - currentEqGainsDb_[band]) * equalizerGainSmoothing_;
                    if (std::abs(targetGain - currentEqGainsDb_[band]) < 0.0005F) {
                        currentEqGainsDb_[band] = targetGain;
                    }
                    const bool bandChanged =
                        std::abs(previousGain - currentEqGainsDb_[band]) > 0.000001F;
                    coefficientsChanged = coefficientsChanged || bandChanged;
                    if (bandChanged) {
                        equalizerBands_[band].setPeaking(
                            sampleRate_, kEqFrequenciesHz[band], kEqQ, currentEqGainsDb_[band]);
                    }
                    if (std::abs(currentEqGainsDb_[band]) >= 0.0005F ||
                        std::abs(targetGain) >= 0.0005F) {
                        nextActiveBands |= static_cast<std::uint16_t>(1U << band);
                    }
                }
            }
            const auto deactivatedBands = static_cast<std::uint16_t>(
                activeEqualizerBands_ & static_cast<std::uint16_t>(~nextActiveBands));
            for (std::size_t band = 0; band < kEqualizerBandCount; ++band) {
                if ((deactivatedBands & static_cast<std::uint16_t>(1U << band)) != 0U) {
                    equalizerBands_[band].clear();
                }
            }
            activeEqualizerBands_ = nextActiveBands;
            if (activeEqualizerBands_ == 0U) {
                equalizerMaximumBoostDb_ = 0.0F;
                equalizerHeadroomCountdown_ = 0;
            } else if (coefficientsChanged && equalizerHeadroomCountdown_ <= 0) {
                equalizerMaximumBoostDb_ = 0.0F;
                for (std::size_t point = 0; point < kEqualizerBandCount * 2U - 1U; ++point) {
                    const double frequency = point % 2U == 0U
                        ? kEqFrequenciesHz[point / 2U]
                        : std::sqrt(
                            kEqFrequenciesHz[point / 2U] *
                            kEqFrequenciesHz[point / 2U + 1U]);
                    double magnitude = 1.0;
                    for (const auto& band : equalizerBands_) {
                        magnitude *= band.magnitude(sampleRate_, frequency);
                    }
                    equalizerMaximumBoostDb_ = std::max(
                        equalizerMaximumBoostDb_,
                        static_cast<float>(20.0 * std::log10(std::max(magnitude, 1.0e-12))));
                }
                equalizerHeadroomCountdown_ = kEqHeadroomIntervalFrames;
            } else {
                equalizerHeadroomCountdown_ -= kEqCoefficientIntervalFrames;
            }
            const float targetPreampDb = activeEqualizerBands_ != 0U
                ? -equalizerMaximumBoostDb_
                : 0.0F;
            const float preampDelta = targetPreampDb - currentPreampDb_;
            // pow() is relatively expensive on 32-bit ARM. Once the smooth
            // transition has converged, retain the exact gain instead of
            // recomputing the same value hundreds of times per second.
            if (preampDelta != 0.0F) {
                currentPreampDb_ = std::abs(preampDelta) < 0.00001F
                    ? targetPreampDb
                    : currentPreampDb_ + preampDelta * equalizerGainSmoothing_;
                currentPreampGain_ = std::pow(10.0F, currentPreampDb_ / 20.0F);
            }
            equalizerUpdateCountdown_ = kEqCoefficientIntervalFrames - 1;
        }

        const std::size_t offset = static_cast<std::size_t>(frame) *
            static_cast<std::size_t>(channelCount);
        // One-pole DC blocker ahead of all gain staging: a stream DC offset
        // asymmetrically consumes peak headroom and clicks at boundaries.
        const double dcInputLeft = samples[offset];
        const double dcOutputLeft =
            dcInputLeft - dcXPrev_[0] + dcBlockerR_ * dcYPrev_[0];
        dcXPrev_[0] = dcInputLeft;
        dcYPrev_[0] = dcOutputLeft;
        float equalizedLeft = static_cast<float>(dcOutputLeft) * currentPreampGain_;
        float equalizedRight;
        if (channelCount == 2) {
            const double dcInputRight = samples[offset + 1U];
            const double dcOutputRight =
                dcInputRight - dcXPrev_[1] + dcBlockerR_ * dcYPrev_[1];
            dcXPrev_[1] = dcInputRight;
            dcYPrev_[1] = dcOutputRight;
            equalizedRight = static_cast<float>(dcOutputRight) * currentPreampGain_;
        } else {
            equalizedRight = equalizedLeft;
        }
        if (activeEqualizerBands_ != 0U) {
            for (std::size_t band = 0; band < kEqualizerBandCount; ++band) {
                if ((activeEqualizerBands_ & static_cast<std::uint16_t>(1U << band)) == 0U) {
                    continue;
                }
                equalizedLeft = equalizerBands_[band].tick(equalizedLeft, 0);
                if (channelCount == 2) {
                    equalizedRight = equalizerBands_[band].tick(equalizedRight, 1);
                }
            }
            if (channelCount == 1) equalizedRight = equalizedLeft;
        }

        const float dryLeft = equalizedLeft;
        const float dryRight = equalizedRight;

        float outputLeft = dryLeft;
        float outputRight = dryRight;
        if (clarityChainActive_) {
            float wetLeft = subBassHighPass_.tick(dryLeft, 0);
            float wetRight = channelCount == 2
                ? subBassHighPass_.tick(dryRight, 1)
                : wetLeft;
            wetLeft = bassFoundation_.tick(wetLeft, 0);
            wetRight = channelCount == 2
                ? bassFoundation_.tick(wetRight, 1)
                : wetLeft;
            wetLeft = lowMidSeparation_.tick(wetLeft, 0);
            wetRight = channelCount == 2
                ? lowMidSeparation_.tick(wetRight, 1)
                : wetLeft;
            wetLeft = presenceDetail_.tick(wetLeft, 0);
            wetRight = channelCount == 2
                ? presenceDetail_.tick(wetRight, 1)
                : wetLeft;
            wetLeft = airDetail_.tick(wetLeft, 0);
            wetRight = channelCount == 2
                ? airDetail_.tick(wetRight, 1)
                : wetLeft;
            if (channelCount == 2) {
                const float mid = (wetLeft + wetRight) * 0.5F;
                const float side = (wetLeft - wetRight) * 0.5F * kClarityStereoWidth;
                wetLeft = mid + side;
                wetRight = mid - side;
            }
            wetLeft *= kClarityMakeupGain;
            wetRight *= kClarityMakeupGain;
            // Headphone crossfeed is intentionally not applied globally: on
            // phone speakers and some OEM spatializers it can create phasey,
            // device-dependent coloration that listeners report as distortion.
            outputLeft += (wetLeft - dryLeft) * currentWet_;
            outputRight += (wetRight - dryRight) * currentWet_;
        }

        currentOutputGain_ +=
            (targetOutputGain - currentOutputGain_) * outputGainSmoothing_;
        if (std::abs(targetOutputGain - currentOutputGain_) < 0.00001F) {
            currentOutputGain_ = targetOutputGain;
        }
        const float peak = std::max(std::abs(outputLeft), std::abs(outputRight)) *
            currentOutputGain_;
        const float requiredLimiterGain = peak > kOutputCeiling
            ? kOutputCeiling / peak
            : 1.0F;
        if (requiredLimiterGain < limiterGain_) {
            // This is a zero-lookahead real-time path, so peak attenuation must
            // engage immediately. The old slow attack missed the peak and the
            // final clamp did the real work, producing grit instead of clean
            // gain. Release remains smooth and stereo-linked below.
            limiterGain_ = requiredLimiterGain;
        } else {
            limiterGain_ += (1.0F - limiterGain_) * limiterRelease_;
            if (1.0F - limiterGain_ < 0.00001F) limiterGain_ = 1.0F;
        }
        float finalGain = currentOutputGain_ * limiterGain_;
        if (microFadePosition_ < microFadeFrameCount_) {
            const double phase = kPi * static_cast<double>(microFadePosition_ + 1) /
                static_cast<double>(microFadeFrameCount_);
            finalGain *= static_cast<float>(0.5 * (1.0 - std::cos(phase)));
            ++microFadePosition_;
        }
        outputLeft *= finalGain;
        outputRight *= finalGain;
        outputLeft = std::isfinite(outputLeft)
            ? std::clamp(outputLeft, -kOutputCeiling, kOutputCeiling)
            : 0.0F;
        outputRight = std::isfinite(outputRight)
            ? std::clamp(outputRight, -kOutputCeiling, kOutputCeiling)
            : 0.0F;
        samples[offset] = outputLeft;
        if (channelCount == 2) samples[offset + 1U] = outputRight;
    }

    if (target == 0.0F && currentWet_ == 0.0F && clarityChainActive_) {
        // Once bypass has fully crossfaded, stop burning CPU on a result that
        // is multiplied by zero. The next enable starts from clean state.
        subBassHighPass_.clear();
        bassFoundation_.clear();
        lowMidSeparation_.clear();
        presenceDetail_.clear();
        airDetail_.clear();
        crossfeed_.clear();
        clarityChainActive_ = false;
    }
}

DspProcessor::Biquad DspProcessor::Biquad::highPass(
    double sampleRate,
    double frequency,
    double q) noexcept {
    Biquad filter;
    const double omega = 2.0 * kPi * safeFrequency(sampleRate, frequency) / sampleRate;
    const double cosine = std::cos(omega);
    const double alpha = std::sin(omega) / (2.0 * std::max(q, 0.01));
    const double a0 = 1.0 + alpha;
    filter.b0 = ((1.0 + cosine) * 0.5) / a0;
    filter.b1 = -(1.0 + cosine) / a0;
    filter.b2 = filter.b0;
    filter.a1 = (-2.0 * cosine) / a0;
    filter.a2 = (1.0 - alpha) / a0;
    return filter;
}

DspProcessor::Biquad DspProcessor::Biquad::peaking(
    double sampleRate,
    double frequency,
    double q,
    double gainDb) noexcept {
    Biquad filter;
    const double omega = 2.0 * kPi * safeFrequency(sampleRate, frequency) / sampleRate;
    const double cosine = std::cos(omega);
    const double alpha = std::sin(omega) / (2.0 * std::max(q, 0.01));
    const double amplitude = std::pow(10.0, gainDb / 40.0);
    const double a0 = 1.0 + alpha / amplitude;
    filter.b0 = (1.0 + alpha * amplitude) / a0;
    filter.b1 = (-2.0 * cosine) / a0;
    filter.b2 = (1.0 - alpha * amplitude) / a0;
    filter.a1 = filter.b1;
    filter.a2 = (1.0 - alpha / amplitude) / a0;
    return filter;
}

DspProcessor::Biquad DspProcessor::Biquad::highShelf(
    double sampleRate,
    double frequency,
    double slope,
    double gainDb) noexcept {
    Biquad filter;
    const double omega = 2.0 * kPi * safeFrequency(sampleRate, frequency) / sampleRate;
    const double cosine = std::cos(omega);
    const double sine = std::sin(omega);
    const double amplitude = std::pow(10.0, gainDb / 40.0);
    const double safeSlope = std::clamp(slope, 0.1, 1.0);
    const double alpha = (sine * 0.5) * std::sqrt(
        (amplitude + 1.0 / amplitude) * (1.0 / safeSlope - 1.0) + 2.0);
    const double beta = 2.0 * std::sqrt(amplitude) * alpha;
    const double a0 =
        (amplitude + 1.0) - (amplitude - 1.0) * cosine + beta;
    filter.b0 = amplitude *
        ((amplitude + 1.0) + (amplitude - 1.0) * cosine + beta) / a0;
    filter.b1 = -2.0 * amplitude *
        ((amplitude - 1.0) + (amplitude + 1.0) * cosine) / a0;
    filter.b2 = amplitude *
        ((amplitude + 1.0) + (amplitude - 1.0) * cosine - beta) / a0;
    filter.a1 = 2.0 *
        ((amplitude - 1.0) - (amplitude + 1.0) * cosine) / a0;
    filter.a2 =
        ((amplitude + 1.0) - (amplitude - 1.0) * cosine - beta) / a0;
    return filter;
}

void DspProcessor::Biquad::setPeaking(
    double sampleRate,
    double frequency,
    double q,
    double gainDb) noexcept {
    const Biquad coefficients = peaking(sampleRate, frequency, q, gainDb);
    b0 = coefficients.b0;
    b1 = coefficients.b1;
    b2 = coefficients.b2;
    a1 = coefficients.a1;
    a2 = coefficients.a2;
}

float DspProcessor::Biquad::tick(float input, std::size_t channel) noexcept {
    if (!std::isfinite(input)) {
        z1[channel] = 0.0;
        z2[channel] = 0.0;
        return 0.0F;
    }
    const double value = static_cast<double>(input);
    const double output = b0 * value + z1[channel];
    z1[channel] = b1 * value - a1 * output + z2[channel];
    z2[channel] = b2 * value - a2 * output;
    if (!std::isfinite(output) || !std::isfinite(z1[channel]) || !std::isfinite(z2[channel])) {
        z1[channel] = 0.0;
        z2[channel] = 0.0;
        return 0.0F;
    }
    if (std::abs(z1[channel]) < 1.0e-30) z1[channel] = 0.0;
    if (std::abs(z2[channel]) < 1.0e-30) z2[channel] = 0.0;
    return static_cast<float>(output);
}

double DspProcessor::Biquad::magnitude(double sampleRate, double frequency) const noexcept {
    const double omega = 2.0 * kPi * safeFrequency(sampleRate, frequency) / sampleRate;
    const double cosine = std::cos(omega);
    const double sine = std::sin(omega);
    const double cosine2 = std::cos(2.0 * omega);
    const double sine2 = std::sin(2.0 * omega);
    const double numeratorReal = b0 + b1 * cosine + b2 * cosine2;
    const double numeratorImaginary = -b1 * sine - b2 * sine2;
    const double denominatorReal = 1.0 + a1 * cosine + a2 * cosine2;
    const double denominatorImaginary = -a1 * sine - a2 * sine2;
    const double numeratorPower = numeratorReal * numeratorReal +
        numeratorImaginary * numeratorImaginary;
    const double denominatorPower = denominatorReal * denominatorReal +
        denominatorImaginary * denominatorImaginary;
    return std::sqrt(numeratorPower / std::max(denominatorPower, 1.0e-24));
}

void DspProcessor::Biquad::clear() noexcept {
    z1.fill(0.0);
    z2.fill(0.0);
}

void DspProcessor::Crossfeed::configure(
    double sampleRate,
    double cutoffHz,
    double levelDb) noexcept {
    // Reference BS2B topology: complementary single-pole low-pass crossfeed
    // and high-boost direct path. 700 Hz / 4.5 dB is Bauer's default profile.
    const double frequencyLow = safeFrequency(sampleRate, cutoffHz);
    const double feedLevel = std::abs(levelDb);
    const double gainDbLow = feedLevel * (-5.0 / 6.0) - 3.0;
    const double gainDbHigh = feedLevel / 6.0 - 3.0;
    const double gainLow = std::pow(10.0, gainDbLow / 20.0);
    const double gainHigh = 1.0 - std::pow(10.0, gainDbHigh / 20.0);
    const double frequencyHigh = frequencyLow * std::pow(
        2.0,
        (gainDbLow - 20.0 * std::log10(gainHigh)) / 12.0);

    const double xLow = std::exp(-2.0 * kPi * frequencyLow / sampleRate);
    b1Low = xLow;
    a0Low = gainLow * (1.0 - xLow);

    const double xHigh = std::exp(
        -2.0 * kPi * safeFrequency(sampleRate, frequencyHigh) / sampleRate);
    b1High = xHigh;
    a0High = 1.0 - gainHigh * (1.0 - xHigh);
    a1High = -xHigh;
    gain = 1.0 / (1.0 - gainHigh + gainLow);
    clear();
}

void DspProcessor::Crossfeed::process(float& left, float& right) noexcept {
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

void DspProcessor::Crossfeed::clear() noexcept {
    low.fill(0.0);
    high.fill(0.0);
    previousInput.fill(0.0);
}

}  // namespace lastwave::audio
