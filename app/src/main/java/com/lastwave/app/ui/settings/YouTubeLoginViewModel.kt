package com.lastwave.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import com.lastwave.app.data.music.InnerTubeMusicApi
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

    /**
     * Persists the cookies captured from the sign-in WebView, then resolves
     * the account identity through InnerTube itself (authoritative — no HTML
     * scraping). Sync kicks off immediately so the user sees their playlists
     * appear right away.
     */
    fun attemptConnect(rawCookieHeader: String?) {
        if (_uiState.value.verifying) return
        val cookies = rawCookieHeader.orEmpty()
        val hasSapisid = listOf("__Secure-3PAPISID=", "SAPISID=", "APISID=").any { it in cookies }
        if (!hasSapisid) {
            _uiState.update {
                it.copy(errorMessage = "Sign-in incomplete — finish signing in, then tap \"I'm signed in\".")
            }
            return
        }

        _uiState.update { it.copy(verifying = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                ytAuthManager.connect(rawCookieHeader!!, "", null, null)
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

    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }
}
