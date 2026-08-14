package com.lastwave.app.ui.settings

import androidx.lifecycle.ViewModel
import com.lastwave.app.service.ScrobbleDebugLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class ScrobblerDebugLogViewModel @Inject constructor(
    private val debugLog: ScrobbleDebugLog,
) : ViewModel() {
    val entries: StateFlow<List<String>> = debugLog.entries

    fun clear() = debugLog.clear()
}
