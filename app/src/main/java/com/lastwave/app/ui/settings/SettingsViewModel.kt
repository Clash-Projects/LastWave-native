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
import com.lastwave.app.playback.NativeAudioEngine
import com.lastwave.app.util.FileExportHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PendingRestoreKind { FULL_BACKUP, PLAYLIST_MIRROR }

private val SettingsSharing = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000)
private const val SETTINGS_TAG = "SettingsViewModel"

/** Settings aggregates several independent stores. A broken legacy value or
 * one unreadable Room table must only disable that section, never crash the
 * entire destination. Flow.catch preserves cancellation and handles only
 * failures from the upstream source. */
private fun <T> Flow<T>.withSettingsFallback(source: String, fallback: T): Flow<T> =
    catch { error ->
        android.util.Log.e(SETTINGS_TAG, "$source unavailable; using safe defaults", error)
        emit(fallback)
    }

data class SettingsScreenState(
    val session: SessionData = SessionData(),
    val theme: ThemeUiState? = null,
    val misc: MiscSettings = MiscSettings(),
    val recommendationExclusionCount: Int = 0,
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
    private val audioEngine: dagger.Lazy<NativeAudioEngine>,
    private val generateRepository: GenerateRepository,
    private val discoverRepository: com.lastwave.app.data.discover.DiscoverRepository,
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
        .withSettingsFallback("YouTube sync preference", false)
        .stateIn(viewModelScope, SettingsSharing, false)
    val ytLastSyncAt: StateFlow<Long> = ytMusicPreferences.lastSyncAt
        .withSettingsFallback("YouTube sync timestamp", 0L)
        .stateIn(viewModelScope, SettingsSharing, 0L)
    val syncedPlaylistIds: StateFlow<Set<Long>?> = ytMusicPreferences.syncedPlaylistIds
        .withSettingsFallback("YouTube playlist selection", null)
        .stateIn(viewModelScope, SettingsSharing, null)
    val allPlaylists: StateFlow<List<com.lastwave.app.data.playlist.SavedPlaylist>> = playlistRepository.playlists
        .withSettingsFallback("playlists", emptyList())
        .stateIn(viewModelScope, SettingsSharing, emptyList())

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
    }
        .withSettingsFallback("session", SessionData())
        .stateIn(viewModelScope, SettingsSharing, SessionData())

    val theme: StateFlow<ThemeUiState> = themeRepository.uiState

    val misc: StateFlow<MiscSettings> = settingsPreferences.settings
        .withSettingsFallback("misc preferences", MiscSettings())
        .stateIn(viewModelScope, SettingsSharing, MiscSettings())

    val scrobbler: StateFlow<ScrobblerSettings> = scrobblerPreferences.settings
        .withSettingsFallback("scrobbler preferences", ScrobblerSettings())
        .stateIn(viewModelScope, SettingsSharing, ScrobblerSettings())

    /** Experimental 15-band equalizer state (Settings → Experimental). */
    val equalizer: StateFlow<EqualizerSettings> = equalizerPreferences.settings
        .withSettingsFallback("equalizer preferences", EqualizerSettings())
        .stateIn(viewModelScope, SettingsSharing, EqualizerSettings())
    private var immediateEqGains = EqualizerSettings().gainsDb.toFloatArray()
    private var immediateVolumeBoostEnabled = false
    private var immediateVolumeBoostPercent = 100

    val downloadCount: StateFlow<Int> = downloadedTrackDao.count()
        .withSettingsFallback("download count", 0)
        .stateIn(viewModelScope, SettingsSharing, 0)

    val downloadTotalBytes: StateFlow<Long?> = downloadedTrackDao.totalBytes()
        .withSettingsFallback("download size", 0L)
        .stateIn(viewModelScope, SettingsSharing, 0L)

    private val _uiState = MutableStateFlow(SettingsScreenState())
    val uiState: StateFlow<SettingsScreenState> = _uiState.asStateFlow()

    /** Prevent DataStore/Room/runtime write failures from escaping as an
     * uncaught root coroutine and terminating the app. */
    private fun launchSettingsAction(action: String, block: suspend () -> Unit) =
        viewModelScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                android.util.Log.e(SETTINGS_TAG, "Failed to $action", error)
                _uiState.update { state ->
                    state.copy(toastMessage = "Couldn't $action. Please try again.")
                }
            } catch (error: LinkageError) {
                // Missing/altered framework or JNI symbols on a custom ROM
                // disable only this action, never the Settings destination.
                android.util.Log.e(SETTINGS_TAG, "Unsupported platform action: $action", error)
                _uiState.update { state ->
                    state.copy(toastMessage = "This action isn't supported on this device.")
                }
            }
        }

    init {
        viewModelScope.launch {
            equalizer.collect { immediateEqGains = it.gainsDb.toFloatArray() }
        }
        viewModelScope.launch {
            misc.collect { settings ->
                immediateVolumeBoostEnabled = settings.volumeBoostEnabled
                immediateVolumeBoostPercent = settings.volumeBoostPercent.coerceIn(100, 150)
            }
        }
        viewModelScope.launch {
            generateRepository.observeRecommendationExclusions()
                .withSettingsFallback("recommendation exclusions", emptyList())
                .collect { exclusions ->
                    _uiState.update { it.copy(recommendationExclusionCount = exclusions.size) }
                }
        }
    }

    fun refreshRecommendationExclusionCount() {
        launchSettingsAction("refresh exclusions") {
            val count = generateRepository.recommendationExclusionCount()
            _uiState.update { it.copy(recommendationExclusionCount = count) }
        }
    }

    fun saveApiCredentials(apiKey: String, apiSecret: String) {
        launchSettingsAction("save API credentials") { authRepository.saveApiCredentials(apiKey, apiSecret) }
    }

    fun logOut(onComplete: () -> Unit) {
        launchSettingsAction("log out") {
            sessionPreferences.logOutApiCredentials()
            onComplete()
        }
    }

    fun clearSession(onComplete: () -> Unit) {
        launchSettingsAction("clear the session") {
            sessionPreferences.clearAll()
            onComplete()
        }
    }

    // ── Appearance (§8.2 / §8.3 / §8.4) ──

    fun setAmoled(enabled: Boolean) = launchSettingsAction("update AMOLED mode") { themeRepository.setAmoled(enabled) }
    fun setLiquidGlass(enabled: Boolean) = launchSettingsAction("update Liquid Glass") { themeRepository.setLiquidGlass(enabled) }
    fun setAccentMode(mode: AccentMode) = launchSettingsAction("update accent mode") { themeRepository.setMode(mode) }
    fun setManualAccent(color: Color) = launchSettingsAction("update accent color") { themeRepository.setManualAccent(color) }
    fun openColorWheel() = _uiState.update { it.copy(showColorWheel = true) }
    fun dismissColorWheel() = _uiState.update { it.copy(showColorWheel = false) }
    fun applyCustomColor(color: Color) {
        setManualAccent(color)
        dismissColorWheel()
    }

    fun setDynamicNowPlaying(enabled: Boolean) = launchSettingsAction("update dynamic theme") { themeRepository.setDynamicNowPlaying(enabled) }
    fun setUseCustomFont(enabled: Boolean) = launchSettingsAction("update the app font") { settingsPreferences.setUseCustomFont(enabled) }
    fun setPreferQobuzStreaming(enabled: Boolean) = launchSettingsAction("update streaming preference") { settingsPreferences.setPreferQobuzStreaming(enabled) }
    fun setQobuzQuality(quality: Int) = launchSettingsAction("update streaming quality") { settingsPreferences.setQobuzQuality(quality) }
    fun setStudioMasterClarity(enabled: Boolean) {
        // Apply immediately; DataStore persists the same state for future engine instances.
        runCatching { audioEngine.get().setStudioMasterClarity(enabled) }
        launchSettingsAction("update Studio Master Clarity") { settingsPreferences.setStudioMasterClarity(enabled) }
    }
    fun setLyricsAnimation(animation: com.lastwave.app.data.local.LyricsAnimation) = launchSettingsAction("update lyrics animation") { settingsPreferences.setLyricsAnimation(animation) }
    fun setVolumeBoostEnabled(enabled: Boolean) {
        immediateVolumeBoostEnabled = enabled
        runCatching { audioEngine.get().setVolumeBoost(enabled, immediateVolumeBoostPercent) }
        launchSettingsAction("update volume boost") { settingsPreferences.setVolumeBoostEnabled(enabled) }
    }
    fun setVolumeBoostPercent(percent: Int) {
        val safePercent = percent.coerceIn(100, 150)
        immediateVolumeBoostPercent = safePercent
        runCatching { audioEngine.get().setVolumeBoost(immediateVolumeBoostEnabled, safePercent) }
        launchSettingsAction("update volume boost") { settingsPreferences.setVolumeBoostPercent(safePercent) }
    }
    fun setCrossfadeEnabled(enabled: Boolean) = launchSettingsAction("update crossfade") { settingsPreferences.setCrossfadeEnabled(enabled) }
    fun setCrossfadeSeconds(seconds: Int) = launchSettingsAction("update crossfade duration") {
        settingsPreferences.setCrossfadeSeconds(seconds.coerceIn(1, 10))
    }

    // ── Experimental: 15-band equalizer ──

    fun setEqualizerEnabled(enabled: Boolean) {
        runCatching { audioEngine.get().setEqualizer(enabled, immediateEqGains) }
        launchSettingsAction("update the equalizer") { equalizerPreferences.setEnabled(enabled) }
    }

    /** Selecting a preset also switches the EQ on — an off equalizer with a
     *  fresh preset would read as a dead control. */
    fun applyEqPreset(name: String) {
        com.lastwave.app.data.local.EqualizerPresets.byName(name)?.let { preset ->
            immediateEqGains = preset.gainsDb.toFloatArray()
            runCatching { audioEngine.get().setEqualizer(true, immediateEqGains) }
            launchSettingsAction("apply the equalizer preset") { equalizerPreferences.applyPreset(preset) }
        }
    }

    /** Manual band drag → curve becomes Custom. */
    fun setEqBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in immediateEqGains.indices) return
        immediateEqGains = immediateEqGains.copyOf().also { it[bandIndex] = gainDb }
        runCatching { audioEngine.get().setEqualizer(equalizer.value.enabled, immediateEqGains) }
        launchSettingsAction("update the equalizer band") { equalizerPreferences.setBandGain(bandIndex, gainDb) }
    }

    // ── Data management (§8.5) ──

    fun clearRecommendationExclusions() {
        launchSettingsAction("clear exclusions") {
            discoverRepository.clearRecommendationExclusions()
            refreshRecommendationExclusionCount()
            _uiState.update { it.copy(toastMessage = "Exclusion history cleared") }
        }
    }

    fun requestClearAllData() = _uiState.update { it.copy(showClearAllConfirm = true) }
    fun dismissClearAllConfirm() = _uiState.update { it.copy(showClearAllConfirm = false) }
    fun confirmClearAllData(onComplete: () -> Unit) {
        launchSettingsAction("clear saved data") {
            sessionPreferences.clearAll()
            discoverRepository.clearRecommendationExclusions()
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
                // Extract filename
                val cursor = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                val displayName = cursor?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "Imported Playlist"

                val isM3u = displayName.endsWith(".m3u", ignoreCase = true) || displayName.endsWith(".m3u8", ignoreCase = true)
                val fileType = if (isM3u) "M3U" else "CSV"
                _uiState.update { it.copy(toastMessage = "Matching and importing $fileType songs...") }

                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: error("Could not open selected playlist file")

                val (saved, result) = playlistImportManager.importCsvStream(inputStream, displayName)
                _uiState.update {
                    it.copy(toastMessage = "Imported \"${saved.title}\" (${result.matchedCount}/${result.totalRows} verified)")
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(toastMessage = "Import failed: ${e.localizedMessage ?: e.message}")
                }
            }
        }
    }

    /** Called once the file picker returns raw file content — validates and
     *  stages the restore, showing a confirm dialog with the item count
     *  before actually applying anything (§8.6). */
    fun stagePendingRestore(content: String, uri: android.net.Uri) {
        launchSettingsAction("stage the restore") {
            val backup = try {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString(com.lastwave.app.data.backup.BackupFile.serializer(), content)
                    .takeIf { it.type == "lastwave-backup" }
            } catch (e: Exception) { null }
            val mirrorCount = playlistRepository.publicMirrorPlaylistCount(content)
            if (backup == null && mirrorCount == null) {
                _uiState.update { it.copy(toastMessage = "That isn't a LastWave backup or playlist JSON") }
                return@launchSettingsAction
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
        launchSettingsAction("restore the backup") {
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
                return@launchSettingsAction
            }

            when (val result = backupRepository.restore(content)) {
                is RestoreResult.Success -> {
                    generateRepository.invalidateRecommendationExclusionCache()
                    discoverRepository.reset()
                    refreshRecommendationExclusionCount()
                    val exclusionNote = if (result.exclusionCount > 0) " and recommendation exclusions" else ""
                    _uiState.update {
                        it.copy(
                            showRestoreConfirm = false,
                            pendingRestoreContent = null,
                            toastMessage = "Restored ${result.playlistCount} playlist(s)$exclusionNote",
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
        launchSettingsAction("update scrobbling") { scrobblerPreferences.setEnabled(enabled) }
    }

    fun setSubmitNowPlaying(enabled: Boolean) = launchSettingsAction("update Now Playing submission") { scrobblerPreferences.setSubmitNowPlaying(enabled) }
    fun setScrobblePercent(percent: Int) = launchSettingsAction("update the scrobble threshold") { scrobblerPreferences.setScrobblePercent(percent) }

    // ── YouTube Music account ──

    /** Sync only turns on with a connected account; flipping it on triggers
     *  an immediate first mirror pass instead of waiting for the next tick. */
    fun setYtSyncEnabled(enabled: Boolean) {
        if (enabled && !ytConnection.value.isConnected) return
        launchSettingsAction("update YouTube sync") {
            ytMusicPreferences.setSyncEnabled(enabled)
            if (enabled) runCatching { ytMusicSyncManager.syncNow("enabled") }
        }
    }

    fun disconnectYouTube() {
        launchSettingsAction("disconnect YouTube Music") {
            ytMusicPreferences.setSyncEnabled(false)
            ytAuthManager.signOut()
            _uiState.update { it.copy(toastMessage = "YouTube Music disconnected") }
        }
    }

    fun togglePlaylistSync(playlistId: Long, enabled: Boolean) {
        launchSettingsAction("update playlist sync") {
            val allIds = allPlaylists.value.map { it.id }
            ytMusicPreferences.togglePlaylistSync(allIds, playlistId, enabled)
            if (ytSyncEnabled.value) {
                runCatching { ytMusicSyncManager.syncNow("selection_change") }
            }
        }
    }

    fun selectAllPlaylistsForSync(select: Boolean) {
        launchSettingsAction("update playlist sync") {
            val allIds = if (select) allPlaylists.value.map { it.id }.toSet() else emptySet()
            ytMusicPreferences.setSyncedPlaylistIds(allIds)
            if (ytSyncEnabled.value) {
                runCatching { ytMusicSyncManager.syncNow("selection_change") }
            }
        }
    }

    fun syncYouTubeNow() {
        launchSettingsAction("sync YouTube Music") { ytMusicSyncManager.syncNow("manual") }
    }

    fun dismissSessionKeyDialog() = _uiState.update { it.copy(showSessionKeyDialog = false, sessionKeyError = null) }

    fun submitPassword(password: String) {
        _uiState.update { it.copy(sessionKeyLoading = true, sessionKeyError = null) }
        launchSettingsAction("enable scrobbling") {
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
