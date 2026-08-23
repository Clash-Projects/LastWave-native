package com.lastwave.app.ui.search

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import com.lastwave.app.ui.common.HeaderActionIcon
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
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance

/** Faithful port of search.js (§6): 3-tab search (Tracks/Artists/Albums)
 *  with idle/loading/empty/results states and the shared track/artist/
 *  album context menu. */
@Composable
fun SearchScreen(onBack: () -> Unit = {}, viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    var menuTarget by remember { mutableStateOf<TrackMenuTarget?>(null) }
    val addToPlaylist = com.lastwave.app.ui.player.LocalAddToPlaylist.current

    Column(Modifier.fillMaxSize()) {
        // Same rounded-container header language as ExpressiveHeader (used
        // by every other pushed screen), built by hand here instead of
        // through that shared component: Search's header content is a live
        // text field, not a static title/subtitle pair, so it doesn't fit
        // ExpressiveHeader's API — but the shape/tone/inset handling below
        // is identical on purpose.
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
                HeaderActionIcon(Icons.Filled.ArrowBack, "Back", onBack)
                TextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text(if (state.tab == SearchTab.USERS) "Search Last.fm users\u2026" else "Search YouTube Music\u2026") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = viewModel::clearQuery) { Icon(Icons.Filled.Clear, contentDescription = "Clear") }
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        // Material3's filled TextField always paints an
                        // underline indicator by default — visible as a
                        // long bar under the field regardless of the
                        // rounded `shape` set below, since that shape only
                        // rounds the top corners of the filled style. This
                        // field is meant to look like the app's other
                        // fully-rounded pill-shaped surfaces, so the
                        // indicator is removed outright rather than just
                        // recolored.
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
            Crossfade(
                targetState = state.status,
                animationSpec = tween(ExpressiveMotion.Standard, easing = FastOutSlowInEasing),
                label = "searchState",
            ) { status ->
                when (status) {
                    SearchStatus.IDLE -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                            val hint = if (state.tab == SearchTab.USERS) {
                            "Enter an exact Last.fm username to look them up"
                        } else {
                            "Search YouTube Music for songs, artists or albums"
                        }
                        Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                    SearchStatus.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { com.lastwave.app.ui.common.ExpressiveLoadingIndicator() }
                    SearchStatus.EMPTY -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No results found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SearchStatus.RESULTS -> LazyColumn(
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = 24.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding()
                        )
                    ) {
                        items(state.results, key = { it.entityId ?: it.url.ifBlank { it.name + it.artist.orEmpty() } }) { item ->
                            SearchResultRow(
                                item = item,
                                tab = state.tab,
                                modifier = Modifier.animateItem(),
                                onClick = { viewModel.playResult(item) },
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

    menuTarget?.let { target ->
        TrackContextMenuSheet(
            target = target,
            capabilities = TrackMenuCapabilities(showCopyActions = true, showDeleteScrobble = true),
            playbackSourceLabel = "Search",
            onDismiss = { menuTarget = null },
        )
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun SearchResultRow(
    item: SearchResultItem,
    tab: SearchTab,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Last.fm's API has no add-friend/remove-friend method at all — the
    // social graph is read-only over the API (user.getfriends is the only
    // friends-related call). The closest real action is opening Last.fm's
    // own web page for it, same +addfriend/+removefriend links the site
    // itself uses, rather than faking an in-app action that can't actually
    // reach Last.fm's servers.
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
            SearchTab.TRACKS -> Icons.Filled.MusicNote
            SearchTab.ARTISTS -> Icons.Filled.Person
            SearchTab.ALBUMS -> Icons.Filled.Album
            SearchTab.USERS -> Icons.Filled.Person
        }
        ArtworkImage(
            name = item.name,
            artist = item.artist.orEmpty(),
            embeddedUrl = item.artworkUrl,
            fallbackIcon = fallback,
            modifier = Modifier.size(44.dp).clip(if (tab == SearchTab.ARTISTS || tab == SearchTab.USERS) CircleShape else RoundedCornerShape(10.dp)),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
