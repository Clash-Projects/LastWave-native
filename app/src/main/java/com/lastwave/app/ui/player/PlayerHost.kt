@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.lastwave.app.ui.player

import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import androidx.media3.common.Player
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.lyrics.LyricsRepository
import com.lastwave.app.data.lyrics.LyricsResult
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlaybackChromeState
import com.lastwave.app.playback.PlaybackProgressState
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.common.PlaylistCover
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.liquidGlassChrome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs

enum class FullPlayerTab {
    NOW_PLAYING,
    LYRICS,
    QUEUE
}

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
    private val lyricsRepository: LyricsRepository,
    private val settingsPreferences: com.lastwave.app.data.local.SettingsPreferences,
    val navigator: com.lastwave.app.ui.navigation.ArtistAlbumNavigator,
    val genreExplorer: com.lastwave.app.ui.genres.GenreExplorer,
    val mixLauncher: com.lastwave.app.ui.generate.MixLauncher,
) : ViewModel() {
    val navEvents = navigator.events
    val state = player.state
    val chromeState = player.chromeState
    val progressState = player.progressState
    val fullPlayerState = player.state
        .map { it.copy(positionMs = 0L, bufferedPositionMs = 0L) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, player.state.value.copy(positionMs = 0L, bufferedPositionMs = 0L))
    val settings: StateFlow<com.lastwave.app.data.local.MiscSettings> = settingsPreferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.lastwave.app.data.local.MiscSettings())
    private val _customPlaylists = MutableStateFlow<List<SavedPlaylist>>(emptyList())
    val customPlaylists = _customPlaylists.asStateFlow()
    private var customPlaylistsLoaded = false

    fun openArtist(name: String, browseId: String? = null) {
        navigator.openArtist(name, browseId)
    }

    fun openAlbum(title: String, artist: String = "", browseId: String? = null) {
        navigator.openAlbum(title, artist, browseId)
    }

    private val _lyricsState = MutableStateFlow<LyricsUiState>(LyricsUiState.Idle)
    val lyricsState = _lyricsState.asStateFlow()

    private var currentTrackLyricsKey: String? = null

    init {
        viewModelScope.launch {
            playlistRepository.changes.collect {
                if (customPlaylistsLoaded) refreshCustomPlaylists()
            }
        }
        viewModelScope.launch {
            player.chromeState.collect { playerState ->
                val track = playerState.current
                val key = track?.let { "${it.artist}|${it.title}" }
                if (key != currentTrackLyricsKey) {
                    currentTrackLyricsKey = key
                    if (track != null) {
                        loadLyrics(track, forceRefresh = false)
                    } else {
                        _lyricsState.value = LyricsUiState.Idle
                    }
                }
            }
        }
    }

    private suspend fun refreshCustomPlaylists() {
        _customPlaylists.value = playlistRepository.getAll().filter { it.mode == "custom" }
    }

    fun prepareCustomPlaylists() {
        if (customPlaylistsLoaded) return
        customPlaylistsLoaded = true
        viewModelScope.launch { refreshCustomPlaylists() }
    }

    fun loadLyrics(track: PlayableTrack, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _lyricsState.value = LyricsUiState.Loading
            val durationSeconds = if (player.state.value.durationMs > 0) {
                (player.state.value.durationMs / 1000).toInt()
            } else null

            when (val result = lyricsRepository.getLyrics(track.title, track.artist, track.album, durationSeconds, forceRefresh)) {
                is LyricsResult.Success -> {
                    _lyricsState.value = LyricsUiState.Success(
                        lines = result.lines,
                        isSynced = result.isSynced,
                        isWordSynced = result.isWordSynced,
                        plainLyrics = result.plainLyrics,
                        isInstrumental = result.isInstrumental,
                        source = result.source,
                    )
                }
                is LyricsResult.Empty -> {
                    _lyricsState.value = LyricsUiState.Empty
                }
                is LyricsResult.Error -> {
                    _lyricsState.value = LyricsUiState.Error(result.message)
                }
            }
        }
    }

    fun retryLyrics() {
        val track = player.state.value.current ?: return
        loadLyrics(track, forceRefresh = true)
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
    val state by viewModel.chromeState.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var currentTab by rememberSaveable { mutableStateOf(FullPlayerTab.NOW_PLAYING) }
    var playlistTrack by remember { mutableStateOf<PlayableTrack?>(null) }
    val requestAddToPlaylist = remember(viewModel) {
        { track: PlayableTrack ->
            viewModel.prepareCustomPlaylists()
            playlistTrack = track
        }
    }
    val trackKey = state.current?.let { it.videoId ?: "${it.artist}|${it.title}" }
    LaunchedEffect(trackKey) {
        if (trackKey == null) {
            expanded = false
            currentTab = FullPlayerTab.NOW_PLAYING
        }
    }
    LaunchedEffect(expanded) {
        if (!expanded) {
            currentTab = FullPlayerTab.NOW_PLAYING
        }
    }
    LaunchedEffect(Unit) {
        viewModel.navEvents.collect {
            expanded = false
            currentTab = FullPlayerTab.NOW_PLAYING
        }
    }
    LaunchedEffect(Unit) {
        viewModel.mixLauncher.requests.collect {
            expanded = false
            currentTab = FullPlayerTab.NOW_PLAYING
        }
    }
    LaunchedEffect(Unit) {
        viewModel.genreExplorer.pendingGenre.collect { genre ->
            if (genre != null) {
                expanded = false
                currentTab = FullPlayerTab.NOW_PLAYING
            }
        }
    }
    BackHandler(enabled = expanded) {
        if (currentTab != FullPlayerTab.NOW_PLAYING) {
            currentTab = FullPlayerTab.NOW_PLAYING
        } else {
            expanded = false
        }
    }

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
                    progressState = viewModel.progressState,
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
                ExpandedPlayer(
                    viewModel = viewModel,
                    currentTab = currentTab,
                    onTabChange = { currentTab = it },
                    onRetryLyrics = viewModel::retryLyrics,
                    onCollapse = { expanded = false },
                    onOpenArtist = { artist ->
                        expanded = false
                        viewModel.openArtist(artist)
                    },
                )
            }
        }
        playlistTrack?.let { track ->
            AddToPlaylistDialogHost(
                viewModel = viewModel,
                track = track,
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
private fun ExpandedPlayer(
    viewModel: PlayerViewModel,
    currentTab: FullPlayerTab,
    onTabChange: (FullPlayerTab) -> Unit,
    onRetryLyrics: () -> Unit,
    onCollapse: () -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val state by viewModel.fullPlayerState.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    FullPlayer(
        state = state,
        progressState = viewModel.progressState,
        player = viewModel.player,
        lyricsState = lyricsState,
        lyricsAnimation = settings.lyricsAnimation,
        wavySeekbarEnabled = settings.wavySeekbarEnabled,
        currentTab = currentTab,
        onTabChange = onTabChange,
        onRetryLyrics = onRetryLyrics,
        onCollapse = onCollapse,
        onOpenArtist = onOpenArtist,
    )
}

@Composable
private fun AddToPlaylistDialogHost(
    viewModel: PlayerViewModel,
    track: PlayableTrack,
    onDismiss: () -> Unit,
    onAdd: (Long, Boolean) -> Unit,
    onCreate: (String) -> Unit,
) {
    val playlists by viewModel.customPlaylists.collectAsStateWithLifecycle()
    AddToPlaylistDialog(
        track = track,
        playlists = playlists,
        onDismiss = onDismiss,
        onAdd = onAdd,
        onCreate = onCreate,
    )
}

@Composable
internal fun AnimatedPlayPauseIcon(isPlaying: Boolean, modifier: Modifier = Modifier) {
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
    state: PlaybackChromeState,
    progressState: StateFlow<PlaybackProgressState>,
    onExpand: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    edgeToEdge: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val track = state.current ?: return
    // Liquid Glass dressing for the floating mini player (no-op when the
    // experimental setting is off — see ui/theme/LiquidGlass.kt).
    val liquidGlass = LocalLiquidGlass.current
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
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = if (edgeToEdge) 0.dp else 6.dp,
            shadowElevation = if (edgeToEdge) 0.dp else 12.dp,
            modifier = Modifier.fillMaxWidth().liquidGlassChrome(shape, liquidGlass),
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
                        shape = CircleShape,
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
                        enabled = state.queueSize > 1,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Icon(
                            Icons.Filled.SkipNext,
                            "Next",
                            tint = if (state.queueSize > 1) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                }
                MiniPlayerProgress(progressState)
            }
        }
    }
}

