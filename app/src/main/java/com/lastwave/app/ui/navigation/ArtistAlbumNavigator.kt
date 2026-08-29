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
    private var lastNavTimestamp = 0L
    private var lastNavTarget: ArtistAlbumNavTarget? = null

    fun openArtist(name: String, browseId: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val target = ArtistAlbumNavTarget.Artist(trimmed, browseId?.takeIf(String::isNotBlank))
        val now = System.currentTimeMillis()
        if (target == lastNavTarget && now - lastNavTimestamp < 600L) {
            return
        }
        lastNavTimestamp = now
        lastNavTarget = target
        _events.tryEmit(target)
    }

    fun openAlbum(title: String, artist: String = "", browseId: String? = null) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) return
        val target = ArtistAlbumNavTarget.Album(trimmedTitle, artist.trim(), browseId?.takeIf(String::isNotBlank))
        val now = System.currentTimeMillis()
        if (target == lastNavTarget && now - lastNavTimestamp < 600L) {
            return
        }
        lastNavTimestamp = now
        lastNavTarget = target
        _events.tryEmit(target)
    }
}
