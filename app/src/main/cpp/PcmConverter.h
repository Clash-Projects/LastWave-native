#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace lastwave::audio {

enum class PcmFormat : std::int32_t {
    Int16 = 0,
    PackedInt24 = 1,
    Int32 = 2,
    Float32 = 3,
};

class PcmConverter final {
public:
    [[nodiscard]] static constexpr std::size_t bytesPerSample(PcmFormat format) noexcept {
        switch (format) {
            case PcmFormat::Int16: return 2;
            case PcmFormat::PackedInt24: return 3;
            case PcmFormat::Int32:
            case PcmFormat::Float32: return 4;
        }
        return 0;
    }

    // Converts little-endian interleaved PCM to normalized Float32. The caller
    // owns the output storage; this function never allocates.
    [[nodiscard]] static bool toFloat(
        const void* input,
        std::size_t sampleCount,
        PcmFormat format,
        float* output) noexcept {
        if (input == nullptr || output == nullptr) return false;

        switch (format) {
            case PcmFormat::Int16: {
                constexpr float kScale16 = 1.0F / 32768.0F;
                if ((reinterpret_cast<std::uintptr_t>(input) & 1U) == 0U) {
                    const auto* samples16 = reinterpret_cast<const std::int16_t*>(input);
                    for (std::size_t i = 0; i < sampleCount; ++i) {
                        output[i] = static_cast<float>(samples16[i]) * kScale16;
                    }
                } else {
                    const auto* bytes = static_cast<const std::uint8_t*>(input);
                    for (std::size_t i = 0; i < sampleCount; ++i) {
                        const std::uint16_t raw = static_cast<std::uint16_t>(
                            static_cast<std::uint16_t>(bytes[0]) |
                            (static_cast<std::uint16_t>(bytes[1]) << 8U));
                        std::int16_t value;
                        std::memcpy(&value, &raw, sizeof(value));
                        output[i] = static_cast<float>(value) * kScale16;
                        bytes += 2;
                    }
                }
                return true;
            }

            case PcmFormat::PackedInt24: {
                constexpr float kScale24 = 1.0F / 8388608.0F;
                const auto* bytes = static_cast<const std::uint8_t*>(input);
                for (std::size_t i = 0; i < sampleCount; ++i) {
                    std::uint32_t raw = static_cast<std::uint32_t>(bytes[0]) |
                        (static_cast<std::uint32_t>(bytes[1]) << 8U) |
                        (static_cast<std::uint32_t>(bytes[2]) << 16U);
                    if ((raw & 0x00800000U) != 0U) raw |= 0xFF000000U;
                    std::int32_t value;
                    std::memcpy(&value, &raw, sizeof(value));
                    output[i] = static_cast<float>(value) * kScale24;
                    bytes += 3;
                }
                return true;
            }

            case PcmFormat::Int32: {
                constexpr float kScale32 = 1.0F / 2147483648.0F;
                if ((reinterpret_cast<std::uintptr_t>(input) & 3U) == 0U) {
                    const auto* samples32 = reinterpret_cast<const std::int32_t*>(input);
                    for (std::size_t i = 0; i < sampleCount; ++i) {
                        output[i] = static_cast<float>(samples32[i]) * kScale32;
                    }
                } else {
                    const auto* bytes = static_cast<const std::uint8_t*>(input);
                    for (std::size_t i = 0; i < sampleCount; ++i) {
                        const std::uint32_t raw = static_cast<std::uint32_t>(bytes[0]) |
                            (static_cast<std::uint32_t>(bytes[1]) << 8U) |
                            (static_cast<std::uint32_t>(bytes[2]) << 16U) |
                            (static_cast<std::uint32_t>(bytes[3]) << 24U);
                        std::int32_t value;
                        std::memcpy(&value, &raw, sizeof(value));
                        output[i] = static_cast<float>(value) * kScale32;
                        bytes += 4;
                    }
                }
                return true;
            }

            case PcmFormat::Float32: {
                if ((reinterpret_cast<std::uintptr_t>(input) & 3U) == 0U) {
                    const auto* inFloat = reinterpret_cast<const float*>(input);
                    if (inFloat == output) {
                        for (std::size_t i = 0; i < sampleCount; ++i) {
                            output[i] = std::isfinite(output[i])
                                ? std::clamp(output[i], -1.0F, 1.0F)
                                : 0.0F;
                        }
                    } else {
                        for (std::size_t i = 0; i < sampleCount; ++i) {
                            const float value = inFloat[i];
                            output[i] = std::isfinite(value)
                                ? std::clamp(value, -1.0F, 1.0F)
                                : 0.0F;
                        }
                    }
                } else {
                    const auto* bytes = static_cast<const std::uint8_t*>(input);
                    for (std::size_t i = 0; i < sampleCount; ++i) {
                        const std::uint32_t raw = static_cast<std::uint32_t>(bytes[0]) |
                            (static_cast<std::uint32_t>(bytes[1]) << 8U) |
                            (static_cast<std::uint32_t>(bytes[2]) << 16U) |
                            (static_cast<std::uint32_t>(bytes[3]) << 24U);
                        float value;
                        std::memcpy(&value, &raw, sizeof(value));
                        output[i] = std::isfinite(value)
                            ? std::clamp(value, -1.0F, 1.0F)
                            : 0.0F;
                        bytes += 4;
                    }
                }
                return true;
            }
        }
        return false;
    }
};

}  // namespace lastwave::audio
