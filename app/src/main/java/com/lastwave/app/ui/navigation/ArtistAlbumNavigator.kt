package com.lastwave.app.ui.navigation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ArtistAlbumNavTarget {
    data class Artist(val name: String, val browseId: String? = null) : ArtistAlbumNavTarget
    data class Album(val title: String, val artist: String = "", val browseId: String? = null) : ArtistAlbumNavTarget
}

/**
 * Singleton navigation dispatcher that lets any component (TrackContextMenuSheet,
 * TrackDetailsSheet, PlayerHost, MiniPlayer, Search, etc.) seamlessly navigate to
 * native Artist and Album detail destinations without 20 levels of callback drilling.
 */
@Singleton
class ArtistAlbumNavigator @Inject constructor() {
    private val _events = MutableSharedFlow<ArtistAlbumNavTarget>(extraBufferCapacity = 1)
    val events: SharedFlow<ArtistAlbumNavTarget> = _events

    fun openArtist(name: String, browseId: String? = null) {
        if (name.isBlank()) return
        _events.tryEmit(ArtistAlbumNavTarget.Artist(name.trim(), browseId?.takeIf(String::isNotBlank)))
    }

    fun openAlbum(title: String, artist: String = "", browseId: String? = null) {
        if (title.isBlank()) return
        _events.tryEmit(ArtistAlbumNavTarget.Album(title.trim(), artist.trim(), browseId?.takeIf(String::isNotBlank)))
    }
}
