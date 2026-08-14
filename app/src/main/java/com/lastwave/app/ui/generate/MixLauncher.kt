package com.lastwave.app.ui.generate

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

data class MixSeed(val trackName: String, val artistName: String)

/**
 * "Start Mix with this Song" (§6) needs to reach GenerateViewModel — which
 * lives on the Create tab, not wherever the track menu was opened from
 * (Home, Discover, Search, Playlist, Genres). Same cross-component signal
 * pattern as AuthDeepLinkDispatcher: a singleton SharedFlow, one emitter
 * (TrackContextMenuSheet's default Start Mix action), two collectors
 * (GenerateViewModel applies the seed and generates; MainShell switches
 * the pager to the Create tab). Neither collector needs to know about the
 * other, and this works whether MainShell is currently visible or sitting
 * underneath a pushed screen like Discover — its composition (and this
 * LaunchedEffect) isn't disposed just because it's not on top.
 */
@Singleton
class MixLauncher @Inject constructor() {
    private val _requests = MutableSharedFlow<MixSeed>(extraBufferCapacity = 1)
    val requests: SharedFlow<MixSeed> = _requests

    fun startMix(trackName: String, artistName: String) {
        if (trackName.isBlank() || artistName.isBlank()) return
        _requests.tryEmit(MixSeed(trackName, artistName))
    }
}
