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
import androidx.compose.material.icons.filled.Share
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

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

enum class PlaylistTrackSort(val label: String) {
    CUSTOM("Custom order"),
    DATE_ADDED("Date added"),
    NAME("Name"),
    ARTIST("Artist"),
    PLAY_TIME("Play time"),
}

@HiltViewModel
class PlaylistDownloadHelperViewModel @Inject constructor(
    private val downloadManager: com.lastwave.app.data.download.TrackDownloadManager,
) : ViewModel() {
    fun downloadAll(tracks: List<GeneratedTrack>) {
        tracks.forEach { t ->
            downloadManager.downloadTrack(t.name, t.artist, t.album, t.artworkUrl)
        }
    }
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    onBack: () -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
    downloadViewModel: PlaylistDownloadHelperViewModel = hiltViewModel(),
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
    var sortMenuOpen by remember { mutableStateOf(false) }
    var currentSort by remember { mutableStateOf(PlaylistTrackSort.CUSTOM) }
    var sortAscending by remember { mutableStateOf(true) }
    var isReorderLocked by remember { mutableStateOf(true) }

    val displayTracks = remember(playlist.tracks, currentSort, sortAscending) {
        when (currentSort) {
            PlaylistTrackSort.CUSTOM -> if (sortAscending) playlist.tracks else playlist.tracks.reversed()
            PlaylistTrackSort.DATE_ADDED -> if (sortAscending) playlist.tracks else playlist.tracks.reversed()
            PlaylistTrackSort.NAME -> if (sortAscending) {
                playlist.tracks.sortedBy { it.name.lowercase() }
            } else {
                playlist.tracks.sortedByDescending { it.name.lowercase() }
            }
            PlaylistTrackSort.ARTIST -> if (sortAscending) {
                playlist.tracks.sortedBy { it.artist.lowercase() }
            } else {
                playlist.tracks.sortedByDescending { it.artist.lowercase() }
            }
            PlaylistTrackSort.PLAY_TIME -> if (sortAscending) {
                playlist.tracks.sortedBy { it.playcount ?: it.listeners ?: 0L }
            } else {
                playlist.tracks.sortedByDescending { it.playcount ?: it.listeners ?: 0L }
            }
        }
    }

    val listState = rememberLazyListState()
    val showScrolledHeader by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 240
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 1. Full-Bleed Cover Art Background at Top (Spotify-styled)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(440.dp),
        ) {
            PlaylistCover(
                playlist = playlist,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 0.dp,
            )
            // Multi-stop cinematic dark gradient overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Black.copy(alpha = 0.55f),
                                0.30f to Color.Black.copy(alpha = 0.20f),
                                0.60f to Color.Black.copy(alpha = 0.55f),
                                0.85f to MaterialTheme.colorScheme.background.copy(alpha = 0.90f),
                                1.0f to MaterialTheme.colorScheme.background,
                            ),
                        ),
                    ),
            )
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 0.dp,
                bottom = FloatingNavDefaults.contentBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Hero Header Section
            item(key = "hero_section") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 150.dp)
                        .padding(horizontal = 4.dp),
                ) {
                    // Big Bold Playlist Title (overlaid in hero)
                    Text(
                        text = playlist.title,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(Modifier.height(8.dp))

                    // Metadata line (tracks count & date)
                    Text(
                        text = "${playlist.tracks.size} songs \u2022 ${formatDate(playlist.createdAtMillis)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(18.dp))

                    // Hero Action Buttons (Shuffle • Big Play Pill)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Circular Shuffle Button
                        FilledTonalIconButton(
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
                            modifier = Modifier.size(50.dp),
                        ) {
                            Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle", modifier = Modifier.size(22.dp))
                        }

                        // Prominent Center Play / Playing Pill Button
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (playlist.tracks.isNotEmpty()) {
                                    musicPlayer.playQueue(
                                        displayTracks.map { track ->
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
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 8.dp),
                            modifier = Modifier.height(50.dp).padding(horizontal = 4.dp),
                        ) {
                            Icon(
                                if (isThisPlaylistPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(26.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isThisPlaylistPlaying) "Playing" else "Play",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    // Sort Filter Pill & Lock Icon Row (Custom order, Date added, Name, Artist, Play time)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    sortMenuOpen = true
                                },
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                tonalElevation = 2.dp,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = currentSort.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    if (currentSort != PlaylistTrackSort.CUSTOM) {
                                        Icon(
                                            imageVector = if (sortAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                        )
                                    }
                                }
                            }

                            DropdownMenu(
                                expanded = sortMenuOpen,
                                onDismissRequest = { sortMenuOpen = false },
                                shape = RoundedCornerShape(20.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 6.dp,
                                shadowElevation = 10.dp,
                                modifier = Modifier.widthIn(min = 210.dp),
                            ) {
                                PlaylistTrackSort.entries.forEach { option ->
                                    val isSelected = currentSort == option
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    text = option.label,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                )
                                                Spacer(Modifier.width(16.dp))
                                                Icon(
                                                    imageVector = when {
                                                        isSelected && !sortAscending -> Icons.Filled.ArrowDownward
                                                        isSelected && sortAscending -> Icons.Filled.ArrowUpward
                                                        else -> Icons.Filled.SwapVert
                                                    },
                                                    contentDescription = null,
                                                    tint = if (isSelected) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                    },
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        },
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            if (currentSort == option) {
                                                sortAscending = !sortAscending
                                            } else {
                                                currentSort = option
                                                sortAscending = option != PlaylistTrackSort.PLAY_TIME
                                            }
                                            sortMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "${playlist.tracks.size} tracks",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium,
                            )
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    isReorderLocked = !isReorderLocked
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = if (isReorderLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                    contentDescription = if (isReorderLocked) "Reorder locked" else "Reorder unlocked",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                }
            }

            // Track items
            if (displayTracks.isEmpty()) {
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
                    items = displayTracks,
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
                                    displayTracks.map { t ->
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

        // 2. Floating Top Bar with Frosted Glass styling & Smooth Scrolled Header
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
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Frosted glass circular back button
                Surface(
                    onClick = onBack,
                    shape = CircleShape,
                    color = if (showScrolledHeader) Color.Transparent else Color.Black.copy(alpha = 0.38f),
                    modifier = Modifier.size(42.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = if (showScrolledHeader) MaterialTheme.colorScheme.onSurface else Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
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
                                displayTracks.map { track ->
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
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            if (isThisPlaylistPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "Play",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Spacer(Modifier.width(6.dp))

                // Translucent Actions Pill (Search / More Menu)
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (showScrolledHeader) Color.Transparent else Color.Black.copy(alpha = 0.38f),
                ) {
                    Box {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                overflowMenuOpen = true
                            },
                            modifier = Modifier.size(42.dp),
                        ) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "Playlist options",
                                tint = if (showScrolledHeader) MaterialTheme.colorScheme.onSurface else Color.White,
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
                            text = { Text("Download all songs") },
                            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                downloadViewModel.downloadAll(playlist.tracks)
                                overflowMenuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Export / Share") },
                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
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
            onRemoveFromPlaylist = {
                val realIndex = playlist.tracks.indexOfFirst { it.name == track.name && it.artist == track.artist }
                if (realIndex >= 0) {
                    viewModel.removeTrack(playlistId, realIndex)
                }
            },
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

            // High-res track artwork (clean, no wave overlay)
            ArtworkImage(
                name = track.name,
                artist = track.artist,
                embeddedUrl = track.artworkUrl,
                fallbackIcon = if (isPlaying) Icons.Filled.GraphicEq else Icons.Filled.MusicNote,
                modifier = Modifier
                    .size(48.dp)
                    .clip(ArtworkShape),
            )

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

            // Now Playing badge if active
            if (isPlaying) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onPrimaryContainer),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Now Playing",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
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
