package com.lastwave.app.ui.generate

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class MixSeed(
    val trackName: String,
    val artistName: String,
    val videoId: String? = null,
)

/**
 * "Start Mix with this Song" needs to reach NavGraph (at the root navigation
 * level) to navigate to the Generator screen, and GenerateViewModel to populate
 * the seed and kick off similar-song generation.
 *
 * Uses both a persistent [pendingSeed] StateFlow (read and consumed when
 * navigating) and [requests] SharedFlow for ephemeral listeners (e.g. collapsing
 * the full player in PlayerHost).
 */
@Singleton
class MixLauncher @Inject constructor() {
    private val _requests = MutableSharedFlow<MixSeed>(replay = 1, extraBufferCapacity = 2)
    val requests: SharedFlow<MixSeed> = _requests

    private val _pendingSeed = MutableStateFlow<MixSeed?>(null)
    val pendingSeed: StateFlow<MixSeed?> = _pendingSeed.asStateFlow()

    fun startMix(trackName: String, artistName: String, videoId: String? = null) {
        if (trackName.isBlank() || artistName.isBlank()) return
        val seed = MixSeed(trackName.trim(), artistName.trim(), videoId?.trim())
        _pendingSeed.value = seed
        _requests.tryEmit(seed)
    }

    fun consume(): MixSeed? {
        val seed = _pendingSeed.value
        _pendingSeed.value = null
        return seed
    }
}
