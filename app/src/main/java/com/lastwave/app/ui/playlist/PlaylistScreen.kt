package com.lastwave.app.ui.playlist

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.ui.common.ArtworkImage
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

    // Re-reads from Room whenever this tab regains visibility — this is how
    // a playlist just saved by Generate shows up here without polling.
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }

    var menuTarget by remember { mutableStateOf<Pair<Long, GeneratedTrack>?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        when {
            state.isLoading && state.playlists.isEmpty() && !state.isGenerating -> LoadingState()
            state.playlists.isEmpty() && !state.isGenerating -> EmptyState()
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = FloatingNavDefaults.contentBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item(key = "header", contentType = "header") {
                    var sortMenuExpanded by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Playlist",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Row(
                            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.End),
                        ) {
                            CountPill("${state.playlists.size} Playlists")
                            CountPill("${state.playlists.sumOf { it.tracks.size }} Tracks")
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
                        }
                    }
                }

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

                items(state.playlists, key = { it.id }) { playlist ->
                    val isNewest = playlist.id == state.newestId
                    PlaylistCard(
                        playlist = playlist,
                        expanded = playlist.id in state.expandedIds,
                        isNewest = isNewest,
                        isRegenerating = state.regeneratingId == playlist.id,
                        isGeneratingSimilar = state.isGeneratingSimilarFor == playlist.id,
                        onToggleExpand = { viewModel.toggleExpanded(playlist.id) },
                        onExport = { viewModel.openExportSheet(playlist.id) },
                        onRegenerate = { viewModel.regenerate(playlist.id) },
                        onGenerateSimilar = { viewModel.generateSimilar(playlist.id) },
                        onDelete = { viewModel.requestDelete(playlist.id) },
                        onTrackMenu = { track -> menuTarget = playlist.id to track },
                    )
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
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = FloatingNavDefaults.ContentBottomPadding + 12.dp),
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
    if (state.deleteScrobbleAuthRequired) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteScrobbleAuthRequired,
            title = { Text("Authorization Required") },
            text = { Text("Deleting scrobbles requires LastWave to be authorized with your Last.fm account. Go to Settings to authorize, then try again.") },
            confirmButton = { TextButton(onClick = viewModel::dismissDeleteScrobbleAuthRequired) { Text("OK") } },
        )
    }

    // ── Shared track context menu (§1.7) ──
    menuTarget?.let { (_, track) ->
        TrackContextMenuSheet(
            target = TrackMenuTarget.Track(track.name, track.artist, track.url),
            capabilities = TrackMenuCapabilities(showCopyActions = true, showDeleteScrobble = true),
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
        CircularProgressIndicator()
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
    isRegenerating: Boolean,
    isGeneratingSimilar: Boolean,
    onToggleExpand: () -> Unit,
    onExport: () -> Unit,
    onRegenerate: () -> Unit,
    onGenerateSimilar: () -> Unit,
    onDelete: () -> Unit,
    onTrackMenu: (GeneratedTrack) -> Unit,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
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
                CoverGrid(playlist.tracks)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(playlist.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onRegenerate, enabled = !isRegenerating) {
                        if (isRegenerating) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Refresh, contentDescription = "Regenerate")
                    }
                    IconButton(onClick = onExport) { Icon(Icons.Filled.Download, contentDescription = "Export") }
                    IconButton(onClick = onGenerateSimilar, enabled = !isGeneratingSimilar) {
                        if (isGeneratingSimilar) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Shuffle, contentDescription = "Generate Similar")
                    }
                    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Column(Modifier.padding(bottom = 8.dp)) {
                    playlist.tracks.forEachIndexed { index, track ->
                        TrackRow(index = index + 1, track = track, onMenuClick = { onTrackMenu(track) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverGrid(tracks: List<GeneratedTrack>) {
    val artworkTracks = tracks.take(4)
    Box(Modifier.size(60.dp).clip(RoundedCornerShape(14.dp))) {
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (artworkTracks.size < 4) {
            val first = artworkTracks.first()
            ArtworkImage(name = first.name, artist = first.artist, embeddedUrl = first.artworkUrl, fallbackIcon = Icons.Filled.MusicNote, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) {
                    val t0 = artworkTracks[0]
                    ArtworkImage(name = t0.name, artist = t0.artist, embeddedUrl = t0.artworkUrl, fallbackIcon = Icons.Filled.MusicNote, modifier = Modifier.weight(1f).fillMaxHeight())
                    val t1 = artworkTracks[1]
                    ArtworkImage(name = t1.name, artist = t1.artist, embeddedUrl = t1.artworkUrl, fallbackIcon = Icons.Filled.MusicNote, modifier = Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.weight(1f)) {
                    val t2 = artworkTracks[2]
                    ArtworkImage(name = t2.name, artist = t2.artist, embeddedUrl = t2.artworkUrl, fallbackIcon = Icons.Filled.MusicNote, modifier = Modifier.weight(1f).fillMaxHeight())
                    val t3 = artworkTracks[3]
                    ArtworkImage(name = t3.name, artist = t3.artist, embeddedUrl = t3.artworkUrl, fallbackIcon = Icons.Filled.MusicNote, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun TrackRow(index: Int, track: GeneratedTrack, onMenuClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val q = java.net.URLEncoder.encode("${track.name} ${track.artist}", "UTF-8")
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=$q"))
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(intent) } catch (e: Exception) { }
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$index", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
        ArtworkImage(name = track.name, artist = track.artist, embeddedUrl = track.artworkUrl, fallbackIcon = Icons.Filled.MusicNote, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onMenuClick) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
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
