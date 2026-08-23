package com.lastwave.app.ui.playlist

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.PlaylistCover
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.shell.FloatingNavDefaults
import com.lastwave.app.ui.theme.ExpressivePillShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Faithful port of playlist.js's saved-playlists screen (§4): card list
 * backed by Room via PlaylistViewModel, expand/collapse with lazy track
 * rendering, the "just generated" regenerate bar, export bottom sheet
 * (CSV/M3U), Generate Similar, and delete — plus the shared track context
 * menu (§1.7) with Copy + Delete Scrobble enabled (this screen's full
 * capability set, matching the original's playlist.js menu exactly).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(viewModel: PlaylistViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val musicPlayer = com.lastwave.app.ui.player.LocalMusicPlayer.current
    val playbackState by musicPlayer.state.collectAsState()
    val addToPlaylist = com.lastwave.app.ui.player.LocalAddToPlaylist.current
    var coverEditorPlaylistId by remember { mutableStateOf<Long?>(null) }
    var coverPickerPlaylistId by remember { mutableStateOf<Long?>(null) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val playlistId = coverPickerPlaylistId
        if (uri != null && playlistId != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.setCustomCover(playlistId, uri.toString())
        }
        coverPickerPlaylistId = null
    }

    // Re-reads from Room whenever this tab regains visibility — this is how
    // a playlist just saved by Generate shows up here without polling.
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }

    var menuTarget by remember { mutableStateOf<Pair<Long, GeneratedTrack>?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            var sortMenuExpanded by remember { mutableStateOf(false) }
            ExpressiveHeader(
                title = "Playlist",
                subtitle = "${state.playlists.size} Playlists \u00b7 ${state.playlists.sumOf { it.tracks.size }} Tracks",
                actions = {
                    IconButton(
                        onClick = viewModel::openCreateDialog,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Create custom playlist")
                    }
                    Box {
                        Surface(
                            onClick = { sortMenuExpanded = true },
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            tonalElevation = 1.dp,
                            modifier = Modifier.heightIn(min = 34.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = "Sort playlists",
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Sort",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
                            shape = RoundedCornerShape(22.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 4.dp,
                            shadowElevation = 10.dp,
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            DropdownMenuItem(text = { Text("Newest first") }, onClick = { viewModel.setSortMode(PlaylistSortMode.DATE_DESC); sortMenuExpanded = false })
                            DropdownMenuItem(text = { Text("Oldest first") }, onClick = { viewModel.setSortMode(PlaylistSortMode.DATE_ASC); sortMenuExpanded = false })
                            DropdownMenuItem(text = { Text("Name") }, onClick = { viewModel.setSortMode(PlaylistSortMode.NAME); sortMenuExpanded = false })
                            DropdownMenuItem(text = { Text("Track count") }, onClick = { viewModel.setSortMode(PlaylistSortMode.TRACK_COUNT); sortMenuExpanded = false })
                        }
                    }
                },
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            ) {
                when {
                    state.isLoading && state.playlists.isEmpty() && !state.isGenerating -> LoadingState()
                    state.playlists.isEmpty() && !state.isGenerating -> EmptyState()
                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = FloatingNavDefaults.contentBottomPadding()),
                        verticalArrangement = Arrangement.spacedBy(com.lastwave.app.ui.common.GroupGap),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.isGenerating) {
                            item(key = "generationProgress", contentType = "generationProgress") {
                                com.lastwave.app.ui.common.GenerationProgressCard(message = state.generatingMessage)
                            }
                        }

                        if (state.justSavedBannerVisible) {
                            item(key = "banner") {
                                LaunchedEffect(state.justSavedBannerVisible) {
                                    kotlinx.coroutines.delay(3000)
                                    viewModel.dismissJustSavedBanner()
                                }
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "Playlist saved!",
                                        modifier = Modifier.padding(14.dp),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }

                        itemsIndexed(state.playlists, key = { _, playlist -> playlist.id }) { index, playlist ->
                            val isNewest = playlist.id == state.newestId
                            Box(Modifier.animateItem()) {
                                PlaylistCard(
                                    playlist = playlist,
                                    expanded = playlist.id in state.expandedIds,
                                    isNewest = isNewest,
                                    position = com.lastwave.app.ui.common.groupPositionFor(index, state.playlists.size),
                                    isRegenerating = state.regeneratingId == playlist.id,
                                    currentTrack = playbackState.current,
                                    isPlaying = playbackState.isPlaying,
                                    playbackSource = playbackState.sourceLabel,
                                    onToggleExpand = { viewModel.toggleExpanded(playlist.id) },
                                    onExport = { viewModel.openExportSheet(playlist.id) },
                                    onRegenerate = { viewModel.regenerate(playlist.id) },
                                    onRename = { viewModel.requestRename(playlist.id) },
                                    onEditCover = { coverEditorPlaylistId = playlist.id },
                                    onComplete = { viewModel.completePlaylist(playlist.id) },
                                    onTogglePin = { viewModel.togglePinned(playlist.id) },
                                    onDelete = { viewModel.requestDelete(playlist.id) },
                                    onRemoveTrack = { trackIndex -> viewModel.removeTrack(playlist.id, trackIndex) },
                                    onPlay = { startIndex ->
                                        musicPlayer.playQueue(
                                            playlist.tracks.map { track ->
                                                com.lastwave.app.playback.PlayableTrack(
                                                    title = track.name,
                                                    artist = track.artist,
                                                    album = track.album,
                                                    artworkUrl = track.artworkUrl,
                                                )
                                            },
                                            startIndex = startIndex,
                                            sourceLabel = playlist.title,
                                        )
                                    },
                                    onShufflePlay = {
                                        val tracks = playlist.tracks.map { track ->
                                            com.lastwave.app.playback.PlayableTrack(
                                                title = track.name,
                                                artist = track.artist,
                                                album = track.album,
                                                artworkUrl = track.artworkUrl,
                                            )
                                        }.shuffled()
                                        musicPlayer.playQueue(
                                            tracks,
                                            startIndex = 0,
                                            sourceLabel = playlist.title,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        state.toastMessage?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(3000)
                viewModel.dismissToast()
            }
            Surface(
                shape = ExpressivePillShape,
                color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = FloatingNavDefaults.contentBottomPadding() + 12.dp),
            ) {
                Text(msg, color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }

    // ── Export bottom sheet (§4.6) ──
    state.exportSheetForPlaylistId?.let { id ->
        ExportBottomSheet(
            onDismiss = viewModel::dismissExportSheet,
            onSaveCsv = { viewModel.exportSave(id, ExportFormat.CSV) },
            onSaveM3u = { viewModel.exportSave(id, ExportFormat.M3U) },
            onShareCsv = { viewModel.exportShare(id, ExportFormat.CSV) },
            onShareM3u = { viewModel.exportShare(id, ExportFormat.M3U) },
        )
    }

    // ── Delete confirm dialog ──
    if (state.deleteConfirmForPlaylistId != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConfirm,
            title = { Text("Delete playlist?") },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = viewModel::dismissDeleteConfirm) { Text("Cancel") } },
        )
    }

    // ── Delete-scrobble authorization-required dialog ──
    if (state.createDialogVisible) {
        var title by remember(state.createDialogVisible) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = viewModel::dismissCreateDialog,
            title = { Text("Create custom playlist") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.createCustomPlaylist(title) }, enabled = title.isNotBlank()) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissCreateDialog) { Text("Cancel") } },
        )
    }

    state.renamePlaylistId?.let { playlistId ->
        val playlist = state.playlists.firstOrNull { it.id == playlistId }
        var title by remember(playlistId) { mutableStateOf(playlist?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = viewModel::dismissRename,
            title = { Text("Rename playlist") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.renamePlaylist(title) }, enabled = title.isNotBlank()) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissRename) { Text("Cancel") } },
        )
    }

    coverEditorPlaylistId?.let { playlistId ->
        state.playlists.firstOrNull { it.id == playlistId }?.let { playlist ->
            AlertDialog(
                onDismissRequest = { coverEditorPlaylistId = null },
                title = { Text("Playlist cover") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PlaylistCover(playlist = playlist, modifier = Modifier.size(132.dp), cornerRadius = 26.dp)
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
                            coverPickerPlaylistId = playlist.id
                            coverEditorPlaylistId = null
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
                                    viewModel.setCustomCover(playlist.id, null)
                                    coverEditorPlaylistId = null
                                },
                            ) { Text("Use automatic") }
                        }
                        TextButton(onClick = { coverEditorPlaylistId = null }) { Text("Done") }
                    }
                },
            )
        }
    }

    if (state.deleteScrobbleAuthRequired) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteScrobbleAuthRequired,
            title = { Text("Not Available") },
            text = { Text("Deleting scrobbles needs a Last.fm session key, which this sign-in method (API key + username) doesn't obtain.") },
            confirmButton = { TextButton(onClick = viewModel::dismissDeleteScrobbleAuthRequired) { Text("OK") } },
        )
    }

    // ── Shared track context menu (§1.7) ──
    menuTarget?.let { (playlistId, track) ->
        TrackContextMenuSheet(
            target = TrackMenuTarget.Track(track.name, track.artist, track.url),
            capabilities = TrackMenuCapabilities(showCopyActions = true, showDeleteScrobble = true),
            playbackSourceLabel = state.playlists.firstOrNull { it.id == playlistId }?.title ?: "Playlists",
            onDismiss = { menuTarget = null },
            onDeleteScrobble = { name, artist -> viewModel.deleteScrobble(name, artist) },
            onRefreshArtwork = { viewModel.refreshArtwork(track.name, track.artist) },
        )
    }
}

