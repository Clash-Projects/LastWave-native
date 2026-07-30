package com.lastwave.app.ui.discover

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.theme.ExpressivePillShape

@Composable
private fun shimmerBrush(): Brush {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.2f),
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
    )
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerAnim",
    )
    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, translateAnim - 200f),
        end = Offset(translateAnim, translateAnim),
    )
}

@Composable
fun DiscoverScreen(onBack: () -> Unit = {}, viewModel: DiscoverViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    var menuTrack by remember { mutableStateOf<GeneratedTrack?>(null) }

    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.tracks.size - 3
        }
    }
    LaunchedEffect(shouldLoadMore, state.tracks.size) {
        if (shouldLoadMore && !state.isLoading && !state.isLoadingMore && state.tracks.isNotEmpty()) viewModel.loadMore()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                Text("Discover", style = MaterialTheme.typography.headlineSmall)
                Text("Fresh tracks, powered by Last.fm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = viewModel::saveAsPlaylist) { Icon(Icons.Filled.BookmarkAdd, contentDescription = "Save as playlist") }
            IconButton(onClick = viewModel::surpriseMe) { Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle Recommendations") }
        }

        Box(Modifier.fillMaxSize()) {
            val shimmer = shimmerBrush()
            Crossfade(targetState = state.isLoading && state.tracks.isEmpty(), label = "discoverState") { isLoading ->
                if (isLoading) {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(10) { SkeletonCard(brush = shimmer) }
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
                            bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.tracks, key = { it.key }) { track ->
                            DiscoverCard(track = track, onMenu = { menuTrack = track })
                        }
                        if (state.isLoadingMore) {
                            item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) } }
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
            onDismiss = { menuTrack = null },
        )
    }
}

@Composable
private fun DiscoverCard(track: GeneratedTrack, onMenu: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val q = java.net.URLEncoder.encode("${track.name} ${track.artist}", "UTF-8")
                try {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=$q")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) { }
            },
    ) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ArtworkImage(name = track.name, artist = track.artist, embeddedUrl = track.artworkUrl, fallbackIcon = Icons.Filled.MusicNote, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onMenu) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
        }
    }
}

@Composable
private fun SkeletonCard(brush: Brush) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(brush))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth(0.6f).height(16.dp).clip(RoundedCornerShape(4.dp)).background(brush))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(0.4f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(brush))
            }
        }
    }
}
