package com.lastwave.app.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Brush
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
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.common.PlaylistCover
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
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
 * screen. The Material 3 bar is about 86dp tall; 88dp clears it without
 * leaving a large empty band at the end of short lists.
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
        _customPlaylists.value = playlistRepository.getAll().filter { it.mode == "custom" && !it.isCompleted }
    }

    fun addToPlaylist(playlistId: Long, track: PlayableTrack, allowDuplicate: Boolean = false) {
        viewModelScope.launch {
            playlistRepository.addTrack(
                id = playlistId,
                track = track.toGeneratedTrack(),
                allowDuplicate = allowDuplicate,
            )
        }
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
                    edgeToEdge = !hasBottomNavigation,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            AnimatedVisibility(
                visible = expanded && state.current != null,
                enter = slideInVertically(
                    animationSpec = ExpressiveMotion.spatialSpring(),
                    initialOffsetY = { it / 4 },
                ) + fadeIn(tween(ExpressiveMotion.Standard)),
                exit = slideOutVertically(
                    animationSpec = tween(ExpressiveMotion.Standard),
                    targetOffsetY = { it / 5 },
                ) + fadeOut(tween(ExpressiveMotion.Quick)),
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
                onAdd = { playlistId, allowDuplicate ->
                    viewModel.addToPlaylist(playlistId, track, allowDuplicate)
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
private fun AnimatedPlayPauseIcon(isPlaying: Boolean, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = isPlaying,
        transitionSpec = {
            (fadeIn(tween(ExpressiveMotion.Quick)) +
                scaleIn(ExpressiveMotion.spatialSpring(), initialScale = 0.7f)) togetherWith
                (fadeOut(tween(ExpressiveMotion.Quick)) +
                    scaleOut(tween(ExpressiveMotion.Quick), targetScale = 0.7f))
        },
        label = "playPauseIcon",
        modifier = modifier,
    ) { playing ->
        Icon(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = "Play or pause",
            modifier = Modifier.fillMaxSize(),
        )
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
    edgeToEdge: Boolean,
    modifier: Modifier = Modifier,
) {
    val track = state.current ?: return
    var dragX by remember(track.videoId, track.title) { mutableFloatStateOf(0f) }
    var dragY by remember(track.videoId, track.title) { mutableFloatStateOf(0f) }
    val shownX by animateFloatAsState(dragX, ExpressiveMotion.spatialSpring(), label = "miniPlayerX")
    val shownY by animateFloatAsState(dragY, ExpressiveMotion.spatialSpring(), label = "miniPlayerY")
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val shape = if (edgeToEdge) {
        RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    } else {
        RoundedCornerShape(32.dp)
    }
    val positionedModifier = if (edgeToEdge) {
        modifier.fillMaxWidth()
    } else {
        modifier
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            )
            .padding(horizontal = 14.dp)
            .padding(bottom = bottomPadding)
            .fillMaxWidth()
    }
    Box(
        modifier = positionedModifier
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
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .padding(horizontal = if (edgeToEdge) 18.dp else 8.dp, vertical = 3.dp)
                .blur(26.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), shape),
        )
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (edgeToEdge) 0.975f else 0.94f),
            tonalElevation = if (edgeToEdge) 0.dp else 6.dp,
            shadowElevation = if (edgeToEdge) 0.dp else 12.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = if (edgeToEdge) {
                    Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    )
                } else {
                    Modifier
                },
            ) {
                val progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(56.dp)) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            PlayerArtwork(track, Modifier.fillMaxSize(), 18.dp)
                        }
                        if (state.isPlaying) {
                            PlayingWaveBars(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp),
                            )
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(
                            track.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            track.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Surface(
                        onClick = onToggle,
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (state.isBuffering) {
                                ExpressiveInlineLoadingIndicator(
                                    size = 24.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.5.dp,
                                )
                            } else {
                                AnimatedPlayPauseIcon(state.isPlaying, Modifier.size(27.dp))
                            }
                        }
                    }
                    IconButton(
                        onClick = onNext,
                        enabled = state.queue.size > 1,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(44.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Icon(
                            Icons.Filled.SkipNext,
                            "Next",
                            tint = if (state.queue.size > 1) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlayingWaveBars(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "miniArtworkWave")
    val first by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse),
        label = "miniWaveFirst",
    )
    val second by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(560), RepeatMode.Reverse),
        label = "miniWaveSecond",
    )
    val third by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(480), RepeatMode.Reverse),
        label = "miniWaveThird",
    )
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(width = 28.dp, height = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            listOf(first, second, third).forEach { level ->
                Box(
                    Modifier
                        .width(3.dp)
                        .height((5f + level * 11f).dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
private fun AddToPlaylistDialog(
    track: PlayableTrack,
    playlists: List<SavedPlaylist>,
    onDismiss: () -> Unit,
    onAdd: (Long, Boolean) -> Unit,
    onCreate: (String) -> Unit,
) {
    var newPlaylistName by remember(track) { mutableStateOf("") }
    var duplicatePlaylist by remember(track) { mutableStateOf<SavedPlaylist?>(null) }
    val trackKey = remember(track.title, track.artist) { track.toGeneratedTrack().key }

    duplicatePlaylist?.let { playlist ->
        AlertDialog(
            onDismissRequest = { duplicatePlaylist = null },
            title = { Text("Song already in playlist") },
            text = {
                Text(
                    "${track.title} by ${track.artist} is already present in ${playlist.title}. " +
                        "You can leave the playlist unchanged or add another copy.",
                )
            },
            confirmButton = {
                TextButton(onClick = { onAdd(playlist.id, true) }) {
                    Text("Add anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { duplicatePlaylist = null }) {
                    Text("Leave unchanged")
                }
            },
        )
        return
    }

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
                                onClick = {
                                    if (playlist.tracks.any { it.key == trackKey }) {
                                        duplicatePlaylist = playlist
                                    } else {
                                        onAdd(playlist.id, false)
                                    }
                                },
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PlaylistCover(
                                        playlist = playlist,
                                        modifier = Modifier.size(46.dp),
                                        cornerRadius = 12.dp,
                                    )
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
                onClick = {
                    val existingPlaylist = playlists.firstOrNull {
                        it.title.equals(newPlaylistName.trim(), ignoreCase = true)
                    }
                    if (existingPlaylist?.tracks?.any { it.key == trackKey } == true) {
                        duplicatePlaylist = existingPlaylist
                    } else {
                        onCreate(newPlaylistName)
                    }
                },
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
    val showQueue = false
    var showTrackMenu by remember(track.videoId, track.title) { mutableStateOf(false) }
    var artworkDragX by remember(track.videoId, track.title) { mutableFloatStateOf(0f) }
    var dismissDragY by remember(track.videoId, track.title) { mutableFloatStateOf(0f) }
    var isDismissDragging by remember { mutableStateOf(false) }
    val shownArtworkX by animateFloatAsState(
        artworkDragX,
        ExpressiveMotion.spatialSpring(),
        label = "fullPlayerArtworkX",
    )
    val shownDismissY by animateFloatAsState(
        targetValue = dismissDragY,
        animationSpec = if (isDismissDragging) snap() else ExpressiveMotion.spatialSpring(),
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
        Box(Modifier.fillMaxSize()) {
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
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 20.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)),
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .swipeToCollapse(enabled = showQueue),
                ) {
                    IconButton(
                        onClick = onCollapse,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(44.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Icon(Icons.Filled.ExpandMore, "Minimize player", modifier = Modifier.size(26.dp))
                    }
                    Column(
                        Modifier.align(Alignment.Center).padding(horizontal = 108.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (showQueue) "PLAYING QUEUE" else "NOW PLAYING",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            track.album?.takeIf { it.isNotBlank() } ?: "LastWave",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        Modifier.align(Alignment.CenterEnd),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        IconButton(
                            onClick = onAddToPlaylist,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        ) {
                            Icon(Icons.Filled.PlaylistAdd, "Add current song to playlist", modifier = Modifier.size(22.dp))
                        }
                        IconButton(
                            onClick = { showTrackMenu = true },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        ) {
                            Icon(
                                Icons.Filled.MoreVert,
                                "Song options",
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                AnimatedContent(
                    targetState = showQueue,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        (fadeIn(tween(ExpressiveMotion.Standard)) +
                            scaleIn(ExpressiveMotion.spatialSpring(), initialScale = 0.98f)) togetherWith
                            (fadeOut(tween(ExpressiveMotion.Quick)) +
                                scaleOut(tween(ExpressiveMotion.Standard), targetScale = 0.98f))
                    },
                    label = "playerQueue",
                ) { queueVisible ->
                    if (queueVisible) {
                        QueuePanel(state, player, Modifier.fillMaxSize())
                    } else {
                        Column(
                            Modifier.fillMaxSize().padding(bottom = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            val artworkSize = minOf(maxWidth, maxHeight).coerceAtMost(420.dp)
                            Surface(
                                shape = RoundedCornerShape(36.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                tonalElevation = 8.dp,
                                shadowElevation = 18.dp,
                                modifier = Modifier
                                    .size(artworkSize)
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
                        }
                        Spacer(Modifier.height(16.dp))
                        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                            Text(
                                track.title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                track.artist,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        SeekBar(state, player::seekTo)
                        Spacer(Modifier.height(8.dp))
                        MainControls(state, player)
                        Spacer(Modifier.height(12.dp))
                            PlayerUtilityControls(state, player)
                        }
                    }
                }
                state.error?.let { message ->
                    Surface(
                        onClick = player::clearError,
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.ErrorOutline, null, modifier = Modifier.size(21.dp))
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(Icons.Filled.Close, "Dismiss error", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
    if (showTrackMenu) {
        TrackContextMenuSheet(
            target = TrackMenuTarget.Track(track.title, track.artist, ""),
            capabilities = TrackMenuCapabilities(
                showCopyActions = true,
                showDeleteScrobble = false,
            ),
            playableTrack = track,
            onDismiss = { showTrackMenu = false },
            onPlayInLastWave = { player.play(track) },
        )
    }
}

@Composable
private fun SeekBar(state: MusicPlayerState, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val end = state.durationMs.coerceAtLeast(1).toFloat()
    val shown = if (dragging) dragValue else state.positionMs.coerceIn(0, state.durationMs.coerceAtLeast(0)).toFloat()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatTime(shown.toLong()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "−${formatTime((state.durationMs - shown.toLong()).coerceAtLeast(0))}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = shown.coerceIn(0f, end),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { onSeek(dragValue.toLong()); dragging = false },
            valueRange = 0f..end,
            enabled = state.durationMs > 0,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MainControls(state: MusicPlayerState, player: MusicPlayer) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = player::toggleShuffle,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    if (state.shuffleEnabled) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
        ) {
            Icon(
                Icons.Filled.Shuffle,
                "Shuffle",
                tint = if (state.shuffleEnabled) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            onClick = player::previous,
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(58.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(31.dp))
            }
        }
        Surface(
            onClick = player::togglePlayPause,
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.size(76.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (state.isBuffering) {
                    ExpressiveInlineLoadingIndicator(
                        size = 30.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 3.dp,
                    )
                } else {
                    AnimatedPlayPauseIcon(state.isPlaying, Modifier.size(39.dp))
                }
            }
        }
        Surface(
            onClick = player::next,
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(58.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.SkipNext, "Next", Modifier.size(31.dp))
            }
        }
        IconButton(
            onClick = player::cycleRepeatMode,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    if (state.repeatMode == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.surfaceContainerHigh
                    else MaterialTheme.colorScheme.primaryContainer,
                ),
        ) {
            Icon(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                "Repeat mode",
                tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun PlayerUtilityControls(state: MusicPlayerState, player: MusicPlayer) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = player::cycleSpeed,
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f).height(48.dp),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Speed, null, modifier = Modifier.size(18.dp))
                Text(
                    "${formatSpeed(state.speed)}×",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.weight(1.25f).height(48.dp),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.HighQuality, null, modifier = Modifier.size(18.dp))
                Text(
                    qualityLabel(state),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        Surface(
            onClick = player::cycleSleepTimer,
            shape = RoundedCornerShape(18.dp),
            color = if (state.sleepTimerRemainingMs != null) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (state.sleepTimerRemainingMs != null) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).height(48.dp),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Timer, "Sleep timer", modifier = Modifier.size(18.dp))
                Text(
                    sleepTimerLabel(state.sleepTimerRemainingMs),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun QueuePanel(state: MusicPlayerState, player: MusicPlayer, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Up next", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (state.isEndlessQueue) {
                        "Unlimited songs · ${state.currentIndex.coerceAtLeast(0) + 1} playing"
                    } else {
                        "${state.queue.size} songs · ${state.currentIndex.coerceAtLeast(0) + 1} playing"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = player::clearUpcoming,
                enabled = state.currentIndex >= 0 && state.currentIndex + 1 < state.queue.size,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Icon(Icons.Filled.ClearAll, "Clear upcoming songs")
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(state.queue, key = { index, item -> "$index:${item.videoId ?: item.artist + item.title}" }) { index, item ->
                val isCurrent = index == state.currentIndex
                Surface(
                    onClick = { player.seekToQueueItem(index) },
                    shape = RoundedCornerShape(20.dp),
                    color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.86f),
                    contentColor = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.animateItem(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlayerArtwork(item, Modifier.size(50.dp), 13.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                item.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                            )
                            Text(
                                item.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (isCurrent) {
                            Icon(
                                Icons.Filled.GraphicEq,
                                "Currently playing",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        IconButton(
                            onClick = { player.removeQueueItem(index) },
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(40.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f)
                                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                        ) {
                            Icon(Icons.Filled.DeleteOutline, "Remove from queue", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
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
