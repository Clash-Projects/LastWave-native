package com.lastwave.app.ui.auth

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.model.AuthState
import com.lastwave.app.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class LoginUiState(
    val apiKey: String = "",
    val apiSecret: String = "",
    val authorizeUrl: String? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val sessionPreferences: SessionPreferences,
    deepLinkDispatcher: AuthDeepLinkDispatcher,
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.authState

    private val _authorizeUrl = MutableStateFlow<String?>(null)

    /**
     * Live mirror of the persisted API key/secret, read directly off
     * DataStore. Uses SharingStarted.Eagerly — NOT WhileSubscribed — because
     * this is read internally by exchangeWithStoredCredentials() whenever the
     * OAuth deep link fires, regardless of whether any screen happens to be
     * collecting it at that moment. With WhileSubscribed, if nothing in the
     * UI ever subscribes to this flow, it never starts collecting from
     * DataStore at all and .value stays stuck at its seeded empty default —
     * which is exactly the "too short after normalisation" bug this replaces.
     */
    val credentials: StateFlow<Pair<String, String>> = sessionPreferences.session
        .map { session -> session.apiKey to session.apiSecret }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "" to "")

    val authorizeUrl: StateFlow<String?> = _authorizeUrl

    init {
        viewModelScope.launch {
            deepLinkDispatcher.tokens.collect { token ->
                exchangeWithStoredCredentials(token)
            }
        }
    }

    fun saveCredentials(apiKey: String, apiSecret: String) {
        viewModelScope.launch {
            authRepository.saveApiCredentials(apiKey, apiSecret)
        }
    }

    fun startAuth(apiKey: String, apiSecret: String) {
        viewModelScope.launch {
            authRepository.saveApiCredentials(apiKey, apiSecret)
            authRepository.startAuth(apiKey).onSuccess { url ->
                _authorizeUrl.value = url
            }
        }
    }

    /** "I've authorized, continue" manual fallback — uses the token stashed
     *  during startAuth() instead of waiting on the deep link. */
    fun continueAfterAuth() {
        viewModelScope.launch {
            val token = authRepository.pendingToken() ?: return@launch
            exchangeWithStoredCredentials(token)
        }
    }

    private suspend fun exchangeWithStoredCredentials(token: String) {
        val (apiKey, apiSecret) = credentials.value
        authRepository.exchangeToken(apiKey, apiSecret, token)
        _authorizeUrl.value = null
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    fun dismissError() {
        authRepository.clearError()
    }
}
