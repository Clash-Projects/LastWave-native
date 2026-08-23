package com.lastwave.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import com.lastwave.app.R
import com.lastwave.app.data.local.AccentMode
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.theme.ExpressivePillShape

private data class AccentPreset(val name: String, val hex: String)
private val ACCENT_PRESETS = listOf(
    AccentPreset("Crimson", "#E03030"),
    AccentPreset("Violet", "#7C4DFF"),
    AccentPreset("Ocean", "#2196C6"),
    AccentPreset("Sage", "#6B9E6B"),
    AccentPreset("Amber", "#E0A030"),
    AccentPreset("Rose", "#E0507A"),
)

// -- Expressive shape scale used only within this screen --
private val CardOuterShape = RoundedCornerShape(28.dp)
private val IconBadgeShape = RoundedCornerShape(14.dp)

/** Where a row sits within a visually-connected group of settings rows —
 *  drives per-row corner radii so a multi-row group reads as one premium
 *  surface split into rows, not a stack of separate cards (see groupShape
 *  below and the GROUP_GAP spacing used between rows in a group's Column). */
private enum class GroupPosition { SINGLE, TOP, MIDDLE, BOTTOM }

private val GROUP_OUTER_RADIUS = 28.dp
private val GROUP_INNER_RADIUS = 6.dp
private val GROUP_GAP = 3.dp

private fun groupShape(position: GroupPosition): RoundedCornerShape = when (position) {
    GroupPosition.SINGLE -> RoundedCornerShape(GROUP_OUTER_RADIUS)
    GroupPosition.TOP -> RoundedCornerShape(
        topStart = GROUP_OUTER_RADIUS, topEnd = GROUP_OUTER_RADIUS,
        bottomStart = GROUP_INNER_RADIUS, bottomEnd = GROUP_INNER_RADIUS,
    )
    GroupPosition.MIDDLE -> RoundedCornerShape(GROUP_INNER_RADIUS)
    GroupPosition.BOTTOM -> RoundedCornerShape(
        topStart = GROUP_INNER_RADIUS, topEnd = GROUP_INNER_RADIUS,
        bottomStart = GROUP_OUTER_RADIUS, bottomEnd = GROUP_OUTER_RADIUS,
    )
}

/** Wraps a fixed list of settings rows and assigns each one its
 *  GroupPosition automatically — SINGLE for a lone row, TOP/BOTTOM for the
 *  ends of a longer group, MIDDLE for everything between. Rows are stacked
 *  with a tiny GROUP_GAP rather than normal item spacing, so the group
 *  reads as one connected surface with rows peeking through a hairline gap
 *  rather than a list of separate cards. */
@Composable
private fun SettingsGroup(rowCount: Int, content: @Composable (index: Int, position: GroupPosition) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
        for (i in 0 until rowCount) {
            val position = when {
                rowCount == 1 -> GroupPosition.SINGLE
                i == 0 -> GroupPosition.TOP
                i == rowCount - 1 -> GroupPosition.BOTTOM
                else -> GroupPosition.MIDDLE
            }
            content(i, position)
        }
    }
}

