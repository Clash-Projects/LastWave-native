package com.lastwave.app.data.model

import com.lastwave.app.data.artwork.ArtworkNormalizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ImageDto(
    @SerialName("#text") val url: String = "",
    val size: String = "",
)

@Serializable
data class TrackAttr(
    val nowplaying: String? = null,
)

@Serializable
data class ArtistRef(
    @SerialName("#text") val textName: String = "",
    val name: String = "",
    val mbid: String = "",
) {
    val displayName: String get() = name.ifBlank { textName }
}

@Serializable
data class DateRef(
    val uts: String = "",
    @SerialName("#text") val text: String = "",
)

@Serializable
data class RecentTrack(
    val name: String = "",
    val artist: ArtistRef = ArtistRef(),
    val album: ArtistRef = ArtistRef(),
    val image: List<ImageDto> = emptyList(),
    val url: String = "",
    val date: DateRef? = null,
    @SerialName("@attr") val attr: TrackAttr? = null,
) {
    val isNowPlaying: Boolean get() = attr?.nowplaying == "true"

    /** extralarge > large > medium > any real image — filtered to exclude
     *  Last.fm's own gray "no artwork" placeholder, matching every image
     *  read site in the original app exactly (see ArtworkNormalizer). */
    val artworkUrl: String?
        get() = ArtworkNormalizer.bestImageUrl(image)
}

/** Handles both the single-object and array shape Last.fm returns for
 *  `track` depending on whether there's 1 result or many. */
@Serializable(with = RecentTracksListSerializer::class)
data class RecentTracksList(val tracks: List<RecentTrack>)

@Serializable
data class RecentTracksAttr(val total: String = "0", val page: String = "1", val totalPages: String = "1")

@Serializable
data class RecentTracksBody(
    val track: RecentTracksList = RecentTracksList(emptyList()),
    @SerialName("@attr") val attr: RecentTracksAttr = RecentTracksAttr(),
)

@Serializable
data class RecentTracksEnvelope(
    val recenttracks: RecentTracksBody? = null,
    val error: Int? = null,
    val message: String? = null,
)

@Serializable
data class StatTotalAttr(val total: String = "0")

@Serializable
data class TopTracksBody(@SerialName("@attr") val attr: StatTotalAttr = StatTotalAttr())
@Serializable
data class TopArtistsBody(@SerialName("@attr") val attr: StatTotalAttr = StatTotalAttr())
@Serializable
data class TopAlbumsBody(@SerialName("@attr") val attr: StatTotalAttr = StatTotalAttr())

@Serializable
data class TopTracksEnvelope(val toptracks: TopTracksBody? = null)
@Serializable
data class TopArtistsEnvelope(val topartists: TopArtistsBody? = null)
@Serializable
data class TopAlbumsEnvelope(val topalbums: TopAlbumsBody? = null)

// ── user.getinfo — powers the big "Scrobbles" number and the listen-timer
//    base (playcount * 210s, the same fixed per-track estimate home.js used).
@Serializable
data class UserInfoUser(
    val playcount: String = "0",
    val image: List<ImageDto> = emptyList(),
)

@Serializable
data class UserInfoEnvelope(
    val user: UserInfoUser? = null,
    val error: Int? = null,
    val message: String? = null,
)

// ── user.gettoptracks (period=overall, limit=50) — carries real playcounts,
//    used to (a) merge play counts onto matching Recent entries and (b) supply
//    "Most Played" / period-tab entries that aren't in the last 50 scrobbles.
@Serializable
data class TopTrackEntry(
    val name: String = "",
    val artist: ArtistRef = ArtistRef(),
    val image: List<ImageDto> = emptyList(),
    val url: String = "",
    val playcount: String = "0",
) {
    val playCount: Int get() = playcount.toIntOrNull() ?: 0
    val artworkUrl: String?
        get() = ArtworkNormalizer.bestImageUrl(image)
}

@Serializable(with = TopTrackListSerializer::class)
data class TopTrackList(val tracks: List<TopTrackEntry>)

@Serializable
data class TopTracksFullBody(
    val track: TopTrackList = TopTrackList(emptyList()),
    @SerialName("@attr") val attr: StatTotalAttr = StatTotalAttr(),
)

@Serializable
data class TopTracksFullEnvelope(
    val toptracks: TopTracksFullBody? = null,
    val error: Int? = null,
    val message: String? = null,
)

// ── track.getInfo — the actual first fallback tier _resolveTrackArt() uses:
//    a dedicated per-track lookup for album art, tried only when the track
//    didn't already carry a real (non-placeholder) image.
@Serializable
data class TrackInfoAlbum(val image: List<ImageDto> = emptyList())

@Serializable
data class TrackInfoDetail(
    val image: List<ImageDto> = emptyList(),
    val album: TrackInfoAlbum? = null,
)

@Serializable
data class TrackInfoEnvelope(
    val track: TrackInfoDetail? = null,
    val error: Int? = null,
    val message: String? = null,
)
