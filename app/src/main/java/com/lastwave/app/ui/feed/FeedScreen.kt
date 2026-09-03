package com.lastwave.app.ui.feed

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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lastwave.app.data.feed.FeedQuickTile
import com.lastwave.app.data.model.FriendEntry
import com.lastwave.app.data.model.RecentTrack
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.ExpressiveLoadingIndicator
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.navigation.ArtistAlbumNavigator
import com.lastwave.app.ui.shell.FloatingNavDefaults
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.liquidGlassChrome
import java.util.Calendar

private val CardShape = RoundedCornerShape(14.dp)
private val CarouselCardShape = RoundedCornerShape(16.dp)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenPlaylist: (Long) -> Unit = {},
    viewModel: FeedViewModel = hiltViewModel(),
    artistAlbumNavigator: ArtistAlbumNavigator = hiltViewModel<ArtistAlbumNavBridgeFeed>().navigator,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val liquidGlass = LocalLiquidGlass.current

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
            ExpressiveHeader(
                title = "LastWave",
                subtitle = null,
                actions = {
                    HeaderActionIcon(
                        icon = Icons.Filled.Explore,
                        contentDescription = "Discover Radar",
                        onClick = onOpenDiscover,
                    )
                    HeaderActionIcon(
                        icon = Icons.Filled.Search,
                        contentDescription = "Search",
                        onClick = onOpenSearch,
                    )
                    HeaderActionIcon(
                        icon = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        onClick = onOpenSettings,
                    )
                },
            )

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ExpressiveLoadingIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        bottom = FloatingNavDefaults.contentBottomPadding(),
                        top = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // Mood Filter Chips
                    item(key = "mood_chips") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.moods) { mood ->
                                val selected = state.selectedMood == mood
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.selectMood(mood) },
                                    label = { Text(mood, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                    shape = RoundedCornerShape(20.dp),
                                )
                            }
                        }
                    }

                    // If mood is selected other than All
                    if (state.selectedMood != "All") {
                        if (state.isLoadingMood) {
                            item {
                                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                                    ExpressiveLoadingIndicator()
                                }
                            }
                        } else {
                            item(key = "mood_section") {
                                FeedSectionHeader(title = "${state.selectedMood} Mixes & Playlists")
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    modifier = Modifier.padding(top = 10.dp),
                                ) {
                                    items(state.moodPlaylists) { summary ->
                                        PlaylistSummaryCard(
                                            summary = summary,
                                            onClick = { viewModel.playPlaylistSummary(summary) },
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Quick 2x3 Tiles
                        if (state.feedData.quickTiles.isNotEmpty()) {
                            item(key = "quick_tiles") {
                                QuickTilesGrid(
                                    tiles = state.feedData.quickTiles,
                                    onTileClick = viewModel::handleQuickTileClick,
                                )
                            }
                        }

                        // Jump Back In (Last.fm Recent Scrobbles)
                        if (state.feedData.jumpBackIn.isNotEmpty()) {
                            item(key = "jump_back_in") {
                                FeedSectionHeader(title = "Jump back in", subtitle = "Your recent listening")
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(top = 10.dp),
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

                        // Friends Activity Strip
                        if (state.feedData.friends.isNotEmpty()) {
                            item(key = "friends_activity") {
                                FeedSectionHeader(title = "Friends Activity", subtitle = "What friends are scrobbling")
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    modifier = Modifier.padding(top = 10.dp),
                                ) {
                                    items(state.feedData.friends) { friend ->
                                        FriendAvatarCard(friend = friend)
                                    }
                                }
                            }
                        }

                        // Mixed For You
                        if (state.feedData.mixes.isNotEmpty()) {
                            item(key = "mixed_for_you") {
                                FeedSectionHeader(title = "Mixed for you", subtitle = "Personalized radios & sessions")
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    modifier = Modifier.padding(top = 10.dp),
                                ) {
                                    items(state.feedData.mixes) { summary ->
                                        PlaylistSummaryCard(
                                            summary = summary,
                                            onClick = { viewModel.playPlaylistSummary(summary) },
                                        )
                                    }
                                }
                            }
                        }

                        // New Releases (InnerTube FEmusic_new_releases)
                        if (state.feedData.newReleases.isNotEmpty()) {
                            item(key = "new_releases") {
                                FeedSectionHeader(title = "New Releases", subtitle = "Fresh drops and new albums")
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    modifier = Modifier.padding(top = 10.dp),
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

                        // Because you listen to [Top Artist]
                        state.feedData.becauseYouListenTo?.let { section ->
                            if (section.items.isNotEmpty()) {
                                item(key = "because_section") {
                                    FeedSectionHeader(title = section.title, subtitle = section.subtitle)
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.padding(top = 10.dp),
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
                        }

                        // Trending Charts (InnerTube FEmusic_charts)
                        if (state.feedData.charts.isNotEmpty()) {
                            item(key = "trending_charts") {
                                FeedSectionHeader(title = "Top Charts & Trending", subtitle = "Most popular right now")
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(top = 10.dp),
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
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedSectionHeader(title: String, subtitle: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuickTilesGrid(
    tiles: List<FeedQuickTile>,
    onTileClick: (FeedQuickTile) -> Unit,
) {
    val rows = tiles.chunked(2)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
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
private fun QuickTileCard(
    tile: FeedQuickTile,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .height(56.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (tile.isLiked) {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
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
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = tile.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .weight(1f),
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
            .width(140.dp)
            .clickable(onClick = onClick),
    ) {
        Surface(
            shape = CarouselCardShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 4.dp,
            modifier = Modifier.size(140.dp),
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
private fun AlbumReleaseCard(
    summary: YouTubePlaylistSummary,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick),
    ) {
        Surface(
            shape = CarouselCardShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 4.dp,
            modifier = Modifier.size(140.dp),
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
            .width(130.dp)
            .clickable(onClick = onClick),
    ) {
        Surface(
            shape = CarouselCardShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 3.dp,
            modifier = Modifier.size(130.dp),
        ) {
            ArtworkImage(
                name = track.name,
                artist = track.artist.displayName,
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
            .width(130.dp)
            .clickable(onClick = onClick),
    ) {
        Surface(
            shape = CarouselCardShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 3.dp,
            modifier = Modifier.size(130.dp),
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
                    Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(36.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
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
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .width(220.dp)
            .height(72.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
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

@dagger.hilt.android.lifecycle.HiltViewModel
class ArtistAlbumNavBridgeFeed @javax.inject.Inject constructor(val navigator: ArtistAlbumNavigator) : androidx.lifecycle.ViewModel()
