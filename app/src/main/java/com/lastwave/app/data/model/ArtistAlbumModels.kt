package com.lastwave.app.data.model

import androidx.compose.runtime.Immutable
import com.lastwave.app.playback.PlayableTrack

@Immutable
data class ArtistAlbumItem(
    val title: String,
    val browseId: String = "",
    val year: String? = null,
    val type: String? = null, // "Album", "Single", "EP"
    val artworkUrl: String? = null,
)

@Immutable
data class ArtistSummaryItem(
    val name: String,
    val browseId: String = "",
    val artworkUrl: String? = null,
    val subtitle: String? = null,
)

@Immutable
data class ArtistPageData(
    val name: String,
    val browseId: String = "",
    val artworkUrl: String? = null,
    val bannerUrl: String? = null,
    val monthlyListeners: String? = null,
    val subscribers: String? = null,
    val bio: String? = null,
    val tags: List<String> = emptyList(),
    val topSongs: List<PlayableTrack> = emptyList(),
    val albums: List<ArtistAlbumItem> = emptyList(),
    val singles: List<ArtistAlbumItem> = emptyList(),
    val similarArtists: List<ArtistSummaryItem> = emptyList(),
)

@Immutable
data class AlbumPageData(
    val title: String,
    val artist: String,
    val artistBrowseId: String? = null,
    val browseId: String = "",
    val artworkUrl: String? = null,
    val releaseYear: String? = null,
    val trackCountText: String? = null,
    val durationText: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val tracks: List<PlayableTrack> = emptyList(),
    val otherAlbums: List<ArtistAlbumItem> = emptyList(),
)
