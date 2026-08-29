# Feature: Android Auto Integration, UI Redesign, and YouTube Music Liked Songs Sync

## Overview
This PR introduces full **Android Auto & Automotive OS** media browsing support, a modern **Home Screen redesign** with QuickPlay & live Last.fm widgets, enhanced **MiniPlayer & WavySeekBar** gesture seeking, and real-time **YouTube Music Liked Songs** synchronization.

---

## Key Highlights & Changes

### 🚗 Android Auto & Automotive OS Integration
- **Full MediaBrowserServiceCompat Implementation**: Registered `MusicPlaybackService` with complete Automotive OS descriptors and IPC binding support.
- **6 Root Media Categories**:
  - 🕒 **Recent Listening**: Scrobbles & history
  - ❤️ **Liked Songs**: Synchronized favorites
  - 📁 **Playlists**: Custom and generated collections
  - 💾 **Downloaded Music**: Offline cached songs
  - 🌟 **Top Tracks**: Overall most played
  - ⚡ **Discover Mix**: Personalized algorithmic recommendations
- **In-Car Dashboard Controls**: Transport controls (Play/Pause, Skip, Seek, Shuffle, Repeat) and a dedicated 1-tap **Favorite** custom action (`ic_auto_favorite`).
- **Voice Search & Google Assistant**: Hands-free voice querying (*"Play my liked songs on LastWave"*, *"Play Discover Mix on LastWave"*).

---

### 🎨 Home Screen & UI Redesign
- **QuickPlayGrid**: Responsive quick-access cards for recently played albums, top mixes, and trending tracks.
- **LastFmMiniWidget**: Live scrobble tracker showing real-time listening count and top artists.
- **Header & Navigation**: Personalized header greeting, sleek top bar icons, and fluid navigation transitions.
- **Theme & Aesthetics**: Refined glassmorphism styling, clean dark-mode contrast, and subtle tonal accents.

---

### 🎵 MiniPlayer & WavySeekBar Enhancements
- **Touch Gesture Resolution**: Implemented `awaitEachGesture` in `MiniWavyProgress` (`WavySeekBar.kt`) with eager touch event consumption for instant, responsive tap and drag-to-seek without parent gesture slop conflicts.
- **Compact & Responsive**: Streamlined MiniPlayer layout with fluid sliding animations and clean playback controls.
- **Synchronized Lyrics**: Clean typography with auto-scroll and intuitive lyric synchronization.

---

### 🔄 YouTube Music Liked Songs Sync & Compact Context Menus
- **Direct YouTube Liked Music Integration**: Added official `$MUSIC_API/like/like` and `like/removelike` InnerTube API endpoints so favorited songs immediately show up in the account's official **Liked Music** playlist.
- **Favorites Sync Engine**: Background batch-syncer for ensuring all saved favorites stay synchronized with YouTube Music.
- **TrackContextMenuSheet**: Compact, sleek bottom sheet for track actions (Add to Playlist, Favorite, Download, View Artist/Album).

---

### ⚡ Audio Engine & Build Optimizations
- **Audio Engine**: Low-latency Oboe C++ ringbuffers and sinc resampling.
- **Smart Stream Caching**: Dynamic LRU and LFU cache eviction strategies.
- **Downloads**: Vorbis/Opus metadata tagging and WebM audio extraction.

---

## Clean Commit Structure

1. `feat(auto): add comprehensive Android Auto and Automotive OS media integration`
2. `feat(ui): implement modern HomeScreen layout with QuickPlayGrid and LastFmMiniWidget`
3. `feat(player): refine PlayerHost, WavySeekBar touch interaction, and synchronized lyrics`
4. `feat(settings): refine settings UI, compact context menus, and direct YouTube Music Liked Songs sync`
5. `feat(audio): optimize native audio pipeline, stream caching, and build configuration`

---

## Verification & Testing
- ✅ Unit tests pass (`./gradlew testDebugUnitTest`) across all modules.
- ✅ Android Auto MediaSession and MediaBrowserService tested via device IPC and ADB.
- ✅ Verified on connected physical device (`M2012K11AI`).
