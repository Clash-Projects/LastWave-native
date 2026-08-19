package com.lastwave.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.model.AuthState
import com.lastwave.app.data.repository.LastFmAuthCallbackCoordinator
import com.lastwave.app.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Completes Last.fm web authentication from its deep-link callback token. */
sealed interface WebAuthState {
    data object Idle : WebAuthState
    data class AwaitingApproval(val authUrl: String) : WebAuthState
    data object CompletingSignIn : WebAuthState
    data class Error(val message: String) : WebAuthState
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val authCallback: LastFmAuthCallbackCoordinator,
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.authState

    private val _webAuthState = MutableStateFlow<WebAuthState>(WebAuthState.Idle)
    val webAuthState: StateFlow<WebAuthState> = _webAuthState.asStateFlow()

    private var completingToken: String? = null

    init {
        viewModelScope.launch {
            authCallback.pendingToken.collect { token ->
                token ?: return@collect
                completeSignIn(token)
            }
        }
    }

    /** Opens Last.fm's callback-based web authorization flow. */
    fun beginSignIn() {
        _webAuthState.value = WebAuthState.AwaitingApproval(authRepository.authUrl())
    }

    /** Lifecycle fallback; the deep link is normally observed in [init]. */
    fun onReturnedFromBrowser() {
        authCallback.pendingToken.value?.let { token ->
            completeSignIn(token)
        }
    }

    private fun completeSignIn(token: String) {
        authCallback.consume(token)
        if (completingToken != null || authState.value is AuthState.SignedIn) return
        completingToken = token
        _webAuthState.value = WebAuthState.CompletingSignIn
        viewModelScope.launch {
            authRepository.completeWebAuth(token).fold(
                onSuccess = { _webAuthState.value = WebAuthState.Idle }, // authState (SignedIn) takes over navigation from here
                onFailure = { e ->
                    _webAuthState.value = WebAuthState.Error(
                        e.message ?: "Could not complete Last.fm sign-in",
                    )
                },
            )
            completingToken = null
        }
    }

    fun cancelSignIn() {
        _webAuthState.value = WebAuthState.Idle
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    fun continueAsGuest() {
        viewModelScope.launch { authRepository.continueAsGuest() }
    }

    fun dismissError() {
        authRepository.clearError()
        _webAuthState.value = WebAuthState.Idle
    }
}
