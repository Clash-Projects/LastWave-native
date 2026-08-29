# Architectural Decision Record: Layered Dark Background and Selective Glassmorphism

## Status
Proposed / Accepted during Redesign Planning

## Context
The previous LastWave UI used heavy card separations, bulky containers, and large flat dark green/grey surfaces that occasionally felt disconnected. As part of the transition to a modern Android design with Apple-style translucency and an immersive dark aesthetic, we need a consistent way to handle backgrounds, depth, and glassmorphism without performance degradation or visual noise.

## Decision
1. **Layered Dark Background System**: We will use a near-black dark neutral base, augmented by subtle ambient gradients and optional, extremely low-opacity artwork-derived color blooms. This avoids pure black everywhere while keeping text contrast high.
2. **Selective Glassmorphism**: Translucent glass surfaces will **only** be used for floating or overlay controls (such as Search, the Last.fm summary widget, Mini-player, Bottom Navigation, Player controls, Bottom sheets, and Context menus). 
3. **Flat Content Flow**: General content rows and sections will remain flat and naturally blended into the background rather than being enclosed in heavy cards.

## Consequences
- **Pros**: Cleaner, less cluttered UI; improved visual hierarchy; reduced overdraw and layout complexity compared to full glassmorphism.
- **Cons**: Requires strict discipline among contributors to avoid adding heavy background cards to standard list items.
