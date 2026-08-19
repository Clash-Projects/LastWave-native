package com.lastwave.app.ui.genres

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance

/**
 * Faithful port of genres.js (§5): bar-chart genre list with a period
 * filter, and a Genre Detail bottom sheet (§5.3) with Start Mix / Discover
 * More actions and an infinite-scrolling, sortable track list reusing the
 * shared track context menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenresScreen(
    onBack: () -> Unit = {},
    onNavigateToPlaylist: () -> Unit = {},
    viewModel: GenresViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.navigateToPlaylist) {
        if (state.navigateToPlaylist) {
            onNavigateToPlaylist()
            viewModel.consumeNavigateToPlaylist()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ExpressiveHeader(
                title = "Your Genres",
                subtitle = "Based on your listening history",
                onBack = onBack,
                actions = { PeriodDropdown(state.period, viewModel::setPeriod) },
            )

            when {
                state.isLoading -> Box(
                    Modifier.fillMaxSize().safeHorizontalContentPadding(),
                    contentAlignment = Alignment.Center,
                ) { com.lastwave.app.ui.common.ExpressiveLoadingIndicator() }
                state.stats.isEmpty() -> Box(
                    Modifier.fillMaxSize().safeHorizontalContentPadding(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No genre data yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 24.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize().safeHorizontalContentPadding(),
                ) {
                    items(state.stats, key = { it.name }) { stat ->
                        GenreBarRow(
                            name = stat.name,
                            percent = stat.percentOfTop,
                            onClick = { viewModel.openDetail(stat.name) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    state.detailGenre?.let { genre ->
        GenreDetailSheet(
            genre = genre,
            state = state,
            onDismiss = viewModel::closeDetail,
            onSortChange = viewModel::setDetailSort,
            onLoadMore = { viewModel.loadDetailPage() },
            onStartMix = { viewModel.startMix(genre) },
            onDiscoverMore = { viewModel.discoverMore(genre) },
            onExploreGenre = { viewModel.exploreGenre(it) },
        )
    }
}


@Composable
private fun PeriodDropdown(period: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = GENRE_PERIODS.firstOrNull { it.first == period }?.second ?: "Overall"
    Box {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.clickable { expanded = true },
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GENRE_PERIODS.forEach { (value, text) ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(text) }, onClick = { onChange(value); expanded = false })
            }
        }
    }
}

@Composable
private fun GenreBarRow(
    name: String,
    percent: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(targetValue = percent.coerceIn(0.03f, 1f), animationSpec = tween(600), label = "genreBar")
    Column(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text("${(percent * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(
                Modifier
                    .fillMaxWidth(animated)
                    .height(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenreDetailSheet(
    genre: String,
    state: GenresUiState,
    onDismiss: () -> Unit,
    onSortChange: (GenreDetailSort) -> Unit,
    onLoadMore: () -> Unit,
    onStartMix: () -> Unit,
    onDiscoverMore: () -> Unit,
    onExploreGenre: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var menuTrack by remember { mutableStateOf<GeneratedTrack?>(null) }
    val listState = rememberLazyListState()

    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.detailTracks.size - 5
        }
    }
    LaunchedEffect(shouldLoadMore, state.detailTracks.size) {
        if (shouldLoadMore && !state.detailLoading && state.detailHasMore) onLoadMore()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(genre.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleLarge)
                    Text("Your Tracks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SortPill(state.detailSort, onSortChange)
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.Button(onClick = onStartMix, modifier = Modifier.weight(1f)) { Text("Start Mix") }
                androidx.compose.material3.OutlinedButton(onClick = onDiscoverMore, modifier = Modifier.weight(1f)) { Text("Discover More") }
            }

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(bottom = 12.dp)) {
                items(state.detailTracks, key = { it.key }) { track ->
                    GenreTrackRow(
                        track,
                        onMenu = { menuTrack = track },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (state.detailLoading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator()
                        }
                    }
                }
                if (!state.detailLoading && state.detailTracks.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No tracks found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    menuTrack?.let { track ->
        TrackContextMenuSheet(
            target = TrackMenuTarget.Track(track.name, track.artist, track.url),
            capabilities = TrackMenuCapabilities(showCopyActions = true, showDeleteScrobble = true),
            onDismiss = { menuTrack = null },
            onExploreGenre = onExploreGenre,
        )
    }
}

@Composable
private fun SortPill(current: GenreDetailSort, onChange: (GenreDetailSort) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = current == GenreDetailSort.POPULAR, onClick = { onChange(GenreDetailSort.POPULAR) }, label = { Text("Popular") })
        FilterChip(selected = current == GenreDetailSort.NEWEST, onClick = { onChange(GenreDetailSort.NEWEST) }, label = { Text("Newest") })
        FilterChip(selected = current == GenreDetailSort.AZ, onClick = { onChange(GenreDetailSort.AZ) }, label = { Text("A-Z") })
    }
}

@Composable
private fun GenreTrackRow(
    track: GeneratedTrack,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val musicPlayer = com.lastwave.app.ui.player.LocalMusicPlayer.current
    Row(
        modifier.fillMaxWidth()
            .clickable { musicPlayer.play(com.lastwave.app.playback.PlayableTrack(track.name, track.artist, album = track.album, artworkUrl = track.artworkUrl)) }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(name = track.name, artist = track.artist, embeddedUrl = track.artworkUrl, fallbackIcon = Icons.Filled.MusicNote, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        com.lastwave.app.ui.common.OverflowMenuButton(onClick = onMenu)
    }
}
