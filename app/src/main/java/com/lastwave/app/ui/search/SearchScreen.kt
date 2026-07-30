package com.lastwave.app.ui.search

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lastwave.app.data.search.SearchResultItem
import com.lastwave.app.data.search.SearchTab
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget

/** Faithful port of search.js (§6): 3-tab search (Tracks/Artists/Albums)
 *  with idle/loading/empty/results states and the shared track/artist/
 *  album context menu. */
@Composable
fun SearchScreen(onBack: () -> Unit = {}, viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    var menuTarget by remember { mutableStateOf<TrackMenuTarget?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            TextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("Search Last.fm\u2026") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearQuery) { Icon(Icons.Filled.Clear, contentDescription = "Clear") }
                    }
                },
                colors = TextFieldDefaults.colors(focusedIndicatorColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
            )
        }

        val tabIndex = when (state.tab) { SearchTab.TRACKS -> 0; SearchTab.ARTISTS -> 1; SearchTab.ALBUMS -> 2 }
        TabRow(selectedTabIndex = tabIndex) {
            Tab(selected = tabIndex == 0, onClick = { viewModel.setTab(SearchTab.TRACKS) }, text = { Text("Tracks") })
            Tab(selected = tabIndex == 1, onClick = { viewModel.setTab(SearchTab.ARTISTS) }, text = { Text("Artists") })
            Tab(selected = tabIndex == 2, onClick = { viewModel.setTab(SearchTab.ALBUMS) }, text = { Text("Albums") })
        }

        Box(Modifier.fillMaxSize()) {
            Crossfade(targetState = state.status, label = "searchState") { status ->
                when (status) {
                    SearchStatus.IDLE -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                            Text("Search Last.fm for tracks, artists or albums", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                    SearchStatus.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    SearchStatus.EMPTY -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No results found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SearchStatus.RESULTS -> LazyColumn(
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        )
                    ) {
                        items(state.results, key = { it.url.ifBlank { it.name + it.artist.orEmpty() } }) { item ->
                            SearchResultRow(
                                item = item,
                                tab = state.tab,
                                onMenu = {
                                    menuTarget = when (state.tab) {
                                        SearchTab.TRACKS -> TrackMenuTarget.Track(item.name, item.artist.orEmpty(), item.url)
                                        SearchTab.ARTISTS -> TrackMenuTarget.Artist(item.name, item.url)
                                        SearchTab.ALBUMS -> TrackMenuTarget.Album(item.name, item.artist.orEmpty(), item.url)
                                    }
                                },
                            )
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
            onDismiss = { menuTarget = null },
        )
    }
}

@Composable
private fun SearchResultRow(item: SearchResultItem, tab: SearchTab, onMenu: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val url = item.url
                if (url.isNotBlank()) {
                    val target = if (tab == SearchTab.TRACKS) {
                        val q = java.net.URLEncoder.encode("${item.name} ${item.artist.orEmpty()}", "UTF-8")
                        "https://www.youtube.com/results?search_query=$q"
                    } else url
                    try {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(target)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) { }
                }
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fallback = when (tab) {
            SearchTab.TRACKS -> Icons.Filled.MusicNote
            SearchTab.ARTISTS -> Icons.Filled.Person
            SearchTab.ALBUMS -> Icons.Filled.Album
        }
        ArtworkImage(
            name = item.name,
            artist = item.artist.orEmpty(),
            embeddedUrl = item.artworkUrl,
            fallbackIcon = fallback,
            modifier = Modifier.size(44.dp).clip(if (tab == SearchTab.ARTISTS) CircleShape else RoundedCornerShape(10.dp)),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = when (tab) {
                SearchTab.TRACKS, SearchTab.ALBUMS -> item.artist.orEmpty()
                SearchTab.ARTISTS -> item.listeners?.let { "$it listeners" } ?: ""
            }
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onMenu) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
    }
}
