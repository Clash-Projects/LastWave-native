# Specification: UI Redesign, YouTube Music Backend Fetching & Cache Pipeline

## Problem Statement

LastWave's current mobile application faces three key limitations:
1. The **Home Screen** lacks an integrated, dense, artwork-driven discovery feed. Components like `HomeScreen.kt` are currently stubbed placeholders despite partial components (`QuickPlayGrid`, `LastFmMiniWidget`) existing.
2. The **Playback & YouTube Music Integration** lacks direct feed-to-player triggering from Home shortcuts, requiring seamless bridging between Last.fm metadata, InnerTube matching/search, and stream resolution.
3. The **Cache Infrastructure** has incomplete test coverage and compilation errors in unit test configurations (missing test dependencies and broken assertions), and lacks a persistent offline store for Home feed recommendations.

## Solution

1. Deliver a production-ready **Home Screen Feed** in `HomeScreen.kt` that cleanly renders the `HomeHeader`, 3-column `QuickPlayGrid`, `LastFmMiniWidget`, and flat uncarded recent/top track rows with pull-to-refresh and empty/error states.
2. Complete the **Instant Playback & YouTube Music Match Pipeline** ensuring clicking any Quick Play item immediately dispatches to `MusicPlayer`, resolves audio streams via `InnerTubeMusicApi`, and pre-caches streams.
3. Fix the **Test Harness & Build Configuration** by adding standard testing dependencies (JUnit4, Truth, Robolectric, MockK, Coroutines-Test), repairing broken tests, and establishing a robust Red-Green-Refactor TDD baseline.
4. Establish **Persistent Offline Caching** in Room DB for feed recommendations and metadata to achieve instant startup times.

## User Stories

1. As a listener, I want the Home screen to display my Quick Play 3-column grid at the top so I can immediately resume top tracks.
2. As a listener, I want to tap any track in the Quick Play grid and have it play instantly without UI freezes.
3. As a user, I want the Home screen to display my Last.fm scrobble count and trend percentage in a compact translucent widget.
4. As a user, I want to scroll seamlessly through my recent and most-played scrobbles in a flat, high-density list without bulky cards.
5. As a user, I want to pull down on the Home feed to refresh my scrobbles and recommendations from Last.fm and YouTube Music.
6. As a user, I want previously loaded feed items to appear instantly when I open the app, even before network calls complete or while offline.
7. As a developer, I want the project unit tests to compile and pass cleanly via `./gradlew test` so that every new feature and bugfix can be developed using strict TDD.

## Implementation Decisions

- **UI Composition**: `HomeScreen` will integrate `HomeViewModel`'s `HomeUiState` using a `LazyColumn` containing the `HomeHeader`, `QuickPlayGrid`, `LastFmMiniWidget`, and `visibleRows` track items.
- **Seams & Player Integration**: Playback actions from `QuickPlayGrid` route directly through `HomeViewModel` to `MusicPlayer.playTrack()` or `MusicPlayer.playHomeTrack()`, leveraging `InnerTubeMusicApi.findBestMatch` with background stream prefetching.
- **Test Infrastructure**: Standardize test libraries in `app/build.gradle.kts` (JUnit 4.13.2, Google Truth 1.4.2, Robolectric 4.12.2, MockK 1.13.10, Coroutines Test 1.8.1).
- **Offline Cache**: Store home feed snapshot payloads in Room database or persistent DataStore cache to decouple cold launches from live network waterfalls.

## Testing Decisions

- **Test-Driven Development (TDD)**: No new feature or bugfix code will be committed without a prior failing unit test verifying the specific behavior.
- **Seams**: Test `HomeRepository` and `HomeViewModel` against mock Last.fm / InnerTube responses; test cache evictors and settings models directly in Robolectric/JVM unit tests.
- **External Behavior Only**: Tests will assert on emitted `StateFlow` states and player queue actions rather than private internal method calls.

## Out of Scope

- Modifying the underlying native Oboe / C++ audio pipeline.
- Modifying the full-screen lyrics visualizer engine beyond ambient color bloom tokens.
