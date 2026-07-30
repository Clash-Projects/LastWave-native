package com.lastwave.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lastwave.app.data.local.AccentMode
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

/**
 * Faithful port of settings.js (§8): Last.fm account management, appearance
 * (AMOLED / Dynamic Color / Monochrome / accent presets / custom color
 * wheel), iTunes/ListenBrainz artwork toggles, data management (clear
 * discovery history, clear all data), backup & restore, and app info.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onLoggedOut: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsState()
    val theme by viewModel.theme.collectAsState()
    val misc by viewModel.misc.collectAsState()
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (text != null) viewModel.stagePendingRestore(text)
            } catch (e: Exception) { }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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

            item { SectionLabel("Appearance") }
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column {
                        SettingsToggleRow("AMOLED Mode", "Pure black background", theme?.amoled ?: false, viewModel::setAmoled)
                        HorizontalDivider()
                        SettingsToggleRow("Dynamic Color", "Use your wallpaper's colors", theme?.mode == AccentMode.DYNAMIC) { enabled ->
                            viewModel.setAccentMode(if (enabled) AccentMode.DYNAMIC else AccentMode.MANUAL)
                        }
                        HorizontalDivider()
                        SettingsToggleRow("iTunes Artwork", "Fallback artwork source", misc.iTunesArtworkEnabled, viewModel::setItunesArtwork)
                        HorizontalDivider()
                        SettingsToggleRow("ListenBrainz Artwork", "Alternate artwork source", misc.listenBrainzArtworkEnabled, viewModel::setListenBrainzArtwork)
                    }
                }
            }

            item {
                Text("Accent", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                AccentPresetGrid(
                    currentMode = theme?.mode ?: AccentMode.MANUAL,
                    onPickPreset = { hex -> viewModel.setManualAccent(Color(android.graphics.Color.parseColor(hex))) },
                    onPickMono = { viewModel.setAccentMode(AccentMode.MONOCHROME) },
                    onPickCustom = viewModel::openColorWheel,
                )
            }

            item { SectionLabel("Data Management") }
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column {
                        SettingsActionRow(
                            icon = Icons.Filled.RestartAlt,
                            title = "Clear Discovery History",
                            subtitle = "${state.seenTracksCount} tracks remembered",
                            onClick = viewModel::clearDiscoveryHistory,
                        )
                        HorizontalDivider()
                        SettingsActionRow(
                            icon = Icons.Filled.Delete,
                            title = "Clear All Saved Data",
                            subtitle = "Wipes everything \u2014 credentials, playlists, cache",
                            danger = true,
                            onClick = viewModel::requestClearAllData,
                        )
                    }
                }
            }

            item { SectionLabel("Backup & Restore") }
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column {
                        SettingsActionRow(
                            icon = Icons.Filled.Backup,
                            title = "Backup",
                            subtitle = "Save all your data to a file",
                            onClick = { viewModel.exportBackup(appVersionName(context)) },
                        )
                        HorizontalDivider()
                        SettingsActionRow(
                            icon = Icons.Filled.CloudDownload,
                            title = "Restore",
                            subtitle = "Load data from a backup file",
                            onClick = { restoreLauncher.launch("application/json") },
                        )
                    }
                }
            }

            item { SectionLabel("About") }
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("LastWave", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Version ${appVersionName(context)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text("Built with the Last.fm API", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    // ── Custom color wheel dialog (§8.4) ──
    if (state.showColorWheel) {
        ColorWheelSheet(onDismiss = viewModel::dismissColorWheel, onApply = viewModel::applyCustomColor)
    }

    // ── Clear-all-data confirm ──
    if (state.showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearAllConfirm,
            title = { Text("Clear all data?") },
            text = { Text("This removes your credentials, playlists, and cached data. This can't be undone.") },
            confirmButton = { TextButton(onClick = { viewModel.confirmClearAllData(onLoggedOut) }) { Text("Clear Everything") } },
            dismissButton = { TextButton(onClick = viewModel::dismissClearAllConfirm) { Text("Cancel") } },
        )
    }

    // ── Restore confirm ──
    if (state.showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRestoreConfirm,
            title = { Text("Restore backup?") },
            text = { Text("This will replace your current data with ${state.pendingRestorePlaylistCount ?: 0} playlist(s) and all settings from the backup file.") },
            confirmButton = { TextButton(onClick = { viewModel.confirmRestore(onBack) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = viewModel::dismissRestoreConfirm) { Text("Cancel") } },
        )
    }

    state.toastMessage?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(3000)
            viewModel.dismissToast()
        }
        Box(Modifier.fillMaxSize().padding(bottom = 24.dp), contentAlignment = Alignment.BottomCenter) {
            Surface(shape = ExpressivePillShape, color = MaterialTheme.colorScheme.inverseSurface) {
                Text(msg, color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
}

private fun appVersionName(context: android.content.Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
} catch (e: Exception) { "1.0" }

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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

    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp)) {
            if (hasCredentials) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Text(username.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Signed in as", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(username.ifBlank { "Last.fm user" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = { showLogoutConfirm = true }) { Icon(Icons.Filled.Logout, contentDescription = "Log out") }
                }
            } else {
                Text("Connect Last.fm", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiSecret,
                    onValueChange = { apiSecret = it },
                    label = { Text("API Secret") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("Get your API key at last.fm/api/account/create", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onSaveCredentials(apiKey, apiSecret) }, enabled = apiKey.isNotBlank() && apiSecret.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("Save API Credentials")
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
private fun SettingsToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AccentPresetGrid(currentMode: AccentMode, onPickPreset: (String) -> Unit, onPickMono: () -> Unit, onPickCustom: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        ACCENT_PRESETS.take(3).forEach { preset -> PresetSwatch(preset, Modifier.weight(1f), onPickPreset) }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        ACCENT_PRESETS.drop(3).forEach { preset -> PresetSwatch(preset, Modifier.weight(1f), onPickPreset) }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = onPickMono,
            shape = RoundedCornerShape(14.dp),
            color = if (currentMode == AccentMode.MONOCHROME) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.weight(1f),
        ) {
            Box(Modifier.padding(vertical = 14.dp).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Mono") }
        }
        Surface(
            onClick = onPickCustom,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.weight(1f),
        ) {
            Box(Modifier.padding(vertical = 14.dp).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Custom") }
        }
    }
}

@Composable
private fun PresetSwatch(preset: AccentPreset, modifier: Modifier, onPick: (String) -> Unit) {
    Column(modifier.clickable { onPick(preset.hex) }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(android.graphics.Color.parseColor(preset.hex))),
        )
        Spacer(Modifier.height(4.dp))
        Text(preset.name, style = MaterialTheme.typography.labelSmall)
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
            Text("Custom Color", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(previewColor)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
            )
            Spacer(Modifier.height(20.dp))
            Text("Hue", style = MaterialTheme.typography.labelLarge)
            Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
            Text("Saturation", style = MaterialTheme.typography.labelLarge)
            Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..1f)
            Text("Lightness", style = MaterialTheme.typography.labelLarge)
            Slider(value = lightness, onValueChange = { lightness = it }, valueRange = 0.15f..0.85f)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = { onApply(previewColor) }, modifier = Modifier.weight(1f)) { Text("Apply") }
            }
        }
    }
}