/**
 * Faithful port of settings.js (par 8): Last.fm account management, appearance
 * (AMOLED / Dynamic Color / Monochrome / accent presets / custom color
 * wheel), iTunes/ListenBrainz artwork toggles, data management (clear
 * discovery history, clear all data), backup & restore, and app info.
 *
 * Visuals only: restyled into a Material 3 Expressive presentation
 * (larger touch targets, per-row cards, tonal icon badges, spring-based
 * press feedback). Every setting, callback, and piece of state below is
 * unchanged from the original implementation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onLoggedOut: () -> Unit = {},
    onOpenChooseApps: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsState()
    val theme by viewModel.theme.collectAsState()
    val misc by viewModel.misc.collectAsState()
    val scrobbler by viewModel.scrobbler.collectAsState()
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showQualityDialog by remember { mutableStateOf(false) }

    // Sends the user to Android's own Notification Listener access screen
    // — the one permission this feature needs that the app can never grant
    // itself, only deep-link to. There's no reliable "is it already
    // granted for THIS app" API pre-33 short of parsing a settings string,
    // so this always opens the picker rather than guessing; picking
    // LastWave again there if it's already on is harmless.
    fun openNotificationAccessSettings() {
        try {
            context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) { }
    }

    // "*/*" rather than "application/json": many document providers (Drive,
    // Downloads, some file managers) report a .json file as
    // application/octet-stream or text/plain, and GetContent's mime filter
    // hides anything that doesn't match — the user's own backup file would
    // silently not show up. Real validation happens right after the file is
    // read (stagePendingRestore), so being permissive here is safe.
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (text != null) viewModel.stagePendingRestore(text, uri)
            } catch (e: Exception) { }
        }
    }

    // Lets the user pick exactly where the backup file is saved (SAF), so
    // it's guaranteed to be somewhere restoreLauncher's picker can browse
    // back to later — unlike a silent write into app-private storage.
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportBackup(uri, appVersionName(context))
    }

    Column(Modifier.fillMaxSize()) {
        ExpressiveHeader(title = "Settings", onBack = onBack)

        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 22.dp,
                bottom = 32.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.safeHorizontalContentPadding(),
        ) {
            item {
                AccountCard(
                    isSignedIn = session.username.isNotBlank(),
                    username = session.username,
                    hasCredentials = session.apiKey.isNotBlank() && session.apiSecret.isNotBlank(),
                    onSaveCredentials = viewModel::saveApiCredentials,
                    onLogOut = { viewModel.logOut(onLoggedOut) },
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("Appearance")
                    SettingsGroup(rowCount = 4) { index, position ->
                        when (index) {
                            0 -> SettingsToggleCard(
                                icon = Icons.Filled.Contrast,
                                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                title = "AMOLED Mode",
                                subtitle = "Pure black background",
                                checked = theme?.amoled ?: false,
                                onCheckedChange = viewModel::setAmoled,
                                position = position,
                            )
                            1 -> SettingsToggleCard(
                                icon = Icons.Filled.Palette,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = "Dynamic Color",
                                subtitle = "Use your wallpaper's colors",
                                checked = theme?.mode == AccentMode.DYNAMIC,
                                onCheckedChange = { enabled ->
                                    viewModel.setAccentMode(if (enabled) AccentMode.DYNAMIC else AccentMode.MANUAL)
                                },
                                position = position,
                            )
                            2 -> SettingsToggleCard(
                                icon = Icons.Filled.Album,
                                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                title = "Dynamic Now Playing",
                                subtitle = "Match player artwork",
                                checked = misc.dynamicNowPlayingEnabled,
                                onCheckedChange = viewModel::setDynamicNowPlaying,
                                position = position,
                            )
                            3 -> SettingsToggleCard(
                                icon = Icons.Filled.TextFields,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = "Use Application Font",
                                subtitle = "A custom look across the whole app",
                                checked = misc.useCustomFont,
                                onCheckedChange = viewModel::setUseCustomFont,
                                position = position,
                            )
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("Audio & Streaming")
                    val qualitySubtitle = when (misc.qobuzQuality) {
                        27 -> "Max (Up to 24-bit / 192 kHz)"
                        7 -> "Hi-Res (24-bit / 96 kHz)"
                        6 -> "CD Lossless (16-bit / 44.1 kHz FLAC)"
                        5 -> "Standard (320 kbps MP3)"
                        else -> "Max (Up to 24-bit / 192 kHz)"
                    }
                    SettingsGroup(rowCount = 2) { index, position ->
                        when (index) {
                            0 -> SettingsToggleCard(
                                icon = Icons.Filled.HighQuality,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = "Prefer Qobuz Audio",
                                subtitle = if (misc.preferQobuzStreaming) "Direct Lossless/Hi-Res stream with YouTube fallback" else "YouTube Music streaming only",
                                checked = misc.preferQobuzStreaming,
                                onCheckedChange = viewModel::setPreferQobuzStreaming,
                                position = position,
                            )
                            1 -> SettingsActionCard(
                                icon = Icons.Filled.Tune,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = "Qobuz Streaming Quality",
                                subtitle = qualitySubtitle,
                                onClick = { showQualityDialog = true },
                                position = position,
                            )
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("Scrobbler")
                    SettingsGroup(rowCount = 4) { index, position ->
                        when (index) {
                            0 -> SettingsToggleCard(
                                icon = Icons.Filled.GraphicEq,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = "Scrobble Music",
                                subtitle = if (scrobbler.enabled) "Watching ${scrobbler.selectedPackages.size} app(s)" else "Detect and submit plays from other apps",
                                checked = scrobbler.enabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) openNotificationAccessSettings()
                                    viewModel.setScrobblerEnabled(enabled)
                                },
                                position = position,
                            )
                            1 -> SettingsActionCard(
                                icon = Icons.Filled.Apps,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = "Choose apps",
                                subtitle = if (scrobbler.selectedPackages.isEmpty()) "None selected yet" else "${scrobbler.selectedPackages.size} app(s) selected",
                                onClick = onOpenChooseApps,
                                position = position,
                            )
                            2 -> SettingsToggleCard(
                                icon = Icons.Filled.NotificationsActive,
                                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                title = "Submit Now Playing",
                                subtitle = "Show what's playing on your profile instantly",
                                checked = scrobbler.submitNowPlaying,
                                onCheckedChange = viewModel::setSubmitNowPlaying,
                                position = position,
                            )
                            3 -> ScrobbleThresholdRow(
                                percent = scrobbler.scrobblePercent,
                                onPercentChange = viewModel::setScrobblePercent,
                                position = position,
                            )
                        }
                    }
                }
            }

            item {
                Column {
                    SectionLabel("Accent")
                    Spacer(Modifier.height(10.dp))
                    Card(
                        shape = CardOuterShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            AccentPresetGrid(
                                currentMode = theme?.mode ?: AccentMode.MANUAL,
                                selectedHex = theme?.accentColorHex,
                                onPickPreset = { hex -> viewModel.setManualAccent(Color(android.graphics.Color.parseColor(hex))) },
                                onPickMono = { viewModel.setAccentMode(AccentMode.MONOCHROME) },
                                onPickCustom = viewModel::openColorWheel,
                            )
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("Data Management")
                    SettingsGroup(rowCount = 2) { index, position ->
                        when (index) {
                            0 -> SettingsActionCard(
                                icon = Icons.Filled.RestartAlt,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = "Clear Discovery History",
                                subtitle = "${state.seenTracksCount} tracks remembered",
                                onClick = viewModel::clearDiscoveryHistory,
                                position = position,
                            )
                            1 -> SettingsActionCard(
                                icon = Icons.Filled.Delete,
                                iconContainer = MaterialTheme.colorScheme.errorContainer,
                                iconTint = MaterialTheme.colorScheme.onErrorContainer,
                                title = "Clear All Saved Data",
                                subtitle = "Wipes all local data",
                                danger = true,
                                onClick = viewModel::requestClearAllData,
                                position = position,
                            )
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("Backup & Restore")
                    SettingsGroup(rowCount = 2) { index, position ->
                        when (index) {
                            0 -> SettingsActionCard(
                                icon = Icons.Filled.Backup,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = "Backup",
                                subtitle = "Save all your data to a file",
                                onClick = { backupLauncher.launch("lastwave-backup-${System.currentTimeMillis()}.json") },
                                position = position,
                            )
                            1 -> SettingsActionCard(
                                icon = Icons.Filled.CloudDownload,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = "Restore",
                                subtitle = "Load a backup or playlist JSON",
                                onClick = { restoreLauncher.launch(arrayOf("*/*")) },
                                position = position,
                            )
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("About")
                    Spacer(Modifier.height(4.dp))
                    AboutCard(versionName = appVersionName(context))
                    SettingsActionCard(
                        icon = Icons.Filled.Code,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                        title = "Source Code",
                        subtitle = "github.com/duxtami/LastWave-native",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/duxtami/LastWave-native"))
                            context.startActivity(intent)
                        },
                    )
                }
            }
        }
    }

    // -- Custom color wheel dialog (par 8.4) --
    if (state.showColorWheel) {
        ColorWheelSheet(onDismiss = viewModel::dismissColorWheel, onApply = viewModel::applyCustomColor)
    }

    // -- Clear-all-data confirm --
    if (state.showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearAllConfirm,
            title = { Text("Clear all data?") },
            text = { Text("This removes your credentials, playlists, and cached data. This can't be undone.") },
            confirmButton = { TextButton(onClick = { viewModel.confirmClearAllData(onLoggedOut) }) { Text("Clear Everything") } },
            dismissButton = { TextButton(onClick = viewModel::dismissClearAllConfirm) { Text("Cancel") } },
        )
    }

    // -- Restore confirm --
    if (state.showRestoreConfirm) {
        val isPlaylistMirror = state.pendingRestoreKind == PendingRestoreKind.PLAYLIST_MIRROR
        AlertDialog(
            onDismissRequest = viewModel::dismissRestoreConfirm,
            title = { Text(if (isPlaylistMirror) "Sync playlist JSON?" else "Restore backup?") },
            text = {
                Text(
                    if (isPlaylistMirror) {
                        "This will merge ${state.pendingRestorePlaylistCount ?: 0} playlist(s) from the local JSON file and reconnect automatic syncing."
                    } else {
                        "This will replace your current data with ${state.pendingRestorePlaylistCount ?: 0} playlist(s) and all settings from the backup file."
                    },
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.confirmRestore(onBack) }) { Text(if (isPlaylistMirror) "Sync" else "Restore") } },
            dismissButton = { TextButton(onClick = viewModel::dismissRestoreConfirm) { Text("Cancel") } },
        )
    }

    // -- Enable Scrobbling password dialog --
    if (state.showSessionKeyDialog) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = viewModel::dismissSessionKeyDialog,
            title = { Text("Enable scrobbling") },
            text = {
                Column {
                    Text(
                        "Last.fm only allows scrobbling through a signed session, and the only way to get one without a browser is with your password. It's sent once, directly to Last.fm over HTTPS, and never stored.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Last.fm password") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        isError = state.sessionKeyError != null,
                        supportingText = state.sessionKeyError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.submitPassword(password) },
                    enabled = !state.sessionKeyLoading,
                ) {
                    if (state.sessionKeyLoading) {
                        com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator(size = 18.dp)
                    } else {
                        Text("Enable")
                    }
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissSessionKeyDialog) { Text("Cancel") } },
        )
    }

    if (showQualityDialog) {
        val qualities = listOf(
            27 to "Max (Up to 24-bit / 192 kHz)",
            7 to "Hi-Res (24-bit / 96 kHz)",
            6 to "CD Lossless (16-bit / 44.1 kHz FLAC)",
            5 to "Standard (320 kbps MP3)",
        )
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text("Streaming Quality") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Select preferred streaming tier. If a track is not available in the selected quality, the highest available quality is streamed automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    qualities.forEach { (qualityId, label) ->
                        val selected = misc.qobuzQuality == qualityId
                        Surface(
                            onClick = {
                                viewModel.setQobuzQuality(qualityId)
                                showQualityDialog = false
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showQualityDialog = false }) { Text("Close") }
            },
        )
    }

    state.toastMessage?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(3000)
            viewModel.dismissToast()
        }
        Box(
            Modifier
                .fillMaxSize()
                .safeHorizontalContentPadding()
                .padding(
                    bottom = 24.dp +
                        LocalMiniPlayerScrollClearance.current +
                        safeDrawingBottomPadding(),
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(shape = ExpressivePillShape, color = MaterialTheme.colorScheme.inverseSurface, tonalElevation = 6.dp) {
                Text(msg, color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
    }
}

private fun appVersionName(context: android.content.Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
} catch (e: Exception) { "1.0" }

/** Small tap-scale used across the row-style cards on this screen for a
 *  softer, springier press response than the plain ripple alone gives. */
@Composable
private fun rememberPressScale(interactionSource: MutableInteractionSource): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    return scale
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun IconBadge(icon: ImageVector, container: Color, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clip(IconBadgeShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun SettingsToggleCard(
    icon: ImageVector,
    iconContainer: Color,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    position: GroupPosition = GroupPosition.SINGLE,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interactionSource)

    Card(
        onClick = { onCheckedChange(!checked) },
        shape = groupShape(position),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        // Pinned at 0dp: Material3's Card blends an extra primary-tinted
        // alpha layer on top of containerColor whenever tonalElevation is
        // above 0dp (surfaceColorAtElevation) — with a Switch already
        // providing this row's own selected/unselected signal, any such
        // blend on the row itself would read as a second, redundant layer
        // behind the label. Same root cause as ModeCard's fix below.
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        interactionSource = interactionSource,
        modifier = Modifier.fillMaxWidth().scale(scale),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon, iconContainer, iconTint)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                thumbContent = if (checked) {
                    {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    }
                } else null,
            )
        }
    }
}

