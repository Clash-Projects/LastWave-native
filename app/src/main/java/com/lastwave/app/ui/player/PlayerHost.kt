package com.lastwave.app.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.SavedPlaylist
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil

val LocalMusicPlayer = staticCompositionLocalOf<MusicPlayer> {
    error("MusicPlayer is only available inside PlayerHost")
}

val LocalAddToPlaylist = staticCompositionLocalOf<(PlayableTrack) -> Unit> {
    error("Add-to-playlist is only available inside PlayerHost")
}

/**
 * Extra list-end clearance needed while the collapsed player overlays a
 * screen. Its measured card is about 75dp tall; 88dp also preserves a small
 * visual gap so a final row can scroll completely above the card.
 */
val LocalMiniPlayerScrollClearance = staticCompositionLocalOf { 0.dp }

@HiltViewModel
class PlayerViewModel @Inject constructor(
    val player: MusicPlayer,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {
    val state = player.state
    private val _customPlaylists = MutableStateFlow<List<SavedPlaylist>>(emptyList())
    val customPlaylists = _customPlaylists.asStateFlow()

    init {
        viewModelScope.launch {
            refreshCustomPlaylists()
            playlistRepository.changes.collect { refreshCustomPlaylists() }
        }
    }

    private suspend fun refreshCustomPlaylists() {
        _customPlaylists.value = playlistRepository.getAll().filter { it.mode == "custom" }
    }

    fun addToPlaylist(playlistId: Long, track: PlayableTrack) {
        viewModelScope.launch { playlistRepository.addTrack(playlistId, track.toGeneratedTrack()) }
    }

    fun createPlaylistAndAdd(title: String, track: PlayableTrack) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val playlist = playlistRepository.createCustom(title)
            playlistRepository.addTrack(playlist.id, track.toGeneratedTrack())
        }
    }
}

/** App-wide collapsed + maximized player layered over every navigation route. */
@Composable
fun PlayerHost(
    viewModel: PlayerViewModel = hiltViewModel(),
    hasBottomNavigation: Boolean = false,
    content: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val customPlaylists by viewModel.customPlaylists.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var playlistTrack by remember { mutableStateOf<PlayableTrack?>(null) }
    val requestAddToPlaylist = remember { { track: PlayableTrack -> playlistTrack = track } }
    val trackKey = state.current?.let { it.videoId ?: "${it.artist}|${it.title}" }
    LaunchedEffect(trackKey) {
        if (trackKey == null) expanded = false
    }
    BackHandler(enabled = expanded) { expanded = false }

    CompositionLocalProvider(
        LocalMusicPlayer provides viewModel.player,
        LocalAddToPlaylist provides requestAddToPlaylist,
        LocalMiniPlayerScrollClearance provides if (state.current != null) 88.dp else 0.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            content()
            if (state.current != null && !expanded) {
                MiniPlayer(
                    state = state,
                    onExpand = { expanded = true },
                    onToggle = viewModel.player::togglePlayPause,
                    onPrevious = viewModel.player::previous,
                    onNext = viewModel.player::next,
                    onClose = viewModel.player::stopAndClear,
                    bottomPadding = if (hasBottomNavigation) 92.dp else 12.dp,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            AnimatedVisibility(
                visible = expanded && state.current != null,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                FullPlayer(
                    state = state,
                    player = viewModel.player,
                    onCollapse = { expanded = false },
                    onAddToPlaylist = { state.current?.let(requestAddToPlaylist) },
                )
            }
        }
        playlistTrack?.let { track ->
            AddToPlaylistDialog(
                track = track,
                playlists = customPlaylists,
                onDismiss = { playlistTrack = null },
                onAdd = { playlistId ->
                    viewModel.addToPlaylist(playlistId, track)
                    playlistTrack = null
                },
                onCreate = { title ->
                    viewModel.createPlaylistAndAdd(title, track)
                    playlistTrack = null
                },
            )
        }
    }
}

@Composable
private fun MiniPlayer(
    state: MusicPlayerState,
    onExpand: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val track = state.current ?: return
    var dragX by remember(track.videoId, track.title) { mutableFloatStateOf(0f) }
    var dragY by remember(track.videoId, track.title) { mutableFloatStateOf(0f) }
    val shownX by animateFloatAsState(dragX, spring(), label = "miniPlayerX")
    val shownY by animateFloatAsState(dragY, spring(), label = "miniPlayerY")
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 10.dp,
        shadowElevation = 16.dp,
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 14.dp)
            .padding(bottom = bottomPadding)
            .fillMaxWidth()
            .graphicsLayer {
                translationX = shownX
                translationY = shownY.coerceAtLeast(0f)
                alpha = (1f - (abs(shownX) + shownY.coerceAtLeast(0f)) / (threshold * 4f)).coerceIn(0.55f, 1f)
            }
            .pointerInput(track.videoId, track.title) {
                detectDragGestures(
                    onDragCancel = { dragX = 0f; dragY = 0f },
                    onDragEnd = {
                        when {
                            dragY > threshold -> onClose()
                            dragY < -threshold -> onExpand()
                            dragX < -threshold -> onNext()
                            dragX > threshold -> onPrevious()
                        }
                        dragX = 0f
                        dragY = 0f
                    },
                ) { change, amount ->
                    change.consume()
                    if (abs(dragX + amount.x) > abs(dragY + amount.y)) dragX += amount.x
                    else dragY += amount.y
                }
            }
            .clickable(onClick = onExpand),
    ) {
        Column {
            val progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
            Box(Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(MaterialTheme.colorScheme.primary))
            }
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerArtwork(track, Modifier.size(52.dp), 14.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (state.isBuffering) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                } else {
                    Surface(
                        onClick = onToggle,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", modifier = Modifier.size(25.dp))
                        }
                    }
                }
                IconButton(onClick = onNext, enabled = state.queue.size > 1) { Icon(Icons.Filled.SkipNext, "Next") }
            }
        }
    }
}

