package com.lastwave.app.ui.discover

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.toPlayableTrack
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.common.GroupGap
import com.lastwave.app.ui.common.GroupPosition
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.common.groupShape
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.ui.theme.ExpressivePillShape

@Composable
private fun shimmerBrush(): Brush {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlight = androidx.compose.ui.graphics.lerp(base, MaterialTheme.colorScheme.primary, 0.22f)
    val shimmerColors = listOf(
        base.copy(alpha = 0.35f),
        highlight.copy(alpha = 0.85f),
        base.copy(alpha = 0.35f),
    )
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = -400f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 2200
                -400f at 0 using LinearEasing
                -400f at 500 using LinearEasing
                1200f at 2000 using LinearEasing
                1200f at 2200
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerAnim",
    )
    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 300f, translateAnim - 300f),
        end = Offset(translateAnim, translateAnim),
    )
}

@Composable
fun DiscoverScreen(onBack: () -> Unit = {}, viewModel: DiscoverViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val musicPlayer = LocalMusicPlayer.current
    val playbackState by musicPlayer.chromeState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var menuTrack by remember { mutableStateOf<GeneratedTrack?>(null) }
    val playbackQueue = remember(state.tracks) { state.tracks.map(GeneratedTrack::toPlayableTrack) }
    val playFromDiscover: (GeneratedTrack) -> Unit = { track ->
        val index = state.tracks.indexOfFirst { it.key == track.key }.coerceAtLeast(0)
        musicPlayer.playDiscoverQueue(playbackQueue, index)
    }

    val shouldLoadMore by remember(listState, state.tracks.size) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.tracks.size - 3
        }
    }
    LaunchedEffect(shouldLoadMore, state.tracks.size) {
        if (shouldLoadMore && !state.isLoading && !state.isLoadingMore && state.tracks.isNotEmpty()) viewModel.loadMore()
    }

    Column(Modifier.fillMaxSize()) {
        ExpressiveHeader(
            title = "Discover",
            subtitle = if (state.isYtConnected) "Fresh tracks, powered by Last.fm & YouTube Music" else "Fresh tracks, powered by Last.fm",
            onBack = onBack,
            actions = {
                HeaderActionIcon(Icons.Filled.BookmarkAdd, "Save as playlist", viewModel::saveAsPlaylist)
                HeaderActionIcon(Icons.Filled.Shuffle, "Shuffle Recommendations", viewModel::surpriseMe)
            },
        )

        Box(Modifier.fillMaxSize().safeHorizontalContentPadding()) {
            Crossfade(
                targetState = state.isLoading && state.tracks.isEmpty(),
                animationSpec = tween(ExpressiveMotion.Standard, easing = FastOutSlowInEasing),
                label = "discoverState",
            ) { isLoading ->
                if (isLoading) {
                    val shimmer = shimmerBrush()
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 16.dp,
                            bottom = 24.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
                        ),
                        verticalArrangement = Arrangement.spacedBy(GroupGap),
                    ) {
                        items(10) { index ->
                            SkeletonCard(
                                brush = shimmer,
                                position = when (index) {
                                    0 -> GroupPosition.TOP
                                    9 -> GroupPosition.BOTTOM
                                    else -> GroupPosition.MIDDLE
                                },
                                titleWidthFraction = listOf(0.62f, 0.48f, 0.7f, 0.55f)[index % 4],
                                subtitleWidthFraction = listOf(0.4f, 0.3f, 0.45f, 0.35f)[index % 4],
                            )
                        }
                    }
                } else when {
                    state.error != null && state.tracks.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Something went wrong", style = MaterialTheme.typography.titleMedium)
                            Text(state.error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = viewModel::loadInitial) { Text("Retry") }
                        }
                    }
                    state.tracks.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No recommendations yet", style = MaterialTheme.typography.titleMedium)
                            Text("Listen to more music on Last.fm, then pull to refresh.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    else -> LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = 24.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding()
                        ),
                        verticalArrangement = Arrangement.spacedBy(GroupGap),
                        modifier = Modifier,
                    ) {
                        itemsIndexed(
                            state.tracks,
                            key = { _, t -> t.key },
                            contentType = { _, _ -> "discover_track" },
                        ) { index, track ->
                            val position = if (index == 0) GroupPosition.TOP else GroupPosition.MIDDLE
                            val isPlayingThisSong = playbackState.isPlaying &&
                                playbackState.current?.title.equals(track.name, ignoreCase = true) &&
                                playbackState.current?.artist.equals(track.artist, ignoreCase = true)

                            DiscoverCard(
                                track = track,
                                position = position,
                                isPlaying = isPlayingThisSong,
                                onPlay = { playFromDiscover(track) },
                                onMenu = { menuTrack = track },
                                modifier = Modifier,
                            )
                        }
                        if (state.isLoadingMore) {
                            item(key = "loading_more", contentType = "loading") {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator()
                                }
                            }
                        }
                    }
                }
            }
        }

        state.saveResultMessage?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(3000)
                viewModel.dismissSaveResult()
            }
            Surface(shape = ExpressivePillShape, color = MaterialTheme.colorScheme.inverseSurface, modifier = Modifier.padding(16.dp)) {
                Text(msg, color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }

    menuTrack?.let { track ->
        TrackContextMenuSheet(
            target = TrackMenuTarget.Track(track.name, track.artist, track.url),
            capabilities = TrackMenuCapabilities(showCopyActions = true, showDeleteScrobble = true),
            onPlayInLastWave = { playFromDiscover(track) },
            onDismiss = { menuTrack = null },
        )
    }
}

@Composable
private fun DiscoverCard(
    track: GeneratedTrack,
    position: GroupPosition,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    com.lastwave.app.ui.common.ExpressiveGroupTrackRow(
        title = track.name,
        subtitle = track.artist,
        position = position,
        isPlaying = isPlaying,
        onClick = onPlay,
        modifier = modifier,
        leading = {
            Box(modifier = Modifier.size(52.dp)) {
                ArtworkImage(
                    name = track.name,
                    artist = track.artist,
                    embeddedUrl = track.artworkUrl,
                    fallbackIcon = if (isPlaying) Icons.Filled.GraphicEq else Icons.Filled.MusicNote,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                )
                if (isPlaying) {
                    com.lastwave.app.ui.player.PlayingWaveBars(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp),
                    )
                }
            }
        },
        trailing = { com.lastwave.app.ui.common.OverflowMenuButton(onClick = onMenu) },
    )
}

private fun GeneratedTrack.toPlayableTrack() = PlayableTrack(
    title = name,
    artist = artist,
    album = album,
    artworkUrl = artworkUrl,
)

@Composable
private fun SkeletonCard(
    brush: Brush,
    position: GroupPosition = GroupPosition.SINGLE,
    titleWidthFraction: Float = 0.6f,
    subtitleWidthFraction: Float = 0.4f,
) {
    Card(
        shape = groupShape(position),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(brush))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth(titleWidthFraction).height(16.dp).clip(RoundedCornerShape(8.dp)).background(brush))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(subtitleWidthFraction).height(12.dp).clip(RoundedCornerShape(6.dp)).background(brush))
            }
        }
    }
}
