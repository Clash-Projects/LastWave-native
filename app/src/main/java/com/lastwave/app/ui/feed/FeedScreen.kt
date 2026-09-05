package com.lastwave.app.ui.feed

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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.common.ExpressiveLoadingIndicator
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.navigation.ArtistAlbumNavigator
import com.lastwave.app.ui.shell.FloatingNavDefaults
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.liquidGlassChrome

private enum class FeedFilter(val label: String) {
    FOR_YOU("For you"), SONGS("Songs"), PLAYLISTS("Playlists")
}

private data class FeedVibe(
    val title: String,
    val subtitle: String,
    val query: String,
    val icon: ImageVector,
)

private val FeedVibes = listOf(
    FeedVibe("Night drive", "Synths & after hours", "synthwave night drive music", Icons.Filled.DarkMode),
    FeedVibe("Feel good", "A little lift", "main character energy songs", Icons.Filled.AutoAwesome),
    FeedVibe("High energy", "Turn it up", "hyperpop alternative electronic music", Icons.Filled.Bolt),
    FeedVibe("Slow down", "Ambient & dream pop", "ambient dream pop chill music", Icons.Filled.GraphicEq),
)

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
            charts.isNotEmpty() || newReleases.isNotEmpty() || friends.isNotEmpty() || ytSuggestedPlaylists.isNotEmpty()
    }
    val hasFilteredContent = with(state.feedData) {
        when (selectedFilter) {
            FeedFilter.FOR_YOU -> hasFeedContent
            FeedFilter.SONGS -> quickPicks.isNotEmpty() || jumpBackIn.isNotEmpty() || heavyRotation.isNotEmpty() ||
                ytLikedSongs.isNotEmpty() || ytRecentSongs.isNotEmpty() || charts.isNotEmpty()
            FeedFilter.PLAYLISTS -> mixes.isNotEmpty() || ytSuggestedPlaylists.isNotEmpty()
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
            FeedHeader(onOpenSearch = onOpenSearch, onOpenSettings = onOpenSettings)

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
                        top = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    item(key = "welcome") {
                        FeedWelcome(
                            onOpenDiscover = onOpenDiscover,
                            onOpenGenerator = onOpenGenerator,
                            onOpenSettings = onOpenSettings,
                            ytAccountName = state.feedData.ytAccountName,
                            hasYtTaste = state.feedData.hasYtRecommendations || state.feedData.ytLikedSongs.isNotEmpty() || state.feedData.ytRecentSongs.isNotEmpty(),
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            items(FeedFilter.entries, key = FeedFilter::name) { filter ->
                                FilterChip(
                                    selected = selectedFilter == filter,
                                    onClick = { selectedFilter = filter },
                                    label = { Text(filter.label) },
                                )
                            }
                        }
                    }
                    if (!hasFilteredContent) {
                        item(key = "filter_empty") {
                            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = onOpenSearch) { Text("Find music") }
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
                        item(key = "yt_quick_picks") {
                            FeedSectionHeader(
                                title = if (state.feedData.hasYtRecommendations) "Picked for you" else "Quick picks",
                                subtitle = if (state.feedData.hasYtRecommendations) {
                                    "From your YouTube Music home"
                                } else {
                                    "Songs worth playing now"
                                },
                                actionText = "Play all",
                                actionIcon = Icons.Filled.PlayArrow,
                                onActionClick = { viewModel.playTracksQueue(state.feedData.quickPicks, 0, "Quick Picks") },
                            )
                            QuickPicksRows(
                                tracks = state.feedData.quickPicks,
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
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                    state.feedData.becauseYouListenTo?.takeIf { showHighlights && it.items.isNotEmpty() }?.let { radio ->
                        item(key = "infinite_radio") {
                            DiscoveryRadioHero(
                                title = radio.title,
                                subtitle = radio.subtitle,
                                tracks = radio.items,
                                onPlay = { viewModel.playTracksQueue(radio.items, 0, radio.title) },
                                onTune = onOpenGenerator,
                            )
                        }
                    }
                    if (showHighlights) item(key = "vibe_portals") {
                        VibePortals(
                            launchingTitle = state.launchingRadio,
                            onSelect = { vibe ->
                                viewModel.playDiscoveryQuery(vibe.title, vibe.query)
                            },
                        )
                    }
                    if (showSongs && state.feedData.jumpBackIn.isNotEmpty()) {
                        item(key = "jump_back_in") {
                            FeedSectionHeader(
                                title = "Jump back in",
                                subtitle = "From your Last.fm listening history",
                                actionText = "Play all",
                                actionIcon = Icons.Filled.PlayArrow,
                                onActionClick = { viewModel.playRecentQueue(state.feedData.jumpBackIn, 0) },
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                                title = if (state.feedData.hasYtMixes) "Your mixes & radios" else "Mixes to explore",
                                subtitle = if (state.feedData.hasYtMixes) "From your YouTube Music home" else "Familiar favorites, fresh combinations",
                                actionText = "Shuffle",
                                actionIcon = Icons.Filled.Shuffle,
                                onActionClick = {
                                    state.feedData.mixes.randomOrNull()?.let(viewModel::playPlaylistSummary)
                                },
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
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
                    if (showPlaylists && state.feedData.ytSuggestedPlaylists.isNotEmpty()) {
                        item(key = "yt_suggested_playlists") {
                            FeedSectionHeader(title = "Selected on YouTube Music", subtitle = "Playlists from your home recommendations")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                items(state.feedData.ytSuggestedPlaylists, key = YouTubePlaylistSummary::id) { playlist ->
                                    PlaylistSummaryCard(summary = playlist, onClick = { onOpenFeedPlaylist(playlist.id) })
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
                                contentPadding = PaddingValues(horizontal = 20.dp),
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
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                                contentPadding = PaddingValues(horizontal = 20.dp),
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
                                contentPadding = PaddingValues(horizontal = 20.dp),
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
                            FeedSectionHeader(title = "New releases", subtitle = "Fresh drops and new albums")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
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
                            FeedSectionHeader(title = "Your friends", subtitle = "People in your listening circle")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
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
                videoId = track.videoId,
            ),
            playbackSourceLabel = "Home",
            onDismiss = { menuTrack = null },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FeedHeader(onOpenSearch: () -> Unit, onOpenSettings: () -> Unit) {
    TopAppBar(
        title = { Text("LastWave", fontWeight = FontWeight.SemiBold) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        actions = {
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Filled.Search, contentDescription = "Search music")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        },
    )
}

@Composable
private fun FeedWelcome(
    onOpenDiscover: () -> Unit,
    onOpenGenerator: () -> Unit,
    onOpenSettings: () -> Unit,
    ytAccountName: String?,
    hasYtTaste: Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(
            text = "Listen now",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (hasYtTaste) "Your listening, with YouTube Music in the mix." else "Your favorites. A few new discoveries.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ytAccountName != null) {
            Spacer(Modifier.height(8.dp))
            AssistChip(
                onClick = onOpenSettings,
                label = {
                    Text("YouTube Music · $ytAccountName", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FeedShortcut(
                title = "Discover",
                icon = Icons.Filled.Explore,
                modifier = Modifier.weight(1f),
                onClick = onOpenDiscover,
            )
            FeedShortcut(
                title = "Create a mix",
                icon = Icons.Filled.AutoAwesome,
                modifier = Modifier.weight(1f),
                onClick = onOpenGenerator,
            )
        }
    }
}

@Composable
private fun FeedShortcut(title: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        modifier = modifier.heightIn(min = 56.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun QuickPicksRows(
    tracks: List<YouTubeMusicTrack>,
    onTrackClick: (Int) -> Unit,
    onMenuClick: (YouTubeMusicTrack) -> Unit,
) {
    val columns = remember(tracks) { tracks.chunked(3) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 12.dp),
    ) {
        itemsIndexed(columns) { columnIndex, column ->
            Column(
                modifier = Modifier.fillParentMaxWidth(0.88f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                column.forEachIndexed { rowIndex, track ->
                    Surface(
                        onClick = { onTrackClick(columnIndex * 3 + rowIndex) },
                        shape = MaterialTheme.shapes.medium,
                        color = Color.Transparent,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ArtworkImage(
                                name = track.title,
                                artist = track.artist,
                                embeddedUrl = track.artworkUrl,
                                fallbackIcon = Icons.Filled.MusicNote,
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    track.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    track.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { onMenuClick(track) }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "More options for ${track.title}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
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
private fun DiscoveryRadioHero(
    title: String,
    subtitle: String?,
    tracks: List<YouTubeMusicTrack>,
    onPlay: () -> Unit,
    onTune: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "MADE FOR YOU",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                tracks.firstOrNull()?.let { track ->
                    ArtworkImage(
                        name = track.title,
                        artist = track.artist,
                        embeddedUrl = track.artworkUrl,
                        fallbackIcon = Icons.Filled.MusicNote,
                        modifier = Modifier.size(80.dp).clip(MaterialTheme.shapes.medium),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onPlay, modifier = Modifier.heightIn(min = 48.dp).weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play radio")
                }
                TextButton(onClick = onTune, modifier = Modifier.heightIn(min = 48.dp).weight(1f)) {
                    Text("Customize")
                }
            }
        }
    }
}

@Composable
private fun AlbumReleaseCard(
    summary: YouTubePlaylistSummary,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(144.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(144.dp),
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
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
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
        )
        Text(
            text = summary.author ?: "Album",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            .width(144.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(144.dp),
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
        )
        Text(
            text = track.artist.displayName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SongTrackCard(
    track: YouTubeMusicTrack,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(144.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(144.dp),
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
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
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
        )
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChartTrackCard(
    rank: Int,
    track: YouTubeMusicTrack,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .width(280.dp)
            .heightIn(min = 72.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = "$rank",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (rank <= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
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
                        Icon(Icons.Filled.MusicNote, contentDescription = null)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VibePortals(launchingTitle: String?, onSelect: (FeedVibe) -> Unit) {
    FeedSectionHeader(title = "Set the mood", subtitle = "A soundtrack for right now")
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(top = 12.dp),
    ) {
        items(FeedVibes, key = FeedVibe::title) { vibe ->
            Surface(
                onClick = { onSelect(vibe) },
                enabled = launchingTitle == null,
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.width(176.dp),
            ) {
                Row(
                    modifier = Modifier.heightIn(min = 80.dp).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (launchingTitle == vibe.title) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    } else {
                        Icon(vibe.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(vibe.title, style = MaterialTheme.typography.labelLarge)
                        Text(
                            if (launchingTitle == vibe.title) "Starting radio..." else vibe.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Find your next favorite",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        FilledTonalButton(onClick = onOpenSearch, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.Search, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Search music")
        }
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Try again")
        }
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (actionText != null && onActionClick != null) {
            TextButton(onClick = onActionClick, modifier = Modifier.heightIn(min = 48.dp)) {
                actionIcon?.let { icon ->
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(actionText, style = MaterialTheme.typography.labelLarge)
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
            .padding(horizontal = 20.dp),
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
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.liquidGlassChrome(MaterialTheme.shapes.medium, liquidGlass),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.heightIn(min = 64.dp).padding(8.dp),
        ) {
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (tile.isLiked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (tile.isLiked) {
                    Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                } else if (!tile.artworkUrl.isNullOrBlank()) {
                    AsyncImage(model = tile.artworkUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                tile.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PlaylistSummaryCard(
    summary: YouTubePlaylistSummary,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(144.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.size(144.dp)) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxSize(),
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
                        Icon(Icons.Filled.Album, contentDescription = null, modifier = Modifier.size(48.dp))
                    }
                }
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(30.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = "Open ${summary.title}",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
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
        )
        Text(
            text = summary.author ?: summary.trackCountText ?: "Playlist",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FriendAvatarCard(friend: FriendEntry) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(84.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
        )
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
            .width(104.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(96.dp),
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
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
            .width(144.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(144.dp),
        ) {
            ArtworkImage(
                name = track.name,
                artist = track.artist,
                embeddedUrl = track.artworkUrl,
                fallbackIcon = Icons.Filled.MusicNote,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = track.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpotlightHeroCard(
    spotlight: FeedSpotlight,
    onPlayRadio: () -> Unit,
    onOpenArtist: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    onClick = onOpenArtist,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(72.dp),
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
                            Text(spotlight.artistName.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Artist spotlight", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        spotlight.artistName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    spotlight.topTrackTitle?.takeIf(String::isNotBlank)?.let { title ->
                        Text(
                            title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onPlayRadio, modifier = Modifier.heightIn(min = 48.dp).weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Artist radio")
                }
                TextButton(onClick = onOpenArtist, modifier = Modifier.heightIn(min = 48.dp).weight(1f)) {
                    Text("View artist")
                }
            }
        }
    }
}

@Composable
private fun FeedAlbumCard(
    album: FeedAlbum,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(144.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(144.dp),
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
                    Icon(Icons.Filled.Album, contentDescription = null, modifier = Modifier.size(48.dp))
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
        )
        Text(
            text = album.artist,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@dagger.hilt.android.lifecycle.HiltViewModel
class ArtistAlbumNavBridgeFeed @javax.inject.Inject constructor(val navigator: ArtistAlbumNavigator) : androidx.lifecycle.ViewModel()