/** The percent-of-track threshold before a scrobble is submitted — same
 *  idea as Pano Scrobbler's "Percent" slider, minus its separate parallel
 *  "Minutes" slider: Last.fm's own scrobble rule already caps the wait at
 *  4 minutes regardless of percent, so that second slider would only ever
 *  matter for tracks over 8 minutes long, a genuine edge case not worth
 *  the extra UI here. */
@Composable
private fun ScrobbleThresholdRow(percent: Int, onPercentChange: (Int) -> Unit, position: GroupPosition = GroupPosition.SINGLE) {
    var sliderValue by remember(percent) { mutableStateOf(percent.toFloat()) }
    Card(
        shape = groupShape(position),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Timer, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Scrobble after", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "${sliderValue.toInt()}% played (capped at 4 min)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onPercentChange(sliderValue.toInt()) },
                valueRange = 25f..90f,
                steps = 12,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SettingsActionCard(
    icon: ImageVector,
    iconContainer: Color,
    iconTint: Color,
    title: String,
    subtitle: String,
    danger: Boolean = false,
    onClick: () -> Unit,
    position: GroupPosition = GroupPosition.SINGLE,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interactionSource)
    val titleColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

    Card(
        onClick = onClick,
        shape = groupShape(position),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        interactionSource = interactionSource,
        modifier = Modifier.fillMaxWidth().scale(scale),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon, iconContainer, iconTint)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = titleColor)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AccountCard(
    isSignedIn: Boolean,
    username: String,
    hasCredentials: Boolean,
    onSaveCredentials: (String, String) -> Unit,
    onLogOut: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var apiSecret by remember { mutableStateOf("") }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    Card(
        shape = CardOuterShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.animateContentSize(),
    ) {
        Column(Modifier.padding(20.dp)) {
            if (hasCredentials) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            username.take(1).uppercase(),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Signed in as",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            username.ifBlank { "Last.fm user" },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalIconButton(
                        onClick = { showLogoutConfirm = true },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(Icons.Filled.Logout, contentDescription = "Log out")
                    }
                }
            } else {
                Text("Connect Last.fm", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = apiSecret,
                    onValueChange = { apiSecret = it },
                    label = { Text("API Secret") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Get your API key at last.fm/api/account/create",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { onSaveCredentials(apiKey, apiSecret) },
                    enabled = apiKey.isNotBlank() && apiSecret.isNotBlank(),
                    shape = ExpressivePillShape,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text("Save API Credentials", fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Log out?") },
            text = { Text("Your playlists and cached data will be kept.") },
            confirmButton = { TextButton(onClick = { showLogoutConfirm = false; onLogOut() }) { Text("Log Out") } },
            dismissButton = { TextButton(onClick = { showLogoutConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AccentPresetGrid(
    currentMode: AccentMode,
    selectedHex: String?,
    onPickPreset: (String) -> Unit,
    onPickMono: () -> Unit,
    onPickCustom: () -> Unit,
) {
    // A preset only reads as "selected" while the user is actually in
    // manual mode — Dynamic/Monochrome shouldn't light up whichever preset
    // happens to hex-match by coincidence.
    fun isPresetSelected(hex: String) =
        currentMode == AccentMode.MANUAL && selectedHex?.equals(hex, ignoreCase = true) == true
    val customSelected = currentMode == AccentMode.MANUAL &&
        selectedHex != null &&
        ACCENT_PRESETS.none { it.hex.equals(selectedHex, ignoreCase = true) }
    val monoSelected = currentMode == AccentMode.MONOCHROME

    // One unified 4-column tile grid — six color presets plus Mono and
    // Custom as tiles of their own, not a separate row of pill buttons.
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        ACCENT_PRESETS.take(4).forEach { preset ->
            ColorTile(
                label = preset.name,
                selected = isPresetSelected(preset.hex),
                modifier = Modifier.weight(1f),
                onClick = { onPickPreset(preset.hex) },
            ) {
                PaletteTilePreview(preset.hex)
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        ACCENT_PRESETS.drop(4).forEach { preset ->
            ColorTile(
                label = preset.name,
                selected = isPresetSelected(preset.hex),
                modifier = Modifier.weight(1f),
                onClick = { onPickPreset(preset.hex) },
            ) {
                PaletteTilePreview(preset.hex)
            }
        }
        ColorTile(
            label = "Mono",
            selected = monoSelected,
            modifier = Modifier.weight(1f),
            onClick = onPickMono,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Contrast,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        ColorTile(
            label = "Custom",
            selected = customSelected,
            modifier = Modifier.weight(1f),
            onClick = onPickCustom,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.sweepGradient(
                            listOf(Color(0xFFE03030), Color(0xFFE0A030), Color(0xFF6B9E6B), Color(0xFF2196C6), Color(0xFF7C4DFF), Color(0xFFE03030)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Colorize,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * Four coordinated shades derived from one preset hex via HSV — Dark,
 * Medium, Light, and a punchier Accent tone — used to render a real
 * multi-tone palette preview inside each Accent tile instead of one flat
 * swatch. Order returned: [dark, medium, light, accent].
 *
 * Tuned for a muted, Material You / Monet feel rather than raw HSV
 * vibrance: saturation is capped well below 100% even for the "accent"
 * shade (Monet's HCT-derived tonal palettes rarely reach full chroma —
 * that's what read as neon here), and the value range is narrower so
 * "dark" and "light" stay closer to the preset's own character instead of
 * swinging to near-black/near-white extremes.
 */
private fun accentShades(hex: String): List<Color> {
    val argb = android.graphics.Color.parseColor(hex)
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    val (h, s, v) = hsv
    val mutedBase = s * 0.72f // the single biggest lever for "less neon"

    fun shade(saturation: Float, value: Float): Color {
        val arr = floatArrayOf(h, saturation.coerceIn(0f, 0.82f), value.coerceIn(0.2f, 0.92f))
        return Color(android.graphics.Color.HSVToColor(arr))
    }

    return listOf(
        shade(mutedBase.coerceAtLeast(0.42f), v * 0.62f), // dark
        shade(mutedBase, v * 0.80f), // medium — closest to the preset's own tone
        shade((mutedBase * 0.55f), (v + (1f - v) * 0.45f).coerceAtLeast(0.78f)), // light
        shade((mutedBase * 1.15f), (v * 0.95f)), // accent — a touch richer, never maxed out
    )
}

/** Renders a preset's four shades as a 2x2 block grid filling the tile,
 *  so selecting a preset previews its whole coordinated palette rather
 *  than one flat color. */
@Composable
private fun PaletteTilePreview(hex: String) {
    val shades = remember(hex) { accentShades(hex) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight().background(shades[0]))
            Box(Modifier.weight(1f).fillMaxHeight().background(shades[1]))
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight().background(shades[2]))
            Box(Modifier.weight(1f).fillMaxHeight().background(shades[3]))
        }
    }
}


/**
 * One expressive accent tile: a real elevated square swatch (Modifier.shadow
 * — a true drop shadow, not Card's tonal-elevation color blend, which would
 * otherwise wash out the exact color a swatch is supposed to preview),
 * a spring scale/elevation lift on selection, a genuine ripple on tap, and
 * an animated check badge. [content] draws the tile's fill — a flat color
 * for presets, an icon-on-surface treatment for Mono/Custom.
 */
@Composable
private fun ColorTile(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable (Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else if (selected) 1.04f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "tileScale",
    )
    val elevation by animateDpAsState(
        targetValue = if (selected) 8.dp else 2.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tileElevation",
    )
    val borderColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "tileBorder",
    )
    val tileShape = RoundedCornerShape(20.dp)

    Column(
        modifier = modifier.scale(scale),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                // Real shadow, not tonal elevation — keeps every tile's
                // color a true, undistorted preview of the accent it
                // represents (see GenerateScreen's ModeCard fix for why
                // Card's own elevation param is the wrong tool for this).
                .shadow(elevation = elevation, shape = tileShape, clip = false)
                .clip(tileShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClick,
                )
                .border(2.5.dp, borderColor, tileShape),
            contentAlignment = Alignment.Center,
        ) {
            content(selected)
            androidx.compose.animation.AnimatedVisibility(
                visible = selected,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AboutCard(versionName: String) {
    Card(
        shape = CardOuterShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    // The launcher icon is an <adaptive-icon> XML on API 26+
                    // (mipmap-anydpi-v26/ic_launcher_round.xml) — Compose's
                    // painterResource() can only parse plain bitmap/vector
                    // drawables, not that root element, and throws the
                    // instant this composable enters composition. Rebuilding
                    // the same mark from its two real layers (the lime
                    // background color + the bars vector, both plain
                    // resources) reproduces it exactly without touching the
                    // adaptive icon resource at all.
                    .background(colorResource(R.color.ic_launcher_background)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_logo),
                    contentDescription = "LastWave",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(72.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text("LastWave", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = ExpressivePillShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    "Version $versionName",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Built with the Last.fm API",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorWheelSheet(onDismiss: () -> Unit, onApply: (Color) -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    var hue by remember { mutableStateOf(4f) }
    var saturation by remember { mutableStateOf(0.75f) }
    var lightness by remember { mutableStateOf(0.5f) }
    val previewColor = Color.hsl(hue, saturation, lightness)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(20.dp)) {
            Text("Custom Color", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(previewColor)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
            )
            Spacer(Modifier.height(20.dp))
            Text("Hue", style = MaterialTheme.typography.labelLarge)
            Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
            Text("Saturation", style = MaterialTheme.typography.labelLarge)
            Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..1f)
            Text("Lightness", style = MaterialTheme.typography.labelLarge)
            Slider(value = lightness, onValueChange = { lightness = it }, valueRange = 0.15f..0.85f)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, shape = ExpressivePillShape, modifier = Modifier.weight(1f).height(48.dp)) { Text("Cancel") }
                Button(onClick = { onApply(previewColor) }, shape = ExpressivePillShape, modifier = Modifier.weight(1f).height(48.dp)) { Text("Apply") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
