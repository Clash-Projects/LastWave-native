package com.lastwave.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.theme.ArtworkShape
import com.lastwave.app.ui.theme.BadgePillShape
import com.lastwave.app.ui.theme.ExpressiveHeroShape
import com.lastwave.app.ui.theme.ExpressivePillShape
import com.lastwave.app.ui.theme.HeroInnerShape
import com.lastwave.app.ui.theme.ListContainerShape
import com.lastwave.app.ui.theme.NowPlayingCardShape
import com.lastwave.app.ui.theme.StatPillShape
import com.lastwave.app.ui.theme.TrackRowShape
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.People
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically

import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.lastwave.app.ui.shell.FloatingNavDefaults
import coil.compose.SubcomposeAsyncImage
import com.lastwave.app.data.repository.HomeSortMode
import com.lastwave.app.data.repository.HomeTrack
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Immutable shapes hoisted out of composition: previously each of these was
// constructed inline inside row/card composables, i.e. re-allocated for every
// row on every recomposition. Rows are the hottest path while scrolling, so
// they must not allocate.
private val ListContainerShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val TrackRowShape = RoundedCornerShape(18.dp)
private val NowPlayingCardShape = RoundedCornerShape(22.dp)
private val ArtworkShape = RoundedCornerShape(14.dp)
private val BadgePillShape = RoundedCornerShape(50)
private val StatPillShape = RoundedCornerShape(20.dp)
private val HeroInnerShape = RoundedCornerShape(24.dp)

