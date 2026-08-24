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
    /** Normalized 0..1 artist strength. Last.fm history remains the strongest
     * signal; connected YT Music history, likes, and Home-feed picks blend in
     * with bounded weights. */
    val artistAffinity: Map<String, Double> = emptyMap(),
    val ytMusicRecentRaw: List<GeneratedTrack> = emptyList(),
    val ytMusicLikedRaw: List<GeneratedTrack> = emptyList(),
    /** Actual playable cards selected by the signed-in YT Music Home feed. */
    val ytMusicFeedRaw: List<GeneratedTrack> = emptyList(),
    val hasPersonalSignals: Boolean = false,
)
