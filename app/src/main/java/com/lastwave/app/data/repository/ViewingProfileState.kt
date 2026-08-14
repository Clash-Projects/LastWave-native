package com.lastwave.app.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide "which Last.fm account is currently being viewed" — null means
 * your own (the signed-in session's username). Set by HomeViewModel when
 * you switch to a friend via the friend-switcher / back to your own
 * profile, and read by anything elsewhere in the app that should follow
 * along with whichever profile Home is currently showing — right now
 * that's GenerateRepository, so playlists generated while viewing a
 * friend's profile are generated FROM that friend's top tracks/recent
 * tracks/loved tracks, not your own.
 *
 * A small shared singleton rather than passing this through navigation
 * args or a shared ViewModel, since MainShell's tabs (Home/Generate/
 * Playlist) are independent NavHost-free HorizontalPager pages with their
 * own separately-scoped ViewModels — this is the simplest thing that lets
 * two of them agree on "whose data is this" without restructuring how
 * they're wired.
 */
@Singleton
class ViewingProfileState @Inject constructor() {
    private val _viewingUsername = MutableStateFlow<String?>(null)
    val viewingUsername: StateFlow<String?> = _viewingUsername.asStateFlow()

    /** Pass null to switch back to the signed-in user's own profile. */
    fun set(username: String?) {
        _viewingUsername.value = username
    }
}
