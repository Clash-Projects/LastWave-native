#include "AudioEngine.h"

#include <jni.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <limits>
#include <new>

namespace {

using lastwave::audio::AudioEngine;
using lastwave::audio::PcmFormat;

AudioEngine* fromHandle(jlong handle) noexcept {
    return reinterpret_cast<AudioEngine*>(static_cast<std::uintptr_t>(handle));
}

bool parseFormat(jint value, PcmFormat& format) noexcept {
    switch (value) {
        case 0: format = PcmFormat::Int16; return true;
        case 1: format = PcmFormat::PackedInt24; return true;
        case 2: format = PcmFormat::Int32; return true;
        case 3: format = PcmFormat::Float32; return true;
        default: return false;
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeCreate(
    JNIEnv*,
    jobject) {
    const auto pointer = reinterpret_cast<std::uintptr_t>(new (std::nothrow) AudioEngine());
    return static_cast<jlong>(pointer);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeDestroy(
    JNIEnv*,
    jobject,
    jlong handle) {
    delete fromHandle(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeStart(
    JNIEnv*,
    jobject,
    jlong handle,
    jint preferredOutputSampleRate) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return JNI_FALSE;
    try {
        return engine->start(preferredOutputSampleRate) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception&) {
        return JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeStop(
    JNIEnv*,
    jobject,
    jlong handle) {
    if (auto* engine = fromHandle(handle); engine != nullptr) engine->stop();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeIsRunning(
    JNIEnv*,
    jobject,
    jlong handle) {
    const auto* engine = fromHandle(handle);
    return (engine != nullptr && engine->isRunning()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeFlushOutput(
    JNIEnv*,
    jobject,
    jlong handle) {
    if (auto* engine = fromHandle(handle); engine != nullptr) engine->flushOutput();
}

extern "C" JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeSetPlaying(
    JNIEnv*,
    jobject,
    jlong handle,
    jboolean playing) {
    if (auto* engine = fromHandle(handle); engine != nullptr) {
        engine->setPlaying(playing == JNI_TRUE);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeSetOutputVolume(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat volume) {
    if (auto* engine = fromHandle(handle); engine != nullptr) {
        engine->setOutputVolume(volume);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeSetStudioMasterClarity(
    JNIEnv*,
    jobject,
    jlong handle,
    jboolean enabled) {
    if (auto* engine = fromHandle(handle); engine != nullptr) {
        engine->setStudioMasterClarity(enabled == JNI_TRUE);
    }
}



extern "C" JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeSetEqualizer(
    JNIEnv* env,
    jobject,
    jlong handle,
    jboolean enabled,
    jfloatArray gainsDb) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr || gainsDb == nullptr ||
        env->GetArrayLength(gainsDb) !=
            static_cast<jsize>(lastwave::audio::DspProcessor::kEqualizerBandCount)) {
        return;
    }
    std::array<float, lastwave::audio::DspProcessor::kEqualizerBandCount> gains{};
    env->GetFloatArrayRegion(gainsDb, 0, static_cast<jsize>(gains.size()), gains.data());
    if (env->ExceptionCheck()) return;
    engine->setEqualizer(enabled == JNI_TRUE, gains.data(), gains.size());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeConfigureMediaProcessor(
    JNIEnv*,
    jobject,
    jlong handle,
    jint inputSampleRate,
    jint outputSampleRate,
    jint channelCount) {
    if (auto* engine = fromHandle(handle); engine != nullptr) {
        try {
            return engine->configureMediaProcessor(
                inputSampleRate, outputSampleRate, channelCount) ? JNI_TRUE : JNI_FALSE;
        } catch (const std::exception&) {
            return JNI_FALSE;
        } catch (...) {
            return JNI_FALSE;
        }
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeProcessMediaPcm(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject inputBuffer,
    jint inputByteOffset,
    jobject outputBuffer,
    jint outputByteOffset,
    jint frameCount,
    jint encoding,
    jint channelCount) {
    auto* engine = fromHandle(handle);
    auto* input = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(inputBuffer));
    auto* output = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(outputBuffer));
    const jlong inputCapacity = env->GetDirectBufferCapacity(inputBuffer);
    const jlong outputCapacity = env->GetDirectBufferCapacity(outputBuffer);
    PcmFormat format{};
    if (engine == nullptr || input == nullptr || output == nullptr ||
        inputCapacity < 0 || outputCapacity < 0 || inputByteOffset < 0 ||
        outputByteOffset < 0 || frameCount <= 0 ||
        (channelCount != 1 && channelCount != 2) || !parseFormat(encoding, format)) {
        return -1;
    }
    if (static_cast<std::size_t>(outputByteOffset) % alignof(float) != 0U) {
        return -1;
    }

    const std::size_t frames = static_cast<std::size_t>(frameCount);
    const std::size_t channels = static_cast<std::size_t>(channelCount);
    const std::size_t inputBytesPerFrame =
        lastwave::audio::PcmConverter::bytesPerSample(format) * channels;
    const std::size_t outputBytesPerFrame = sizeof(float) * channels;
    if (frames > std::numeric_limits<std::size_t>::max() / inputBytesPerFrame ||
        frames > std::numeric_limits<std::size_t>::max() / outputBytesPerFrame) {
        return -1;
    }
    const std::size_t inputOffset = static_cast<std::size_t>(inputByteOffset);
    const std::size_t outputOffset = static_cast<std::size_t>(outputByteOffset);
    const std::size_t requiredInput = frames * inputBytesPerFrame;
    if (inputOffset > static_cast<std::size_t>(inputCapacity) ||
        requiredInput > static_cast<std::size_t>(inputCapacity) - inputOffset ||
        outputOffset > static_cast<std::size_t>(outputCapacity) ||
        static_cast<std::size_t>(outputCapacity) - outputOffset < outputBytesPerFrame) {
        return -1;
    }
    const std::size_t outputFrameCapacity =
        (static_cast<std::size_t>(outputCapacity) - outputOffset) / outputBytesPerFrame;
    std::size_t outputFrames = 0;
    if (!engine->processMediaPcm(
        input + inputOffset,
        frames,
        format,
        reinterpret_cast<float*>(output + outputOffset),
        outputFrameCapacity,
        outputFrames)) {
        return -1;
    }
    return outputFrames <= static_cast<std::size_t>(std::numeric_limits<jint>::max())
        ? static_cast<jint>(outputFrames)
        : -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeFlushMediaProcessor(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject outputBuffer,
    jint outputByteOffset,
    jint channelCount) {
    auto* engine = fromHandle(handle);
    auto* output = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(outputBuffer));
    const jlong outputCapacity = env->GetDirectBufferCapacity(outputBuffer);
    if (engine == nullptr || output == nullptr || outputCapacity < 0 ||
        outputByteOffset < 0 || (channelCount != 1 && channelCount != 2)) {
        return -1;
    }
    if (static_cast<std::size_t>(outputByteOffset) % alignof(float) != 0U) {
        return -1;
    }
    const std::size_t offset = static_cast<std::size_t>(outputByteOffset);
    const std::size_t bytesPerFrame = sizeof(float) * static_cast<std::size_t>(channelCount);
    if (offset > static_cast<std::size_t>(outputCapacity) ||
        static_cast<std::size_t>(outputCapacity) - offset < bytesPerFrame) {
        return -1;
    }
    const std::size_t frameCapacity =
        (static_cast<std::size_t>(outputCapacity) - offset) / bytesPerFrame;
    std::size_t outputFrames = 0;
    if (!engine->flushMediaProcessor(
            reinterpret_cast<float*>(output + offset),
            frameCapacity,
            outputFrames)) {
        return -1;
    }
    return outputFrames <= static_cast<std::size_t>(std::numeric_limits<jint>::max())
        ? static_cast<jint>(outputFrames)
        : -1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeResetMediaProcessor(
    JNIEnv*,
    jobject,
    jlong handle) {
    if (auto* engine = fromHandle(handle); engine != nullptr) {
        engine->resetMediaProcessor();
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeWritePcm(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject directBuffer,
    jint byteOffset,
    jint frameCount,
    jint encoding,
    jint inputSampleRate,
    jint inputChannelCount) {
    auto* engine = fromHandle(handle);
    auto* base = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(directBuffer));
    const jlong capacity = env->GetDirectBufferCapacity(directBuffer);
    PcmFormat format{};
    if (engine == nullptr || base == nullptr || capacity < 0 || byteOffset < 0 ||
        frameCount <= 0 || inputSampleRate <= 0 ||
        (inputChannelCount != 1 && inputChannelCount != 2) ||
        !parseFormat(encoding, format)) {
        return 0;
    }

    const std::size_t bytesPerFrame = lastwave::audio::PcmConverter::bytesPerSample(format) *
        static_cast<std::size_t>(inputChannelCount);
    const std::size_t frames = static_cast<std::size_t>(frameCount);
    if (bytesPerFrame == 0 || frames > std::numeric_limits<std::size_t>::max() / bytesPerFrame) {
        return 0;
    }
    const std::size_t requiredBytes = frames * bytesPerFrame;
    const std::size_t offset = static_cast<std::size_t>(byteOffset);
    if (offset > static_cast<std::size_t>(capacity) ||
        requiredBytes > static_cast<std::size_t>(capacity) - offset) {
        return 0;
    }

    const std::size_t consumed = engine->writePcm(
        base + offset,
        frames,
        format,
        inputSampleRate,
        inputChannelCount);
    return static_cast<jint>(std::min<std::size_t>(
        consumed,
        static_cast<std::size_t>(std::numeric_limits<jint>::max())));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeWriteProcessedPcm(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject directBuffer,
    jint byteOffset,
    jint frameCount,
    jint inputSampleRate,
    jint inputChannelCount) {
    auto* engine = fromHandle(handle);
    auto* base = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(directBuffer));
    const jlong capacity = env->GetDirectBufferCapacity(directBuffer);
    if (engine == nullptr || base == nullptr || capacity < 0 || byteOffset < 0 ||
        frameCount <= 0 || inputSampleRate <= 0 ||
        (inputChannelCount != 1 && inputChannelCount != 2)) {
        return 0;
    }

    const std::size_t offset = static_cast<std::size_t>(byteOffset);
    if (offset % alignof(float) != 0U) return 0;
    const std::size_t frames = static_cast<std::size_t>(frameCount);
    const std::size_t channels = static_cast<std::size_t>(inputChannelCount);
    if (frames > std::numeric_limits<std::size_t>::max() / channels / sizeof(float)) {
        return 0;
    }
    const std::size_t requiredBytes = frames * channels * sizeof(float);
    if (offset > static_cast<std::size_t>(capacity) ||
        requiredBytes > static_cast<std::size_t>(capacity) - offset) {
        return 0;
    }

    const std::size_t accepted = engine->writeProcessedPcm(
        reinterpret_cast<const float*>(base + offset),
        frames,
        inputSampleRate,
        inputChannelCount);
    return static_cast<jint>(std::min<std::size_t>(
        accepted,
        static_cast<std::size_t>(std::numeric_limits<jint>::max())));
}

extern "C" JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeFlushResampler(
    JNIEnv*,
    jobject,
    jlong handle) {
    if (auto* engine = fromHandle(handle); engine != nullptr) engine->flushResampler();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeOutputSampleRate(
    JNIEnv*,
    jobject,
    jlong handle) {
    const auto* engine = fromHandle(handle);
    return engine == nullptr ? 0 : engine->outputSampleRate();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeBufferedFrames(
    JNIEnv*,
    jobject,
    jlong handle) {
    const auto* engine = fromHandle(handle);
    return engine == nullptr ? 0 : static_cast<jlong>(engine->bufferedFrames());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeUnderrunCount(
    JNIEnv*,
    jobject,
    jlong handle) {
    const auto* engine = fromHandle(handle);
    return engine == nullptr ? 0 : static_cast<jlong>(engine->underrunCount());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeRenderedFrames(
    JNIEnv*,
    jobject,
    jlong handle) {
    const auto* engine = fromHandle(handle);
    return engine == nullptr ? 0 : static_cast<jlong>(engine->renderedFrames());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeRequestedSampleRate(
    JNIEnv*,
    jobject,
    jlong handle) {
    const auto* engine = fromHandle(handle);
    return engine == nullptr ? 0 : engine->requestedSampleRate();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeStreamOpenCount(
    JNIEnv*,
    jobject,
    jlong handle) {
    const auto* engine = fromHandle(handle);
    return engine == nullptr ? 0 : static_cast<jlong>(engine->streamOpenCount());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeStreamRestartCount(
    JNIEnv*,
    jobject,
    jlong handle) {
    const auto* engine = fromHandle(handle);
    return engine == nullptr ? 0 : static_cast<jlong>(engine->streamRestartCount());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lastwave_app_playback_NativeAudioEngine_nativeRateAdaptationCount(
    JNIEnv*,
    jobject,
    jlong handle) {
    const auto* engine = fromHandle(handle);
    return engine == nullptr ? 0 : static_cast<jlong>(engine->rateAdaptationCount());
}
