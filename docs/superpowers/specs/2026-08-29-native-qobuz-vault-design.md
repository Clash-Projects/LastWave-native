# Design: Native C++ Obfuscated Vault for API Keys

## Problem Statement
`app/build.gradle.kts` currently injects API keys (`QOBUZ_API_KEY`, `LYRICS_API_KEY`) via `buildConfigField(...)`, which compiles the keys as plaintext ASCII string constants into `BuildConfig.java` and `classes.dex`. Any static analysis tool (e.g. `jadx`, `apktool`, or `strings`) can extract the full secrets in seconds without executing the APK.

## Proposed Solution: Native C++ Obfuscation & JNI Context Verification

### 1. Build-Time Masking in Gradle (`app/build.gradle.kts`)
- Remove plaintext `buildConfigField` for `QOBUZ_API_KEY` (and `LYRICS_API_KEY`).
- A Gradle task or build step encodes the secret using a multi-byte pseudo-random XOR key schedule (e.g., dynamic byte array + bitwise rotation).
- The obfuscated byte array and XOR schedule are passed into the C++ compilation layer via CMake compile definitions or a generated private native header (`app/src/main/cpp/GeneratedSecrets.h` / CMake arguments).

### 2. Native C++ Vault (`app/src/main/cpp/NativeBridge.cpp`)
- Implement `Java_com_lastwave_app_data_qobuz_QobuzMusicApi_nativeGetBackendKey(JNIEnv* env, jobject clazz, jobject context)`.
- **Integrity & Calling Context Verification**:
  1. Retrieves the calling Android `Context` and inspects `context.getPackageName()`.
  2. Confirms the calling package matches `"com.lastwave.app"`.
  3. If verification fails, the native method immediately aborts and returns an empty string or error.
- **De-obfuscation in Ephemeral Stack Memory**:
  1. Evaluates the XOR byte stream dynamically on the C++ stack.
  2. Constructs a localized `jstring`.
  3. Uses `memset_s` / `std::fill` to securely zero-out the unmasked buffer in native memory.

### 3. Kotlin Integration (`QobuzMusicApi.kt`)
- Ensure `System.loadLibrary("lastwave_audio")` is loaded in `QobuzMusicApi` companion object.
- Inject `@ApplicationContext context: Context`.
- Replace `BuildConfig.QOBUZ_API_KEY` with a call to `nativeGetBackendKey(context)`.

---

## Security Trade-Offs & Analysis

| Attack Vector | Before (BuildConfig) | After (Native Vault) |
|---|---|---|
| `strings classes.dex` | **Vulnerable** (plaintext instant extraction) | **Protected** (No key strings in DEX) |
| Java Decompilation (`jadx`) | **Vulnerable** (plaintext field in `BuildConfig.java`) | **Protected** (Calls opaque `external fun`) |
| `strings liblastwave_audio.so` | N/A | **Protected** (Only XOR byte constants, no ASCII string) |
| APK Repackaging / Clones | **Vulnerable** | **Protected** (Native package name check fails) |
| Runtime Memory Inspection (Frida) | Trivial | Requires native function hooking / memory scanning |

---

## Verification Plan
1. **Compilation**: Build APK via `./gradlew assembleRelease` and `./gradlew assembleDebug`.
2. **Static Inspection**:
   - Run `unzip -p app/build/outputs/apk/release/app-release.apk classes.dex | strings | grep -i "qobuz"` and confirm 0 plaintext matches.
   - Run `strings` on `liblastwave_audio.so` and verify no plaintext key appears.
3. **Runtime Test**:
   - Run the app on physical device `M2012K11AI`.
   - Play a Hi-Res FLAC song resolving via Qobuz and verify playback and track resolution succeed bit-perfect.
