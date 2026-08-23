package com.lastwave.app.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.backup.BackupRepository
import com.lastwave.app.data.backup.RestoreResult
import com.lastwave.app.data.generate.GenerateRepository
import com.lastwave.app.data.local.AccentMode
import com.lastwave.app.data.local.EqualizerSettings
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.ScrobblerPreferences
import com.lastwave.app.data.local.ScrobblerSettings
import com.lastwave.app.data.local.SessionData
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.playlist.PlaylistRepository
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

enum class PendingRestoreKind { FULL_BACKUP, PLAYLIST_MIRROR }

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
    val pendingRestoreKind: PendingRestoreKind? = null,
    val pendingRestoreUri: android.net.Uri? = null,
    val showSessionKeyDialog: Boolean = false,
    val sessionKeyError: String? = null,
    val sessionKeyLoading: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val sessionPreferences: SessionPreferences,
    private val themeRepository: ThemeRepository,
    private val settingsPreferences: SettingsPreferences,
    private val generateRepository: GenerateRepository,
    private val backupRepository: BackupRepository,
    private val playlistRepository: PlaylistRepository,
    private val fileExportHelper: FileExportHelper,
    private val scrobblerPreferences: ScrobblerPreferences,
    private val equalizerPreferences: com.lastwave.app.data.local.EqualizerPreferences,
    private val ytAuthManager: com.lastwave.app.data.ytmusic.YtMusicAuthManager,
    private val ytMusicSyncManager: com.lastwave.app.data.ytmusic.YtMusicSyncManager,
    private val ytMusicPreferences: com.lastwave.app.data.ytmusic.YtMusicPreferences,
    private val downloadedTrackDao: com.lastwave.app.data.local.db.DownloadedTrackDao,
    val playlistImportManager: com.lastwave.app.data.playlist.PlaylistImportManager,
    val innerTube: com.lastwave.app.data.music.InnerTubeMusicApi,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    val authState: StateFlow<com.lastwave.app.data.model.AuthState> = authRepository.authState

    /** YouTube Music account connection + playlist-sync state (§ YouTube Music). */
    val ytConnection: StateFlow<com.lastwave.app.data.ytmusic.YtConnection> = ytAuthManager.connection
    val ytSyncState: StateFlow<com.lastwave.app.data.ytmusic.YtSyncState> = ytMusicSyncManager.state
    val ytSyncEnabled: StateFlow<Boolean> = ytMusicPreferences.syncEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val ytLastSyncAt: StateFlow<Long> = ytMusicPreferences.lastSyncAt
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val session: StateFlow<SessionData> = kotlinx.coroutines.flow.combine(
        sessionPreferences.session,
        authRepository.authState,
    ) { sess, auth ->
        if (sess.username.isNotBlank()) {
            sess
        } else if (auth is com.lastwave.app.data.model.AuthState.SignedIn && auth.username.isNotBlank()) {
            sess.copy(username = auth.username)
        } else {
            sess
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionData())

    val theme: StateFlow<ThemeUiState> = themeRepository.uiState

    val misc: StateFlow<MiscSettings> = settingsPreferences.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, MiscSettings())

    val scrobbler: StateFlow<ScrobblerSettings> = scrobblerPreferences.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, ScrobblerSettings())

    /** Experimental 15-band equalizer state (Settings → Experimental). */
    val equalizer: StateFlow<EqualizerSettings> = equalizerPreferences.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, EqualizerSettings())

    val downloadCount: StateFlow<Int> = downloadedTrackDao.count()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val downloadTotalBytes: StateFlow<Long?> = downloadedTrackDao.totalBytes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

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
    fun setLiquidGlass(enabled: Boolean) = viewModelScope.launch { themeRepository.setLiquidGlass(enabled) }
    fun setAccentMode(mode: AccentMode) = viewModelScope.launch { themeRepository.setMode(mode) }
    fun setManualAccent(color: Color) = viewModelScope.launch { themeRepository.setManualAccent(color) }
    fun openColorWheel() = _uiState.update { it.copy(showColorWheel = true) }
    fun dismissColorWheel() = _uiState.update { it.copy(showColorWheel = false) }
    fun applyCustomColor(color: Color) {
        setManualAccent(color)
        dismissColorWheel()
    }

    fun setDynamicNowPlaying(enabled: Boolean) = viewModelScope.launch { themeRepository.setDynamicNowPlaying(enabled) }
    fun setUseCustomFont(enabled: Boolean) = viewModelScope.launch { settingsPreferences.setUseCustomFont(enabled) }
    fun setPreferQobuzStreaming(enabled: Boolean) = viewModelScope.launch { settingsPreferences.setPreferQobuzStreaming(enabled) }
    fun setQobuzQuality(quality: Int) = viewModelScope.launch { settingsPreferences.setQobuzQuality(quality) }
    fun setMusicEnhancer(enabled: Boolean) = viewModelScope.launch { settingsPreferences.setMusicEnhancer(enabled) }

    // ── Experimental: 15-band equalizer ──

    fun setEqualizerEnabled(enabled: Boolean) = viewModelScope.launch { equalizerPreferences.setEnabled(enabled) }

    /** Selecting a preset also switches the EQ on — an off equalizer with a
     *  fresh preset would read as a dead control. */
    fun applyEqPreset(name: String) {
        com.lastwave.app.data.local.EqualizerPresets.byName(name)?.let { preset ->
            viewModelScope.launch { equalizerPreferences.applyPreset(preset) }
        }
    }

    /** Manual band drag → curve becomes Custom. */
    fun setEqBandGain(bandIndex: Int, gainDb: Float) = viewModelScope.launch {
        equalizerPreferences.setBandGain(bandIndex, gainDb)
    }

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
            playlistRepository.clearAll()
            _uiState.update { it.copy(showClearAllConfirm = false) }
            onComplete()
        }
    }

    // ── Backup & Restore (§8.6) ──

    fun exportBackup(uri: android.net.Uri, appVersionName: String) {
        viewModelScope.launch {
            try {
                val json = backupRepository.buildBackup(appVersionName)
                if (json.isBlank()) {
                    _uiState.update { it.copy(toastMessage = "Backup creation failed: empty data") }
                    return@launch
                }
                val bytesWritten = fileExportHelper.writeTextToUri(uri, json)
                _uiState.update { it.copy(toastMessage = "Backup saved successfully (${bytesWritten / 1024} KB)") }
            } catch (e: Exception) {
                _uiState.update { it.copy(toastMessage = "Backup failed: ${e.localizedMessage ?: e.message}") }
            }
        }
    }

    fun handleRestorePicked(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val content = fileExportHelper.readTextFromUri(uri)
                if (content.isNullOrBlank()) {
                    _uiState.update { it.copy(toastMessage = "Selected file is empty or unreadable") }
                    return@launch
                }
                stagePendingRestore(content, uri)
            } catch (e: Exception) {
                _uiState.update { it.copy(toastMessage = "Restore read error: ${e.localizedMessage ?: e.message}") }
            }
        }
    }

    fun handleCsvPicked(uri: android.net.Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                _uiState.update { it.copy(toastMessage = "Matching and importing CSV songs...") }
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: error("Could not open selected CSV file")

                // Extract filename
                val cursor = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                val displayName = cursor?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "Imported Playlist"

                val (saved, result) = playlistImportManager.importCsvStream(inputStream, displayName)
                _uiState.update {
                    it.copy(toastMessage = "Imported \"${saved.title}\" (${result.matchedCount}/${result.totalRows} verified)")
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(toastMessage = "CSV Import failed: ${e.localizedMessage ?: e.message}")
                }
            }
        }
    }

    /** Called once the file picker returns raw file content — validates and
     *  stages the restore, showing a confirm dialog with the item count
     *  before actually applying anything (§8.6). */
    fun stagePendingRestore(content: String, uri: android.net.Uri) {
        viewModelScope.launch {
            val backup = try {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString(com.lastwave.app.data.backup.BackupFile.serializer(), content)
                    .takeIf { it.type == "lastwave-backup" }
            } catch (e: Exception) { null }
            val mirrorCount = playlistRepository.publicMirrorPlaylistCount(content)
            if (backup == null && mirrorCount == null) {
                _uiState.update { it.copy(toastMessage = "That isn't a LastWave backup or playlist JSON") }
                return@launch
            }
            _uiState.update {
                it.copy(
                    showRestoreConfirm = true,
                    pendingRestoreContent = content,
                    pendingRestorePlaylistCount = backup?.playlists?.size ?: mirrorCount,
                    pendingRestoreKind = if (backup != null) PendingRestoreKind.FULL_BACKUP else PendingRestoreKind.PLAYLIST_MIRROR,
                    pendingRestoreUri = uri,
                )
            }
        }
    }

    fun dismissRestoreConfirm() = _uiState.update {
        it.copy(
            showRestoreConfirm = false,
            pendingRestoreContent = null,
            pendingRestorePlaylistCount = null,
            pendingRestoreKind = null,
            pendingRestoreUri = null,
        )
    }

    fun confirmRestore(onComplete: () -> Unit) {
        val pending = _uiState.value
        val content = pending.pendingRestoreContent ?: return
        viewModelScope.launch {
            if (pending.pendingRestoreKind == PendingRestoreKind.PLAYLIST_MIRROR) {
                pending.pendingRestoreUri?.let(fileExportHelper::rememberPlaylistMirrorUri)
                playlistRepository.importPublicMirror(content)
                    .onSuccess { count ->
                        _uiState.update {
                            it.copy(
                                showRestoreConfirm = false,
                                pendingRestoreContent = null,
                                pendingRestoreKind = null,
                                pendingRestoreUri = null,
                                toastMessage = "Synced $count playlist(s) from local JSON",
                            )
                        }
                        kotlinx.coroutines.delay(900)
                        onComplete()
                    }
                    .onFailure { error ->
                        _uiState.update {
                            it.copy(showRestoreConfirm = false, toastMessage = "Playlist sync failed: ${error.message}")
                        }
                    }
                return@launch
            }

            when (val result = backupRepository.restore(content)) {
                is RestoreResult.Success -> {
                    val historyNote = if (result.seenTrackCount > 0) " and discovery history" else ""
                    _uiState.update {
                        it.copy(
                            showRestoreConfirm = false,
                            pendingRestoreContent = null,
                            toastMessage = "Restored ${result.playlistCount} playlist(s)$historyNote",
                        )
                    }
                    kotlinx.coroutines.delay(900)
                    onComplete()
                }
                RestoreResult.UnsupportedSchema -> _uiState.update { it.copy(showRestoreConfirm = false, toastMessage = "This backup was made with a newer version of LastWave") }
                RestoreResult.InvalidFile -> _uiState.update { it.copy(showRestoreConfirm = false, toastMessage = "That file doesn't look like a LastWave backup") }
                is RestoreResult.Failed -> _uiState.update { it.copy(showRestoreConfirm = false, toastMessage = "Restore failed: ${result.message}") }
            }
        }
    }

    fun showToast(message: String) = _uiState.update { it.copy(toastMessage = message) }

    fun dismissToast() = _uiState.update { it.copy(toastMessage = null) }

    // ── Scrobbler ──

    /** The master toggle only turns scrobbling on if a session key already
     *  exists — track.scrobble/updateNowPlaying are signed calls this app
     *  can't make without one. If it's missing, this opens the password
     *  dialog instead of silently flipping a switch that wouldn't actually
     *  do anything yet; the toggle itself gets set once that succeeds. */
    fun setScrobblerEnabled(enabled: Boolean) {
        if (enabled && session.value.sessionKey.isBlank()) {
            _uiState.update { it.copy(showSessionKeyDialog = true) }
            return
        }
        viewModelScope.launch { scrobblerPreferences.setEnabled(enabled) }
    }

    fun setSubmitNowPlaying(enabled: Boolean) = viewModelScope.launch { scrobblerPreferences.setSubmitNowPlaying(enabled) }
    fun setScrobblePercent(percent: Int) = viewModelScope.launch { scrobblerPreferences.setScrobblePercent(percent) }

    // ── YouTube Music account ──

    /** Sync only turns on with a connected account; flipping it on triggers
     *  an immediate first mirror pass instead of waiting for the next tick. */
    fun setYtSyncEnabled(enabled: Boolean) {
        if (enabled && !ytConnection.value.isConnected) return
        viewModelScope.launch {
            ytMusicPreferences.setSyncEnabled(enabled)
            if (enabled) runCatching { ytMusicSyncManager.syncNow("enabled") }
        }
    }

    fun disconnectYouTube() {
        viewModelScope.launch {
            ytMusicPreferences.setSyncEnabled(false)
            ytAuthManager.signOut()
            _uiState.update { it.copy(toastMessage = "YouTube Music disconnected") }
        }
    }

    fun syncYouTubeNow() {
        viewModelScope.launch { runCatching { ytMusicSyncManager.syncNow("manual") } }
    }

    fun dismissSessionKeyDialog() = _uiState.update { it.copy(showSessionKeyDialog = false, sessionKeyError = null) }

    fun submitPassword(password: String) {
        _uiState.update { it.copy(sessionKeyLoading = true, sessionKeyError = null) }
        viewModelScope.launch {
            when (val result = authRepository.obtainSessionKey(password)) {
                AuthRepository.SessionKeyResult.Success -> {
                    scrobblerPreferences.setEnabled(true)
                    _uiState.update { it.copy(showSessionKeyDialog = false, sessionKeyLoading = false, toastMessage = "Scrobbling enabled") }
                }
                is AuthRepository.SessionKeyResult.Failed -> {
                    _uiState.update { it.copy(sessionKeyLoading = false, sessionKeyError = result.message) }
                }
            }
        }
    }
}
