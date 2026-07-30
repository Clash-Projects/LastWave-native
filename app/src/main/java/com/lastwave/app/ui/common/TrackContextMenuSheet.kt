package com.lastwave.app.ui.common

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** Which optional rows this instance of the sheet should show — matches the
 *  reference's per-screen menu variance (§1.7 / §6.5 / home.js's reduced
 *  menu): Home and Discover omit Copy/Delete Scrobble; Playlist/Search/
 *  Genre Detail include everything. */
data class TrackMenuCapabilities(
    val showCopyActions: Boolean = true,
    val showDeleteScrobble: Boolean = true,
)

sealed interface TrackMenuTarget {
    data class Track(val name: String, val artist: String, val url: String) : TrackMenuTarget
    data class Artist(val name: String, val url: String) : TrackMenuTarget
    data class Album(val name: String, val artist: String, val url: String) : TrackMenuTarget
}

private fun youtubeSearchUrl(track: String, artist: String): String {
    val q = java.net.URLEncoder.encode("$track $artist", "UTF-8")
    return "https://www.youtube.com/results?search_query=$q"
}

private fun openUrl(context: Context, url: String) {
    if (url.isBlank()) return
    try {
        val targetUri = if (url.startsWith("http://") || url.startsWith("https://")) {
            android.net.Uri.parse(url)
        } else {
            android.net.Uri.parse("https://$url")
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, targetUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // Fallback or handle gracefully
    }
}

private fun buildLastFmUrl(target: TrackMenuTarget): String {
    return when (target) {
        is TrackMenuTarget.Track -> if (target.url.isNotBlank()) target.url else try {
            "https://www.last.fm/music/${java.net.URLEncoder.encode(target.artist, "UTF-8")}/_/${java.net.URLEncoder.encode(target.name, "UTF-8")}"
        } catch (e: Exception) { "" }
        is TrackMenuTarget.Artist -> if (target.url.isNotBlank()) target.url else try {
            "https://www.last.fm/music/${java.net.URLEncoder.encode(target.name, "UTF-8")}"
        } catch (e: Exception) { "" }
        is TrackMenuTarget.Album -> if (target.url.isNotBlank()) target.url else try {
            "https://www.last.fm/music/${java.net.URLEncoder.encode(target.artist, "UTF-8")}/${java.net.URLEncoder.encode(target.name, "UTF-8")}"
        } catch (e: Exception) { "" }
    }
}

/**
 * Faithful port of the shared track/artist/album 3-dot menu used across
 * Home, Playlist, Search, Discover, and Genre Detail (§1.7 / §6.5). One
 * component, capability-gated per screen rather than duplicated per screen.
 *
 * [onStartMix] / [onExploreGenre] / [onDeleteScrobble] are callbacks so the
 * caller's ViewModel owns the actual playlist-generation / API side effects
 * — this composable only owns the menu's presentation and simple actions
 * (open URL, copy, refresh art) that have no cross-screen state impact.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackContextMenuSheet(
    target: TrackMenuTarget,
    capabilities: TrackMenuCapabilities,
    onDismiss: () -> Unit,
    onStartMix: ((trackName: String, artistName: String) -> Unit)? = null,
    onExploreGenre: ((genre: String) -> Unit)? = null,
    onDeleteScrobble: ((trackName: String, artistName: String) -> Unit)? = null,
    onRefreshArtwork: (() -> Unit)? = null,
    genreResolverViewModel: GenreRowViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState()

    var genre by remember(target) { mutableStateOf<String?>(null) }
    var resolvingGenre by remember(target) { mutableStateOf(false) }

    val isTrack = target is TrackMenuTarget.Track
    LaunchedEffect(target) {
        if (target is TrackMenuTarget.Track) {
            resolvingGenre = true
            genre = genreResolverViewModel.resolve(target.name, target.artist)
            resolvingGenre = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(bottom = 24.dp)) {
            if (isTrack) {
                val t = target as TrackMenuTarget.Track

                if (resolvingGenre || !genre.isNullOrBlank() || genre == "") {
                    MenuInfoRow(
                        icon = Icons.Filled.Sell,
                        text = if (resolvingGenre) "Resolving genre\u2026" else "Genre: ${genre?.takeIf { it.isNotBlank() } ?: "Unknown"}",
                        loading = resolvingGenre,
                    )
                }

                if (onStartMix != null) {
                    MenuActionRow(Icons.Filled.Shuffle, "Start Mix from this") {
                        onStartMix(t.name, t.artist); onDismiss()
                    }
                }
                MenuActionRow(Icons.Filled.Language, "Open in Last.fm") {
                    openUrl(context, buildLastFmUrl(target)); onDismiss()
                }
                MenuActionRow(Icons.Filled.PlayCircle, "Play on YouTube") {
                    openUrl(context, youtubeSearchUrl(t.name, t.artist)); onDismiss()
                }
                if (onRefreshArtwork != null) {
                    MenuActionRow(Icons.Filled.Refresh, "Refresh Cover Art") {
                        onRefreshArtwork(); onDismiss()
                    }
                }
                if (capabilities.showCopyActions) {
                    MenuActionRow(Icons.Filled.ContentCopy, "Copy song name") {
                        clipboard.setText(AnnotatedString(t.name)); onDismiss()
                    }
                    MenuActionRow(Icons.Filled.ContentCopy, "Copy artist") {
                        clipboard.setText(AnnotatedString(t.artist)); onDismiss()
                    }
                }
                if (capabilities.showDeleteScrobble && onDeleteScrobble != null) {
                    MenuActionRow(Icons.Filled.Delete, "Delete Scrobble", danger = true) {
                        onDeleteScrobble(t.name, t.artist); onDismiss()
                    }
                }
                if (!genre.isNullOrBlank() && onExploreGenre != null) {
                    MenuActionRow(Icons.Filled.Explore, "Explore this genre") {
                        onExploreGenre(genre!!); onDismiss()
                    }
                }
            } else if (target is TrackMenuTarget.Artist) {
                MenuActionRow(Icons.Filled.Person, "Open in Last.fm") { openUrl(context, buildLastFmUrl(target)); onDismiss() }
            } else if (target is TrackMenuTarget.Album) {
                MenuActionRow(Icons.Filled.OpenInNew, "Open in Last.fm") { openUrl(context, buildLastFmUrl(target)); onDismiss() }
            }
        }
    }
}

@Composable
private fun MenuActionRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

@Composable
private fun MenuInfoRow(icon: ImageVector, text: String, loading: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (loading) CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp).width(14.dp), strokeWidth = 2.dp)
    }
}
