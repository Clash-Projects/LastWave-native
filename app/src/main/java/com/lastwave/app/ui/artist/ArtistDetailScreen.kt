package com.lastwave.app.ui.artist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lastwave.app.data.model.ArtistAlbumItem
import com.lastwave.app.data.model.ArtistPageData
import com.lastwave.app.data.model.ArtistSummaryItem
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveLoadingIndicator
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.genres.GenreExplorer
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.ui.player.PlayingWaveBars

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArtistDetailScreen(
    artistName: String,
    browseId: String? = null,
    onBack: () -> Unit,
    onOpenAlbum: (title: String, artist: String, browseId: String?) -> Unit = { _, _, _ -> },
    onOpenArtist: (name: String, browseId: String?) -> Unit = { _, _ -> },
    viewModel: ArtistViewModel = hiltViewModel(),
    genreBridge: ArtistDetailGenreBridge = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val musicPlayer = LocalMusicPlayer.current
    val playbackState by musicPlayer.chromeState.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(artistName, browseId) {
        viewModel.loadArtist(artistName, browseId)
    }

    var selectedTrackMenu by remember { mutableStateOf<PlayableTrack?>(null) }
    var showAllSongs by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val showScrolledHeader by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 240
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (val state = uiState) {
            is ArtistUiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ExpressiveLoadingIndicator(message = "Loading $artistName...")
                }
            }
            is ArtistUiState.Error -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.loadArtist(artistName, browseId) }) {
                            Text("Retry")
                        }
                    }
                }
            }
            is ArtistUiState.Success -> {
                val data = state.data
                val isArtistPlaying = playbackState.isPlaying &&
                    (playbackState.sourceLabel == data.name || playbackState.current?.artist.equals(data.name, ignoreCase = true))

                // Ambient Backdrop Mesh Gradient
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
                                    Color.Transparent,
                                ),
                                startY = 0f,
                                endY = 1000f,
                            ),
                        ),
                )

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp,
                        bottom = 24.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .adaptiveContentWidth(maxWidth = 860.dp)
                        .align(Alignment.TopCenter),
                ) {
                    // 1. Artist Hero Section
                    item(key = "artist_hero") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            // Full-width Hero Cover Banner with gradient scrim
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                                    .shadow(
                                        elevation = 16.dp,
                                        shape = RoundedCornerShape(28.dp),
                                        ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                        spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    )
                                    .clip(RoundedCornerShape(28.dp)),
                            ) {
                                if (!data.artworkUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = data.artworkUrl,
                                        contentDescription = data.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Filled.Person,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(96.dp),
                                            )
                                        }
                                    }
                                }

                                // Dark gradient overlay at the base of the hero
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                colors = listOf(
                                                    Color.Transparent,
                                                    Color.Black.copy(alpha = 0.4f),
                                                    Color.Black.copy(alpha = 0.85f),
                                                ),
                                                startY = 100f,
                                            ),
                                        ),
                                )

                                if (isArtistPlaying) {
                                    Surface(
                                        shape = RoundedCornerShape(topStart = 14.dp, bottomEnd = 28.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                                        tonalElevation = 4.dp,
                                        modifier = Modifier.align(Alignment.BottomEnd),
                                    ) {
                                        PlayingWaveBars(
                                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                                            containerColor = Color.Transparent,
                                            waveColor = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }

                                // Artist Title & Stats overlaid on hero
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(start = 18.dp, bottom = 18.dp, top = 18.dp, end = if (isArtistPlaying) 72.dp else 18.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = data.name,
                                            style = MaterialTheme.typography.headlineLarge,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Icon(
                                            Icons.Filled.Verified,
                                            contentDescription = "Verified Artist",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }

                                    if (!data.subscribers.isNullOrBlank() || !data.monthlyListeners.isNullOrBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = Color.White.copy(alpha = 0.18f),
                                        ) {
                                            Text(
                                                text = data.subscribers ?: data.monthlyListeners.orEmpty(),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            // Native Music App Action Bar (Shuffle, Radio, Primary Play FAB)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    FilledTonalIconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.playShuffle()
                                        },
                                        enabled = data.topSongs.isNotEmpty(),
                                        shape = CircleShape,
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Shuffle,
                                            contentDescription = "Shuffle Artist",
                                            modifier = Modifier.size(22.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }

                                    FilledTonalIconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.startArtistMix()
                                        },
                                        shape = CircleShape,
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Radio,
                                            contentDescription = "Artist Radio",
                                            modifier = Modifier.size(22.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }

                                // Large Floating Primary Play Button (56dp circle)
                                Surface(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.playAll()
                                    },
                                    enabled = data.topSongs.isNotEmpty(),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    shadowElevation = 6.dp,
                                    modifier = Modifier.size(56.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            if (isArtistPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                            contentDescription = "Play Artist",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(30.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }


                    // 2. Genre Tags & Bio
                    if (data.tags.isNotEmpty() || !data.bio.isNullOrBlank()) {
                        item(key = "artist_tags_bio") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (data.tags.isNotEmpty()) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        data.tags.take(6).forEach { tag ->
                                            AssistChip(
                                                onClick = { genreBridge.genreExplorer.explore(tag) },
                                                label = { Text(tag) },
                                                shape = RoundedCornerShape(12.dp),
                                                colors = AssistChipDefaults.assistChipColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                ),
                                            )
                                        }
                                    }
                                }

                                if (!data.bio.isNullOrBlank()) {
                                    var isBioExpanded by remember { mutableStateOf(false) }
                                    Card(
                                        shape = RoundedCornerShape(20.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(Modifier.padding(16.dp)) {
                                            Text(
                                                text = "About",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                text = data.bio,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                                                maxLines = if (isBioExpanded) Int.MAX_VALUE else 4,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (data.bio.length > 120) {
                                                Spacer(Modifier.height(6.dp))
                                                Text(
                                                    text = if (isBioExpanded) "Show less" else "... Read more",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .clickable {
                                                            isBioExpanded = !isBioExpanded
                                                        }
                                                        .padding(vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Top Songs Section Header
                    if (data.topSongs.isNotEmpty()) {
                        val initialDisplayLimit = 10
                        val visibleSongs = if (showAllSongs || data.topSongs.size <= initialDisplayLimit) {
                            data.topSongs
                        } else {
                            data.topSongs.take(initialDisplayLimit)
                        }

                        item(key = "top_songs_header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Songs",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        // Top Songs List
                        itemsIndexed(
                            visibleSongs,
                            key = { index, track -> "${track.videoId ?: track.title}_$index" },
                            contentType = { _, _ -> "artist_top_song" },
                        ) { index, track ->
                            val isPlayingThis = playbackState.isPlaying &&
                                playbackState.current?.title.equals(track.title, ignoreCase = true) &&
                                playbackState.current?.artist.equals(track.artist, ignoreCase = true)

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isPlayingThis) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val fullIndex = data.topSongs.indexOf(track).takeIf { it >= 0 } ?: index
                                        viewModel.playAll(startIndex = fullIndex)
                                    },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Track number / Artwork
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        ArtworkImage(
                                            name = track.title,
                                            artist = track.artist,
                                            embeddedUrl = track.artworkUrl,
                                            fallbackIcon = Icons.Filled.MusicNote,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                        if (isPlayingThis) {
                                            PlayingWaveBars(
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .padding(2.dp)
                                                    .size(24.dp, 18.dp),
                                            )
                                        }
                                    }

                                    Spacer(Modifier.width(14.dp))

                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = track.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isPlayingThis) FontWeight.Bold else FontWeight.SemiBold,
                                            color = if (isPlayingThis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = track.album ?: data.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            selectedTrackMenu = track
                                        },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.MoreVert,
                                            contentDescription = "Options",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }

                        if (data.topSongs.size > initialDisplayLimit) {
                            item(key = "top_songs_toggle_button") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Surface(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            showAllSongs = !showAllSongs
                                        },
                                        shape = RoundedCornerShape(24.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                text = if (showAllSongs) "Show less" else "Show all songs",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            Icon(
                                                imageVector = if (showAllSongs) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. Albums Carousel
                    if (data.albums.isNotEmpty()) {
                        item(key = "albums_header") {
                            Text(
                                text = "Albums",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }

                        item(key = "albums_row") {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) {
                                items(data.albums, key = { it.browseId.ifBlank { it.title } }) { album ->
                                    ArtistAlbumCard(
                                        item = album,
                                        onClick = {
                                            onOpenAlbum(album.title, data.name, album.browseId)
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // 5. Singles & EPs Carousel
                    if (data.singles.isNotEmpty()) {
                        item(key = "singles_header") {
                            Text(
                                text = "Singles & EPs",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }

                        item(key = "singles_row") {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) {
                                items(data.singles, key = { it.browseId.ifBlank { it.title } }) { single ->
                                    ArtistAlbumCard(
                                        item = single,
                                        onClick = {
                                            onOpenAlbum(single.title, data.name, single.browseId)
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // 6. Similar Artists Carousel
                    if (data.similarArtists.isNotEmpty()) {
                        item(key = "similar_artists_header") {
                            Text(
                                text = "Fans Also Like",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }

                        item(key = "similar_artists_row") {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) {
                                items(data.similarArtists, key = { it.browseId.ifBlank { it.name } }) { artist ->
                                    SimilarArtistCard(
                                        artist = artist,
                                        onClick = {
                                            onOpenArtist(artist.name, artist.browseId)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Native Top Bar with Back Navigation & Fade Header
        Surface(
            color = if (showScrolledHeader) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.98f) else Color.Transparent,
            tonalElevation = if (showScrolledHeader) 4.dp else 0.dp,
            shadowElevation = if (showScrolledHeader) 6.dp else 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .adaptiveContentWidth(maxWidth = 860.dp)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedVisibility(
                        visible = showScrolledHeader,
                        enter = fadeIn() + scaleIn(initialScale = 0.9f),
                        exit = fadeOut() + scaleOut(targetScale = 0.9f),
                    ) {
                        Text(
                            text = (uiState as? ArtistUiState.Success)?.data?.name ?: artistName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Quick Play Button on Scroll
                AnimatedVisibility(
                    visible = showScrolledHeader && (uiState as? ArtistUiState.Success)?.data?.topSongs?.isNotEmpty() == true,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.playAll()
                        },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play", modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }

    // Context Menu Sheet for Tracks
    selectedTrackMenu?.let { track ->
        TrackContextMenuSheet(
            target = TrackMenuTarget.Track(track.title, track.artist, ""),
            capabilities = TrackMenuCapabilities(showCopyActions = true, showDeleteScrobble = false),
            playableTrack = track,
            playbackSourceLabel = artistName,
            onDismiss = { selectedTrackMenu = null },
        )
    }
}

@Composable
private fun ArtistAlbumCard(
    item: ArtistAlbumItem,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.width(150.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .clip(RoundedCornerShape(16.dp)),
            ) {
                ArtworkImage(
                    name = item.title,
                    artist = "",
                    embeddedUrl = item.artworkUrl,
                    fallbackIcon = Icons.Filled.Album,
                    modifier = Modifier.fillMaxSize(),
                )
                if (!item.year.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.65f),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp),
                    ) {
                        Text(
                            text = item.year,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.type ?: "Album",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SimilarArtistCard(
    artist: ArtistSummaryItem,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.width(130.dp),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape),
            ) {
                if (!artist.artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = artist.artworkUrl,
                        contentDescription = artist.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = artist.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@dagger.hilt.android.lifecycle.HiltViewModel
class ArtistDetailGenreBridge @javax.inject.Inject constructor(
    val genreExplorer: GenreExplorer,
) : androidx.lifecycle.ViewModel()
