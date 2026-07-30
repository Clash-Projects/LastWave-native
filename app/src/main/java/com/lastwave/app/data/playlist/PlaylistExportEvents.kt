package com.lastwave.app.data.playlist

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fires only when the best-effort public Download/LastWave/ export
 * genuinely fails (not when it's skipped as a harmless no-op, e.g. no
 * legacy storage permission). The Room save has already succeeded by the
 * time this could fire, so this is a non-blocking heads-up, not a
 * generation-failure error.
 */
@Singleton
class PlaylistExportEvents @Inject constructor() {
    private val _failures = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val failures: SharedFlow<String> = _failures.asSharedFlow()

    fun notifyFailure(message: String) {
        _failures.tryEmit(message)
    }
}
