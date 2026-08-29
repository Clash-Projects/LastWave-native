package com.lastwave.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import com.lastwave.app.data.ytmusic.YtMusicSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class YouTubeLoginUiState(
    val verifying: Boolean = false,
    val connectedName: String? = null,
    val errorMessage: String? = null,
)

@HiltViewModel
class YouTubeLoginViewModel @Inject constructor(
    private val ytAuthManager: YtMusicAuthManager,
    private val innerTube: InnerTubeMusicApi,
    private val syncManager: YtMusicSyncManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(YouTubeLoginUiState())
    val uiState: StateFlow<YouTubeLoginUiState> = _uiState.asStateFlow()

    init {
        val current = ytAuthManager.connection.value
        if (current.isConnected) {
            _uiState.update { it.copy(connectedName = current.accountName) }
        }
    }

    /**
     * Persists the cookies captured from the sign-in flow or user input, then
     * resolves the account identity through InnerTube itself.
     */
    fun attemptConnect(rawInput: String?) {
        if (_uiState.value.verifying) return
        val raw = rawInput.orEmpty().trim()
        val cleanedCookies = sanitizeCookieString(raw)

        val hasSapisid = listOf("__Secure-3PAPISID=", "SAPISID=", "APISID=").any { it in cleanedCookies }
        if (!hasSapisid) {
            _uiState.update {
                it.copy(errorMessage = "Sign-in incomplete — please ensure you're signed in on YouTube Music, or paste a valid session cookie.")
            }
            return
        }

        _uiState.update { it.copy(verifying = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                ytAuthManager.connect(cleanedCookies, "", null, null)
                val info = runCatching { innerTube.fetchAccountInfo() }.getOrNull()
                val displayName = info?.accountName ?: "Google account"
                if (info != null) {
                    ytAuthManager.updateAccountIdentity(info.accountName, info.channelHandle, info.photoUrl)
                }
                _uiState.update { it.copy(verifying = false, connectedName = displayName) }
                viewModelScope.launch {
                    runCatching { syncManager.syncNow("connected") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        verifying = false,
                        errorMessage = "Couldn't finish connecting: ${e.localizedMessage ?: e.message}",
                    )
                }
            } catch (error: LinkageError) {
                _uiState.update {
                    it.copy(
                        verifying = false,
                        errorMessage = "YouTube Music sign-in isn't supported by this ROM.",
                    )
                }
            }
        }
    }

    private fun sanitizeCookieString(input: String): String {
        var str = input
        if (str.startsWith("Cookie:", ignoreCase = true)) {
            str = str.substringAfter(":").trim()
        }
        if (str.contains("-H 'cookie:", ignoreCase = true)) {
            str = str.substringAfter("-H 'cookie:").substringBefore("'").trim()
        }
        if (str.contains("-H \"cookie:", ignoreCase = true)) {
            str = str.substringAfter("-H \"cookie:").substringBefore("\"").trim()
        }
        return str
    }

    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }
}
