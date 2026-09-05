package com.lastwave.app.ui.navigation

/**
 * Route constants for the whole app. Declaring the full set up front — even
 * though only [Login] has a screen behind it yet — means NavGraph.kt's
 * shape doesn't change on every future module; each module just adds its
 * composable() block under the route that's already named here.
 */
sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Login : Screen("login")
    data object MainShell : Screen("main_shell")

    // Bottom-nav swipeable tabs (Home module wires these + the pager together)
    data object Home : Screen("home")
    data object Create : Screen("create")
    data object Playlist : Screen("playlist")

    // Pushed screens, slide-in from the right (matches PAGE_TRANSITIONS in nav.js)
    data object Discover : Screen("discover")
    data object Genres : Screen("genres")
    data object Search : Screen("search")
    data object Settings : Screen("settings")
    data object ScrobblerApps : Screen("scrobbler_apps")
    data object Friends : Screen("friends")
    data object Downloads : Screen("downloads")
    data object ExcludedSongs : Screen("excluded_songs")
    data object YouTubeImport : Screen("youtube_import")
    data object YouTubeLogin : Screen("youtube_login")
    data object FeedPlaylistDetail : Screen("feed_playlist/{playlistId}") {
        fun createRoute(playlistId: String) = "feed_playlist/${android.net.Uri.encode(playlistId)}"
    }
    data object PlaylistDetail : Screen("playlist_detail/{playlistId}") {
        fun createRoute(playlistId: Long) = "playlist_detail/$playlistId"
    }
    data object ArtistDetail : Screen("artist_detail/{artistName}?browseId={browseId}") {
        fun createRoute(artistName: String, browseId: String? = null): String {
            val encName = android.net.Uri.encode(artistName)
            val encBrowseId = if (!browseId.isNullOrBlank()) android.net.Uri.encode(browseId) else ""
            return "artist_detail/$encName?browseId=$encBrowseId"
        }
    }
    data object AlbumDetail : Screen("album_detail/{albumTitle}?artistName={artistName}&browseId={browseId}") {
        fun createRoute(albumTitle: String, artistName: String = "", browseId: String? = null): String {
            val encTitle = android.net.Uri.encode(albumTitle)
            val encArtist = android.net.Uri.encode(artistName)
            val encBrowseId = if (!browseId.isNullOrBlank()) android.net.Uri.encode(browseId) else ""
            return "album_detail/$encTitle?artistName=$encArtist&browseId=$encBrowseId"
        }
    }
}

