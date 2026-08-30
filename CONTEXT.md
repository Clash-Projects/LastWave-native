# CONTEXT.md — LastWave-native Domain Glossary

This document serves as the canonical glossary and domain model for the LastWave-native music client. It defines the core terms used across the codebase and user interface to ensure absolute precision during the redesign.

## Core Concepts

### Quick Play
- **Definition**: An instant playback launcher situated near the top of the Home screen, structured as a 3-column grid.
- **Constraints**: Contains actual playable songs (not artist circles or generic playlists). Tapping an item starts playback immediately. Items feature square artwork, song title, artist name, and a subtle play overlay.

### Quick Picks
- **Definition**: A compact vertical recommendation list of tracks (distinct from Quick Play).
- **Constraints**: Used for discovery and recommendation feeds, showing approximately 4–6 tracks with a "Play All" action.

### Last.fm Mini Widget
- **Definition**: A compact, translucent horizontal glass container placed on the Home screen immediately following Quick Play.
- **Constraints**: Displays key scrobble metrics (total scrobbles, trend graph, percentage change, and top artist) without cluttering the Home feed with full statistics. Detailed statistics remain available in the dedicated Last.fm screen.

### Glass Surface
- **Definition**: Selective Apple-style translucent glass used sparingly for overlays, floating controls, mini-players, bottom navigation, and widgets.
- **Constraints**: Avoided on general content cards; most content remains flat and naturally blended into the layered dark background.

### Wavy SeekBar (One UI 9 Inspired)
- **Definition**: An advanced, multi-layer frosted glass progress indicator featuring smooth animated wave forms and a hue-shifting gradient based on progress.
- **Constraints**: Exists as `WavySeekBar.kt` and is reused directly in the player without unnecessary replacement.

### Layered Dark Background System
- **Definition**: A background hierarchy starting from a near-black dark neutral base, augmented by subtle ambient gradients and optional low-opacity, artwork-derived color blooms.
- **Constraints**: Must never reduce text readability or make the entire screen overly saturated.
