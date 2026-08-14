package com.lastwave.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.model.AuthState
import com.lastwave.app.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Sign-in now happens in the system browser (Chrome Custom Tabs — real
 * Chrome, with its own saved passwords/autofill, not this app's own
 * embedded WebView), per explicit request. That meant giving up on
 * detecting completion via a redirect callback: Last.fm's `cb` parameter
 * (the documented way a web-auth flow is supposed to redirect back after
 * approval) did NOT honor a custom app:// scheme when tested — Last.fm's
 * own server just showed its normal static "close your browser and return
 * to the app" confirmation page instead of redirecting anywhere, which is
 * exactly what it does when it doesn't recognize/trust the callback URL.
 * A Custom Tab is a genuinely separate browser process anyway — this app
 * has no way to inspect its page content even if the redirect had worked.
 *
 * So instead: once the browser is opened, this doesn't wait for anything
 * FROM the browser at all. When the person switches back to LastWave
 * (this Activity resumes — see LoginScreen's lifecycle observer), that's
 * the trigger to just attempt the token exchange (auth.getSession) right
 * then. If they'd actually approved it, Last.fm's server already has that
 * recorded and the exchange just succeeds; if they backed out without
 * approving, it fails cleanly and they can try again. No callback URL,
 * deep link, or app-side page-content sniffing required at all — the
 * approval already happened server-side by the time they're back.
 */
sealed interface WebAuthState {
    data object Idle : WebAuthState
    data object FetchingToken : WebAuthState
    data class AwaitingApproval(val token: String, val authUrl: String) : WebAuthState
    data object CompletingSignIn : WebAuthState
    data class Error(val message: String) : WebAuthState
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.authState

    private val _webAuthState = MutableStateFlow<WebAuthState>(WebAuthState.Idle)
    val webAuthState: StateFlow<WebAuthState> = _webAuthState.asStateFlow()

    /** Step 1 — "Connect with Last.fm" tapped. Fetches a fresh token; once
     *  this reaches AwaitingApproval, LoginScreen opens the Custom Tab. */
    fun beginSignIn() {
        _webAuthState.value = WebAuthState.FetchingToken
        viewModelScope.launch {
            authRepository.fetchAuthToken().fold(
                onSuccess = { token ->
                    _webAuthState.value = WebAuthState.AwaitingApproval(token, authRepository.authUrl(token))
                },
                onFailure = { e ->
                    _webAuthState.value = WebAuthState.Error(e.message ?: "Could not reach Last.fm")
                },
            )
        }
    }

    /** Step 2 — called when the app resumes after the Custom Tab was
     *  opened (see class doc for why this is the trigger instead of a
     *  callback). Safe to call more than once — only actually does
     *  anything while still AwaitingApproval for that exact token. */
    fun onReturnedFromBrowser() {
        val state = _webAuthState.value
        if (state !is WebAuthState.AwaitingApproval) return
        _webAuthState.value = WebAuthState.CompletingSignIn
        viewModelScope.launch {
            authRepository.completeWebAuth(state.token).fold(
                onSuccess = { _webAuthState.value = WebAuthState.Idle }, // authState (SignedIn) takes over navigation from here
                onFailure = { e ->
                    // Most common real case: they backed out of the
                    // browser without tapping Allow — not a hard error,
                    // just back to AwaitingApproval so "Open Last.fm
                    // again" (same token, still valid for 60 minutes) is
                    // what they see, not a scary permanent error state.
                    _webAuthState.value = WebAuthState.AwaitingApproval(state.token, authRepository.authUrl(state.token))
                },
            )
        }
    }

    fun cancelSignIn() {
        _webAuthState.value = WebAuthState.Idle
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    fun dismissError() {
        authRepository.clearError()
        _webAuthState.value = WebAuthState.Idle
    }
}
