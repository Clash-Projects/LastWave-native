package com.lastwave.app.ui.playlist

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.PlaylistCover
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.shell.FloatingNavDefaults
import com.lastwave.app.ui.theme.ArtworkShape
import com.lastwave.app.ui.theme.ExpressivePillShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatDate(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    onBack: () -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val musicPlayer = com.lastwave.app.ui.player.LocalMusicPlayer.current
    val playbackState by musicPlayer.state.collectAsState()
    LaunchedEffect(playlistId) {
        viewModel.loadDetail(playlistId)
    }

    val playlist = state.detailPlaylist?.takeIf { it.id == playlistId }
        ?: state.playlists.firstOrNull { it.id == playlistId }

    if (playlist == null) {
        if (state.isLoading || state.isDetailLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                com.lastwave.app.ui.common.ExpressiveLoadingIndicator(message = "Loading playlist...")
            }
            return
        }
        // Loading finished and playlist not found
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val isThisPlaylistPlaying = playbackState.isPlaying && playbackState.sourceLabel == playlist.title

    var coverEditorOpen by remember { mutableStateOf(false) }
    var coverPickerPending by remember { mutableStateOf(false) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.setCustomCover(playlistId, uri.toString())
        }
        coverPickerPending = false
    }

    var menuTarget by remember { mutableStateOf<GeneratedTrack?>(null) }
    var overflowMenuOpen by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val showScrolledHeader by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 280
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Ambient Mesh Gradient Backdrop
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f),
                            Color.Transparent,
                        ),
                        startY = 0f,
                        endY = 1000f,
                    ),
                ),
        )

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 58.dp,
                bottom = FloatingNavDefaults.contentBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Ambient Backdrop Glow & Hero Header
            item(key = "hero_section") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Elevated cover artwork with breathing glow shadow
                    Box(
                        modifier = Modifier
                            .size(208.dp)
                            .shadow(
                                elevation = 20.dp,
                                shape = RoundedCornerShape(28.dp),
                                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            ),
                    ) {
                        PlaylistCover(
                            playlist = playlist,
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = 28.dp,
                        )
                        if (isThisPlaylistPlaying) {
                            Surface(
                                shape = RoundedCornerShape(topStart = 14.dp, bottomEnd = 28.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
                                tonalElevation = 4.dp,
                                modifier = Modifier.align(Alignment.BottomEnd),
                            ) {
                                com.lastwave.app.ui.player.PlayingWaveBars(
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // Playlist Title
                    Text(
                        text = playlist.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(Modifier.height(10.dp))

                    // Metadata Pill
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                        tonalElevation = 1.dp,
                    ) {
                        Text(
                            text = "${playlist.tracks.size} songs \u2022 ${formatDate(playlist.createdAtMillis)}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }

                    Spacer(Modifier.height(22.dp))

                    // Hero Action Buttons (Play & Shuffle)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (playlist.tracks.isNotEmpty()) {
                                    musicPlayer.playQueue(
                                        playlist.tracks.map { track ->
                                            com.lastwave.app.playback.PlayableTrack(
                                                title = track.name,
                                                artist = track.artist,
                                                album = track.album,
                                                artworkUrl = track.artworkUrl,
                                            )
                                        },
                                        startIndex = 0,
                                        sourceLabel = playlist.title,
                                    )
                                }
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp, pressedElevation = 7.dp),
                            modifier = Modifier.weight(1f).height(52.dp),
                        ) {
                            Icon(
                                if (isThisPlaylistPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isThisPlaylistPlaying) "Playing" else "Play",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (playlist.tracks.isNotEmpty()) {
                                    val shuffled = playlist.tracks.map { track ->
                                        com.lastwave.app.playback.PlayableTrack(
                                            title = track.name,
                                            artist = track.artist,
                                            album = track.album,
                                            artworkUrl = track.artworkUrl,
                                        )
                                    }.shuffled()
                                    musicPlayer.playQueue(
                                        shuffled,
                                        startIndex = 0,
                                        sourceLabel = playlist.title,
                                    )
                                }
                            },
                            shape = CircleShape,
                            modifier = Modifier.weight(1f).height(52.dp),
                        ) {
                            Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Shuffle", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                }
            }

            // Track list header
            if (playlist.tracks.isNotEmpty()) {
                item(key = "track_list_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Tracks",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "${playlist.tracks.size} tracks",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            // Track items
            if (playlist.tracks.isEmpty()) {
                item(key = "empty_tracks") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No tracks in this playlist yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = playlist.tracks,
                    key = { index, track -> "${track.name}|${track.artist}|$index" },
                ) { index, track ->
                    val isPlayingThisSong = playbackState.isPlaying &&
                        playbackState.current?.title.equals(track.name, ignoreCase = true) &&
                        playbackState.current?.artist.equals(track.artist, ignoreCase = true)

                    Box(Modifier.animateItem()) {
                        NativeTrackRow(
                            index = index + 1,
                            track = track,
                            isPlaying = isPlayingThisSong,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                musicPlayer.playQueue(
                                    playlist.tracks.map { t ->
                                        com.lastwave.app.playback.PlayableTrack(
                                            title = t.name,
                                            artist = t.artist,
                                            album = t.album,
                                            artworkUrl = t.artworkUrl,
                                        )
                                    },
                                    startIndex = index,
                                    sourceLabel = playlist.title,
                                )
                            },
                            onMenu = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuTarget = track
                            },
                        )
                    }
                }
            }
        }

        // Native Collapsing Top Bar with Dynamic Elevation & Title Fade
        Surface(
            color = if (showScrolledHeader) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.98f) else Color.Transparent,
            tonalElevation = if (showScrolledHeader) 4.dp else 0.dp,
            shadowElevation = if (showScrolledHeader) 6.dp else 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedVisibility(
                        visible = showScrolledHeader,
                        enter = fadeIn() + scaleIn(initialScale = 0.9f),
                        exit = fadeOut() + scaleOut(targetScale = 0.9f),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(32.dp)) {
                                PlaylistCover(
                                    playlist = playlist,
                                    modifier = Modifier.fillMaxSize(),
                                    cornerRadius = 8.dp,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = playlist.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // Scrolled Quick Play Mini Button
                AnimatedVisibility(
                    visible = showScrolledHeader && playlist.tracks.isNotEmpty(),
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            musicPlayer.playQueue(
                                playlist.tracks.map { track ->
                                    com.lastwave.app.playback.PlayableTrack(
                                        title = track.name,
                                        artist = track.artist,
                                        album = track.album,
                                        artworkUrl = track.artworkUrl,
                                    )
                                },
                                startIndex = 0,
                                sourceLabel = playlist.title,
                            )
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            if (isThisPlaylistPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "Play",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Box {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            overflowMenuOpen = true
                        },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Playlist options",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    DropdownMenu(
                        expanded = overflowMenuOpen,
                        onDismissRequest = { overflowMenuOpen = false },
                        shape = RoundedCornerShape(24.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                        shadowElevation = 12.dp,
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (playlist.isPinned) "Unpin playlist" else "Pin to top") },
                            leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                            onClick = {
                                viewModel.togglePinned(playlistId)
                                overflowMenuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Change cover image") },
                            leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                            onClick = {
                                coverEditorOpen = true
                                overflowMenuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Rename playlist") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = {
                                viewModel.requestRename(playlistId)
                                overflowMenuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Export / Share") },
                            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                            onClick = {
                                viewModel.openExportSheet(playlistId)
                                overflowMenuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Mark as completed") },
                            leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                            onClick = {
                                viewModel.completePlaylist(playlistId)
                                overflowMenuOpen = false
                                onBack()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete playlist", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                viewModel.requestDelete(playlistId)
                                overflowMenuOpen = false
                            },
                        )
                    }
                }
            }
        }

        // Toasts
        state.toastMessage?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(3000)
                viewModel.dismissToast()
            }
            Surface(
                shape = ExpressivePillShape,
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = FloatingNavDefaults.contentBottomPadding() + 12.dp),
            ) {
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }

    // Cover Editor Dialog
    if (coverEditorOpen) {
        AlertDialog(
            onDismissRequest = { coverEditorOpen = false },
            title = { Text("Playlist cover") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PlaylistCover(playlist = playlist, modifier = Modifier.size(140.dp), cornerRadius = 26.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (playlist.customCoverUri.isNullOrBlank()) {
                            "Automatic cover uses the first song with available artwork metadata."
                        } else {
                            "This playlist is using your selected image."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coverPickerPending = true
                        coverEditorOpen = false
                        coverPicker.launch(arrayOf("image/*"))
                    },
                ) {
                    Text(if (playlist.customCoverUri.isNullOrBlank()) "Choose image" else "Change image")
                }
            },
            dismissButton = {
                Row {
                    if (!playlist.customCoverUri.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                viewModel.setCustomCover(playlistId, null)
                                coverEditorOpen = false
                            },
                        ) { Text("Use automatic") }
                    }
                    TextButton(onClick = { coverEditorOpen = false }) { Text("Done") }
                }
            },
        )
    }

    // Delete confirmation dialog
    if (state.deleteConfirmForPlaylistId != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConfirm,
            title = { Text("Delete playlist?") },
            text = { Text("This will permanently remove \"${playlist.title}\".") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.confirmDelete()
                    onBack()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDeleteConfirm) { Text("Cancel") } },
        )
    }

    // Rename dialog
    state.renamePlaylistId?.let { id ->
        var title by remember(id) { mutableStateOf(playlist.title) }
        AlertDialog(
            onDismissRequest = viewModel::dismissRename,
            title = { Text("Rename playlist") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.renamePlaylist(title) }, enabled = title.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissRename) { Text("Cancel") } },
        )
    }

    // Export bottom sheet
    state.exportSheetForPlaylistId?.let { id ->
        ExportBottomSheet(
            onDismiss = viewModel::dismissExportSheet,
            onSaveCsv = { viewModel.exportSave(id, ExportFormat.CSV) },
            onSaveM3u = { viewModel.exportSave(id, ExportFormat.M3U) },
            onShareCsv = { viewModel.exportShare(id, ExportFormat.CSV) },
            onShareM3u = { viewModel.exportShare(id, ExportFormat.M3U) },
        )
    }

    // Track Context Menu
    menuTarget?.let { track ->
        TrackContextMenuSheet(
            target = TrackMenuTarget.Track(track.name, track.artist, track.url),
            capabilities = TrackMenuCapabilities(showCopyActions = true, showDeleteScrobble = true),
            playbackSourceLabel = playlist.title,
            onDismiss = { menuTarget = null },
            onDeleteScrobble = { name, artist -> viewModel.deleteScrobble(name, artist) },
            onRefreshArtwork = { viewModel.refreshArtwork(track.name, track.artist) },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NativeTrackRow(
    index: Int,
    track: GeneratedTrack,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onMenu,
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Track index or playing animation
            Box(
                modifier = Modifier.width(24.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (isPlaying) {
                    com.lastwave.app.ui.player.PlayingWaveBars(Modifier.size(18.dp))
                } else {
                    Text(
                        text = "$index",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            // High-res track artwork with animated waves overlay
            Box(modifier = Modifier.size(48.dp)) {
                ArtworkImage(
                    name = track.name,
                    artist = track.artist,
                    embeddedUrl = track.artworkUrl,
                    fallbackIcon = if (isPlaying) Icons.Filled.GraphicEq else Icons.Filled.MusicNote,
                    modifier = Modifier.fillMaxSize().clip(ArtworkShape),
                )
                if (isPlaying) {
                    com.lastwave.app.ui.player.PlayingWaveBars(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp),
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            // Title & Artist
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Options menu button
            IconButton(
                onClick = onMenu,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Song options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
