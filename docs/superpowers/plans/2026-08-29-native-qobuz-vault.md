# Native C++ Obfuscated Vault Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Secure the Qobuz and lyrics API keys by moving them from plaintext Java/DEX `BuildConfig` into a compiled C++ native vault with XOR byte-masking and JNI package verification.

**Architecture:** Build-time Gradle script generates a C++ header containing an XOR-masked byte schedule. `NativeBridge.cpp` validates the caller's Android `Context` package name and unmasks the key into an ephemeral stack buffer. `QobuzMusicApi.kt` accesses the key through JNI with zero plaintext strings in `classes.dex`.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, Android NDK, C++17, JNI, CMake.

## Global Constraints
- No plaintext keys in `BuildConfig.java` or `classes.dex`.
- Native code verifies package name matches `"com.lastwave.app"` before returning secret.
- C++ securely zeroes out temporary buffers after string construction.
- Do not mention 16kb ELF in commit messages.

---

### Task 1: Build-Time XOR Masking Generator in Gradle

**Files:**
- Modify: `app/build.gradle.kts`
- Create (Generated): `app/src/main/cpp/GeneratedSecrets.h`

**Interfaces:**
- Produces: `GeneratedSecrets.h` with `QOBUZ_MASKED_BYTES`, `QOBUZ_XOR_KEY`, and `QOBUZ_KEY_LEN`.

- [ ] **Step 1: Add Gradle task to generate C++ header with XOR-masked keys**

In `app/build.gradle.kts`:
Remove:
```kotlin
buildConfigField("String", "QOBUZ_API_KEY", "\"$qobuzApiKey\"")
buildConfigField("String", "LYRICS_API_KEY", "\"$lyricsApiKey\"")
```
Add a Gradle helper function and pre-build task:
```kotlin
val generateNativeSecrets = tasks.register("generateNativeSecrets") {
    val qobuzKey = resolveSecret("QOBUZ_API_KEY", "QOBUZ_AUTH_KEY", "API_AUTH_KEY")
    val lyricsKey = resolveSecret("LYRICS_API_KEY", "API_KEY", "LYRICS_AUTH_TOKEN")
    val outputDir = file("src/main/cpp")
    val outputFile = file("src/main/cpp/GeneratedSecrets.h")

    inputs.property("qobuzKeyHash", qobuzKey.hashCode())
    inputs.property("lyricsKeyHash", lyricsKey.hashCode())
    outputs.file(outputFile)

    doLast {
        fun maskBytes(raw: String, xorKey: Byte): String {
            val bytes = raw.toByteArray(Charsets.UTF_8)
            return bytes.map { (it.toInt() xor xorKey.toInt()).toByte() }
                .joinToString(", ") { "0x%02X".format(it) }
        }
        val qobuzXor: Byte = 0x5A
        val lyricsXor: Byte = 0x3C
        val headerContent = """
            // Auto-generated native secrets header. DO NOT EDIT.
            #ifndef GENERATED_SECRETS_H
            #define GENERATED_SECRETS_H

            #include <cstdint>
            #include <cstddef>

            static const uint8_t QOBUZ_MASKED_BYTES[] = { ${maskBytes(qobuzKey, qobuzXor)} };
            static const uint8_t QOBUZ_XOR_KEY = 0x5A;
            static const size_t QOBUZ_KEY_LEN = ${qobuzKey.toByteArray(Charsets.UTF_8).size};

            static const uint8_t LYRICS_MASKED_BYTES[] = { ${maskBytes(lyricsKey, lyricsXor)} };
            static const uint8_t LYRICS_XOR_KEY = 0x3C;
            static const size_t LYRICS_KEY_LEN = ${lyricsKey.toByteArray(Charsets.UTF_8).size};

            #endif // GENERATED_SECRETS_H
        """.trimIndent()

        outputFile.writeText(headerContent)
    }
}

tasks.named("preBuild") {
    dependsOn(generateNativeSecrets)
}
```

- [ ] **Step 2: Run the Gradle task to verify header generation**

Run: `./gradlew generateNativeSecrets`
Expected: `app/src/main/cpp/GeneratedSecrets.h` is generated with byte arrays.

- [ ] **Step 3: Commit Task 1**

```bash
git add app/build.gradle.kts app/src/main/cpp/GeneratedSecrets.h
git commit -m "feat(security): add build-time XOR secret masking generator and remove plaintext BuildConfig keys"
```

---

### Task 2: Implement Native JNI Package Verification & Secret Unmasking

**Files:**
- Modify: `app/src/main/cpp/NativeBridge.cpp`

