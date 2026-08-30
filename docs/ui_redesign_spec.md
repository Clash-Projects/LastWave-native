## Problem Statement

LastWave's current mobile application user interface uses heavy card separation, oversized typography, bulky containers, and excessive vertical spacing on the Home screen. This prevents users from experiencing an immersive, discovery-rich, continuous feed of content. Additionally, the existing Home layout lacks a dedicated "Quick Play" instant playback grid of playable songs (currently showing only artist circles or long list items) and suffers from a lack of selective Apple-style translucent glass details on floating overlays and mini-players.

## Solution

Overhaul the Home screen and player layout to transition to a dense, continuous, artwork-driven dark-mode music client. The redesigned Home screen will feel like an unified discovery feed, incorporating a 3-column "Quick Play" grid of actual tracks, a compact "Last.fm Mini Widget," and color-tinted ambient category pills. The mini-player will be modernized into a translucent floating overlay that aligns with the bottom navigation, retaining the high-fidelity fluid wave seek bar already implemented.

## User Stories

1. As an active listener, I want to launch recently played, frequently played, or liked tracks instantly from a 3-column Quick Play grid on the Home screen, so that I can resume playback with a single tap.
2. As an active listener, I want to see a small, high-density Last.fm stats widget on my Home feed, so that I can quickly monitor my monthly scrobble metrics without navigating to a separate dashboard.
3. As a user, I want the Home screen to scroll vertically as a continuous discovery feed rather than a set of disjointed large cards, so that browsing for music feels fluid and atmospheric.
4. As a listener, I want the mini-player to float at the bottom of the screen with a translucent glass finish, so that it blends seamlessly with the navigation bar and current artwork.
5. As a user, I want the application typography, spacing, and icons to be scaled down to a compact, professional dark premium music application style, so that more content is visible on a single screen without feeling crowded.
6. As a user, I want the background of the player and main screen to have a low-opacity, artwork-derived color bloom, so that the visual theme adapts dynamically to whatever song is playing.

## Implementation Decisions

- **Domain Integrity**: All changes will adhere strictly to definitions in `CONTEXT.md`. General content lists will remain flat and naturally blended without elevated background cards.
- **Selective Translucency**: Glassmorphism (`liquidGlassChrome`) is strictly reserved for the floating Mini-Player, Last.fm Widget, Search bar, and Bottom Navigation to prevent overdraw and visual noise.
- **Home Feed restructuring**: Restructure the primary composable to integrate the newly created `QuickPlayGrid` and `LastFmMiniWidget` into the scrolling flow of the list.
- **Ambient Color Bloom**: Adapt the existing dynamic theme builder to apply soft radial background gradients influenced by the playing track's artwork.

## Testing Decisions

- **Home Feed Behavior**: Verify that clicking a song on the Quick Play grid triggers the correct playback action inside the shared player state.
- **Mini-Player Lifecycle**: Ensure the translucent mini-player correctly reacts to play/pause state transitions and seek bar drags.
- **Mocking**: Leverage existing Hilt ViewModel test targets to mock Last.fm scrobble data feeds without hitting live API networks.

## Out of Scope

- Rebranding or renaming the application to resemble proprietary music streaming applications.
- Rewriting or replacing the underlying Exoplayer/Media3 media playback service or database schema.
- Rebuilding the separate, dedicated full-statistics Last.fm dashboard screen.
