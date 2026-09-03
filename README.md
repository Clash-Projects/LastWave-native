<div align="center">

<img src="lastwave_logo.png" alt="LastWave Logo" width="120" height="120" style="border-radius: 50%;" />

# LastWave

### High-Resolution Lossless Audio Streaming & Native Music Player for Android

<p align="center">
  <a href="https://github.com/Clash-Projects/LastWave-native/releases">
    <img src="https://img.shields.io/badge/Platform-Android_8.0+-3DDC84?style=for-the-badge&logo=android&logoColor=white&labelColor=1a1a1a" alt="Platform" />
  </a>
  <a href="#">
    <img src="https://img.shields.io/badge/Audio-24--bit_%2F_192kHz_FLAC-00E5FF?style=for-the-badge&logo=flac&logoColor=white&labelColor=1a1a1a" alt="Hi-Res Lossless FLAC" />
  </a>
  <a href="#">
    <img src="https://img.shields.io/badge/Language-Kotlin_%26_C++-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white&labelColor=1a1a1a" alt="Kotlin & C++" />
  </a>
  <a href="#">
    <img src="https://img.shields.io/badge/UI-Material_3_Expressive-C6F100?style=for-the-badge&labelColor=1a1a1a" alt="Material 3 Expressive" />
  </a>
</p>

<p align="center">
  <a href="https://t.me/clashprojects">
    <img src="https://img.shields.io/badge/Telegram-Join_Community-24A1DE?style=flat-square&logo=telegram&logoColor=white" alt="Telegram Community" />
  </a>
  <a href="https://github.com/Clash-Projects/LastWave-native/releases">
    <img src="https://img.shields.io/badge/Release-v3.4.1--native-blue?style=flat-square" alt="Latest Release" />
  </a>
</p>

</div>

<br/>

<div align="center">
  <img src="Screenshot/screenshot_1.png" width="31%" style="border-radius: 14px; margin: 4px;" />
  <img src="Screenshot/screenshot_2.png" width="31%" style="border-radius: 14px; margin: 4px;" />
  <img src="Screenshot/screenshot_3.png" width="31%" style="border-radius: 14px; margin: 4px;" />
  <br/>
  <br/>
  <img src="Screenshot/screenshot_4.png" width="31%" style="border-radius: 14px; margin: 4px;" />
  <img src="Screenshot/screenshot_5.png" width="31%" style="border-radius: 14px; margin: 4px;" />
  <img src="Screenshot/screenshot_6.png" width="31%" style="border-radius: 14px; margin: 4px;" />
</div>

<br/>

## Overview

**LastWave** is an advanced native Android music streaming and playback application crafted for audiophiles and high-fidelity audio enthusiasts. Built from the ground up with **Kotlin** and a **Custom Native C++ Audio Pipeline**, LastWave delivers uncompressed, bit-perfect **24-bit / 192kHz Studio Master FLAC** reproduction alongside intelligent recommendations, synchronized kinetic typography, and persistent background scrobbling.

The interface is architected according to Google's latest **Material 3 Expressive** design system, combining fluid morphing transitions, dynamic HSL tonal palettes, and tactile haptic feedback.

---

## Technical Specifications & Architecture

| Layer | Technology | Technical Capabilities |
| :--- | :--- | :--- |
| **Audio Pipeline** | Custom Native C++ Audio Engine | • Bit-perfect FLAC decoding up to 24-bit / 192kHz<br/>• Direct hardware bitstream output<br/>• Sample-accurate gapless playback<br/>• Low-jitter clock synchronization and low-latency DSP |
| **User Interface** | Jetpack Compose | • Material 3 Expressive design tokens<br/>• Dynamic wallpaper extraction & custom HSL palette engine<br/>• 60/120 FPS hardware-accelerated fluid motion transitions |
| **Lyrics Subsystem** | LRCLIB Protocol Integration | • Millisecond-precise synchronized word/line tracking<br/>• 8 physics-based kinetic motion presets (Apple Fluid, Karaoke Pulse, Kinetic Slide) |
| **Architecture & State** | Clean Architecture (MVI / MVVM) | • Unidirectional data flow with Kotlin Coroutines & StateFlow<br/>• Dagger Hilt dependency injection<br/>• Reactive local persistence via Room DB & DataStore |
| **Intelligence & Sync** | Discovery & Scrobbler Engine | • Real-time taste profiling, genre DNA decomposition, and mood generation<br/>• System-wide media scrobbling with zero background wake-lock overhead |

---

## Key Features

- **Bit-Perfect Lossless Streaming:** Studio Master FLAC reproduction up to 24-bit depth and 192kHz sample rates.
- **Offline High-Res Library:** Bit-exact local saving to device storage (`Music/LastWave`) with embedded high-resolution artwork, complete ID3/Vorbis metadata, and synchronized `.lrc` lyrics.
- **Kinetic Synchronized Lyrics:** Real-time millisecond-accurate animated karaoke lyrics with customizable kinetic physics styles.
- **Dynamic Discovery & Taste Engine:** Algorithmic music recommendation feeds constructed from user listening history, seed tracks, and genre classifications.
- **Genre DNA Explorer:** Real-time breakdown of listening habits with immediate seed-based radio mix generation.
- **Universal Background Scrobbler:** Integrated background service listening to active media sessions across any Android audio player without battery penalty.
- **Expressive Theming:** Wallpaper-derived color harmonies, manual HSL color picker, and dynamic album palette adaptation.

---

## Installation

1. Navigate to the **[Releases](https://github.com/Clash-Projects/LastWave-native/releases)** page.
2. Download the latest signed package (`LastWave-v3.4.1-release.apk`).
3. Install the APK on your Android device (Android 8.0+ / API 26+).
4. *(Optional)* Authenticate your Last.fm profile for scrobble tracking and personalized recommendations.

---

## Building from Source

### Prerequisites
- Android Studio Ladybug | 2024.2.1+ or newer
- JDK 17+
- Android SDK 35 (API Level 35)
- Android NDK (r26b or newer)

```bash
git clone https://github.com/Clash-Projects/LastWave-native.git
cd LastWave-native
./gradlew assembleRelease
```

---

## Community & Support

- **Telegram Channel & Discussions:** [Join @clashprojects](https://t.me/clashprojects)
- **Design & UI Updates:** [Join @MaterialYouApp](https://t.me/MaterialYouApp)
- **Issue Tracking:** Report bugs and feature requests via the [GitHub Issues](https://github.com/Clash-Projects/LastWave-native/issues) tab.