@Composable
private fun MiniPlayerProgress(progressState: StateFlow<PlaybackProgressState>) {
    val state by progressState.collectAsStateWithLifecycle()
    val progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(3.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // Scale an already measured layer instead of changing its width and
        // forcing the mini-player through measure/layout on every ticker tick.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = progress.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
fun PlayingWaveBars(
    modifier: Modifier = Modifier,
    waveColor: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
) {
    val transition = rememberInfiniteTransition(label = "miniArtworkWave")
    val first = transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse),
        label = "miniWaveFirst",
    )
    val second = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(560), RepeatMode.Reverse),
        label = "miniWaveSecond",
    )
    val third = transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(480), RepeatMode.Reverse),
        label = "miniWaveThird",
    )
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        contentColor = waveColor,
        shadowElevation = 2.dp,
        modifier = modifier.size(width = 26.dp, height = 22.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.5.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier
                    .width(2.5.dp)
                    .height(14.dp)
                    .graphicsLayer {
                        scaleY = (4f + first.value * 10f) / 14f
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .background(waveColor, CircleShape),
            )
            Box(
                Modifier
                    .width(2.5.dp)
                    .height(14.dp)
                    .graphicsLayer {
                        scaleY = (4f + second.value * 10f) / 14f
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .background(waveColor, CircleShape),
            )
            Box(
                Modifier
                    .width(2.5.dp)
                    .height(14.dp)
                    .graphicsLayer {
                        scaleY = (4f + third.value * 10f) / 14f
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .background(waveColor, CircleShape),
            )
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

private enum class SeekDirection { REWIND, FORWARD }

@Composable
private fun FullPlayer(
    state: MusicPlayerState,
    progressState: StateFlow<PlaybackProgressState>,
    player: MusicPlayer,
    lyricsState: LyricsUiState,
    lyricsAnimation: com.lastwave.app.data.local.LyricsAnimation = com.lastwave.app.data.local.LyricsAnimation.APPLE_FLUID,
    wavySeekbarEnabled: Boolean = true,
    currentTab: FullPlayerTab,
    onTabChange: (FullPlayerTab) -> Unit,
    onRetryLyrics: () -> Unit,
    onCollapse: () -> Unit,
    onOpenArtist: (String) -> Unit = {},
) {
    val track = state.current ?: return
    var showTrackMenu by remember(track.videoId, track.title) { mutableStateOf(false) }
    var artworkDragX by remember(track.videoId, track.title) { mutableFloatStateOf(0f) }
    var dismissDragY by remember(track.videoId, track.title) { mutableFloatStateOf(0f) }
    var isDismissDragging by remember { mutableStateOf(false) }
    var seekOverlayDirection by remember(track.videoId, track.title) { mutableStateOf<SeekDirection?>(null) }
    var seekOverlaySeconds by remember(track.videoId, track.title) { mutableIntStateOf(0) }
    var lastTapTimestamp by remember(track.videoId, track.title) { mutableLongStateOf(0L) }
    var lastTapSide by remember(track.videoId, track.title) { mutableStateOf<SeekDirection?>(null) }
    var seekResetJob by remember(track.videoId, track.title) { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()
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

    // Dominant cover-art color for the ambient background glow. Extracted
    // once per track through the shared Coil loader (normally a cache hit)
    // with Palette; any failure leaves the standard surface gradient.
    val context = LocalContext.current
    var ambientPrimary by remember(track.videoId, track.artworkUrl, track.title, track.artist) { mutableStateOf<Color?>(null) }
    var ambientSecondary by remember(track.videoId, track.artworkUrl, track.title, track.artist) { mutableStateOf<Color?>(null) }
    var ambientTertiary by remember(track.videoId, track.artworkUrl, track.title, track.artist) { mutableStateOf<Color?>(null) }
    LaunchedEffect(track.videoId, track.artworkUrl, track.title, track.artist) {
        val url = track.artworkUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .size(128)
                    .build()
                val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                    ?: return@runCatching
                val palette = Palette.from(bitmap).clearFilters().generate()
                val p = palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch
                val s = palette.lightVibrantSwatch ?: palette.darkVibrantSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch
                val t = palette.darkVibrantSwatch ?: palette.darkMutedSwatch ?: palette.dominantSwatch
                if (p != null) ambientPrimary = Color(p.rgb)
                if (s != null) ambientSecondary = Color(s.rgb)
                if (t != null) ambientTertiary = Color(t.rgb)
            }
        }
    }
    val ambientColor by animateColorAsState(
        targetValue = ambientPrimary ?: MaterialTheme.colorScheme.primary,
        animationSpec = tween(700),
        label = "playerAmbientColor",
    )
    val ambientCompanion by animateColorAsState(
        targetValue = ambientSecondary ?: androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.tertiary, ambientColor, 0.42f),
        animationSpec = tween(700),
        label = "playerAmbientCompanion",
    )
    val ambientDeep by animateColorAsState(
        targetValue = ambientTertiary ?: androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.secondary, ambientColor, 0.25f),
        animationSpec = tween(700),
        label = "playerAmbientDeep",
    )

    fun Modifier.playerVerticalSwipe(enabled: Boolean): Modifier = if (!enabled) this else pointerInput(track.videoId, track.title, currentTab) {
        detectVerticalDragGestures(
            onDragStart = { isDismissDragging = true },
            onDragCancel = {
                isDismissDragging = false
                dismissDragY = 0f
            },
            onDragEnd = {
                isDismissDragging = false
                when {
                    currentTab != FullPlayerTab.NOW_PLAYING && dismissDragY > swipeThreshold -> onTabChange(FullPlayerTab.NOW_PLAYING)
                    currentTab == FullPlayerTab.NOW_PLAYING && dismissDragY < -swipeThreshold -> onTabChange(FullPlayerTab.QUEUE)
                    currentTab == FullPlayerTab.NOW_PLAYING && dismissDragY > swipeThreshold -> onCollapse()
                }
                dismissDragY = 0f
            },
        ) { change, amount ->
            val updatedDrag = if (currentTab != FullPlayerTab.NOW_PLAYING) {
                (dismissDragY + amount).coerceAtLeast(0f)
            } else {
                dismissDragY + amount
            }
            if (updatedDrag != dismissDragY) change.consume()
            dismissDragY = updatedDrag
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = shownDismissY.coerceAtLeast(0f)
                val playerHeight = size.height.coerceAtLeast(1f)
                alpha = (1f - shownDismissY.coerceAtLeast(0f) / (playerHeight * 1.5f)).coerceIn(0.72f, 1f)
            }
            .playerVerticalSwipe(enabled = currentTab == FullPlayerTab.NOW_PLAYING),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Apple Music: Full-bleed scaled & deeply blurred artwork
            PlayerArtwork(
                track = track,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.35f
                        scaleY = 1.35f
                        alpha = 0.72f
                    }
                    .blur(36.dp),
                corner = 0.dp,
                decodeSizePx = 200,
            )

            // Apple Music: Vibrant chromatic ambient mesh blobs
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            0f to ambientColor.copy(alpha = 0.58f),
                            0.45f to ambientColor.copy(alpha = 0.22f),
                            1f to Color.Transparent,
                            center = androidx.compose.ui.geometry.Offset(
                                constraints.maxWidth * 0.25f,
                                constraints.maxHeight * 0.20f,
                            ),
                            radius = maxOf(constraints.maxWidth, constraints.maxHeight) * 0.85f,
                        ),
                    ),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            0f to ambientCompanion.copy(alpha = 0.52f),
                            0.50f to ambientCompanion.copy(alpha = 0.20f),
                            1f to Color.Transparent,
                            center = androidx.compose.ui.geometry.Offset(
                                constraints.maxWidth * 0.88f,
                                constraints.maxHeight * 0.65f,
                            ),
                            radius = maxOf(constraints.maxWidth, constraints.maxHeight) * 0.78f,
                        ),
                    ),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            0f to ambientDeep.copy(alpha = 0.42f),
                            0.55f to ambientDeep.copy(alpha = 0.14f),
                            1f to Color.Transparent,
                            center = androidx.compose.ui.geometry.Offset(
                                constraints.maxWidth * 0.15f,
                                constraints.maxHeight * 0.82f,
                            ),
                            radius = maxOf(constraints.maxWidth, constraints.maxHeight) * 0.70f,
                        ),
                    ),
            )

            // Apple Music: Contrast scrim gradient (ensures text & controls are clear while preserving vibrant colors)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color.Black.copy(alpha = 0.35f),
                            0.28f to Color.Black.copy(alpha = 0.15f),
                            0.65f to Color.Black.copy(alpha = 0.40f),
                            1.00f to Color.Black.copy(alpha = 0.72f),
                        ),
                    ),
            )
            // Subtle edge vignette
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            0f to Color.Transparent,
                            0.65f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.30f),
                            center = androidx.compose.ui.geometry.Offset(
                                constraints.maxWidth * 0.50f,
                                constraints.maxHeight * 0.40f,
                            ),
                            radius = maxOf(constraints.maxWidth, constraints.maxHeight) * 0.80f,
                        ),
                    ),
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 20.dp),
            ) {
                // ── Header: slimmer, calmer, premium ─────────────────────
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .playerVerticalSwipe(enabled = currentTab != FullPlayerTab.NOW_PLAYING),
                ) {
                    IconButton(
                        onClick = {
                            if (currentTab != FullPlayerTab.NOW_PLAYING) {
                                onTabChange(FullPlayerTab.NOW_PLAYING)
                            } else {
                                onCollapse()
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.40f),
                            ),
                    ) {
                        Icon(
                            if (currentTab != FullPlayerTab.NOW_PLAYING) Icons.Filled.ArrowBack else Icons.Filled.ExpandMore,
                            if (currentTab != FullPlayerTab.NOW_PLAYING) "Back to player" else "Minimize player",
                            modifier = Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Column(
                        Modifier.align(Alignment.Center).padding(horizontal = 96.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            when (currentTab) {
                                FullPlayerTab.NOW_PLAYING -> "NOW PLAYING"
                                FullPlayerTab.LYRICS -> "LYRICS"
                                FullPlayerTab.QUEUE -> "PLAYING QUEUE"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.9.sp,
                        )
                        Text(
                            if (currentTab == FullPlayerTab.LYRICS) {
                                "${track.title} • ${track.artist}"
                            } else {
                                state.sourceLabel.takeIf { it.isNotBlank() } ?: "LastWave"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = { showTrackMenu = true },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.40f),
                            ),
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            "Song options",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f),
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                AnimatedContent(
                    targetState = currentTab,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        (fadeIn(tween(ExpressiveMotion.Standard)) +
                            scaleIn(ExpressiveMotion.spatialSpring(), initialScale = 0.98f)) togetherWith
                            (fadeOut(tween(ExpressiveMotion.Quick)) +
                                scaleOut(tween(ExpressiveMotion.Standard), targetScale = 0.98f))
                    },
                    label = "playerTabContent",
                ) { tab ->
                    when (tab) {
                        FullPlayerTab.LYRICS -> {
                            LyricsPanel(
                                state = state,
                                progressState = progressState,
                                player = player,
                                lyricsState = lyricsState,
                                lyricsAnimation = lyricsAnimation,
                                ambientPrimary = ambientColor,
                                ambientSecondary = ambientCompanion,
                                ambientDeep = ambientDeep,
                                onRetry = onRetryLyrics,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        FullPlayerTab.QUEUE -> {
                            QueuePanel(state, player, Modifier.fillMaxSize())
                        }

                        FullPlayerTab.NOW_PLAYING -> {
                                // ── Standard layout (unchanged) ────────────────
                                Column(
                                    Modifier.fillMaxSize().padding(bottom = 18.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    BoxWithConstraints(
                                        modifier = Modifier.fillMaxWidth().weight(1f),
                                        contentAlignment = BiasAlignment(0f, -0.55f),
                                    ) {
                                        val artworkSize = (minOf(maxWidth, maxHeight) - 6.dp)
                                            .coerceAtLeast(0.dp)
                                            .coerceAtMost(370.dp)

                                        val glowAlpha by animateFloatAsState(
                                            targetValue = if (state.isPlaying) 0.65f else 0.35f,
                                            animationSpec = tween(600),
                                            label = "artworkGlowAlpha",
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(artworkSize + 28.dp)
                                                .graphicsLayer {
                                                    translationX = shownArtworkX * 0.7f
                                                    alpha = glowAlpha
                                                }
                                                .background(
                                                    Brush.radialGradient(
                                                        0.0f to ambientColor.copy(alpha = 0.50f),
                                                        0.50f to ambientCompanion.copy(alpha = 0.22f),
                                                        1.0f to Color.Transparent,
                                                    ),
                                                    shape = CircleShape,
                                                ),
                                        )

                                        Surface(
                                            shape = RoundedCornerShape(32.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.88f),
                                            tonalElevation = 6.dp,
                                            shadowElevation = 24.dp,
                                            modifier = Modifier
                                                .size(artworkSize)
                                                .graphicsLayer {
                                                    translationX = shownArtworkX
                                                    rotationZ = shownArtworkX / 80f
                                                }
                                                .pointerInput(track.videoId, track.title) {
                                                    awaitEachGesture {
                                                        val down = awaitFirstDown(requireUnconsumed = false)
                                                        var isDrag = false
                                                        val touchSlop = viewConfiguration.touchSlop
                                                        val initialX = down.position.x
                                                        val initialY = down.position.y

                                                        while (true) {
                                                            val event = awaitPointerEvent()
                                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                                            if (!change.pressed) {
                                                                if (isDrag) {
                                                                    when {
                                                                        artworkDragX < -swipeThreshold -> player.next()
                                                                        artworkDragX > swipeThreshold -> player.previous()
                                                                    }
                                                                    artworkDragX = 0f
                                                                } else {
                                                                    val isLeft = initialX < size.width * 0.5f
                                                                    val side = if (isLeft) SeekDirection.REWIND else SeekDirection.FORWARD
                                                                    val now = SystemClock.elapsedRealtime()

                                                                    if (lastTapSide != side) {
                                                                        seekResetJob?.cancel()
                                                                        seekOverlayDirection = null
                                                                        lastTapSide = side
                                                                        lastTapTimestamp = now
                                                                    } else if (now - lastTapTimestamp < 450L) {
                                                                        val newSeconds = if (seekOverlayDirection == side) seekOverlaySeconds + 5 else 5
                                                                        seekOverlaySeconds = newSeconds
                                                                        seekOverlayDirection = side
                                                                        lastTapTimestamp = now
                                                                        val deltaMs = if (side == SeekDirection.FORWARD) 5_000L else -5_000L
                                                                        val newPos = (player.state.value.positionMs + deltaMs).coerceIn(0L, player.state.value.durationMs.coerceAtLeast(0L))
                                                                        player.seekTo(newPos)

                                                                        seekResetJob?.cancel()
                                                                        seekResetJob = coroutineScope.launch {
                                                                            delay(700L)
                                                                            seekOverlayDirection = null
                                                                            lastTapSide = null
                                                                        }
                                                                    } else {
                                                                        lastTapTimestamp = now
                                                                        lastTapSide = side
                                                                        seekOverlayDirection = null
                                                                    }
                                                                }
                                                                break
                                                            }

                                                            if (change.isConsumed) {
                                                                artworkDragX = 0f
                                                                break
                                                            }

                                                            val dx = change.position.x - initialX
                                                            val dy = change.position.y - initialY
                                                            if (!isDrag) {
                                                                if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                                                                    isDrag = true
                                                                    change.consume()
                                                                }
                                                            } else {
                                                                change.consume()
                                                                artworkDragX = dx
                                                            }
                                                        }
                                                    }
                                                },
                                        ) {
                                            Box(Modifier.fillMaxSize()) {
                                                PlayerArtwork(track, Modifier.fillMaxSize(), 32.dp)

                                                androidx.compose.animation.AnimatedVisibility(
                                                    visible = seekOverlayDirection == SeekDirection.REWIND,
                                                    enter = fadeIn(tween(100)) + scaleIn(ExpressiveMotion.spatialSpring(), initialScale = 0.88f),
                                                    exit = fadeOut(tween(200)),
                                                    modifier = Modifier
                                                        .align(Alignment.CenterStart)
                                                        .fillMaxHeight()
                                                        .fillMaxWidth(0.5f),
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .clip(RoundedCornerShape(topStart = 32.dp, bottomStart = 32.dp, topEnd = 120.dp, bottomEnd = 120.dp))
                                                            .background(Color.Black.copy(alpha = 0.58f)),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Column(
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                            verticalArrangement = Arrangement.Center,
                                                        ) {
                                                            Surface(
                                                                shape = CircleShape,
                                                                color = Color.White.copy(alpha = 0.22f),
                                                                modifier = Modifier.size(52.dp),
                                                            ) {
                                                                Box(contentAlignment = Alignment.Center) {
                                                                    Icon(
                                                                        Icons.Filled.FastRewind,
                                                                        contentDescription = "Seek rewind",
                                                                        tint = Color.White,
                                                                        modifier = Modifier.size(28.dp),
                                                                    )
                                                                }
                                                            }
                                                            Spacer(Modifier.height(6.dp))
                                                            Text(
                                                                "-${seekOverlaySeconds}s",
                                                                style = MaterialTheme.typography.titleMedium,
                                                                fontWeight = FontWeight.ExtraBold,
                                                                color = Color.White,
                                                            )
                                                        }
                                                    }
                                                }

                                                androidx.compose.animation.AnimatedVisibility(
                                                    visible = seekOverlayDirection == SeekDirection.FORWARD,
                                                    enter = fadeIn(tween(100)) + scaleIn(ExpressiveMotion.spatialSpring(), initialScale = 0.88f),
                                                    exit = fadeOut(tween(200)),
                                                    modifier = Modifier
                                                        .align(Alignment.CenterEnd)
                                                        .fillMaxHeight()
                                                        .fillMaxWidth(0.5f),
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .clip(RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp, topStart = 120.dp, bottomStart = 120.dp))
                                                            .background(Color.Black.copy(alpha = 0.58f)),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Column(
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                            verticalArrangement = Arrangement.Center,
                                                        ) {
                                                            Surface(
                                                                shape = CircleShape,
                                                                color = Color.White.copy(alpha = 0.22f),
                                                                modifier = Modifier.size(52.dp),
                                                            ) {
                                                                Box(contentAlignment = Alignment.Center) {
                                                                    Icon(
                                                                        Icons.Filled.FastForward,
                                                                        contentDescription = "Seek forward",
                                                                        tint = Color.White,
                                                                        modifier = Modifier.size(28.dp),
                                                                    )
                                                                }
                                                            }
                                                            Spacer(Modifier.height(6.dp))
                                                            Text(
                                                                "+${seekOverlaySeconds}s",
                                                                style = MaterialTheme.typography.titleMedium,
                                                                fontWeight = FontWeight.ExtraBold,
                                                                color = Color.White,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                track.title,
                                                style = MaterialTheme.typography.headlineSmall.copy(
                                                    letterSpacing = (-0.35).sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            val splitArtists = remember(track.artist) {
                                                com.lastwave.app.util.ArtistHelper.splitArtists(track.artist)
                                            }
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(0.dp),
                                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                            ) {
                                                splitArtists.forEachIndexed { index, artName ->
                                                    Text(
                                                        text = artName,
                                                        style = MaterialTheme.typography.titleMedium.copy(
                                                            fontSize = 17.sp,
                                                            fontWeight = FontWeight.Medium,
                                                        ),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.94f),
                                                        modifier = Modifier
                                                            .clickable(
                                                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                                                indication = null,
                                                            ) { onOpenArtist(artName) },
                                                    )
                                                    if (index < splitArtists.lastIndex) {
                                                        Text(
                                                            text = ", ",
                                                            style = MaterialTheme.typography.titleMedium.copy(
                                                                fontSize = 17.sp,
                                                                fontWeight = FontWeight.Normal,
                                                            ),
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f),
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(Modifier.width(12.dp))

                                        Surface(
                                            onClick = { onTabChange(FullPlayerTab.LYRICS) },
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.40f),
                                            contentColor = MaterialTheme.colorScheme.primary,
                                            tonalElevation = 0.dp,
                                            shadowElevation = 0.dp,
                                            modifier = Modifier.size(46.dp),
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Filled.FormatQuote,
                                                    contentDescription = "Show lyrics",
                                                    modifier = Modifier.size(24.dp),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    SeekBar(
                                        progressState = progressState,
                                        isPlaying = state.isPlaying,
                                        trackKey = track.videoId ?: "${track.artist}|${track.title}",
                                        wavyEnabled = wavySeekbarEnabled,
                                        onSeek = player::seekTo,
                                        isTranslucent = false,
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    MainControls(state, player, isTranslucent = false)
                                    Spacer(Modifier.height(16.dp))
                                    PlayerUtilityControls(state, player, isTranslucent = false)
                                }
                        }
                    }
                }
                state.error?.let { message ->
                    Surface(
                        onClick = player::retry,
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
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "Tap to retry",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                                )
                            }
                            IconButton(
                                onClick = player::clearError,
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Filled.Close, "Dismiss error", modifier = Modifier.size(18.dp))
                            }
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
            onPlayInLastWave = { player.play(track, sourceLabel = state.sourceLabel) },
        )
    }
}

@Composable
internal fun PlayerProgressSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.20f else 0.12f)
    val range = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val fraction = ((value - valueRange.start) / range).coerceIn(0f, 1f)

    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        enabled = enabled,
        modifier = modifier.drawBehind {
            val inset = 10.dp.toPx()
            val startX = inset
            val endX = (size.width - inset).coerceAtLeast(startX)
            val activeEndX = startX + ((endX - startX) * fraction)
            val centerY = size.height / 2f
            drawLine(
                color = inactive,
                start = androidx.compose.ui.geometry.Offset(startX, centerY),
                end = androidx.compose.ui.geometry.Offset(endX, centerY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            if (activeEndX > startX) {
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            primary.copy(alpha = if (enabled) 1f else 0.42f),
                            tertiary.copy(alpha = if (enabled) 0.92f else 0.36f),
                        ),
                        startX = startX,
                        endX = activeEndX,
                    ),
                    start = androidx.compose.ui.geometry.Offset(startX, centerY),
                    end = androidx.compose.ui.geometry.Offset(activeEndX, centerY),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        },
        colors = SliderDefaults.colors(
            thumbColor = primary,
            activeTrackColor = Color.Transparent,
            inactiveTrackColor = Color.Transparent,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
            disabledThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.34f),
            disabledActiveTrackColor = Color.Transparent,
            disabledInactiveTrackColor = Color.Transparent,
            disabledActiveTickColor = Color.Transparent,
            disabledInactiveTickColor = Color.Transparent,
        ),
    )
}

@Composable
private fun SeekBar(
    progressState: StateFlow<PlaybackProgressState>,
    isPlaying: Boolean,
    trackKey: String?,
    wavyEnabled: Boolean = true,
    onSeek: (Long) -> Unit,
    isTranslucent: Boolean = false,
) {
    if (wavyEnabled) {
        val progress by progressState.collectAsStateWithLifecycle()
        WavySeekBar(
            positionMs = progress.positionMs,
            durationMs = progress.durationMs,
            isPlaying = isPlaying,
            onSeek = onSeek,
            isTranslucent = isTranslucent,
            trackKey = trackKey,
        )
    } else {
        val progress by progressState.collectAsStateWithLifecycle()
        var dragging by remember { mutableStateOf(false) }
        var dragValue by remember { mutableFloatStateOf(0f) }
        val end = progress.durationMs.coerceAtLeast(1).toFloat()
        val shown = if (dragging) dragValue else progress.positionMs.coerceIn(0, progress.durationMs.coerceAtLeast(0)).toFloat()
        Column(Modifier.fillMaxWidth()) {
            PlayerProgressSlider(
                value = shown.coerceIn(0f, end),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { onSeek(dragValue.toLong()); dragging = false },
                valueRange = 0f..end,
                enabled = progress.durationMs > 0,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    formatTime(shown.toLong()),
                    style = if (isTranslucent) MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium) else MaterialTheme.typography.labelMedium,
                    color = if (isTranslucent) Color.White.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
                )
                Text(
                    "−${formatTime((progress.durationMs - shown.toLong()).coerceAtLeast(0))}",
                    style = if (isTranslucent) MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium) else MaterialTheme.typography.labelMedium,
                    color = if (isTranslucent) Color.White.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
                )
            }
        }
    }
}

@Composable
private fun MainControls(state: MusicPlayerState, player: MusicPlayer, isTranslucent: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (isTranslucent) 18.dp else 16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = player::previous,
            shape = CircleShape,
            color = if (isTranslucent) Color.White.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.40f),
            contentColor = if (isTranslucent) Color.White.copy(alpha = 0.94f) else MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.size(if (isTranslucent) 54.dp else 58.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(if (isTranslucent) 28.dp else 31.dp))
            }
        }
        Surface(
            onClick = player::togglePlayPause,
            shape = CircleShape,
            color = if (isTranslucent) Color.White else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            contentColor = if (isTranslucent) Color.Black else MaterialTheme.colorScheme.onPrimary,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.size(if (isTranslucent) 72.dp else 76.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (state.isBuffering) {
                    ExpressiveInlineLoadingIndicator(
                        size = if (isTranslucent) 28.dp else 30.dp,
                        color = if (isTranslucent) Color.Black else MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 3.dp,
                    )
                } else {
                    AnimatedPlayPauseIcon(state.isPlaying, Modifier.size(if (isTranslucent) 36.dp else 39.dp))
                }
            }
        }
        Surface(
            onClick = player::next,
            shape = CircleShape,
            color = if (isTranslucent) Color.White.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.40f),
            contentColor = if (isTranslucent) Color.White.copy(alpha = 0.94f) else MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.size(if (isTranslucent) 54.dp else 58.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.SkipNext, "Next", Modifier.size(if (isTranslucent) 28.dp else 31.dp))
            }
        }
    }
}