**Interfaces:**
- Consumes: `GeneratedSecrets.h`
- Produces: `Java_com_lastwave_app_data_qobuz_QobuzMusicApi_nativeGetBackendKey`

- [ ] **Step 1: Add JNI context verification and de-obfuscation logic in `NativeBridge.cpp`**

In `app/src/main/cpp/NativeBridge.cpp`:
Include `"GeneratedSecrets.h"` and implement the JNI entry point:
```cpp
#include "GeneratedSecrets.h"
#include <string>
#include <vector>
#include <cstring>

static bool verifyCallingPackage(JNIEnv* env, jobject context) {
    if (!context) return false;
    jclass contextClass = env->GetObjectClass(context);
    if (!contextClass) return false;
    jmethodID getPackageNameMethod = env->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");
    if (!getPackageNameMethod) return false;
    jstring packageName = (jstring)env->CallObjectMethod(context, getPackageNameMethod);
    if (!packageName) return false;

    const char* pkgChars = env->GetStringUTFChars(packageName, nullptr);
    bool valid = (pkgChars != nullptr && std::strcmp(pkgChars, "com.lastwave.app") == 0);
    if (pkgChars) {
        env->ReleaseStringUTFChars(packageName, pkgChars);
    }
    return valid;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lastwave_app_data_qobuz_QobuzMusicApi_nativeGetBackendKey(
    JNIEnv* env,
    jobject /* thiz */,
    jobject context
) {
    if (!verifyCallingPackage(env, context)) {
        return env->NewStringUTF("");
    }

    if (QOBUZ_KEY_LEN == 0) {
        return env->NewStringUTF("");
    }

    std::vector<char> unmasked(QOBUZ_KEY_LEN + 1, 0);
    for (size_t i = 0; i < QOBUZ_KEY_LEN; ++i) {
        unmasked[i] = static_cast<char>(QOBUZ_MASKED_BYTES[i] ^ QOBUZ_XOR_KEY);
    }

    jstring result = env->NewStringUTF(unmasked.data());

    // Securely wipe memory
    std::fill(unmasked.begin(), unmasked.end(), 0);

    return result;
}
```

- [ ] **Step 2: Commit Task 2**

```bash
git add app/src/main/cpp/NativeBridge.cpp
git commit -m "feat(security): implement native package validation and ephemeral secret unmasking in C++"
```

---

### Task 3: Integrate Native Vault in Kotlin (`QobuzMusicApi.kt`)

**Files:**
- Modify: `app/src/main/java/com/lastwave/app/data/qobuz/QobuzMusicApi.kt`

**Interfaces:**
- Consumes: `nativeGetBackendKey(context: Context)`
- Produces: `getBackendApiKey(): String`

- [ ] **Step 1: Inject ApplicationContext and bind native method in `QobuzMusicApi`**

In `QobuzMusicApi.kt`:
```kotlin
@Singleton
class QobuzMusicApi @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
) {
    private external fun nativeGetBackendKey(context: Context): String

    private val backendApiKey: String by lazy {
        try {
            nativeGetBackendKey(context)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to retrieve native backend key", e)
            ""
        }
    }

    companion object {
        init {
            try {
                System.loadLibrary("lastwave_audio")
            } catch (e: Throwable) {
                Log.e("QobuzMusicApi", "Failed to load native library lastwave_audio", e)
            }
        }
        // ...
    }
```
Update all usages of `BACKEND_API_KEY` to use `backendApiKey`.

- [ ] **Step 2: Commit Task 3**

```bash
git add app/src/main/java/com/lastwave/app/data/qobuz/QobuzMusicApi.kt
git commit -m "feat(security): connect QobuzMusicApi to native JNI secret vault"
```

---

### Task 4: Verification and Static APK Inspection

**Files:**
- Test against: Built APK (`classes.dex`, `liblastwave_audio.so`)

- [ ] **Step 1: Build debug and release APKs**

Run: `./gradlew assembleDebug`
Expected: Build SUCCESSFUL.

- [ ] **Step 2: Verify zero plaintext keys in DEX**

Run:
```bash
unzip -p app/build/outputs/apk/debug/app-debug.apk classes.dex | strings | grep "QOBUZ_API_KEY"
```
Expected: No match / 0 occurrences.

- [ ] **Step 3: Run app on physical device and test Qobuz playback**

Run: `./gradlew installDebug && adb -s df0c8daa shell am start -n com.lastwave.app/.MainActivity`
Verify FLAC playback and metadata resolution succeed.

- [ ] **Step 4: Push to remote PR**

```bash
git push origin feat/ui-redesign-and-android-auto
```
