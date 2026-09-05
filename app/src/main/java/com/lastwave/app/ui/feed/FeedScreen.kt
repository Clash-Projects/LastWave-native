package com.lastwave.app.ui.feed

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lastwave.app.data.feed.FeedAlbum
import com.lastwave.app.data.feed.FeedArtist
import com.lastwave.app.data.feed.FeedQuickTile
import com.lastwave.app.data.feed.FeedSpotlight
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.model.FriendEntry
import com.lastwave.app.data.model.RecentTrack
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.common.ExpressiveLoadingIndicator
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.navigation.ArtistAlbumNavigator
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.ui.player.PlayingWaveBars
import com.lastwave.app.ui.shell.FloatingNavDefaults
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.liquidGlassChrome

private enum class FeedFilter(val label: String) {
    FOR_YOU("For you"), SONGS("Songs"), PLAYLISTS("Playlists")
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenFeedPlaylist: (String) -> Unit,
    onOpenPlaylist: (Long) -> Unit = {},
    onOpenGenerator: () -> Unit = {},
    viewModel: FeedViewModel = hiltViewModel(),
    artistAlbumNavigator: ArtistAlbumNavigator = hiltViewModel<ArtistAlbumNavBridgeFeed>().navigator,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val musicPlayer = LocalMusicPlayer.current
    val playbackState by musicPlayer.chromeState.collectAsStateWithLifecycle()
    var selectedFilter by rememberSaveable { mutableStateOf(FeedFilter.FOR_YOU) }
    var menuTrack by remember { mutableStateOf<YouTubeMusicTrack?>(null) }
    val showSongs = selectedFilter != FeedFilter.PLAYLISTS
    val showPlaylists = selectedFilter != FeedFilter.SONGS
    val showHighlights = selectedFilter == FeedFilter.FOR_YOU
    val snackbarHostState = remember { SnackbarHostState() }
    val hasFeedContent = with(state.feedData) {
        quickTiles.isNotEmpty() || mixes.isNotEmpty() || topArtists.isNotEmpty() ||
            quickPicks.isNotEmpty() || jumpBackIn.isNotEmpty() || recentAlbums.isNotEmpty() ||
            heavyRotation.isNotEmpty() || ytLikedSongs.isNotEmpty() || ytRecentSongs.isNotEmpty() ||
            (becauseYouListenTo?.items?.isNotEmpty() == true) || spotlight != null ||
            charts.isNotEmpty() || newReleases.isNotEmpty() || friends.isNotEmpty()
    }
    val hasFilteredContent = with(state.feedData) {
        when (selectedFilter) {
            FeedFilter.FOR_YOU -> hasFeedContent
            FeedFilter.SONGS -> quickPicks.isNotEmpty() || jumpBackIn.isNotEmpty() || heavyRotation.isNotEmpty() ||
                charts.isNotEmpty() || ytLikedSongs.isNotEmpty() || ytRecentSongs.isNotEmpty()
            FeedFilter.PLAYLISTS -> mixes.isNotEmpty()
        }
    }
    LaunchedEffect(state.error, hasFeedContent) {
        val error = state.error ?: return@LaunchedEffect
        if (hasFeedContent) {
            val result = snackbarHostState.showSnackbar(error, actionLabel = "Retry", withDismissAction = true)
            viewModel.dismissError()
            if (result == SnackbarResult.ActionPerformed) viewModel.refresh()
        }
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
            ExpressiveHeader(
                title = "LastWave",
                actions = {
                    HeaderActionIcon(Icons.Filled.Explore, "Discover Radar", onOpenDiscover)
                    HeaderActionIcon(Icons.Filled.Search, "Search", onOpenSearch)
                    HeaderActionIcon(Icons.Filled.Settings, "Settings", onOpenSettings)
                },
            )

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ExpressiveLoadingIndicator()
                }
            } else if (!hasFeedContent) {
                FeedEmptyState(
                    message = if (state.error != null) {
                        "We couldn't load your recommendations. Try again, or find something in search."
                    } else {
                        "Search for a favorite or explore something new. Your music starts here."
                    },
                    onRetry = viewModel::loadFeed,
                    onOpenSearch = onOpenSearch,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().safeHorizontalContentPadding(),
                    contentPadding = PaddingValues(
                        bottom = FloatingNavDefaults.contentBottomPadding(),
                        top = 10.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    item(key = "welcome_and_filter") {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            FeedWelcome(onOpenGenerator = onOpenGenerator)
                            FeedFilterPills(
                                selectedFilter = selectedFilter,
                                onSelectFilter = { selectedFilter = it },
                            )
                        }
                    }

                    if (!hasFilteredContent) {
                        item(key = "filter_empty") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp, horizontal = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "Nothing here yet",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(6.dp))
                                TextButton(onClick = onOpenSearch) {
                                    Text("Find music", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }

                    if (showHighlights && state.feedData.quickTiles.isNotEmpty()) {
                        item(key = "quick_tiles") {
                            QuickTilesGrid(
                                tiles = state.feedData.quickTiles,
                                onTileClick = { tile ->
                                    when {
                                        tile.localPlaylistId != null -> onOpenPlaylist(tile.localPlaylistId)
                                        tile.playlistId != null -> onOpenFeedPlaylist(tile.playlistId)
                                        else -> viewModel.handleQuickTileClick(tile)
                                    }
                                },
                            )
                        }
                    }

                    if (showSongs && state.feedData.quickPicks.isNotEmpty()) {
                        item(key = "quick_picks") {
                            val title = if (state.feedData.hasYtRecommendations) "Picked for you" else "Quick picks"
                            val subtitle = if (state.feedData.hasYtRecommendations) {
                                "From your YouTube Music home"
                            } else {
                                "Songs worth playing now"
                            }
                            FeedSectionHeader(
                                title = title,
                                subtitle = subtitle,
                                actionText = "Play all",
                                actionIcon = Icons.Filled.PlayArrow,
                                onActionClick = { viewModel.playTracksQueue(state.feedData.quickPicks, 0, "Quick Picks") },
                            )
                            QuickPicksRows(
                                tracks = state.feedData.quickPicks,
                                currentPlayingVideoId = playbackState.current?.videoId,
                                isPlaying = playbackState.isPlaying,
                                onTrackClick = { index -> viewModel.playTracksQueue(state.feedData.quickPicks, index, "Quick Picks") },
                                onMenuClick = { menuTrack = it },
                            )
                        }
                    }

                    if (showSongs && state.feedData.isYtConnected && state.feedData.ytLikedSongs.isNotEmpty()) {
                        item(key = "yt_liked_songs") {
                            FeedSectionHeader(
                                title = "Liked on YouTube",
                                subtitle = "Favorites from your YouTube Music library",
                                actionText = "Play all",
                                actionIcon = Icons.Filled.PlayArrow,
                                onActionClick = { viewModel.playTracksQueue(state.feedData.ytLikedSongs, 0, "YouTube Liked") },
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                itemsIndexed(state.feedData.ytLikedSongs) { index, track ->
                                    SongTrackCard(
                                        track = track,
                                        onClick = { viewModel.playTracksQueue(state.feedData.ytLikedSongs, index, "YouTube Liked") },
                                    )
                                }
                            }
                        }
                    }

                    if (showSongs && state.feedData.isYtConnected && state.feedData.ytRecentSongs.isNotEmpty()) {
                        item(key = "yt_recent_songs") {
                            FeedSectionHeader(
                                title = "Recently on YouTube Music",
                                subtitle = "Pick up where you left off",
                                actionText = "Play all",
                                actionIcon = Icons.Filled.PlayArrow,
                                onActionClick = { viewModel.playTracksQueue(state.feedData.ytRecentSongs, 0, "YouTube History") },
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                itemsIndexed(state.feedData.ytRecentSongs) { index, track ->
                                    SongTrackCard(
                                        track = track,
                                        onClick = { viewModel.playTracksQueue(state.feedData.ytRecentSongs, index, "YouTube History") },
                                    )
                                }
                            }
                        }
                    }

                    state.feedData.becauseYouListenTo?.takeIf { showSongs && it.items.isNotEmpty() }?.let { section ->
                        item(key = "because_you_listen_to") {
                            FeedSectionHeader(
                                title = section.title,
                                subtitle = section.subtitle,
                                actionText = "Play all",
                                actionIcon = Icons.Filled.PlayArrow,
                                onActionClick = { viewModel.playTracksQueue(section.items, 0, section.title) },
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                itemsIndexed(section.items) { index, track ->
                                    SongTrackCard(
                                        track = track,
                                        onClick = { viewModel.playTracksQueue(section.items, index, section.title) },
                                    )
                                }
                            }
                        }
                    }

                    if (showSongs && state.feedData.jumpBackIn.isNotEmpty()) {
                        item(key = "jump_back_in") {
                            FeedSectionHeader(
                                title = "Jump back in",
                                subtitle = "From your listening history",
                                actionText = "Play all",
                                actionIcon = Icons.Filled.PlayArrow,
                                onActionClick = { viewModel.playRecentQueue(state.feedData.jumpBackIn, 0) },
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                itemsIndexed(state.feedData.jumpBackIn) { index, track ->
                                    RecentTrackCard(
                                        track = track,
                                        onClick = { viewModel.playRecentQueue(state.feedData.jumpBackIn, index) },
                                    )
                                }
                            }
                        }
                    }

                    if (showPlaylists && state.feedData.mixes.isNotEmpty()) {
                        item(key = "mixed_for_you") {
                            FeedSectionHeader(
                                title = "Mixes to explore",
                                subtitle = "Familiar favorites, fresh combinations",
                                actionText = "Shuffle",
                                actionIcon = Icons.Filled.Shuffle,
                                onActionClick = {
                                    state.feedData.mixes.randomOrNull()?.let(viewModel::playPlaylistSummary)
                                },
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                items(state.feedData.mixes, key = YouTubePlaylistSummary::id) { summary ->
                                    PlaylistSummaryCard(
                                        summary = summary,
                                        onClick = { onOpenFeedPlaylist(summary.id) },
                                    )
                                }
                            }
                        }
                    }

                    state.feedData.spotlight?.takeIf { showHighlights }?.let { spotlight ->
                        item(key = "spotlight_hero") {
                            SpotlightHeroCard(
                                spotlight = spotlight,
                                onPlayRadio = {
                                    viewModel.playArtistRadio(FeedArtist(spotlight.artistName, spotlight.browseId, spotlight.artworkUrl))
                                },
                                onOpenArtist = {
                                    artistAlbumNavigator.openArtist(spotlight.artistName, spotlight.browseId ?: "")
                                },
                            )
                        }
                    }

                    if (showHighlights && state.feedData.topArtists.isNotEmpty()) {
                        item(key = "top_artists") {
                            FeedSectionHeader(
                                title = "Artists for you",
                                subtitle = "Worth another listen",
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                items(state.feedData.topArtists) { artist ->
                                    ArtistAvatarCard(
                                        artist = artist,
                                        onClick = {
                                            artistAlbumNavigator.openArtist(
                                                name = artist.name,
                                                browseId = artist.browseId ?: "",
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }

                    if (showSongs && state.feedData.heavyRotation.isNotEmpty()) {
                        item(key = "heavy_rotation") {
                            FeedSectionHeader(
                                title = "Favorites to revisit",
                                subtitle = "From your listening profile",
                                actionText = "Play all",
                                actionIcon = Icons.Filled.PlayArrow,
                                onActionClick = { viewModel.playGeneratedQueue(state.feedData.heavyRotation, 0, "Heavy Rotation") },
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                itemsIndexed(state.feedData.heavyRotation) { index, track ->
                                    GeneratedTrackCard(
                                        track = track,
                                        onClick = { viewModel.playGeneratedQueue(state.feedData.heavyRotation, index, "Heavy Rotation") },
                                    )
                                }
                            }
                        }
                    }

                    if (showHighlights && state.feedData.recentAlbums.isNotEmpty()) {
                        item(key = "albums_in_rotation") {
                            FeedSectionHeader(
                                title = "Albums for you",
                                subtitle = "From your listening and recommendations",
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                items(state.feedData.recentAlbums) { album ->
                                    FeedAlbumCard(
                                        album = album,
                                        onClick = {
                                            artistAlbumNavigator.openAlbum(
                                                title = album.title,
                                                artist = album.artist,
                                                browseId = album.browseId ?: "",
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }

                    if (showSongs && state.feedData.charts.isNotEmpty()) {
                        item(key = "trending_charts") {
                            FeedSectionHeader(
                                title = "Trending now",
                                subtitle = "Most popular right now",
                                actionText = "Play all",
                                actionIcon = Icons.Filled.PlayArrow,
                                onActionClick = { viewModel.playTracksQueue(state.feedData.charts, 0, "Top Charts") },
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                itemsIndexed(state.feedData.charts.take(15)) { index, track ->
                                    ChartTrackCard(
                                        rank = index + 1,
                                        track = track,
                                        onClick = { viewModel.playTracksQueue(state.feedData.charts, index, "Top Charts") },
                                    )
                                }
                            }
                        }
                    }

                    if (showHighlights && state.feedData.newReleases.isNotEmpty()) {
                        item(key = "new_releases") {
                            FeedSectionHeader(
                                title = "New releases",
                                subtitle = "Fresh drops and new albums",
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                items(state.feedData.newReleases) { summary ->
                                    AlbumReleaseCard(
                                        summary = summary,
                                        onClick = {
                                            artistAlbumNavigator.openAlbum(
                                                title = summary.title,
                                                artist = summary.author ?: "",
                                                browseId = summary.id,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }

                    if (showHighlights && state.feedData.friends.isNotEmpty()) {
                        item(key = "friends_activity") {
                            FeedSectionHeader(
                                title = "Your friends",
                                subtitle = "People in your listening circle",
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                items(state.feedData.friends) { friend ->
                                    FriendAvatarCard(friend = friend)
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
                .safeHorizontalContentPadding()
                .padding(bottom = FloatingNavDefaults.contentBottomPadding()),
        )
    }
    menuTrack?.let { track ->
        TrackContextMenuSheet(
            target = TrackMenuTarget.Track(track.title, track.artist, ""),
            capabilities = TrackMenuCapabilities(showCopyActions = false, showDeleteScrobble = false),
            playableTrack = PlayableTrack(
                title = track.title,
                artist = track.artist,
                album = track.album,
                artworkUrl = track.artworkUrl,
                videoId = track.videoId.takeIf(String::isNotBlank),
            ),
            playbackSourceLabel = "Home",
            onDismiss = { menuTrack = null },
        )
    }
}

@Composable
private fun FeedWelcome(
    onOpenGenerator: () -> Unit,
) {
    val greeting = remember {
        when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            in 18..22 -> "Good evening"
            else -> "Good night"
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, letterSpacing = (-0.3).sp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "A soundtrack curated for your day",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
        Surface(
            onClick = onOpenGenerator,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.75f),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    "Mix studio",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun FeedFilterPills(
    selectedFilter: FeedFilter,
    onSelectFilter: (FeedFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FeedFilter.entries.forEach { filter ->
            val selected = selectedFilter == filter
            val animContainer by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                animationSpec = tween(durationMillis = 200),
                label = "filterContainer",
            )
            val animContent by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(durationMillis = 200),
                label = "filterContent",
            )
            Surface(
                onClick = { onSelectFilter(filter) },
                shape = CircleShape,
                color = animContainer,
                border = if (!selected) BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)) else null,
            ) {
                Text(
                    text = filter.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = animContent,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun QuickTilesGrid(
    tiles: List<FeedQuickTile>,
    onTileClick: (FeedQuickTile) -> Unit,
) {
    val rows = remember(tiles) { tiles.take(6).chunked(2) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pair.forEach { tile ->
                    QuickTileCard(
                        tile = tile,
                        modifier = Modifier.weight(1f),
                        onClick = { onTileClick(tile) },
                    )
                }
                if (pair.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun QuickTileCard(tile: FeedQuickTile, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val liquidGlass = LocalLiquidGlass.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
        modifier = modifier
            .height(56.dp)
            .liquidGlassChrome(RoundedCornerShape(12.dp), liquidGlass),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .background(
                        if (tile.isLiked) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (tile.isLiked) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                } else if (!tile.artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = tile.artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = tile.title,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp, start = 2.dp),
            )
        }
    }
}

@Composable
private fun QuickPicksRows(
    tracks: List<YouTubeMusicTrack>,
    currentPlayingVideoId: String?,
    isPlaying: Boolean,
    onTrackClick: (Int) -> Unit,
    onMenuClick: (YouTubeMusicTrack) -> Unit,
) {
    val columns = remember(tracks) { tracks.chunked(3) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(top = 12.dp),
    ) {
        itemsIndexed(columns) { columnIndex, column ->
            Column(
                modifier = Modifier.fillParentMaxWidth(0.86f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                column.forEachIndexed { rowIndex, track ->
                    val overallIndex = columnIndex * 3 + rowIndex
                    val isCurrent = track.videoId.isNotBlank() && track.videoId == currentPlayingVideoId
                    Surface(
                        onClick = { onTrackClick(overallIndex) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
                        border = if (isCurrent) BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)) else null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(modifier = Modifier.size(48.dp)) {
                                ArtworkImage(
                                    name = track.title,
                                    artist = track.artist,
                                    embeddedUrl = track.artworkUrl,
                                    fallbackIcon = Icons.Filled.MusicNote,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                                )
                                if (isCurrent && isPlaying) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color.Black.copy(alpha = 0.45f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        PlayingWaveBars(modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    track.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(start = 2.dp),
                                )
                                Text(
                                    track.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 2.dp),
                                )
                            }
                            IconButton(
                                onClick = { onMenuClick(track) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "More options for ${track.title}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongTrackCard(
    track: YouTubeMusicTrack,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(142.dp)
            .clickable(onClick = onClick),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)),
            modifier = Modifier.size(142.dp),
        ) {
            if (!track.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = track.artworkUrl,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun PlaylistSummaryCard(
    summary: YouTubePlaylistSummary,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(142.dp)
            .clickable(onClick = onClick),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)),
            modifier = Modifier.size(142.dp),
        ) {
            if (!summary.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = summary.artworkUrl,
                    contentDescription = summary.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Album,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = summary.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = summary.author ?: summary.trackCountText ?: "Mix",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun RecentTrackCard(
    track: RecentTrack,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(142.dp)
            .clickable(onClick = onClick),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)),
            modifier = Modifier.size(142.dp),
        ) {
            ArtworkImage(
                name = track.name,
                artist = track.artist.displayName,
                embeddedUrl = track.artworkUrl,
                fallbackIcon = Icons.Filled.MusicNote,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = track.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = track.artist.displayName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun FeedAlbumCard(
    album: FeedAlbum,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(142.dp)
            .clickable(onClick = onClick),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)),
            modifier = Modifier.size(142.dp),
        ) {
            if (!album.artworkUrl.isNullOrBlank()) {
                ArtworkImage(
                    name = album.title,
                    artist = album.artist,
                    embeddedUrl = album.artworkUrl,
                    fallbackIcon = Icons.Filled.Album,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Album,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = album.artist,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun GeneratedTrackCard(
    track: GeneratedTrack,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(142.dp)
            .clickable(onClick = onClick),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)),
            modifier = Modifier.size(142.dp),
        ) {
            ArtworkImage(
                name = track.name,
                artist = track.artist,
                embeddedUrl = track.artworkUrl,
                fallbackIcon = Icons.Filled.MusicNote,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = track.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun AlbumReleaseCard(
    summary: YouTubePlaylistSummary,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(142.dp)
            .clickable(onClick = onClick),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)),
            modifier = Modifier.size(142.dp),
        ) {
            if (!summary.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = summary.artworkUrl,
                    contentDescription = summary.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Album,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = summary.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = summary.author ?: "Album",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ChartTrackCard(
    rank: Int,
    track: YouTubeMusicTrack,
    onClick: () -> Unit,
) {
    val isTop3 = rank <= 3
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f),
        border = BorderStroke(
            0.5.dp,
            if (isTop3) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f),
        ),
        modifier = Modifier
            .width(280.dp)
            .height(72.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Box(
                modifier = Modifier.width(32.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "$rank",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isTop3) FontWeight.ExtraBold else FontWeight.Medium,
                    color = if (isTop3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(52.dp),
            ) {
                if (!track.artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = track.artworkUrl,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 2.dp),
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SpotlightHeroCard(
    spotlight: FeedSpotlight,
    onPlayRadio: () -> Unit,
    onOpenArtist: () -> Unit,
) {
    val liquidGlass = LocalLiquidGlass.current
    val gradientBrush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
        ),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    )
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .liquidGlassChrome(RoundedCornerShape(24.dp), liquidGlass),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush)
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            "ARTIST SPOTLIGHT",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Surface(
                        onClick = onOpenArtist,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        modifier = Modifier.size(76.dp),
                    ) {
                        if (!spotlight.artworkUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = spotlight.artworkUrl,
                                contentDescription = "Open ${spotlight.artistName}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    spotlight.artistName.take(1).uppercase(),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            spotlight.artistName,
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                        spotlight.topTrackTitle?.takeIf(String::isNotBlank)?.let { title ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    Icons.Filled.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 2.dp),
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onPlayRadio,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f).height(42.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Artist radio", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                    Surface(
                        onClick = onOpenArtist,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                        modifier = Modifier.weight(1f).height(42.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "View artist",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistAvatarCard(
    artist: FeedArtist,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(96.dp)
            .clickable(onClick = onClick),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
            modifier = Modifier.size(88.dp),
        ) {
            if (!artist.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = artist.artworkUrl,
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = artist.name.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun FriendAvatarCard(friend: FriendEntry) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(80.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
            modifier = Modifier.size(60.dp),
        ) {
            if (!friend.avatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = friend.avatarUrl,
                    contentDescription = friend.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = friend.displayName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = friend.displayName,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun FeedSectionHeader(
    title: String,
    subtitle: String? = null,
    actionText: String? = null,
    actionIcon: ImageVector? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, letterSpacing = (-0.3).sp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .semantics { heading() }
                    .padding(start = 2.dp),
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
        if (actionText != null && onActionClick != null) {
            Surface(
                onClick = onActionClick,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.75f),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    actionIcon?.let { icon ->
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    Text(
                        actionText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedEmptyState(
    message: String,
    onRetry: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeHorizontalContentPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = FloatingNavDefaults.contentBottomPadding())
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            modifier = Modifier.size(72.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Find your next favorite",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onOpenSearch,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Search music", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            Surface(
                onClick = onRetry,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Try again",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@dagger.hilt.android.lifecycle.HiltViewModel
class ArtistAlbumNavBridgeFeed @javax.inject.Inject constructor(val navigator: ArtistAlbumNavigator) : androidx.lifecycle.ViewModel()