@Composable
private fun PlayerUtilityControls(state: MusicPlayerState, player: MusicPlayer, isTranslucent: Boolean = false) {
    val shuffleActive = state.shuffleEnabled
    val repeatActive = state.repeatMode != Player.REPEAT_MODE_OFF

    val shuffleBg by animateColorAsState(
        targetValue = when {
            isTranslucent && shuffleActive -> Color.White.copy(alpha = 0.22f)
            isTranslucent -> Color.White.copy(alpha = 0.12f)
            shuffleActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
            else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.40f)
        },
        animationSpec = tween(ExpressiveMotion.Quick),
        label = "shuffleBg",
    )
    val shuffleContent by animateColorAsState(
        targetValue = when {
            isTranslucent && shuffleActive -> Color.White
            isTranslucent -> Color.White.copy(alpha = 0.85f)
            shuffleActive -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f)
        },
        animationSpec = tween(ExpressiveMotion.Quick),
        label = "shuffleContent",
    )
    val repeatBg by animateColorAsState(
        targetValue = when {
            isTranslucent && repeatActive -> Color.White.copy(alpha = 0.22f)
            isTranslucent -> Color.White.copy(alpha = 0.12f)
            repeatActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
            else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.40f)
        },
        animationSpec = tween(ExpressiveMotion.Quick),
        label = "repeatBg",
    )
    val repeatContent by animateColorAsState(
        targetValue = when {
            isTranslucent && repeatActive -> Color.White
            isTranslucent -> Color.White.copy(alpha = 0.85f)
            repeatActive -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f)
        },
        animationSpec = tween(ExpressiveMotion.Quick),
        label = "repeatContent",
    )

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (isTranslucent) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = player::toggleShuffle,
            shape = CircleShape,
            color = shuffleBg,
            contentColor = shuffleContent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.weight(1f).height(if (isTranslucent) 44.dp else 48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Shuffle, "Shuffle", modifier = Modifier.size(if (isTranslucent) 19.dp else 20.dp))
            }
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = when {
                isTranslucent -> Color.White.copy(alpha = 0.12f)
                state.isQobuz -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.40f)
                else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.40f)
            },
            contentColor = when {
                isTranslucent -> Color.White
                state.isQobuz -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f)
            },
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.weight(1.3f).height(if (isTranslucent) 44.dp else 48.dp),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.HighQuality,
                    null,
                    tint = if (isTranslucent) Color.White.copy(alpha = 0.92f) else if (state.isQobuz) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f),
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    qualityLabel(state),
                    style = if (isTranslucent) MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp) else MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (isTranslucent) Color.White.copy(alpha = 0.90f) else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
        }
        Surface(
            onClick = player::cycleRepeatMode,
            shape = CircleShape,
            color = repeatBg,
            contentColor = repeatContent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.weight(1f).height(if (isTranslucent) 44.dp else 48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    "Repeat mode",
                    modifier = Modifier.size(if (isTranslucent) 19.dp else 20.dp),
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
private fun PlayerArtwork(
    track: PlayableTrack,
    modifier: Modifier,
    corner: androidx.compose.ui.unit.Dp,
    decodeSizePx: Int? = null,
) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        ArtworkImage(
            name = track.title,
            artist = track.artist,
            embeddedUrl = track.artworkUrl,
            fallbackIcon = Icons.Filled.MusicNote,
            modifier = Modifier.fillMaxSize(),
            decodeSizePx = decodeSizePx,
        )
    }
}

internal fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(total / 60, total % 60)
}

private fun qualityLabel(state: MusicPlayerState): String = when {
    // Qobuz with known bit depth / sampling rate → show precise format
    state.isQobuz && state.bitDepth != null && state.samplingRateKHz != null -> {
        val rate = if (state.samplingRateKHz % 1.0 == 0.0) state.samplingRateKHz.toInt().toString()
        else state.samplingRateKHz.toString()
        "FLAC ${state.bitDepth}/$rate"
    }
    state.isQobuz && state.audioCodec == "MP3 320k" -> "MP3 320k"
    state.isQobuz -> state.audioCodec ?: "LOSSLESS"
    // YouTube with known codec + bitrate → e.g. "OPUS 160k" or "AAC 256k"
    state.audioCodec != null && state.bitrateKbps != null -> "${state.audioCodec} ${state.bitrateKbps}k"
    state.audioCodec != null -> state.audioCodec
    state.bitrateKbps != null -> "${state.bitrateKbps} kbps"
    else -> "AUDIO"
}

private fun PlayableTrack.toGeneratedTrack() = com.lastwave.app.data.generate.GeneratedTrack(
    name = title,
    artist = artist,
    artworkUrl = artworkUrl,
    album = album,
)
