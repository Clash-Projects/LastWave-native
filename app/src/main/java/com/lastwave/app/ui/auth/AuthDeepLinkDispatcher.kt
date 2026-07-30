package com.lastwave.app.ui.auth

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MainActivity.onNewIntent() pushes the OAuth token here as soon as the
 * `lastwave://auth?token=...` deep link fires; AuthViewModel collects it and
 * calls AuthRepository.exchangeToken(). Equivalent to window._lfmDeepLink()
 * in bridge.js, minus the WebView round-trip.
 */
@Singleton
class AuthDeepLinkDispatcher @Inject constructor() {
    private val _tokens = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val tokens: SharedFlow<String> = _tokens

    fun onTokenReceived(token: String) {
        _tokens.tryEmit(token)
    }
}
