package com.lastwave.app.data.repository

import androidx.compose.runtime.Immutable

/**
 * Mirrors one entry of home.js's `_homeAllTracks`: a track that either came
 * from user.getrecenttracks (has [timestampMillis]) or was added from the
 * user's all-time top tracks to fill gaps (`extra` in home.js — no
 * timestamp, only used by the Most Played / period tabs).
 */
@Immutable
data class HomeTrack(
    val name: String,
    val artist: String,
    val artworkUrl: String?,
    val timestampMillis: Long?,
    val playCount: Int,
    val isNowPlaying: Boolean = false,
) {
    val key: String get() = "${name.lowercase()}|${artist.lowercase()}"
}

enum class HomeSortMode { RECENT, MOST_PLAYED, LAST_7_DAYS, LAST_30_DAYS }
 
@Immutable
data class HomeArtistItem(
    val name: String,
    val playCount: Long = 0L,
    val artworkUrl: String? = null,
)
