<div align="center">

<img src="lastwave_logo.png" alt="LastWave Logo" width="120" height="120" style="border-radius: 50%;" />

# ✨ LastWave v3.0

**A fully-featured Last.fm client, high-fidelity music streamer, and native scrobbler for Android, built with Material 3 Expressive design.**

<p align="center">
  <a href="https://github.com/duxtami/LastWave-native/stargazers">
    <img src="https://img.shields.io/github/stars/duxtami/LastWave-native?style=for-the-badge&color=ffd0b0&labelColor=2d2d2d" alt="Stars" />
  </a>
  <a href="https://github.com/duxtami/LastWave-native/network/members">
    <img src="https://img.shields.io/github/forks/duxtami/LastWave-native?style=for-the-badge&color=ffb4a2&labelColor=2d2d2d" alt="Forks" />
  </a>
  <a href="#">
    <img src="https://img.shields.io/badge/Version-3.0.0--native-C6F100?style=for-the-badge&labelColor=012226" alt="Version 3.0.0-native" />
  </a>
  <a href="#">
    <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white&labelColor=2d2d2d" alt="Platform" />
  </a>
</p>

<p align="center">
  <a href="https://t.me/clashprojects">
    <img src="https://img.shields.io/badge/Telegram-Updates%20%26%20Support-24A1DE?style=for-the-badge&logo=telegram&logoColor=white" alt="Telegram Support" />
  </a>
  <a href="https://t.me/MaterialYouApp">
    <img src="https://img.shields.io/badge/Telegram-More%20From%20Us-0088cc?style=for-the-badge&logo=telegram&logoColor=white" alt="More From Us" />
  </a>
</p>

</div>

<br/>

<div align="center">
  <img src="Screenshot/Screenshot_20260815-095128.png" width="32%" style="border-radius: 12px;" />
  <img src="Screenshot/Screenshot_20260815-095135.png" width="32%" style="border-radius: 12px;" />
  <img src="Screenshot/Screenshot_20260815-095140.png" width="32%" style="border-radius: 12px;" />
  <br/>
  <br/>
  <img src="Screenshot/Screenshot_20260815-095145.png" width="32%" style="border-radius: 12px;" />
  <img src="Screenshot/Screenshot_20260815-095150.png" width="32%" style="border-radius: 12px;" />
  <img src="Screenshot/Screenshot_20260815-095158.png" width="32%" style="border-radius: 12px;" />
</div>

<br/>

## About

LastWave is a native Android music client and intelligent companion for Last.fm — built purely in Kotlin and Jetpack Compose around the latest Material 3 Expressive guidelines. It features lossless high-res audio streaming, full-fidelity offline downloads with embedded metadata and lyrics, real-time synchronized LRCLIB lyrics with kinetic animations, taste-mix generation, and a powerful background scrobbler.

---

## Core Features

| Feature | Description |
|:---|:---|
| 🏠 **Home & Activity** | Recent scrobbles, top tracks & artists by timeframe (7d / 30d / 90d / 180d / 365d / overall), live Now Playing pulsing indicator, and loved tracks. |
| 🔮 **Smart Discovery** | Adaptive recommendation engine using track & artist similarity, loved seeds, tag graphs, and personalized charting. |
| 🎸 **Genre DNA & Explorer** | Detailed genre breakdown of your listening habits with one-tap mix generation and instant "Discover More" recommendations. |
| 🪄 **Taste Mix & Playlists** | Generate unique 30–35 track dynamic playlists, regenerate mixes, export to custom in-app playlists, and sync with YouTube Music. |
| 🎵 **Lossless Audio & Downloads** | Hi-Res Qobuz FLAC (up to 24-bit/192kHz) and YouTube Opus streaming with offline downloads, tagged with synchronized lyrics & cover art. |
| 🎤 **Real-time Synced Lyrics** | Millisecond-accurate synchronized lyrics powered by LRCLIB with 8 customizable fluid physics animations. |
| 🔍 **Universal Search** | Instant search for tracks, artists, albums, and Last.fm user profiles. |
| 👥 **Friends Activity** | Seamlessly switch between friends' profiles to explore their listening stats and taste mixes. |
| ⚙️ **Customization & Backup** | Material You wallpaper color extraction, custom HSL color wheel, dynamic Now Playing palette, and full JSON backup & restore. |

---

## Lossless Streaming & Backend Credits

- **Qobuz Lossless FLAC Backend:** High-fidelity audio playback and download engine is powered by the [clashflac](https://github.com/ajisth69/clashflac) backend by [Ajisth (ajisth69)](https://github.com/ajisth69).
- **Lyrics Provider:** [LRCLIB](https://lrclib.net) for synced and plain lyric data.
- **Scrobble & Metadata Service:** Official [Last.fm API](https://www.last.fm/api).

---

## The Native Scrobbler

- Detects currently playing media via Android's native `MediaSessionManager` across all music apps without requiring per-app setup.
- Configurable scrobble percentage threshold and custom app allowlists.
- Handles track repeats, pauses, seeking, and rapid app switching accurately.
- Automatically cleans auto-generated suffix tags (such as ` - Topic`) from YouTube Music artists.
- Built-in live debug log in Settings for full scrobble status visibility.

---

## Design & Aesthetics

- **Material 3 Expressive:** Grouped surface cards, fluid shape morphing, spring-physics motion, and contextual haptics.
- **Theming:** Material You wallpaper dynamic theming, custom HSL color picker, and dynamic album art extraction.
- **Predictive Back:** Native gesture animations throughout sheets and nested screens.
- **Custom Typography:** Google Sans Flex variable typography.

---

## Tech Stack

- **UI:** Jetpack Compose + Material 3 Expressive (Compose 1.8+)
- **Architecture:** MVVM + Clean Architecture + Kotlin Coroutines & Flow
- **Dependency Injection:** Dagger Hilt
- **Media Playback:** Media3 ExoPlayer with foreground audio service & notification controls
- **Networking & Storage:** Retrofit, OkHttp, Room, Jetpack DataStore

---

## Getting Started

1. Download the latest release from the [Releases](https://github.com/duxtami/LastWave-native/actions) tab.
2. Tap **Connect with Last.fm** to securely authenticate via Custom Tabs.
3. Grant notification listener permission for background scrobbling.

## Building from Source

```bash
git clone https://github.com/duxtami/LastWave-native.git
cd LastWave-native
./gradlew assembleRelease
```

---

## Community & Support

- 📢 **Updates & Support:** [Join @clashprojects on Telegram](https://t.me/clashprojects)
- 🚀 **More From Us:** [Join @MaterialYouApp on Telegram](https://t.me/MaterialYouApp)

---

<div align="center">
  <p><b>LastWave</b> is built with ❤️ by <a href="https://github.com/duxtami">Duxtami</a> & <a href="https://github.com/ajisth69">Ajisth</a>.</p>
</div>
