package com.lastwave.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
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

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .safeHorizontalContentPadding(),
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

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    top = 4.dp,
                    bottom = FloatingNavDefaults.contentBottomPadding(),
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header_row", contentType = "header") {
                    HeaderRow(
                        displayUsername = if (uiState.isViewingFriend) uiState.viewingUsername else uiState.username,
                        isViewingFriend = uiState.isViewingFriend,
                        onClick = onOpenFriends,
                        onReturnToSelf = viewModel::returnToOwnProfile,
                        viewModel = viewModel,
                    )
                }

                uiState.stats?.let { stats ->
                    item(key = "stats_card", contentType = "stats") {
                        StatsCard(
                            scrobbles = stats.scrobbles,
                            trackCount = stats.trackCount,
                            artistCount = stats.artistCount,
                            albumCount = stats.albumCount,
                            onOpenGenres = onOpenGenres,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }

                item(key = "mix_header", contentType = "mix_header") {
                    MixHeader(
                        sortMode = uiState.sortMode,
                        onSortModeChange = viewModel::setSortMode,
                        trackCount = rows.count { it is HomeRow.Track },
                    )
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
                    when (row) {
                        is HomeRow.DateHeader -> DateHeaderRow(row.label)
                        is HomeRow.Track -> TrackRow(
                            track = row.track,
                            badge = row.badge,
                            onClick = {
                                musicPlayer.playQueue(
                                    tracks = playbackQueue,
                                    startIndex = playbackIndexByRow[rowIndex],
                                    sourceLabel = "Home",
                                )
                            },
                            onLongClick = {
                                addToPlaylist(
                                    com.lastwave.app.playback.PlayableTrack(
                                        title = row.track.name,
                                        artist = row.track.artist,
                                        artworkUrl = row.track.artworkUrl,
                                    ),
                                )
                            },
                            onMenuClick = { menuTrack = row.track },
                        )
                    }
                }

                if (rows.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 28.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 36.dp, horizontal = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    modifier = Modifier.size(52.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Filled.MusicNote,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(26.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "No Tracks Found",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "Play music on your preferred app to see scrobbles appear in real-time.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
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
    onReturnToSelf: () -> Unit,
    viewModel: HomeViewModel,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                onClick = onClick,
                shape = BadgePillShape,
                color = if (isViewingFriend) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(
                    1.dp,
                    if (isViewingFriend) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = if (isViewingFriend) Icons.Filled.People else Icons.Filled.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        displayUsername.ifBlank { "Guest" },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isViewingFriend) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (isViewingFriend) {
                Surface(
                    onClick = onReturnToSelf,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    modifier = Modifier.size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Return to own profile",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
        color = if (isPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            1.dp,
            if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            if (isPlaying) {
                EqualizerWaveBars(tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(
                    Icons.Filled.Headset,
                    contentDescription = "Estimated lifetime listening time",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatTimer(totalSeconds),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun EqualizerWaveBars(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "equalizerTransition")
    val bar1 by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bar1",
    )
    val bar2 by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bar2",
    )
    val bar3 by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(380, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bar3",
    )

    Row(
        modifier = modifier.size(width = 13.dp, height = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            Modifier
                .width(2.5.dp)
                .fillMaxHeight(bar1)
                .clip(RoundedCornerShape(1.dp))
                .background(tint),
        )
        Box(
            Modifier
                .width(2.5.dp)
                .fillMaxHeight(bar2)
                .clip(RoundedCornerShape(1.dp))
                .background(tint),
        )
        Box(
            Modifier
                .width(2.5.dp)
                .fillMaxHeight(bar3)
                .clip(RoundedCornerShape(1.dp))
                .background(tint),
        )
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
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
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
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
        enter = fadeIn(animationSpec = tween(400)) + expandVertically(animationSpec = tween(400)),
        modifier = modifier,
    ) {
        Surface(
            shape = ExpressiveHeroShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Surface(
                    shape = HeroInnerShape,
                    color = Color.Transparent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f),
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.60f),
                                ),
                            ),
                            shape = HeroInnerShape,
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                )
                                Text(
                                    "SCROBBLES",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                formatCount(rememberAnimatedCount(scrobbles)),
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }

                        Surface(
                            onClick = onOpenGenres,
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primary,
                            shadowElevation = 3.dp,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    "Genres",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "View genres",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatPill(icon = Icons.Filled.MusicNote, label = "Tracks", value = trackCount, modifier = Modifier.weight(1f))
                    StatPill(icon = Icons.Filled.Person, label = "Artists", value = artistCount, modifier = Modifier.weight(1f))
                    StatPill(icon = Icons.Filled.Album, label = "Albums", value = albumCount, modifier = Modifier.weight(1f))
                }
            }
        }
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

@Composable
private fun StatPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: Long,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = StatPillShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f),
        ),
    ) {
        Column(
            Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                )
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                formatCount(rememberAnimatedCount(value)),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun formatCount(value: Long): String = if (value <= 0) "—" else "%,d".format(value)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MixHeader(
    sortMode: HomeSortMode,
    onSortModeChange: (HomeSortMode) -> Unit,
    trackCount: Int = 0,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val pillInteractionSource = remember { MutableInteractionSource() }
    val pillPressed by pillInteractionSource.collectIsPressedAsState()
    LaunchedEffect(pillPressed) {
        if (pillPressed) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val pillScale by animateFloatAsState(
        targetValue = if (pillPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "sortPillPressScale",
    )

    val title = when (sortMode) {
        HomeSortMode.RECENT -> "Recent History"
        HomeSortMode.MOST_PLAYED -> "Most Played"
        HomeSortMode.LAST_7_DAYS -> "Last 7 Days"
        HomeSortMode.LAST_30_DAYS -> "Last 30 Days"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (trackCount > 0) {
                    Text(
                        text = "$trackCount tracks recorded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box {
                Surface(
                    onClick = { menuOpen = true },
                    shape = BadgePillShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                    ),
                    tonalElevation = 1.dp,
                    interactionSource = pillInteractionSource,
                    modifier = Modifier.heightIn(min = 36.dp).scale(pillScale),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            iconForSortMode(sortMode),
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            sortModeLabel(sortMode),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 3.dp,
                    shadowElevation = 4.dp,
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

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeSortChip(
                label = "Recent",
                icon = Icons.Filled.Schedule,
                isSelected = sortMode == HomeSortMode.RECENT,
                onClick = {
                    if (sortMode != HomeSortMode.RECENT) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSortModeChange(HomeSortMode.RECENT)
                    }
                },
            )
            HomeSortChip(
                label = "Most Played",
                icon = Icons.Filled.BarChart,
                isSelected = sortMode == HomeSortMode.MOST_PLAYED,
                onClick = {
                    if (sortMode != HomeSortMode.MOST_PLAYED) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSortModeChange(HomeSortMode.MOST_PLAYED)
                    }
                },
            )
            HomeSortChip(
                label = "Last 7 Days",
                icon = Icons.Filled.DateRange,
                isSelected = sortMode == HomeSortMode.LAST_7_DAYS,
                onClick = {
                    if (sortMode != HomeSortMode.LAST_7_DAYS) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSortModeChange(HomeSortMode.LAST_7_DAYS)
                    }
                },
            )
            HomeSortChip(
                label = "Last 30 Days",
                icon = Icons.Filled.CalendarMonth,
                isSelected = sortMode == HomeSortMode.LAST_30_DAYS,
                onClick = {
                    if (sortMode != HomeSortMode.LAST_30_DAYS) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSortModeChange(HomeSortMode.LAST_30_DAYS)
                    }
                },
            )
        }
    }
}

@Composable
private fun HomeSortChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = BadgePillShape,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
        ),
        tonalElevation = if (isSelected) 2.dp else 0.dp,
        modifier = Modifier.height(36.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
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
        label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
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
        color = if (isNowPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent,
        border = if (isNowPlaying) BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
        ) else null,
        tonalElevation = if (isNowPlaying) 1.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = if (isNowPlaying) 4.dp else 1.dp)
            .clip(if (isNowPlaying) NowPlayingCardShape else TrackRowShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 66.dp)
                .padding(vertical = 7.dp, horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
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
                if (isNowPlaying) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 4.dp, y = 4.dp)
                            .size(18.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            EqualizerWaveBars(tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    track.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isNowPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
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
                Surface(
                    shape = BadgePillShape,
                    color = MaterialTheme.colorScheme.primary,
                    tonalElevation = 2.dp,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    ) {
                        EqualizerWaveBars(tint = MaterialTheme.colorScheme.onPrimary)
                        Text(
                            "LIVE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.8.sp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }

            if (badge != null && !isNowPlaying) {
                Surface(
                    shape = BadgePillShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                ) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            }

            com.lastwave.app.ui.common.OverflowMenuButton(onClick = onMenuClick)
        }
    }
}
