#pragma once

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>

namespace lastwave::audio {

// Single-producer/single-consumer ring. The producer publishes samples with a
// release store; the Oboe consumer observes them with an acquire load. Indices
// are monotonic so all capacity slots remain usable.
template <typename T>
class LockFreeRingBuffer final {
public:
    explicit LockFreeRingBuffer(std::size_t capacity)
        : capacity_(std::max<std::size_t>(capacity, 2)),
          data_(std::make_unique<T[]>(capacity_)) {}

    LockFreeRingBuffer(const LockFreeRingBuffer&) = delete;
    LockFreeRingBuffer& operator=(const LockFreeRingBuffer&) = delete;

    [[nodiscard]] std::size_t capacity() const noexcept { return capacity_; }

    [[nodiscard]] std::size_t availableToRead() const noexcept {
        const auto read = readIndex_.load(std::memory_order_relaxed);
        const auto write = writeIndex_.load(std::memory_order_acquire);
        return static_cast<std::size_t>(write - read);
    }

    [[nodiscard]] std::size_t availableToWrite() const noexcept {
        const auto write = writeIndex_.load(std::memory_order_relaxed);
        const auto read = readIndex_.load(std::memory_order_acquire);
        return capacity_ - static_cast<std::size_t>(write - read);
    }

    std::size_t write(const T* source, std::size_t count) noexcept {
        if (source == nullptr || count == 0) return 0;
        const auto write = writeIndex_.load(std::memory_order_relaxed);
        const auto read = readIndex_.load(std::memory_order_acquire);
        const auto writable = std::min(
            count,
            capacity_ - static_cast<std::size_t>(write - read));
        copyIntoRing(source, writable, static_cast<std::size_t>(write % capacity_));
        writeIndex_.store(write + writable, std::memory_order_release);
        return writable;
    }

    std::size_t read(T* destination, std::size_t count) noexcept {
        if (destination == nullptr || count == 0) return 0;
        const auto read = readIndex_.load(std::memory_order_relaxed);
        const auto write = writeIndex_.load(std::memory_order_acquire);
        const auto readable = std::min(count, static_cast<std::size_t>(write - read));
        copyFromRing(destination, readable, static_cast<std::size_t>(read % capacity_));
        readIndex_.store(read + readable, std::memory_order_release);
        return readable;
    }

private:
    void copyIntoRing(const T* source, std::size_t count, std::size_t offset) noexcept {
        const auto first = std::min(count, capacity_ - offset);
        std::memcpy(data_.get() + offset, source, first * sizeof(T));
        std::memcpy(data_.get(), source + first, (count - first) * sizeof(T));
    }

    void copyFromRing(T* destination, std::size_t count, std::size_t offset) noexcept {
        const auto first = std::min(count, capacity_ - offset);
        std::memcpy(destination, data_.get() + offset, first * sizeof(T));
        std::memcpy(destination + first, data_.get(), (count - first) * sizeof(T));
    }

    const std::size_t capacity_;
    std::unique_ptr<T[]> data_;
    alignas(64) std::atomic<std::uint64_t> writeIndex_{0};
    alignas(64) std::atomic<std::uint64_t> readIndex_{0};
};

}  // namespace lastwave::audio
