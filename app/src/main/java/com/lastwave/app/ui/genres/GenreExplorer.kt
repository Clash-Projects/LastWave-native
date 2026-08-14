package com.lastwave.app.ui.genres

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Explore this genre" from a track's context menu (Home, Discover,
 * Playlist, Search — anywhere TrackContextMenuSheet's Genre row appears)
 * needs to reach the Genres screen, which is a PUSHED NavHost destination
 * that doesn't exist yet at the moment the menu is tapped — unlike
 * MixLauncher's SharedFlow (Generate's ViewModel is already alive as a
 * MainShell tab), a hot event here could be emitted before anything is
 * listening and simply be lost. Plain persisted state instead: whoever
 * taps a genre row sets [pendingGenre], NavGraph's top level notices it's
 * non-null and navigates to Genres, and GenresViewModel reads + consumes
 * it in its own init — correct regardless of exactly when each side
 * happens to start observing.
 */
@Singleton
class GenreExplorer @Inject constructor() {
    private val _pendingGenre = MutableStateFlow<String?>(null)
    val pendingGenre: StateFlow<String?> = _pendingGenre.asStateFlow()

    fun explore(genre: String) {
        if (genre.isNotBlank()) _pendingGenre.value = genre
    }

    fun consume() {
        _pendingGenre.value = null
    }
}
