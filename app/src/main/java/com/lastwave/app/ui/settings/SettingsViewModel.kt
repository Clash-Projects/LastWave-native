package com.lastwave.app.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.backup.BackupRepository
import com.lastwave.app.data.backup.RestoreResult
import com.lastwave.app.data.generate.GenerateRepository
import com.lastwave.app.data.local.AccentMode
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SessionData
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.repository.AuthRepository
import com.lastwave.app.data.repository.ThemeRepository
import com.lastwave.app.data.repository.ThemeUiState
import com.lastwave.app.util.FileExportHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsScreenState(
    val session: SessionData = SessionData(),
    val theme: ThemeUiState? = null,
    val misc: MiscSettings = MiscSettings(),
    val seenTracksCount: Int = 0,
    val toastMessage: String? = null,
    val showColorWheel: Boolean = false,
    val showClearAllConfirm: Boolean = false,
    val showRestoreConfirm: Boolean = false,
    val pendingRestoreContent: String? = null,
    val pendingRestorePlaylistCount: Int? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val sessionPreferences: SessionPreferences,
    private val themeRepository: ThemeRepository,
    private val settingsPreferences: SettingsPreferences,
    private val generateRepository: GenerateRepository,
    private val backupRepository: BackupRepository,
    private val fileExportHelper: FileExportHelper,
) : ViewModel() {

    val session: StateFlow<SessionData> = sessionPreferences.session
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionData())

    val theme: StateFlow<ThemeUiState> = themeRepository.uiState

    val misc: StateFlow<MiscSettings> = settingsPreferences.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, MiscSettings())

    private val _uiState = MutableStateFlow(SettingsScreenState())
    val uiState: StateFlow<SettingsScreenState> = _uiState.asStateFlow()

    init {
        refreshSeenTracksCount()
    }

    fun refreshSeenTracksCount() {
        viewModelScope.launch {
            val count = generateRepository.seenTracksCount()
            _uiState.update { it.copy(seenTracksCount = count) }
        }
    }

    fun saveApiCredentials(apiKey: String, apiSecret: String) {
        viewModelScope.launch { authRepository.saveApiCredentials(apiKey, apiSecret) }
    }

    fun logOut(onComplete: () -> Unit) {
        viewModelScope.launch {
            sessionPreferences.logOutApiCredentials()
            onComplete()
        }
    }

    fun clearSession(onComplete: () -> Unit) {
        viewModelScope.launch {
            sessionPreferences.clearAll()
            onComplete()
        }
    }

    // ── Appearance (§8.2 / §8.3 / §8.4) ──

    fun setAmoled(enabled: Boolean) = viewModelScope.launch { themeRepository.setAmoled(enabled) }
    fun setAccentMode(mode: AccentMode) = viewModelScope.launch { themeRepository.setMode(mode) }
    fun setManualAccent(color: Color) = viewModelScope.launch { themeRepository.setManualAccent(color) }
    fun openColorWheel() = _uiState.update { it.copy(showColorWheel = true) }
    fun dismissColorWheel() = _uiState.update { it.copy(showColorWheel = false) }
    fun applyCustomColor(color: Color) {
        setManualAccent(color)
        dismissColorWheel()
    }

    fun setItunesArtwork(enabled: Boolean) = viewModelScope.launch { settingsPreferences.setItunesArtwork(enabled) }
    fun setListenBrainzArtwork(enabled: Boolean) = viewModelScope.launch { settingsPreferences.setListenBrainzArtwork(enabled) }

    // ── Data management (§8.5) ──

    fun clearDiscoveryHistory() {
        viewModelScope.launch {
            generateRepository.clearSeenTracks()
            refreshSeenTracksCount()
            _uiState.update { it.copy(toastMessage = "Discovery history cleared") }
        }
    }

    fun requestClearAllData() = _uiState.update { it.copy(showClearAllConfirm = true) }
    fun dismissClearAllConfirm() = _uiState.update { it.copy(showClearAllConfirm = false) }
    fun confirmClearAllData(onComplete: () -> Unit) {
        viewModelScope.launch {
            sessionPreferences.clearAll()
            generateRepository.clearSeenTracks()
            _uiState.update { it.copy(showClearAllConfirm = false) }
            onComplete()
        }
    }

    // ── Backup & Restore (§8.6) ──

    fun exportBackup(appVersionName: String) {
        viewModelScope.launch {
            try {
                val json = backupRepository.buildBackup(appVersionName)
                val filename = "lastwave-backup-${System.currentTimeMillis()}.json"
                fileExportHelper.saveToDocuments(filename, json)
                _uiState.update { it.copy(toastMessage = "Saved $filename") }
            } catch (e: Exception) {
                _uiState.update { it.copy(toastMessage = "Backup failed: ${e.message}") }
            }
        }
    }

    /** Called once the file picker returns raw file content — validates and
     *  stages the restore, showing a confirm dialog with the item count
     *  before actually applying anything (§8.6). */
    fun stagePendingRestore(content: String) {
        viewModelScope.launch {
            val parseCheck = try {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString(com.lastwave.app.data.backup.BackupFile.serializer(), content)
            } catch (e: Exception) {
                _uiState.update { it.copy(toastMessage = "That file doesn't look like a LastWave backup") }
                return@launch
            }
            _uiState.update {
                it.copy(
                    showRestoreConfirm = true,
                    pendingRestoreContent = content,
                    pendingRestorePlaylistCount = parseCheck.playlists.size,
                )
            }
        }
    }

    fun dismissRestoreConfirm() = _uiState.update { it.copy(showRestoreConfirm = false, pendingRestoreContent = null, pendingRestorePlaylistCount = null) }

    fun confirmRestore(onComplete: () -> Unit) {
        val content = _uiState.value.pendingRestoreContent ?: return
        viewModelScope.launch {
            when (val result = backupRepository.restore(content)) {
                is RestoreResult.Success -> {
                    _uiState.update { it.copy(showRestoreConfirm = false, pendingRestoreContent = null, toastMessage = "Restored ${result.playlistCount} playlist(s)") }
                    kotlinx.coroutines.delay(900)
                    onComplete()
                }
                RestoreResult.UnsupportedSchema -> _uiState.update { it.copy(showRestoreConfirm = false, toastMessage = "This backup was made with a newer version of LastWave") }
                RestoreResult.InvalidFile -> _uiState.update { it.copy(showRestoreConfirm = false, toastMessage = "That file doesn't look like a LastWave backup") }
                is RestoreResult.Failed -> _uiState.update { it.copy(showRestoreConfirm = false, toastMessage = "Restore failed: ${result.message}") }
            }
        }
    }

    fun dismissToast() = _uiState.update { it.copy(toastMessage = null) }
}