/**
 * Faithful port of home.html/home.js's layout, top to bottom:
 *  1. Header row — username pill (left) + live listen timer (right)
 *  2. Stats card — big "Scrobbles" number + arrow-to-Genres, then a
 *     Tracks / Artists / Albums row
 *  3. Mix card — "List" title + sort dropdown (Recent / Most Played /
 *     Last 7 Days / Last 30 Days), then the track list itself, with the
 *     Now Playing row always pinned first when present.
 * There's no separate "Now Playing card" — that was an earlier, simplified
 * substitute; the real app renders it as the first row of the same list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenGenres: () -> Unit,
    onOpenFriends: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.pollWhileActive()
        }
    }

    var menuTrack by remember { mutableStateOf<HomeTrack?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ExpressiveHeader(
                title = "LastWave",
                actions = {
                    HeaderActionIcon(Icons.Filled.Explore, "Discover", onOpenDiscover)
                    HeaderActionIcon(Icons.Filled.Search, "Search", onOpenSearch)
                    IconButton(onClick = onOpenSettings) {
                        ProfileAvatar(avatarUrl = uiState.stats?.avatarUrl, modifier = Modifier.size(30.dp))
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        if (uiState.isLoading) {
            Box(
                Modifier.fillMaxSize().padding(scaffoldPadding).safeHorizontalContentPadding(),
                contentAlignment = Alignment.Center,
            ) {
                com.lastwave.app.ui.common.ExpressiveLoadingIndicator(message = "Loading your listening history")
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(scaffoldPadding).safeHorizontalContentPadding()) {
            HeaderRow(
                displayUsername = if (uiState.isViewingFriend) uiState.viewingUsername else uiState.username,
                isViewingFriend = uiState.isViewingFriend,
                onClick = onOpenFriends,
                viewModel = viewModel,
            )
            Spacer(Modifier.height(2.dp))

            uiState.stats?.let { stats ->
                StatsCard(
                    scrobbles = stats.scrobbles,
                    trackCount = stats.trackCount,
                    artistCount = stats.artistCount,
                    albumCount = stats.albumCount,
                    onOpenGenres = onOpenGenres,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 0.dp),
                )
                Spacer(Modifier.height(12.dp))
            }

            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                val listState = rememberLazyListState()
                LaunchedEffect(listState, uiState.allTracks.size) {
                    snapshotFlowNearEnd(listState) { viewModel.loadNextPage() }
                }

                val rows = remember(uiState.allTracks, uiState.sortMode, uiState.nowPlaying, uiState.topTracksOverall, uiState.topTracks7Days, uiState.topTracks30Days) {
                    uiState.visibleRows()
                }
                val playbackQueue = remember(rows) {
                    rows.mapNotNull { row ->
                        (row as? HomeRow.Track)?.track?.let { track ->
                            com.lastwave.app.playback.PlayableTrack(
                                title = track.name,
                                artist = track.artist,
                                artworkUrl = track.artworkUrl,
                            )
                        }
                    }
                }
                val playbackIndexByRow = remember(rows) {
                    var nextPlaybackIndex = 0
                    IntArray(rows.size) { rowIndex ->
                        if (rows[rowIndex] is HomeRow.Track) nextPlaybackIndex++ else -1
                    }
                }
                val musicPlayer = com.lastwave.app.ui.player.LocalMusicPlayer.current
                val addToPlaylist = com.lastwave.app.ui.player.LocalAddToPlaylist.current
                val quickTracks = remember(uiState.allTracks) {
                    uiState.allTracks.distinctBy { "${it.name}|${it.artist}" }
                }

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 2.dp,
                        bottom = FloatingNavDefaults.contentBottomPadding(),
                    ),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (quickTracks.isNotEmpty()) {
                        item(key = "quick_play_container", contentType = "quick_play") {
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = 1.dp,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                QuickPlayGrid(
                                    tracks = quickTracks,
                                    onTrackClick = { track ->
                                        musicPlayer.play(
                                            com.lastwave.app.playback.PlayableTrack(
                                                title = track.name,
                                                artist = track.artist,
                                                artworkUrl = track.artworkUrl,
                                            ),
                                            sourceLabel = "Quick Play",
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                        }
                    }

                    item(key = "list_header", contentType = "header") {
                        Surface(
                            shape = if (rows.isEmpty()) RoundedCornerShape(22.dp) else RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp)) {
                                MixHeader(
                                    sortMode = uiState.sortMode,
                                    onSortModeChange = viewModel::setSortMode,
                                )
                            }
                        }
                    }

                    itemsIndexed(
                        rows,
                        key = { _, row ->
                            when (row) {
                                is HomeRow.DateHeader -> "date_${row.label}"
                                is HomeRow.Track -> if (row.track.isNowPlaying) {
                                    "nowplaying_${row.track.key}"
                                } else {
                                    "track_${row.track.key}_${row.track.timestampMillis}"
                                }
                            }
                        },
                        contentType = { _, row ->
                            when (row) {
                                is HomeRow.DateHeader -> "date"
                                is HomeRow.Track -> "track"
                            }
                        },
                    ) { rowIndex, row ->
                        Surface(
                            shape = if (rowIndex == rows.lastIndex) RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp) else androidx.compose.ui.graphics.RectangleShape,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Box(Modifier.padding(horizontal = 8.dp)) {
                                when (row) {
                                    is HomeRow.DateHeader -> DateHeaderRow(row.label)
                                    is HomeRow.Track -> TrackRow(
                                        track = row.track,
                                        badge = row.badge,
                                        onClick = {
                                            musicPlayer.playQueue(
                                                tracks = playbackQueue,
                                                startIndex = playbackIndexByRow[rowIndex],
                                                sourceLabel = "Recent Scrobbles",
                                            )
                                        },
                                        onMenuClick = { menuTrack = row.track },
                                        onLongClick = {
                                            addToPlaylist(
                                                com.lastwave.app.playback.PlayableTrack(
                                                    title = row.track.name,
                                                    artist = row.track.artist,
                                                    artworkUrl = row.track.artworkUrl,
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }

                    if (rows.isEmpty()) {
                        item(key = "empty", contentType = "empty") {
                            Surface(
                                shape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = 1.dp,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("No tracks yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    menuTrack?.let { track ->
        com.lastwave.app.ui.common.TrackContextMenuSheet(
            target = com.lastwave.app.ui.common.TrackMenuTarget.Track(track.name, track.artist, track.artworkUrl.orEmpty()),
            capabilities = com.lastwave.app.ui.common.TrackMenuCapabilities(showCopyActions = true, showDeleteScrobble = true),
            playbackSourceLabel = "Home",
            onDismiss = { menuTrack = null },
        )
    }

}

private suspend fun snapshotFlowNearEnd(listState: LazyListState, onNearEnd: () -> Unit) {
    snapshotFlow {
        val info = listState.layoutInfo
        val total = info.totalItemsCount
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        total > 0 && lastVisible >= total - 5
    }.collect { isNear -> if (isNear) onNearEnd() }
}

@Composable
private fun HeaderRow(
    displayUsername: String,
    isViewingFriend: Boolean,
    onClick: () -> Unit,
    viewModel: HomeViewModel,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Surface(
            onClick = onClick,
            shape = BadgePillShape,
            color = if (isViewingFriend) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    displayUsername.ifBlank { "—" },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isViewingFriend) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                )
                if (isViewingFriend) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.People,
                        contentDescription = "Switch profile",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        LiveListenTimer(viewModel)
    }
}

@Composable
private fun LiveListenTimer(viewModel: HomeViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listenElapsedSeconds by viewModel.listenElapsedSeconds.collectAsStateWithLifecycle()
    val totalSeconds = (uiState.stats?.timerBaseSeconds ?: 0) + listenElapsedSeconds.toLong()
    val isPlaying = uiState.nowPlaying != null

    Surface(
        shape = BadgePillShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
        ) {
            Icon(
                Icons.Filled.Headset,
                contentDescription = "Estimated lifetime listening time",
                modifier = Modifier.size(16.dp),
                tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                formatTimer(totalSeconds),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
            )
        }
    }
}

private fun formatTimer(totalSeconds: Long): String {
    if (totalSeconds <= 0) return "--"
    val d = totalSeconds / 86400
    val h = (totalSeconds % 86400) / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        d > 0 -> "${d}d ${h}h ${m}m"
        h > 0 -> "${h}h ${m}m ${s}s"
        else -> "${m}m ${s}s"
    }
}

@Composable
private fun ProfileAvatar(avatarUrl: String?, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = modifier,
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            ArtworkImage(
                name = "profile",
                artist = "avatar",
                embeddedUrl = avatarUrl,
                fallbackIcon = Icons.Filled.AccountCircle,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatsCard(
    scrobbles: Long,
    trackCount: Long,
    artistCount: Long,
    albumCount: Long,
    onOpenGenres: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(300)) + expandVertically(animationSpec = tween(300)),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f),
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenGenres() },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Left: Main Scrobble Stat with subtle pill background
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            formatCount(rememberAnimatedCount(scrobbles)),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Scrobbles",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }
                }

                // Middle: Compact stats row (Tracks, Artists, Albums)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactStatItem(count = trackCount, label = "Tracks")
                    Box(
                        modifier = Modifier
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    )
                    CompactStatItem(count = artistCount, label = "Artists")
                    Box(
                        modifier = Modifier
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    )
                    CompactStatItem(count = albumCount, label = "Albums")
                }

                // Right: Sleek mini arrow action
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "View genres",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactStatItem(count: Long, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            formatCount(rememberAnimatedCount(count)),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun rememberAnimatedCount(target: Long): Long {
    val animated = remember { Animatable(0f) }
    LaunchedEffect(target) {
        animated.animateTo(
            targetValue = target.toFloat(),
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessVeryLow,
            ),
        )
    }
    return animated.value.toLong()
}

private fun formatCount(value: Long): String = if (value <= 0) "—" else "%,d".format(value)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MixHeader(sortMode: HomeSortMode, onSortModeChange: (HomeSortMode) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    // Real, felt tactile feedback: haptic now fires the instant the pill is
    // pressed (finger down), not only after the click completes — firing it
    // solely inside onClick meant it landed at the same moment the dropdown
    // menu opened and covered the pill, which could read as "nothing
    // happened" since the press-scale's own short spring had barely started
    // by then. A dedicated LaunchedEffect on the raw pressed state decouples
    // the haptic from whatever the click itself goes on to do.
    val pillInteractionSource = remember { MutableInteractionSource() }
    val pillPressed by pillInteractionSource.collectIsPressedAsState()
    LaunchedEffect(pillPressed) {
        if (pillPressed) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    // A more pronounced dip than the shared rememberGroupPressScale (tuned
    // for full-width group rows) — a small pill needs a bigger relative
    // shrink to actually read as a press at this size.
    val pillScale by animateFloatAsState(
        targetValue = if (pillPressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "sortPillPressScale",
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "List",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        Box {
            Surface(
                onClick = { menuOpen = true },
                shape = BadgePillShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 1.dp,
                interactionSource = pillInteractionSource,
                modifier = Modifier.heightIn(min = 34.dp).scale(pillScale),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        iconForSortMode(sortMode),
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        sortModeLabel(sortMode),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                // Lowered from 10dp: a heavy shadowElevation on a popup
                // paints a mostly-rectangular drop shadow around the
                // rounded card (the shadow's own corner falloff is much
                // subtler than the card's actual corner radius), which is
                // exactly what read as "squarish" — a light shadow plus a
                // bit more tonalElevation for legibility fixes that
                // without losing depth entirely.
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                SortOption(Icons.Filled.Schedule, "Recent", sortMode == HomeSortMode.RECENT) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSortModeChange(HomeSortMode.RECENT); menuOpen = false
                }
                SortOption(Icons.Filled.BarChart, "Most Played", sortMode == HomeSortMode.MOST_PLAYED) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSortModeChange(HomeSortMode.MOST_PLAYED); menuOpen = false
                }
                SortOption(Icons.Filled.DateRange, "Last 7 Days", sortMode == HomeSortMode.LAST_7_DAYS) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSortModeChange(HomeSortMode.LAST_7_DAYS); menuOpen = false
                }
                SortOption(Icons.Filled.CalendarMonth, "Last 30 Days", sortMode == HomeSortMode.LAST_30_DAYS) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSortModeChange(HomeSortMode.LAST_30_DAYS); menuOpen = false
                }
            }
        }
    }
}

private fun iconForSortMode(mode: HomeSortMode): androidx.compose.ui.graphics.vector.ImageVector = when (mode) {
    HomeSortMode.RECENT -> Icons.Filled.Schedule
    HomeSortMode.MOST_PLAYED -> Icons.Filled.BarChart
    HomeSortMode.LAST_7_DAYS -> Icons.Filled.DateRange
    HomeSortMode.LAST_30_DAYS -> Icons.Filled.CalendarMonth
}

private fun sortModeLabel(mode: HomeSortMode) = when (mode) {
    HomeSortMode.RECENT -> "Recent"
    HomeSortMode.MOST_PLAYED -> "Most Played"
    HomeSortMode.LAST_7_DAYS -> "Last 7 Days"
    HomeSortMode.LAST_30_DAYS -> "Last 30 Days"
}

@Composable
private fun SortOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        },
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            if (active) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        onClick = onClick,
        modifier = if (active) {
            Modifier
                .padding(horizontal = 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
        } else {
            Modifier.padding(horizontal = 6.dp)
        },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
    )
}

@Composable
private fun DateHeaderRow(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun TrackRow(
    track: HomeTrack,
    badge: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenuClick: () -> Unit,
) {
    val isNowPlaying = track.isNowPlaying
    val secondaryTextColor =
        if (isNowPlaying) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
        else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = if (isNowPlaying) NowPlayingCardShape else TrackRowShape,
        color = if (isNowPlaying) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        tonalElevation = if (isNowPlaying) 1.dp else 0.dp,
        modifier = if (isNowPlaying) {
            Modifier.fillMaxWidth().padding(vertical = 4.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick)
        } else {
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(vertical = 6.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkImage(
                name = track.name,
                artist = track.artist,
                embeddedUrl = track.artworkUrl,
                fallbackIcon = if (isNowPlaying) Icons.Filled.GraphicEq else Icons.Filled.MusicNote,
                modifier = Modifier
                    .size(52.dp)
                    .clip(ArtworkShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (isNowPlaying) {
                val infiniteTransition = rememberInfiniteTransition(label = "nowPlayingPulse")
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 1.0f,
                    targetValue = 1.06f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "pulseScale",
                )
                Surface(
                    shape = BadgePillShape,
                    color = MaterialTheme.colorScheme.primary,
                    tonalElevation = 4.dp,
                    shadowElevation = 2.dp,
                    modifier = Modifier.graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                    },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Spacer(
                            Modifier
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.onPrimary, CircleShape),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Now Playing",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            if (badge != null) {
                Surface(shape = BadgePillShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            // Home-only per item 7: a small filled tonal container around
            // the overflow trigger. This composable is private to
            // HomeScreen.kt, so this doesn't touch the three-dot button on
            // Item 1 (consistency pass): the same OverflowMenuButton is now
            // used on every screen's song list, not just Home.
            com.lastwave.app.ui.common.OverflowMenuButton(onClick = onMenuClick)
        }
    }
}
