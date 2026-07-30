package com.lastwave.app.ui.common

import androidx.lifecycle.ViewModel
import com.lastwave.app.data.genre.GenreResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Thin Hilt-scoped wrapper so TrackContextMenuSheet can call the
 *  (singleton, LRU-cached) GenreResolver without every caller needing to
 *  thread it through manually. */
@HiltViewModel
class GenreRowViewModel @Inject constructor(
    private val genreResolver: GenreResolver,
) : ViewModel() {
    suspend fun resolve(name: String, artist: String): String = genreResolver.resolve(name, artist)
}
