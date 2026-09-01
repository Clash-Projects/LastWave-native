package com.lastwave.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.download.DownloadProgress
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.GroupPosition
import com.lastwave.app.ui.common.groupPositionFor
import com.lastwave.app.ui.common.groupShape
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.ui.player.PlayingWaveBars
import com.lastwave.app.ui.shell.FloatingNavDefaults
import com.lastwave.app.ui.theme.ArtworkShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatDate(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes.toDouble() / (1024 * 1024)
    return if (mb >= 1000) "%.1f GB".format(mb / 1024) else "%.1f MB".format(mb)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val tracks by viewModel.downloadedTracks.collectAsStateWithLifecycle()
    val totalBytes by viewModel.totalBytes.collectAsStateWithLifecycle()
    val activeDownloadsMap by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val downloadLyrics by viewModel.downloadLyrics.collectAsStateWithLifecycle()
    val activeDownloads = remember(activeDownloadsMap) {
        activeDownloadsMap.values.filter { !it.isFinished && it.error == null }
    }

    val haptic = LocalHapticFeedback.current
    val musicPlayer = LocalMusicPlayer.current
    val playbackState by musicPlayer.chromeState.collectAsStateWithLifecycle()

    var showClearAllConfirm by remember { mutableStateOf(false) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    var trackToDelete by remember { mutableStateOf<DownloadedTrackEntity?>(null) }
    var showOptionsMenu by remember { mutableStateOf(false) }

    val totalSizeText = remember(totalBytes) { formatBytes(totalBytes ?: 0L) }
    val subtitleText = remember(tracks.size, totalSizeText) {
        "${tracks.size} song(s) \u2022 $totalSizeText \u2022 Music/LastWave"
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            ExpressiveHeader(
                title = "Downloads",
                subtitle = subtitleText,
                onBack = onBack,
                actions = {
                    Box {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showOptionsMenu = true
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Options")
                        }
                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    ) {
                                        Text("Download Lyrics")
                                        androidx.compose.material3.Switch(
                                            checked = downloadLyrics,
                                            onCheckedChange = { viewModel.setDownloadLyrics(it) },
                                        )
                                    }
                                },
                                leadingIcon = { Icon(Icons.Filled.FormatQuote, contentDescription = null) },
                                onClick = { viewModel.setDownloadLyrics(!downloadLyrics) },
                            )
                            DropdownMenuItem(
                                text = { Text("Open in File Manager") },
                                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                                onClick = {
                                    showOptionsMenu = false
                                    viewModel.openInFileManager()
                                },
                            )
                            if (tracks.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Clear Download History Only") },
                                    leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                                    onClick = {
                                        showOptionsMenu = false
                                        showClearHistoryConfirm = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete All Files & Free Space", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showOptionsMenu = false
                                        showClearAllConfirm = true
                                    },
                                )
                            }
                        }
                    }
                },
            )

            if (tracks.isEmpty() && activeDownloads.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(88.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.Download,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(44.dp),
                                )
                            }
                        }
                        Text(
                            "No Downloaded Songs",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Tap the 3-dot menu on any song and select \"Download (Max Quality)\". Qobuz FLAC tracks and YouTube audio are saved directly to your device's Music/LastWave folder with embedded metadata & synchronized LRCLIB lyrics.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 14.dp,
                        end = 14.dp,
                        top = 10.dp,
                        bottom = 32.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // Storage summary card
                    item {
                        Card(
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "Storage: $totalSizeText",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "Saved in Music/LastWave/ \u2022 ${tracks.size} songs",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                FilledTonalButton(
                                    onClick = { viewModel.openInFileManager() },
                                    shape = CircleShape,
                                ) {
                                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Files", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }

                    // Active ongoing downloads
                    if (activeDownloads.isNotEmpty()) {
                        item {
                            Text(
                                "Downloading Now",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                            )
                        }

                        items(activeDownloads, key = { it.key }) { download ->
                            ActiveDownloadCard(
                                download = download,
                                onCancel = { viewModel.cancelDownload(download.key) },
                            )
                        }
                    }

                    // Completed songs header
                    if (tracks.isNotEmpty()) {
                        item {
                            Text(
                                "Downloaded Songs (${tracks.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                            )
                        }

                        itemsIndexed(
                            items = tracks,
                            key = { _, item -> item.id },
                        ) { index, track ->
                            val isPlayingThis = playbackState.isPlaying &&
                                playbackState.current?.title.equals(track.title, ignoreCase = true) &&
                                playbackState.current?.artist.equals(track.artist, ignoreCase = true)

                            Box(Modifier.animateItem()) {
                                DownloadedTrackCard(
                                    track = track,
                                    isPlaying = isPlayingThis,
                                    position = groupPositionFor(index, tracks.size),
                                    onPlay = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.playTrack(track)
                                    },
                                    onDelete = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        trackToDelete = track
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Single item delete confirm
    trackToDelete?.let { track ->
        var deleteFileAndHistory by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { trackToDelete = null },
            title = { Text("Delete \"${track.title}\"?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose how you want to remove this download:")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (deleteFileAndHistory) "Delete audio file from Music/LastWave and remove from history" else "Remove from history only (keep file on device)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTrack(track)
                        trackToDelete = null
                    },
                ) {
                    Text("Delete File", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            viewModel.deleteHistoryRecordOnly(track)
                            trackToDelete = null
                        },
                    ) {
                        Text("History Only")
                    }
                    TextButton(onClick = { trackToDelete = null }) { Text("Cancel") }
                }
            },
        )
    }

    // Clear history only confirm
    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { Text("Clear Download History?") },
            text = { Text("This will clear the history list in the app. Your audio files in Music/LastWave will stay safely on your device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistoryOnly()
                        showClearHistoryConfirm = false
                    },
                ) {
                    Text("Clear History")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) { Text("Cancel") }
            },
        )
    }

    // Clear all files confirm
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("Delete All Downloaded Files?") },
            text = { Text("This will permanently delete all ${tracks.size} downloaded audio files from Music/LastWave and free up $totalSizeText of storage.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAll()
                        showClearAllConfirm = false
                    },
                ) {
                    Text("Delete All Files", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ActiveDownloadCard(
    download: DownloadProgress,
    onCancel: () -> Unit,
) {
    val progressFloat by animateFloatAsState(
        targetValue = download.progressPercent / 100f,
        label = "downloadProgress",
    )

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = download.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${download.artist} \u2022 ${download.formatBadge} \u2022 ${download.progressPercent}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel download", tint = MaterialTheme.colorScheme.error)
                }
            }

            LinearProgressIndicator(
                progress = { progressFloat },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DownloadedTrackCard(
    track: DownloadedTrackEntity,
    isPlaying: Boolean,
    position: GroupPosition,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    val metadataText = remember(track.artist, track.fileSizeBytes, track.downloadedAtMillis) {
        "${track.artist} \u2022 ${formatBytes(track.fileSizeBytes)} \u2022 ${formatDate(track.downloadedAtMillis)}"
    }
    Card(
        shape = groupShape(position),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onPlay, onLongClick = onDelete)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(52.dp)) {
                ArtworkImage(
                    name = track.title,
                    artist = track.artist,
                    embeddedUrl = track.artworkUrl,
                    fallbackIcon = Icons.Filled.MusicNote,
                    modifier = Modifier.fillMaxSize().clip(ArtworkShape),
                )
                if (isPlaying) {
                    PlayingWaveBars(
                        Modifier.align(Alignment.BottomEnd).padding(3.dp),
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (track.isQobuz) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = track.formatBadge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (track.isQobuz) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    if (track.hasLyrics) {
                        Spacer(Modifier.width(4.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Text(
                                text = "LRC",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))

                Text(
                    text = metadataText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Play button
            Surface(
                onClick = onPlay,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play offline track",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.width(4.dp))

            // Delete action
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete download",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
