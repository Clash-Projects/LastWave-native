package com.lastwave.app.data.search

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchHistoryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _history = MutableStateFlow<List<String>>(loadHistory())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    fun add(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        val current = _history.value.toMutableList()
        current.removeAll { it.equals(trimmed, ignoreCase = true) }
        current.add(0, trimmed)
        val capped = current.take(MAX_HISTORY_ITEMS)
        saveHistory(capped)
        _history.value = capped
    }

    fun remove(query: String) {
        val current = _history.value.toMutableList()
        current.removeAll { it.equals(query, ignoreCase = true) }
        saveHistory(current)
        _history.value = current
    }

    fun clear() {
        saveHistory(emptyList())
        _history.value = emptyList()
    }

    private fun loadHistory(): List<String> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    private fun saveHistory(list: List<String>) {
        val encoded = json.encodeToString(list)
        prefs.edit().putString(KEY_HISTORY, encoded).apply()
    }

    companion object {
        private const val PREFS_NAME = "lastwave_search_history"
        private const val KEY_HISTORY = "recent_queries"
        private const val MAX_HISTORY_ITEMS = 25
    }
}
