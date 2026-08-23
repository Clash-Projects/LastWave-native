package com.lastwave.app.ui.search

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lastwave.app.data.search.SearchResultItem
import com.lastwave.app.data.search.SearchTab
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance

private val EXPLORE_GENRES = listOf(
    "Pop", "Rock", "Hip-Hop", "Lo-Fi", "Electronic", "Indie",
    "R&B", "Bollywood", "Jazz", "Metal", "Acoustic", "Chill",
    "Anime", "Classical", "Synthwave",
)

/**
 * YouTube Music styled search experience with live auto-complete suggestions,
 * persistent search history, top result card, explore tags, and 4 tabs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit = {},
    onOpenArtist: (name: String, browseId: String?) -> Unit = { _, _ -> },
    onOpenAlbum: (title: String, artist: String, browseId: String?) -> Unit = { _, _, _ -> },
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val musicPlayer = com.lastwave.app.ui.player.LocalMusicPlayer.current
    val playbackState by musicPlayer.state.collectAsState()
    var menuTarget by remember { mutableStateOf<TrackMenuTarget?>(null) }
    val addToPlaylist = com.lastwave.app.ui.player.LocalAddToPlaylist.current
    val focusManager = LocalFocusManager.current

    Column(Modifier.fillMaxSize()) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderActionIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                TextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = {
                        Text(
                            if (state.tab == SearchTab.USERS) "Search Last.fm users\u2026"
                            else "Search YouTube Music\u2026",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = viewModel::clearQuery) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            focusManager.clearFocus()
                            viewModel.searchNow()
                        },
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
        }

        val tabIndex = when (state.tab) {
            SearchTab.TRACKS -> 0; SearchTab.ARTISTS -> 1; SearchTab.ALBUMS -> 2; SearchTab.USERS -> 3
        }
        ScrollableTabRow(
            selectedTabIndex = tabIndex,
            edgePadding = 16.dp,
            modifier = Modifier.safeHorizontalContentPadding(),
        ) {
            Tab(selected = tabIndex == 0, onClick = { viewModel.setTab(SearchTab.TRACKS) }, text = { Text("Tracks") })
            Tab(selected = tabIndex == 1, onClick = { viewModel.setTab(SearchTab.ARTISTS) }, text = { Text("Artists") })
            Tab(selected = tabIndex == 2, onClick = { viewModel.setTab(SearchTab.ALBUMS) }, text = { Text("Albums") })
            Tab(selected = tabIndex == 3, onClick = { viewModel.setTab(SearchTab.USERS) }, text = { Text("Users") })
        }

        Box(Modifier.fillMaxSize().safeHorizontalContentPadding()) {
            when {
                // 1. Live Auto-Complete Suggestions (while actively typing)
                state.isShowingSuggestions && state.suggestions.isNotEmpty() -> {
                    val matchingRecent = remember(state.query, state.recentSearches) {
                        val q = state.query.trim().lowercase()
                        if (q.isEmpty()) emptyList()
                        else state.recentSearches.filter { it.lowercase().contains(q) }.take(3)
                    }

                    LazyColumn(
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = 24.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
                        ),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (matchingRecent.isNotEmpty()) {
                            items(matchingRecent, key = { "recent_match_$it" }) { recentText ->
                                SuggestionRow(
                                    fullText = recentText,
                                    query = state.query,
                                    isHistory = true,
                                    onClick = {
                                        focusManager.clearFocus()
                                        viewModel.executeSearch(recentText)
                                    },
                                    onInsert = { viewModel.setQuery(recentText) },
                                )
                            }
                        }

                        items(state.suggestions.filter { !matchingRecent.contains(it) }, key = { "sugg_$it" }) { suggestion ->
                            SuggestionRow(
                                fullText = suggestion,
                                query = state.query,
                                isHistory = false,
                                onClick = {
                                    focusManager.clearFocus()
                                    viewModel.executeSearch(suggestion)
                                },
                                onInsert = { viewModel.setQuery(suggestion) },
                            )
                        }
                    }
                }

                // 2. Empty Query State: Show Recent Search History & Explore Tags
                state.query.isBlank() -> {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            top = 12.dp,
                            bottom = 24.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
                        ),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (state.recentSearches.isNotEmpty()) {
                            item(key = "history_header") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Recent searches",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(onClick = viewModel::clearRecentSearches) {
                                        Text("Clear all", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                            items(state.recentSearches, key = { "hist_$it" }) { recentQuery ->
                                RecentSearchRow(
                                    text = recentQuery,
                                    onClick = {
                                        focusManager.clearFocus()
                                        viewModel.executeSearch(recentQuery)
                                    },
                                    onDelete = { viewModel.removeRecentSearch(recentQuery) },
                                )
                            }
                            item(key = "history_spacer") {
                                Spacer(Modifier.height(16.dp))
                            }
                        }

                        item(key = "explore_section") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 12.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Explore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Explore genres & moods",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    EXPLORE_GENRES.forEach { genre ->
                                        AssistChip(
                                            onClick = {
                                                focusManager.clearFocus()
                                                viewModel.executeSearch(genre)
                                            },
                                            label = { Text(genre, style = MaterialTheme.typography.labelLarge) },
                                            colors = AssistChipDefaults.assistChipColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                labelColor = MaterialTheme.colorScheme.onSurface,
                                            ),
                                            shape = RoundedCornerShape(12.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Search Results State: Loading, Empty, Results
                else -> {
                    Crossfade(
                        targetState = state.status,
                        animationSpec = tween(ExpressiveMotion.Standard, easing = FastOutSlowInEasing),
                        label = "searchState",
                    ) { status ->
                        when (status) {
                            SearchStatus.IDLE -> Box(Modifier.fillMaxSize())
                            SearchStatus.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                com.lastwave.app.ui.common.ExpressiveLoadingIndicator()
                            }
                            SearchStatus.EMPTY -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Filled.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(44.dp),
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text("No results found for \"${state.query}\"", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            SearchStatus.RESULTS -> {
                                val topResult = state.results.firstOrNull()
                                val otherResults = if (state.results.size > 1) state.results.drop(1) else emptyList()

                                LazyColumn(
                                    contentPadding = PaddingValues(
                                        top = 8.dp,
                                        bottom = 24.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
                                    ),
                                ) {
                                    if (topResult != null && state.tab != SearchTab.USERS) {
                                        val isTopPlaying = state.tab == SearchTab.TRACKS && playbackState.isPlaying &&
                                            playbackState.current?.title.equals(topResult.name, ignoreCase = true) &&
                                            (topResult.artist.isNullOrBlank() || playbackState.current?.artist.equals(topResult.artist, ignoreCase = true))

                                        item(key = "top_result_card") {
                                            TopResultCard(
                                                item = topResult,
                                                tab = state.tab,
                                                isPlaying = isTopPlaying,
                                                onPlay = {
                                                    when (state.tab) {
                                                        SearchTab.ARTISTS -> onOpenArtist(topResult.name, topResult.entityId)
                                                        SearchTab.ALBUMS -> onOpenAlbum(topResult.name, topResult.artist.orEmpty(), topResult.entityId)
                                                        else -> viewModel.playResult(topResult)
                                                    }
                                                },
                                                onMenu = {
                                                    menuTarget = when (state.tab) {
                                                        SearchTab.TRACKS -> TrackMenuTarget.Track(topResult.name, topResult.artist.orEmpty(), topResult.url)
                                                        SearchTab.ARTISTS -> TrackMenuTarget.Artist(topResult.name, topResult.url)
                                                        SearchTab.ALBUMS -> TrackMenuTarget.Album(topResult.name, topResult.artist.orEmpty(), topResult.url)
                                                        SearchTab.USERS -> null
                                                    }
                                                },
                                            )
                                            Spacer(Modifier.height(8.dp))
                                        }
                                    }

                                    items(if (state.tab == SearchTab.USERS) state.results else otherResults, key = { it.entityId ?: it.url.ifBlank { it.name + it.artist.orEmpty() } }) { item ->
                                        val isItemPlaying = state.tab == SearchTab.TRACKS && playbackState.isPlaying &&
                                            playbackState.current?.title.equals(item.name, ignoreCase = true) &&
                                            (item.artist.isNullOrBlank() || playbackState.current?.artist.equals(item.artist, ignoreCase = true))

                                        SearchResultRow(
                                            item = item,
                                            tab = state.tab,
                                            isPlaying = isItemPlaying,
                                            modifier = Modifier.animateItem(),
                                            onClick = {
                                                when (state.tab) {
                                                    SearchTab.ARTISTS -> onOpenArtist(item.name, item.entityId)
                                                    SearchTab.ALBUMS -> onOpenAlbum(item.name, item.artist.orEmpty(), item.entityId)
                                                    else -> viewModel.playResult(item)
                                                }
                                            },
                                            onLongClick = {
                                                if (state.tab == SearchTab.TRACKS) {
                                                    addToPlaylist(
                                                        com.lastwave.app.playback.PlayableTrack(
                                                            title = item.name,
                                                            artist = item.artist.orEmpty(),
                                                            album = item.subtitle,
                                                            artworkUrl = item.artworkUrl,
                                                            videoId = item.videoId,
                                                        ),
                                                    )
                                                }
                                            },
                                            onMenu = {
                                                menuTarget = when (state.tab) {
                                                    SearchTab.TRACKS -> TrackMenuTarget.Track(item.name, item.artist.orEmpty(), item.url)
                                                    SearchTab.ARTISTS -> TrackMenuTarget.Artist(item.name, item.url)
                                                    SearchTab.ALBUMS -> TrackMenuTarget.Album(item.name, item.artist.orEmpty(), item.url)
                                                    SearchTab.USERS -> null
                                                }
                                            },
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

    menuTarget?.let { target ->
        TrackContextMenuSheet(
            target = target,
            capabilities = TrackMenuCapabilities(showCopyActions = true, showDeleteScrobble = true),
            playbackSourceLabel = "Search",
            onDismiss = { menuTarget = null },
        )
    }
}

/** Featured Top Result Card styled after YouTube Music hero cards. */
@Composable
private fun TopResultCard(
    item: SearchResultItem,
    tab: SearchTab,
    isPlaying: Boolean = false,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(onClick = onPlay),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fallback = when (tab) {
                SearchTab.TRACKS -> if (isPlaying) Icons.Filled.GraphicEq else Icons.Filled.MusicNote
                SearchTab.ARTISTS -> Icons.Filled.Person
                SearchTab.ALBUMS -> Icons.Filled.Album
                SearchTab.USERS -> Icons.Filled.Person
            }
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(if (tab == SearchTab.ARTISTS) CircleShape else RoundedCornerShape(14.dp)),
            ) {
                ArtworkImage(
                    name = item.name,
                    artist = item.artist.orEmpty(),
                    embeddedUrl = item.artworkUrl,
                    fallbackIcon = fallback,
                    modifier = Modifier.fillMaxSize(),
                )
                if (isPlaying) {
                    com.lastwave.app.ui.player.PlayingWaveBars(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    Text(
                        when (tab) {
                            SearchTab.TRACKS -> "TOP SONG"
                            SearchTab.ARTISTS -> "TOP ARTIST"
                            SearchTab.ALBUMS -> "TOP ALBUM"
                            SearchTab.USERS -> "USER"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = when (tab) {
                    SearchTab.TRACKS -> item.artist.orEmpty()
                    SearchTab.ALBUMS -> item.artist.orEmpty()
                    SearchTab.ARTISTS -> item.subtitle.orEmpty().ifBlank { "Artist" }
                    SearchTab.USERS -> item.artist.orEmpty()
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = onPlay,
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(46.dp),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Play top result",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    fullText: String,
    query: String,
    isHistory: Boolean,
    onClick: () -> Unit,
    onInsert: () -> Unit,
) {
    val baseColor = MaterialTheme.colorScheme.onSurface
    val highlightColor = MaterialTheme.colorScheme.primary
    val annotated = remember(fullText, query, baseColor, highlightColor) {
        buildHighlightedSuggestion(fullText, query, baseColor, highlightColor)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isHistory) Icons.Filled.History else Icons.Filled.Search,
            contentDescription = null,
            tint = if (isHistory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
        )
        IconButton(
            onClick = onInsert,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Insert into search bar",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = 135f },
            )
        }
    }
}

private fun buildHighlightedSuggestion(
    suggestion: String,
    query: String,
    baseColor: Color,
    highlightColor: Color,
): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return AnnotatedString(suggestion)
    val lowerSug = suggestion.lowercase()
    val lowerQ = q.lowercase()
    val idx = lowerSug.indexOf(lowerQ)
    if (idx < 0) return AnnotatedString(suggestion)

    return buildAnnotatedString {
        if (idx > 0) {
            withStyle(SpanStyle(color = baseColor, fontWeight = FontWeight.Normal)) {
                append(suggestion.substring(0, idx))
            }
        }
        withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold)) {
            append(suggestion.substring(idx, idx + q.length))
        }
        if (idx + q.length < suggestion.length) {
            withStyle(SpanStyle(color = baseColor, fontWeight = FontWeight.SemiBold)) {
                append(suggestion.substring(idx + q.length))
            }
        }
    }
}

