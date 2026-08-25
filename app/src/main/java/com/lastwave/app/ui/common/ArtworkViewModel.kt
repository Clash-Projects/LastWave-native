package com.lastwave.app.ui.common

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.artwork.ArtworkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val CRASH_TAG = "ArtworkCrash"

@HiltViewModel
class ArtworkViewModel @Inject constructor(
    private val repository: ArtworkRepository,
) : ViewModel() {

    /** key -> url ("" = confirmed no art anywhere; absent = not yet resolved). */
    val resolved: StateFlow<Map<String, String>> = repository.resolved

    fun resolve(name: String, artist: String) {
        viewModelScope.launch {
            // ArtworkRepository.resolve() already never throws — this
            // try/catch is a second line of defense, so that even a bug in
            // a future change to that contract can't crash the screen a
            // track row happens to be on.
            try {
                repository.resolve(name = name, artist = artist)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Log.e(CRASH_TAG, "Unexpected exception escaped ArtworkRepository.resolve() and was suppressed | Track: $name | Artist: $artist", t)
            }
        }
    }
}
