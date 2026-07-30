package com.lastwave.app.data.generate

/**
 * Port of app.js's _buildUserTasteProfile(): a snapshot of the user's
 * listening signals, hydrated into sets for O(1) lookup. Shared by My Mix,
 * My Recommendations, and Genre Detail's "Explore This Genre" scoring.
 *
 * Cached for 1 hour by [TasteProfileProvider] — matches the original's
 * in-memory 1hr cache (rebuilding this on every playlist generation would
 * mean 4 extra parallel API calls every single time).
 */
data class TasteProfile(
    val topArtistNames: Set<String>,
    val recentArtists: Set<String>,
    val topTags: Set<String>,
    val topTrackKeys: Set<String>,
    val recentTrackKeys: Set<String>,
    val topTracksRaw: List<GeneratedTrack>,
    val recentTracksRaw: List<GeneratedTrack>,
    val topArtistsRaw: List<String>,
    val builtAtMillis: Long,
)
