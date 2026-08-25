package com.lastwave.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.music.YouTubePlaylistResult
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.groupPositionFor
import com.lastwave.app.ui.common.groupShape
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import com.lastwave.app.ui.shell.FloatingNavDefaults
import com.lastwave.app.ui.theme.ArtworkShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubePlaylistImportScreen(
    onBack: () -> Unit,
    onImportSuccess: (List<SavedPlaylist>) -> Unit,
    viewModel: YouTubePlaylistImportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current

    val quickPills = listOf("Top Hits 2024", "Pop Classics", "Lofi Chill", "Hip Hop Gold", "Workout Energy", "Deep Focus")

    // CSV Document Picker Launcher
    val csvPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val filename = queryFileName(context, uri) ?: "Imported Playlist.csv"
                val stream = context.contentResolver.openInputStream(uri)
                if (stream != null) {
                    viewModel.importCsv(stream, filename) { saved ->
                        onImportSuccess(listOf(saved))
                    }
                } else {
                    viewModel.showError("The selected file could not be opened")
                }
            } catch (error: Exception) {
                viewModel.showError("The selected file is unavailable on this device")
            } catch (error: LinkageError) {
                viewModel.showError("File import isn't supported by this ROM")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            ExpressiveHeader(
                title = "Import Playlist",
                subtitle = "YouTube Music, Links & CSV Files",
                onBack = onBack,
                actions = {
                    val activeList = when (state.selectedTab) {
                        ImportTab.LIBRARY -> state.libraryPlaylists
                        ImportTab.SEARCH -> state.searchResults
                        else -> emptyList()
                    }
                    if (activeList.isNotEmpty()) {
                        val allSelected = activeList.all { it.id in state.selectedPlaylistIds }
                        TextButton(onClick = {
                            viewModel.selectAll(!allSelected)
                        }) {
                            Text(if (allSelected) "Deselect All" else "Select All")
                        }
                    }
                },
            )

            // Segmented Tab Selector with Vector Icons
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Row(
                    modifier = Modifier.padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // The account's own library leads the list once a YouTube
                    // Music account is connected in Settings.
                    val visibleTabs = if (state.ytConnected) {
                        ImportTab.entries.toList()
                    } else {
                        ImportTab.entries.filter { it != ImportTab.LIBRARY }
                    }
                    visibleTabs.forEach { tab ->
                        val selected = state.selectedTab == tab
                        val icon = when (tab) {
                            ImportTab.LIBRARY -> Icons.Filled.LibraryMusic
                            ImportTab.SEARCH -> Icons.Filled.QueueMusic
                            ImportTab.LINK -> Icons.Filled.Link
                            ImportTab.CSV -> Icons.Filled.Description
                        }
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.selectTab(tab)
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = tab.title,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 10.dp,
                    bottom = 80.dp + LocalMiniPlayerScrollClearance.current,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // TAB 0: The connected account's own library playlists —
                // select any and import them into LastWave.
                if (state.selectedTab == ImportTab.LIBRARY) {
                    when {
                        state.isLoadingLibrary -> item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        "Loading ${state.ytAccountName}'s playlists...",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }

                        state.libraryPlaylists.isEmpty() -> item {
                            Card(
                                shape = RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.LibraryMusic,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(36.dp),
                                    )
                                    Text(
                                        "No library playlists found",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "Create a playlist in YouTube Music (or refresh) and it will show up here.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                    FilledTonalButton(onClick = { viewModel.loadLibrary(force = true) }) {
                                        Text("Refresh")
                                    }
                                }
                            }
                        }

                        else -> {
                            item {
                                Text(
                                    "${state.libraryPlaylists.size} playlist(s) on your account — pick any to import",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                                )
                            }
                            itemsIndexed(
                                items = state.libraryPlaylists,
                                key = { _, item -> "lib_${item.id}" },
                            ) { index, playlist ->
                                val isSelected = playlist.id in state.selectedPlaylistIds
                                Box(Modifier.animateItem()) {
                                    YouTubePlaylistCard(
                                        playlist = playlist,
                                        isSelected = isSelected,
                                        position = groupPositionFor(index, state.libraryPlaylists.size),
                                        onToggleSelect = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.togglePlaylistSelection(playlist.id)
                                        },
                                        onPreview = {
                                            viewModel.loadPreview(playlist.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // TAB 1: YouTube Music Search
                if (state.selectedTab == ImportTab.SEARCH) {
                    // Search & Paste Input Box
                    item {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::onQueryChange,
                            placeholder = { Text("Search YouTube Music playlists...") },
                            leadingIcon = {
                                Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (state.query.isNotBlank()) {
                                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                                            Icon(Icons.Filled.Clear, contentDescription = "Clear")
                                        }
                                    } else {
                                        IconButton(onClick = {
                                            val text = clipboard.getText()?.text.orEmpty()
                                            if (text.isNotBlank()) {
                                                viewModel.onQueryChange(text)
                                                viewModel.search(text)
                                            }
                                        }) {
                                            Icon(Icons.Filled.ContentPaste, contentDescription = "Paste", tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                keyboard?.hide()
                                viewModel.search()
                            }),
                            shape = RoundedCornerShape(20.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Quick Pills
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                        ) {
                            items(quickPills) { pill ->
                                FilterChip(
                                    selected = state.query.equals(pill, ignoreCase = true),
                                    onClick = {
                                        viewModel.onQueryChange(pill)
                                        viewModel.search(pill)
                                    },
                                    label = { Text(pill, style = MaterialTheme.typography.labelMedium) },
                                    shape = CircleShape,
                                )
                            }
                        }
                    }

                    // Loading State
                    if (state.isSearching) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                    Text("Searching YouTube Music playlists...", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    } else if (state.searchResults.isNotEmpty()) {
                        item {
                            Text(
                                "Found ${state.searchResults.size} Playlists",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                            )
                        }

                        itemsIndexed(
                            items = state.searchResults,
                            key = { _, item -> item.id },
                        ) { index, playlist ->
                            val isSelected = playlist.id in state.selectedPlaylistIds

                            Box(Modifier.animateItem()) {
                                YouTubePlaylistCard(
                                    playlist = playlist,
                                    isSelected = isSelected,
                                    position = groupPositionFor(index, state.searchResults.size),
                                    onToggleSelect = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.togglePlaylistSelection(playlist.id)
                                    },
                                    onPreview = {
                                        viewModel.loadPreview(playlist.id)
                                    },
                                )
                            }
                        }
                    }
                }

                // TAB 2: Direct Link Importer
                if (state.selectedTab == ImportTab.LINK) {
                    item {
                        Card(
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Text(
                                    "Paste Playlist Link",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "Paste any YouTube or YouTube Music playlist URL. LastWave resolves every track — no 100-song cap, full playlists import completely.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                OutlinedTextField(
                                    value = state.directLink,
                                    onValueChange = viewModel::onDirectLinkChange,
                                    placeholder = { Text("https://music.youtube.com/playlist?list=...") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    },
                                    trailingIcon = {
                                        if (state.directLink.isNotBlank()) {
                                            IconButton(onClick = { viewModel.onDirectLinkChange("") }) {
                                                Icon(Icons.Filled.Clear, contentDescription = "Clear")
                                            }
                                        } else {
                                            IconButton(onClick = {
                                                val text = clipboard.getText()?.text.orEmpty()
                                                if (text.isNotBlank()) {
                                                    viewModel.onDirectLinkChange(text)
                                                    viewModel.resolveDirectLink(text)
                                                }
                                            }) {
                                                Icon(Icons.Filled.ContentPaste, contentDescription = "Paste", tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        keyboard?.hide()
                                        viewModel.resolveDirectLink()
                                    },
                                    enabled = state.directLink.isNotBlank() && !state.isPreviewLoading,
                                    shape = CircleShape,
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                ) {
                                    if (state.isPreviewLoading) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Resolving Playlist...")
                                    } else {
                                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Preview & Import Link", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                // TAB 3: CSV File Importer
                if (state.selectedTab == ImportTab.CSV) {
                    item {
                        Card(
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(64.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Filled.Description,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(32.dp),
                                        )
                                    }
                                }

                                Text(
                                    "Import from Playlist File",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                )

                                Text(
                                    "Import your playlists from M3U, M3U8, or CSV exports (Spotify, Apple Music, VLC, Soundiiz, or local storage). LastWave parses and matches original titles and artists.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )

                                Spacer(Modifier.height(4.dp))

                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        try {
                                            csvPickerLauncher.launch(arrayOf("text/*", "text/csv", "application/csv", "audio/x-mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl", "*/*"))
                                        } catch (error: Exception) {
                                            viewModel.showError("No file picker is available")
                                        } catch (error: LinkageError) {
                                            viewModel.showError("File picking isn't supported by this ROM")
                                        }
                                    },
                                    enabled = !state.isCsvImporting,
                                    shape = CircleShape,
                                    modifier = Modifier.fillMaxWidth().height(50.dp),
                                ) {
                                    if (state.isCsvImporting) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                        Spacer(Modifier.width(8.dp))
                                        Text(state.importProgress ?: "Importing Playlist...")
                                    } else {
                                        Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Select CSV or M3U File", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                // Error Message Card
                state.errorMessage?.let { error ->
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                }
            }
        }

        // Floating Bottom Import CTA (for multi-select on library & search tabs)
        AnimatedVisibility(
            visible = state.selectedTab in listOf(ImportTab.SEARCH, ImportTab.LIBRARY) &&
                state.selectedPlaylistIds.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(bottom = 16.dp + LocalMiniPlayerScrollClearance.current)
                .padding(horizontal = 20.dp),
        ) {
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.importSelected { savedList ->
                        onImportSuccess(savedList)
                    }
                },
                enabled = !state.isImporting,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (state.isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        state.importProgress ?: "Importing Playlists...",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Import ${state.selectedPlaylistIds.size} Selected Playlist(s)",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    // Modal Preview for Songs inside playlist
    state.previewPlaylist?.let { preview ->
        PlaylistPreviewModal(
            preview = preview,
            onDismiss = { viewModel.dismissPreview() },
            onImportThis = {
                viewModel.importSelected { savedList ->
                    viewModel.dismissPreview()
                    onImportSuccess(savedList)
                }
            },
        )
    }
}

@Composable
private fun YouTubePlaylistCard(
    playlist: YouTubePlaylistSummary,
    isSelected: Boolean,
    position: com.lastwave.app.ui.common.GroupPosition,
    onToggleSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    Card(
        shape = groupShape(position),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleSelect),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
            )

            Spacer(Modifier.width(10.dp))

            ArtworkImage(
                name = playlist.title,
                artist = playlist.author.orEmpty(),
                embeddedUrl = playlist.artworkUrl,
                fallbackIcon = Icons.Filled.MusicNote,
                modifier = Modifier.size(54.dp).clip(ArtworkShape),
            )

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${playlist.author ?: "YouTube Music"} \u2022 ${playlist.trackCountText ?: "Playlist"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onPreview, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Visibility,
                    contentDescription = "Preview tracks",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistPreviewModal(
    preview: YouTubePlaylistResult,
    onDismiss: () -> Unit,
    onImportThis: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp + safeDrawingBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(
                    name = preview.title,
                    artist = preview.author.orEmpty(),
                    embeddedUrl = preview.artworkUrl,
                    fallbackIcon = Icons.Filled.MusicNote,
                    modifier = Modifier.size(64.dp).clip(ArtworkShape),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        preview.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${preview.author ?: "YouTube"} \u2022 ${preview.trackCount} tracks",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                "Tracklist Preview",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(preview.tracks) { track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                track.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Button(
                onClick = onImportThis,
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Import \"${preview.title}\"", fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun queryFileName(context: Context, uri: Uri): String? {
    var name: String? = null
    val returnCursor = context.contentResolver.query(uri, null, null, null, null)
    if (returnCursor != null) {
        val nameIndex = returnCursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        returnCursor.moveToFirst()
        if (nameIndex != -1) {
            name = returnCursor.getString(nameIndex)
        }
        returnCursor.close()
    }
    return name
}