@Composable
private fun RecentSearchRow(
    text: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
        )
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove from history",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun SearchResultRow(
    item: SearchResultItem,
    tab: SearchTab,
    isPlaying: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    fun openLastFm(path: String) {
        val base = item.url.ifBlank { "https://www.last.fm/user/${item.name}" }.trimEnd('/')
        try {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("$base$path"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) { }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = tab != SearchTab.USERS,
                onClick = onClick,
                onLongClick = if (tab == SearchTab.TRACKS) onLongClick else onMenu,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fallback = when (tab) {
            SearchTab.TRACKS -> if (isPlaying) Icons.Filled.GraphicEq else Icons.Filled.MusicNote
            SearchTab.ARTISTS -> Icons.Filled.Person
            SearchTab.ALBUMS -> Icons.Filled.Album
            SearchTab.USERS -> Icons.Filled.Person
        }
        Box(modifier = Modifier.size(44.dp)) {
            ArtworkImage(
                name = item.name,
                artist = item.artist.orEmpty(),
                embeddedUrl = item.artworkUrl,
                fallbackIcon = fallback,
                modifier = Modifier.fillMaxSize().clip(if (tab == SearchTab.ARTISTS || tab == SearchTab.USERS) CircleShape else RoundedCornerShape(10.dp)),
            )
            if (isPlaying) {
                com.lastwave.app.ui.player.PlayingWaveBars(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = when (tab) {
                SearchTab.TRACKS -> listOfNotNull(item.artist, item.subtitle).joinToString(" \u00b7 ")
                SearchTab.ALBUMS -> item.subtitle ?: item.artist.orEmpty()
                SearchTab.ARTISTS -> item.subtitle.orEmpty()
                SearchTab.USERS -> listOfNotNull(item.artist, item.listeners?.let { "$it scrobbles" }).joinToString(" \u00b7 ")
            }
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (tab == SearchTab.USERS) {
            IconButton(onClick = { openLastFm("/+addfriend") }) {
                Icon(Icons.Filled.PersonAdd, contentDescription = "Follow ${item.name} on Last.fm")
            }
            IconButton(onClick = { openLastFm("/+removefriend") }) {
                Icon(Icons.Filled.PersonRemove, contentDescription = "Unfollow ${item.name} on Last.fm")
            }
        } else {
            com.lastwave.app.ui.common.OverflowMenuButton(onClick = onMenu)
        }
    }
}
