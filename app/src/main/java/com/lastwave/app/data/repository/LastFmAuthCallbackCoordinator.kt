package com.lastwave.app.data.repository

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

const val LAST_FM_AUTH_CALLBACK_URI = "lastwave://auth-callback"

/** Carries the browser redirect safely into the login ViewModel. */
@Singleton
class LastFmAuthCallbackCoordinator @Inject constructor() {
    private val _pendingToken = MutableStateFlow<String?>(null)
    val pendingToken: StateFlow<String?> = _pendingToken.asStateFlow()

    private var lastAcceptedToken: String? = null

    @Synchronized
    fun capture(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "lastwave" || uri.host != "auth-callback") return false

        val token = uri.getQueryParameter("token")?.trim().orEmpty()
        if (token.isEmpty()) return false

        if (token != lastAcceptedToken) {
            lastAcceptedToken = token
            _pendingToken.value = token
        }
        return true
    }

    @Synchronized
    fun consume(token: String) {
        if (_pendingToken.value == token) _pendingToken.value = null
    }
}