@Composable
private fun CountPill(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.heightIn(min = 34.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxHeight()) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        com.lastwave.app.ui.common.ExpressiveLoadingIndicator()
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = ExpressivePillShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(96.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("No playlists yet", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(
                "Head to Generate to create your first mix.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlaylistCard(
    playlist: SavedPlaylist,
    expanded: Boolean,
    isNewest: Boolean,
    position: com.lastwave.app.ui.common.GroupPosition,
    isRegenerating: Boolean,
    currentTrack: com.lastwave.app.playback.PlayableTrack?,
    isPlaying: Boolean,
    playbackSource: String,
    onToggleExpand: () -> Unit,
    onExport: () -> Unit,
    onRegenerate: () -> Unit,
    onRename: () -> Unit,
    onEditCover: () -> Unit,
    onComplete: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onRemoveTrack: (Int) -> Unit,
    onPlay: (Int) -> Unit,
    onShufflePlay: () -> Unit = {},
    onAddTrackToPlaylist: (GeneratedTrack) -> Unit = {},
    onTrackMenu: (GeneratedTrack) -> Unit = {},
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val isThisPlaylistPlaying = isPlaying && playbackSource == playlist.title
    Card(
        shape = com.lastwave.app.ui.common.groupShape(position),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onToggleExpand,
                        onLongClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(playlist.title)) },
                    )
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(60.dp)) {
                    PlaylistCover(playlist = playlist, modifier = Modifier.fillMaxSize())
                    if (isThisPlaylistPlaying) {
                        com.lastwave.app.ui.player.PlayingWaveBars(
                            Modifier.align(Alignment.BottomEnd).padding(3.dp),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            playlist.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (playlist.isPinned) {
                            Icon(
                                Icons.Filled.PushPin,
                                contentDescription = "Pinned",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Text(
                        "${playlist.tracks.size} tracks \u00b7 ${formatDate(playlist.createdAtMillis)}${if (playlist.subtitle.isNotBlank()) " \u00b7 ${playlist.subtitle}" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (expanded) {
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        onClick = onComplete,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Mark playlist complete",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Surface(
                        onClick = { onPlay(0) },
                        enabled = playlist.tracks.isNotEmpty(),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shadowElevation = 2.dp,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Play playlist",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = onShufflePlay,
                        enabled = playlist.tracks.isNotEmpty(),
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = "Shuffle playlist",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = onEditCover,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            Icons.Filled.PhotoLibrary,
                            contentDescription = "Choose playlist cover",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    if (playlist.mode == "custom") {
                        IconButton(
                            onClick = onRename,
                            modifier = Modifier.size(38.dp),
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Rename playlist",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else {
                        com.lastwave.app.ui.common.ExpressiveRefreshButton(
                            isRefreshing = isRegenerating,
                            onClick = onRegenerate,
                            contentDescription = "Regenerate",
                            modifier = Modifier.size(38.dp),
                            iconSize = 20.dp,
                        )
                    }
                    IconButton(
                        onClick = onExport,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = "Export",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = onTogglePin,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = if (playlist.isPinned) "Unpin playlist" else "Pin playlist",
                            tint = if (playlist.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Column(Modifier.padding(bottom = 8.dp)) {
                    playlist.tracks.forEachIndexed { index, track ->
                        TrackRow(
                            index = index + 1,
                            track = track,
                            isPlaying = isThisPlaylistPlaying &&
                                currentTrack?.title?.equals(track.name, ignoreCase = true) == true &&
                                currentTrack?.artist?.equals(track.artist, ignoreCase = true) == true,
                            onClick = { onPlay(index) },
                            onLongClick = { onAddTrackToPlaylist(track) },
                            onRemove = if (playlist.mode == "custom") ({ onRemoveTrack(index) }) else null,
                            onMenuClick = { onTrackMenu(track) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun TrackRow(
    index: Int,
    track: GeneratedTrack,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemove: (() -> Unit)?,
    onMenuClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$index", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
        Box(Modifier.size(40.dp)) {
            ArtworkImage(
                name = track.name,
                artist = track.artist,
                embeddedUrl = track.artworkUrl,
                fallbackIcon = Icons.Filled.MusicNote,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
            )
            if (isPlaying) {
                com.lastwave.app.ui.player.PlayingWaveBars(
                    Modifier.align(Alignment.BottomEnd).padding(2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove from playlist", tint = MaterialTheme.colorScheme.error)
            }
        }
        com.lastwave.app.ui.common.OverflowMenuButton(onClick = onMenuClick)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportBottomSheet(
    onDismiss: () -> Unit,
    onSaveCsv: () -> Unit,
    onSaveM3u: () -> Unit,
    onShareCsv: () -> Unit,
    onShareM3u: () -> Unit,
) {
    var selected by remember { mutableStateOf(ExportFormat.CSV) }
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(20.dp)) {
            Text("Export Playlist", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            FormatOptionRow(
                title = "CSV",
                description = "Spreadsheet-compatible",
                selected = selected == ExportFormat.CSV,
                onClick = { selected = ExportFormat.CSV },
            )
            Spacer(Modifier.height(8.dp))
            FormatOptionRow(
                title = "M3U",
                description = "Media-player playlist file",
                selected = selected == ExportFormat.M3U,
                onClick = { selected = ExportFormat.M3U },
            )

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                TextButton(
                    onClick = { if (selected == ExportFormat.CSV) onSaveCsv() else onSaveM3u() },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
                TextButton(
                    onClick = { if (selected == ExportFormat.CSV) onShareCsv() else onShareM3u() },
                    modifier = Modifier.weight(1f),
                ) { Text("Share") }
            }
        }
    }
}

@Composable
private fun FormatOptionRow(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))
