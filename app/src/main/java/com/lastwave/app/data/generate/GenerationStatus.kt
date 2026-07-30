package com.lastwave.app.data.generate

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Immutable
data class GenerationProgress(
    val isGenerating: Boolean = false,
    val message: String = "",
)

/**
 * Single source of truth for "a playlist is currently generating", so the
 * exact same progress card + state can be shown on both the Generate screen
 * and the Playlist screen without either one duplicating the other's logic.
 * GenerateViewModel is the only writer; any screen can read [state].
 */
@Singleton
class GenerationStatus @Inject constructor() {
    private val _state = MutableStateFlow(GenerationProgress())
    val state: StateFlow<GenerationProgress> = _state.asStateFlow()

    fun update(isGenerating: Boolean, message: String = "") {
        _state.value = GenerationProgress(isGenerating, message)
    }
}