@Composable
private fun AddToPlaylistDialog(
    track: PlayableTrack,
    playlists: List<SavedPlaylist>,
    onDismiss: () -> Unit,
    onAdd: (Long) -> Unit,
    onCreate: (String) -> Unit,
) {
    var newPlaylistName by remember(track) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${track.title} — ${track.artist}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (playlists.isEmpty()) {
                    Text("Create your first custom playlist below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(playlists, key = { _, playlist -> playlist.id }) { _, playlist ->
                            Surface(
                                onClick = { onAdd(playlist.id) },
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.PlaylistAdd, contentDescription = null)
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${playlist.tracks.size} tracks",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("New playlist name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(newPlaylistName) },
                enabled = newPlaylistName.isNotBlank(),
            ) {
                Text("Create and add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FullPlayer(
    state: MusicPlayerState,
    player: MusicPlayer,
    onCollapse: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    val track = state.current ?: return
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var artworkDragX by remember(track.videoId, track.title) { mutableFloatStateOf(0f) }
    var dismissDragY by remember(track.videoId, track.title) { mutableFloatStateOf(0f) }
    var isDismissDragging by remember { mutableStateOf(false) }
    val shownArtworkX by animateFloatAsState(artworkDragX, spring(), label = "fullPlayerArtworkX")
    val shownDismissY by animateFloatAsState(
        targetValue = dismissDragY,
        animationSpec = if (isDismissDragging) snap() else spring(),
        label = "fullPlayerDismissY",
    )
    val swipeThreshold = with(LocalDensity.current) { 88.dp.toPx() }

    fun Modifier.swipeToCollapse(enabled: Boolean): Modifier = if (!enabled) this else pointerInput(track.videoId, track.title) {
        detectVerticalDragGestures(
            onDragStart = { isDismissDragging = true },
            onDragCancel = {
                isDismissDragging = false
                dismissDragY = 0f
            },
            onDragEnd = {
                isDismissDragging = false
                if (dismissDragY > swipeThreshold) onCollapse() else dismissDragY = 0f
            },
        ) { change, amount ->
            val updatedDrag = (dismissDragY + amount).coerceAtLeast(0f)
            if (updatedDrag != dismissDragY) change.consume()
            dismissDragY = updatedDrag
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = shownDismissY
                val playerHeight = size.height.coerceAtLeast(1f)
                alpha = (1f - shownDismissY / (playerHeight * 1.5f)).coerceIn(0.72f, 1f)
            }
            .swipeToCollapse(enabled = !showQueue),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            PlayerArtwork(
                track = track,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.24f; scaleX = 1.25f; scaleY = 1.25f }
                    .blur(72.dp),
                corner = 0.dp,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.42f),
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                                MaterialTheme.colorScheme.surface,
                            ),
                        ),
                    ),
            )
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 42.dp, height = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)))
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .swipeToCollapse(enabled = showQueue),
                ) {
                    IconButton(onClick = onCollapse, modifier = Modifier.align(Alignment.CenterStart)) {
                        Icon(Icons.Filled.ExpandMore, "Minimize player")
                    }
                    Column(
                        Modifier.align(Alignment.Center).padding(horizontal = 104.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(if (showQueue) "Playing queue" else "Now playing", style = MaterialTheme.typography.labelLarge)
                        track.album?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                    }
                    Row(Modifier.align(Alignment.CenterEnd)) {
                        IconButton(onClick = onAddToPlaylist) {
                            Icon(Icons.Filled.PlaylistAdd, "Add current song to playlist")
                        }
                        IconButton(onClick = { showQueue = !showQueue }) {
                            Icon(if (showQueue) Icons.Filled.Close else Icons.Filled.QueueMusic, if (showQueue) "Close queue" else "Open queue")
                        }
                    }
                }

                if (showQueue) {
                    QueuePanel(state, player, Modifier.weight(1f))
                } else {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        Surface(
                            shape = RoundedCornerShape(36.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            tonalElevation = 8.dp,
                            shadowElevation = 20.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .graphicsLayer {
                                    translationX = shownArtworkX
                                    rotationZ = shownArtworkX / 80f
                                }
                                .pointerInput(track.videoId, track.title) {
                                    detectHorizontalDragGestures(
                                        onDragCancel = { artworkDragX = 0f },
                                        onDragEnd = {
                                            when {
                                                artworkDragX < -swipeThreshold -> player.next()
                                                artworkDragX > swipeThreshold -> player.previous()
                                            }
                                            artworkDragX = 0f
                                        },
                                    ) { change, amount ->
                                        change.consume()
                                        artworkDragX += amount
                                    }
                                },
                        ) {
                            PlayerArtwork(track, Modifier.fillMaxSize(), 36.dp)
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(
                            track.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Text(
                            track.artist,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Spacer(Modifier.height(18.dp))
                        SeekBar(state, player::seekTo)
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(32.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                            tonalElevation = 5.dp,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Box(Modifier.padding(vertical = 10.dp)) { MainControls(state, player) }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                onClick = player::cycleSpeed,
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.weight(1f).height(44.dp),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("${formatSpeed(state.speed)}×", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.weight(1f).height(44.dp),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        qualityLabel(state),
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Surface(
                                onClick = player::cycleSleepTimer,
                                shape = CircleShape,
                                color = if (state.sleepTimerRemainingMs != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.weight(1f).height(44.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.Timer, "Sleep timer", modifier = Modifier.size(17.dp))
                                    Text(sleepTimerLabel(state.sleepTimerRemainingMs), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
                                }
                            }
                        }
                    }
                }
                state.error?.let { message ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable(onClick = player::clearError),
                    ) {
                        Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp), maxLines = 3)
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekBar(state: MusicPlayerState, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val end = state.durationMs.coerceAtLeast(1).toFloat()
    val shown = if (dragging) dragValue else state.positionMs.coerceIn(0, state.durationMs.coerceAtLeast(0)).toFloat()
    Slider(
        value = shown.coerceIn(0f, end),
        onValueChange = { dragging = true; dragValue = it },
        onValueChangeFinished = { onSeek(dragValue.toLong()); dragging = false },
        valueRange = 0f..end,
        enabled = state.durationMs > 0,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(shown.toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MainControls(state: MusicPlayerState, player: MusicPlayer) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = player::toggleShuffle) {
            Icon(Icons.Filled.Shuffle, "Shuffle", tint = if (state.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = player::previous, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(34.dp)) }
        Surface(
            onClick = player::togglePlayPause,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (state.isBuffering) CircularProgressIndicator(Modifier.size(30.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp)
                else Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(38.dp))
            }
        }
        IconButton(onClick = player::next, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.SkipNext, "Next", Modifier.size(34.dp)) }
        IconButton(onClick = player::cycleRepeatMode) {
            Icon(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                "Repeat mode",
                tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun QueuePanel(state: MusicPlayerState, player: MusicPlayer, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${state.queue.size} songs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            IconButton(
                onClick = player::clearUpcoming,
                enabled = state.currentIndex >= 0 && state.currentIndex + 1 < state.queue.size,
            ) {
                Icon(Icons.Filled.ClearAll, "Clear upcoming songs")
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(state.queue, key = { index, item -> "$index:${item.videoId ?: item.artist + item.title}" }) { index, item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (index == state.currentIndex) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { player.seekToQueueItem(index) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerArtwork(item, Modifier.size(48.dp), 10.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (index == state.currentIndex) FontWeight.Bold else FontWeight.Normal)
                        Text(item.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { player.removeQueueItem(index) }) { Icon(Icons.Filled.DeleteOutline, "Remove from queue") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
            }
        }
    }
}

@Composable
private fun PlayerArtwork(track: PlayableTrack, modifier: Modifier, corner: androidx.compose.ui.unit.Dp) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        ArtworkImage(
            name = track.title,
            artist = track.artist,
            embeddedUrl = track.artworkUrl,
            fallbackIcon = Icons.Filled.MusicNote,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(total / 60, total % 60)
}

private fun formatSpeed(speed: Float): String = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')

private fun qualityLabel(state: MusicPlayerState): String = when {
    state.bitrateKbps != null && state.audioCodec != null -> "${state.audioCodec} ${state.bitrateKbps}k"
    state.bitrateKbps != null -> "${state.bitrateKbps} kbps"
    else -> "Best quality"
}

private fun sleepTimerLabel(remainingMs: Long?): String = remainingMs?.let {
    "${ceil(it / 60_000.0).toInt()}m"
} ?: "Timer"

private fun PlayableTrack.toGeneratedTrack() = GeneratedTrack(
    name = title,
    artist = artist,
    artworkUrl = artworkUrl,
    album = album,
)
