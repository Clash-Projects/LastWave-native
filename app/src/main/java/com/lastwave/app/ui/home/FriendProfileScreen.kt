package com.lastwave.app.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.repository.HomeArtistItem
import com.lastwave.app.data.repository.HomeTrack
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.ExpressiveLoadingIndicator
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.player.LocalAddToPlaylist
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.ui.player.PlayingWaveBars
import com.lastwave.app.ui.theme.ArtworkShape
import com.lastwave.app.ui.theme.BadgePillShape
import com.lastwave.app.ui.theme.ExpressiveHeroShape
import com.lastwave.app.ui.theme.HeroInnerShape
import com.lastwave.app.ui.theme.ListContainerShape
import com.lastwave.app.ui.theme.NowPlayingCardShape
import com.lastwave.app.ui.theme.StatPillShape
import com.lastwave.app.ui.theme.TrackRowShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendProfileScreen(
    username: String,
    initialDisplayName: String? = null,
    initialAvatarUrl: String? = null,
    onBack: () -> Unit,
    onOpenArtist: (String) -> Unit = {},
    onOpenAlbum: (String, String) -> Unit = { _, _ -> },
    viewModel: FriendProfileViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val musicPlayer = LocalMusicPlayer.current
    val addToPlaylist = LocalAddToPlaylist.current
    val listState = rememberLazyListState()
    var menuTrack by remember { mutableStateOf<HomeTrack?>(null) }

    LaunchedEffect(username) {
        viewModel.loadFriend(username, initialDisplayName, initialAvatarUrl)
    }

    val displayTitle = uiState.displayName.ifBlank { username }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 760.dp),
        ) {
            ExpressiveHeader(
                title = displayTitle,
                subtitle = "@$username",
                onBack = onBack,
                actions = {
                    HeaderActionIcon(
                        icon = Icons.Filled.PushPin,
                        contentDescription = if (uiState.isPinned) "Unpin friend" else "Pin friend",
                        onClick = viewModel::togglePinned,
                    )
                    HeaderActionIcon(
                        icon = Icons.Filled.OpenInNew,
                        contentDescription = "Open Last.fm profile",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.last.fm/user/$username"))
                            context.startActivity(intent)
                        },
                    )
                },
            )

            Spacer(Modifier.height(8.dp))

            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(ListContainerShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ExpressiveLoadingIndicator(message = "Loading $displayTitle's profile")
                    }
                } else if (uiState.error != null && uiState.recentTracks.isEmpty() && uiState.topTracksOverall.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = uiState.error ?: "Unable to load friend's profile",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = viewModel::refresh) {
                                Text("Retry")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 10.dp,
                            bottom = 24.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
                        ),
                        modifier = Modifier.fillMaxSize().safeHorizontalContentPadding(),
                    ) {
                        // 1. Hero Profile Header
                        item(key = "friend_hero") {
                            FriendHeroCard(
                                displayName = uiState.displayName,
                                username = username,
                                avatarUrl = uiState.avatarUrl,
                                isPinned = uiState.isPinned,
                            )
                        }

                        // 2. Live Now Playing Card (if actively listening)
                        uiState.nowPlaying?.let { np ->
                            item(key = "friend_now_playing") {
                                FriendNowPlayingBanner(
                                    track = np,
                                    onPlay = { viewModel.playTrack(np) },
                                    onStartMix = { viewModel.startMix(np.name, np.artist) },
                                    onMenuClick = { menuTrack = np },
                                )
                            }
                        }

                        // 3. Listening Statistics Card
                        item(key = "friend_stats") {
                            uiState.stats?.let { stats ->
                                FriendStatsCard(
                                    scrobbles = stats.scrobbles,
                                    trackCount = stats.trackCount,
                                    artistCount = stats.artistCount,
                                    albumCount = stats.albumCount,
                                    timerBaseSeconds = stats.timerBaseSeconds,
                                )
                            }
                        }

                        // 4. Tab Selector
                        item(key = "friend_tabs") {
                            FriendTabSelector(
                                selectedTab = uiState.selectedTab,
                                onTabSelect = viewModel::setTab,
                            )
                        }

                        // 5. If Top Tracks tab, show Period Filter Chips
                        if (uiState.selectedTab == FriendProfileTab.TOP_TRACKS) {
                            item(key = "top_tracks_periods") {
                                PeriodFilterChips(
                                    selectedPeriod = uiState.selectedPeriod,
                                    onPeriodSelect = viewModel::setPeriod,
                                )
                            }
                        }

                        // 6. Tab Content Rows
                        when (uiState.selectedTab) {
                            FriendProfileTab.RECENT -> {
                                if (uiState.recentTracks.isEmpty()) {
                                    item(key = "empty_recents") {
                                        EmptyStateText("No recent scrobbles found for this friend.")
                                    }
                                } else {
                                    itemsIndexed(
                                        uiState.recentTracks,
                                        key = { index, track -> "recent_${track.key}_${track.timestampMillis ?: index}" },
                                    ) { index, track ->
                                        FriendTrackRow(
                                            track = track,
                                            badge = formatRelativeTime(track.timestampMillis),
                                            onClick = { viewModel.playQueue(uiState.recentTracks, index) },
                                            onLongClick = {
                                                addToPlaylist(
                                                    PlayableTrack(
                                                        title = track.name,
                                                        artist = track.artist,
                                                        artworkUrl = track.artworkUrl,
                                                    ),
                                                )
                                            },
                                            onMenuClick = { menuTrack = track },
                                        )
                                    }
                                }
                            }

                            FriendProfileTab.TOP_TRACKS -> {
                                val tracks = when (uiState.selectedPeriod) {
                                    "7day" -> uiState.topTracks7Days
                                    "1month" -> uiState.topTracks30Days
                                    else -> uiState.topTracksOverall
                                }
                                if (tracks.isEmpty()) {
                                    item(key = "empty_top_tracks") {
                                        EmptyStateText("No top tracks available for this period.")
                                    }
                                } else {
                                    itemsIndexed(
                                        tracks,
                                        key = { index, track -> "top_${uiState.selectedPeriod}_${track.key}_$index" },
                                    ) { index, track ->
                                        FriendTrackRow(
                                            track = track,
                                            badge = if (track.playCount > 0) "${track.playCount} plays" else null,
                                            rank = index + 1,
                                            onClick = { viewModel.playQueue(tracks, index) },
                                            onLongClick = {
                                                addToPlaylist(
                                                    PlayableTrack(
                                                        title = track.name,
                                                        artist = track.artist,
                                                        artworkUrl = track.artworkUrl,
                                                    ),
                                                )
                                            },
                                            onMenuClick = { menuTrack = track },
                                        )
                                    }
                                }
                            }

                            FriendProfileTab.TOP_ARTISTS -> {
                                if (uiState.topArtists.isEmpty()) {
                                    item(key = "empty_top_artists") {
                                        EmptyStateText("No top artists available for this friend.")
                                    }
                                } else {
                                    itemsIndexed(
                                        uiState.topArtists,
                                        key = { index, artist -> "artist_${artist.name}_$index" },
                                    ) { index, artist ->
                                        FriendArtistRow(
                                            artist = artist,
                                            rank = index + 1,
                                            onClick = { onOpenArtist(artist.name) },
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

    menuTrack?.let { track ->
        TrackContextMenuSheet(
            target = TrackMenuTarget.Track(track.name, track.artist, track.artworkUrl.orEmpty()),
            capabilities = TrackMenuCapabilities(showCopyActions = true, showDeleteScrobble = false),
            playbackSourceLabel = "${uiState.displayName}'s Profile",
            onDismiss = { menuTrack = null },
        )
    }
}

@Composable
private fun FriendHeroCard(
    displayName: String,
    username: String,
    avatarUrl: String?,
    isPinned: Boolean,
) {
    Surface(
        shape = ExpressiveHeroShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(68.dp),
            ) {
                if (!avatarUrl.isNullOrBlank()) {
                    ArtworkImage(
                        name = displayName,
                        artist = username,
                        embeddedUrl = avatarUrl,
                        fallbackIcon = Icons.Filled.AccountCircle,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = displayName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "@$username",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = BadgePillShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Last.fm Friend",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (isPinned) {
                        Surface(
                            shape = BadgePillShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Icon(
                                    Icons.Filled.PushPin,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "Pinned",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
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
private fun FriendNowPlayingBanner(
    track: HomeTrack,
    onPlay: () -> Unit,
    onStartMix: () -> Unit,
    onMenuClick: () -> Unit,
) {
    Surface(
        shape = NowPlayingCardShape,
        color = Color.Transparent,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(NowPlayingCardShape)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f),
                    ),
                ),
            )
            .border(
                BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                        ),
                    ),
                ),
                shape = NowPlayingCardShape,
            ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    shape = BadgePillShape,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlayingWaveBars(
                            modifier = Modifier.size(14.dp, 12.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "NOW LISTENING",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                IconButton(onClick = onMenuClick, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Menu",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(ArtworkShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    ArtworkImage(
                        name = track.name,
                        artist = track.artist,
                        embeddedUrl = track.artworkUrl,
                        fallbackIcon = Icons.Filled.GraphicEq,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onPlay,
                    modifier = Modifier.weight(1f).height(38.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = BadgePillShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Listen Along", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onStartMix,
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = BadgePillShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Start Mix", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun FriendStatsCard(
    scrobbles: Long,
    trackCount: Long,
    artistCount: Long,
    albumCount: Long,
    timerBaseSeconds: Long,
) {
    Surface(
        shape = ExpressiveHeroShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Surface(
                shape = HeroInnerShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        Text(
                            formatCount(rememberAnimatedCount(scrobbles)),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "Total Scrobbles",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                        )
                    }
                    if (timerBaseSeconds > 0) {
                        Surface(
                            shape = BadgePillShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Headset,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    formatDuration(timerBaseSeconds),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FriendStatPill("Tracks", trackCount, Modifier.weight(1f))
                FriendStatPill("Artists", artistCount, Modifier.weight(1f))
                FriendStatPill("Albums", albumCount, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FriendStatPill(label: String, value: Long, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = StatPillShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
    ) {
        Column(
            Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                formatCount(rememberAnimatedCount(value)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun FriendTabSelector(
    selectedTab: FriendProfileTab,
    onTabSelect: (FriendProfileTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FriendProfileTab.entries.forEach { tab ->
            val isSelected = tab == selectedTab
            Surface(
                onClick = { onTabSelect(tab) },
                shape = BadgePillShape,
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)) else null,
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PeriodFilterChips(
    selectedPeriod: String,
    onPeriodSelect: (String) -> Unit,
) {
    val periods = listOf("overall" to "Overall", "7day" to "7 Days", "1month" to "30 Days")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        periods.forEach { (key, label) ->
            val isSelected = key == selectedPeriod
            Surface(
                onClick = { onPeriodSelect(key) },
                shape = BadgePillShape,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.height(30.dp),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FriendTrackRow(
    track: HomeTrack,
    badge: String?,
    rank: Int? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenuClick: () -> Unit,
) {
    Surface(
        shape = TrackRowShape,
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(vertical = 6.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (rank != null) {
                Text(
                    text = "#$rank",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(32.dp),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.width(4.dp))
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(ArtworkShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                ArtworkImage(
                    name = track.name,
                    artist = track.artist,
                    embeddedUrl = track.artworkUrl,
                    fallbackIcon = Icons.Filled.MusicNote,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (badge != null) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = BadgePillShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            IconButton(onClick = onMenuClick, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Options",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FriendArtistRow(
    artist: HomeArtistItem,
    rank: Int,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = TrackRowShape,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(vertical = 6.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "#$rank",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(4.dp))

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(48.dp),
            ) {
                if (!artist.artworkUrl.isNullOrBlank()) {
                    ArtworkImage(
                        name = artist.name,
                        artist = artist.name,
                        embeddedUrl = artist.artworkUrl,
                        fallbackIcon = Icons.Filled.AccountCircle,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = artist.name.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (artist.playCount > 0) {
                    Text(
                        text = "${formatCount(artist.playCount)} scrobbles",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyStateText(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
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

private fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0) return "--"
    val d = totalSeconds / 86400
    val h = (totalSeconds % 86400) / 3600
    val m = (totalSeconds % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

private fun formatRelativeTime(millis: Long?): String {
    if (millis == null || millis <= 0) return ""
    val now = System.currentTimeMillis()
    val diff = now - millis
    val minutes = diff / (1000 * 60)
    val hours = minutes / 60
    val days = hours / 24
    return when {
        diff < 60_000L -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days == 1L -> "Yesterday"
        days < 7 -> "${days}d ago"
        else -> {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
            val months = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
            "${cal.get(java.util.Calendar.DAY_OF_MONTH)} ${months[cal.get(java.util.Calendar.MONTH)]}"
        }
    }
}
