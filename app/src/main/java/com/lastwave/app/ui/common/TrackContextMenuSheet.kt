package com.lastwave.app.ui.common

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.lastwave.app.ui.generate.MixLauncher
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.ui.player.LocalAddToPlaylist
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

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

/** Thin bridge so TrackContextMenuSheet can reach the MixLauncher singleton
 *  the same way it already reaches GenreRowViewModel — every caller gets
 *  "Start Mix with this Song" working for free, with no per-screen wiring. */
@HiltViewModel
class StartMixMenuViewModel @Inject constructor(private val mixLauncher: MixLauncher) : ViewModel() {
    fun startMix(trackName: String, artistName: String) {
        mixLauncher.startMix(trackName, artistName)
    }
}

/** Same idea, for the Genre row — every caller (Home, Discover, Playlist,
 *  Search) gets "tap the genre to open it in Genres" for free, without
 *  each of them needing to pass onExploreGenre + a NavController down
 *  through their own screen. */
@HiltViewModel
class ExploreGenreMenuViewModel @Inject constructor(private val genreExplorer: com.lastwave.app.ui.genres.GenreExplorer) : ViewModel() {
    fun explore(genre: String) {
        genreExplorer.explore(genre)
    }
}

/** music.youtube.com rather than youtube.com: if YouTube Music is installed
 *  it's registered as that domain's Android App Link target, so a plain
 *  ACTION_VIEW opens the app directly — no explicit package targeting (and
 *  the manifest <queries> visibility declaration that would need) required.
 *  Falls back to the YouTube Music website when the app isn't installed. */
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
 * The sheet's own surface and every row here are tinted from the live app
 * accent (MaterialTheme.colorScheme) rather than a fixed neutral color —
 * see accentTint() below — so switching accent (a preset, Monochrome,
 * wallpaper Dynamic Color, or Dynamic Now Playing) restyles this popup the
 * same way it restyles the rest of the app, with no extra wiring needed
 * here: colorScheme.primary/primaryContainer already reflect whichever
 * source is currently driving the theme.
 *
 * [onStartMix] defaults to routing through MixLauncher (which
 * GenerateViewModel and MainShell both listen to) rather than requiring
 * every call site to wire it — "Start Mix with this Song" now works
 * everywhere this sheet is used with zero per-screen changes. A caller can
 * still pass its own [onStartMix] to override that default if a screen
 * ever needs different behavior.
 *
 * [onExploreGenre] / [onDeleteScrobble] remain caller-supplied: those need
 * screen-specific navigation / API side effects this component doesn't own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackContextMenuSheet(
    target: TrackMenuTarget,
    capabilities: TrackMenuCapabilities,
    playableTrack: PlayableTrack? = null,
    onDismiss: () -> Unit,
    onPlayInLastWave: (() -> Unit)? = null,
    onStartMix: ((trackName: String, artistName: String) -> Unit)? = null,
    onExploreGenre: ((genre: String) -> Unit)? = null,
    onDeleteScrobble: ((trackName: String, artistName: String) -> Unit)? = null,
    onRefreshArtwork: (() -> Unit)? = null,
    genreResolverViewModel: GenreRowViewModel = hiltViewModel(),
    startMixViewModel: StartMixMenuViewModel = hiltViewModel(),
    exploreGenreViewModel: ExploreGenreMenuViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val musicPlayer = LocalMusicPlayer.current
    val addToPlaylist = LocalAddToPlaylist.current
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState()
    val startMix = onStartMix ?: { name: String, artist: String -> startMixViewModel.startMix(name, artist) }
    val exploreGenre = onExploreGenre ?: { genre: String -> exploreGenreViewModel.explore(genre) }

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

    // Previously washed toward primaryContainer for an accent tint — on a
    // system-derived dynamic scheme, primaryContainer can resolve to a
    // pale, light swatch (exactly what Material You's tonal palette design
    // calls for) that reads as jarringly bright against this app's dark
    // theme. But setting this to the SAME surfaceContainerHigh the grouped
    // rows below use made the sheet background and the row cards
    // indistinguishable — the grouped-container look needs the sheet
    // itself a shade darker than its rows for the rows to read as
    // containers at all, same as Settings/Generator (plain screen
    // background behind surfaceContainerHigh rows).
    val sheetContainerColor = MaterialTheme.colorScheme.surfaceContainer

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = sheetContainerColor,
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp).padding(bottom = 24.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (isTrack) {
                val t = target as TrackMenuTarget.Track

                StartMixCard {
                    startMix(t.name, t.artist); onDismiss()
                }

                // Every row below (Genre info line + all actions) is one
                // grouped surface now — Settings/Generator's look — instead
                // of separate transparent rows with gaps between them.
                // Built as a list first so the first/last row (whichever
                // ones actually render, since Genre/Refresh/Copy/Delete
                // are each conditional) get the group's rounded top/bottom
                // corners rather than a hardcoded index.
                val rows = buildList<@Composable (GroupPosition) -> Unit> {
                    if (resolvingGenre || !genre.isNullOrBlank() || genre == "") {
                        val resolvedGenre = genre
                        // Clickable itself now (opens that genre in the
                        // Genres tab via GenreExplorer) instead of a plain
                        // display-only row with a separate, redundant
                        // "Explore this genre" action further down — one
                        // row, one obvious tap target, same place your eye
                        // already goes to read the genre.
                        add { pos ->
                            MenuInfoRow(
                                icon = Icons.Filled.Sell,
                                text = if (resolvingGenre) "Resolving genre\u2026" else "Genre: ${resolvedGenre?.takeIf { it.isNotBlank() } ?: "Unknown"}",
                                loading = resolvingGenre,
                                position = pos,
                                onClick = if (!resolvingGenre && !resolvedGenre.isNullOrBlank()) {
                                    { exploreGenre(resolvedGenre); onDismiss() }
                                } else null,
                            )
                        }
                    }
                    val playable = playableTrack ?: PlayableTrack(title = t.name, artist = t.artist)
                    add { pos ->
                        MenuActionRow(Icons.Filled.PlayCircle, "Play in LastWave", position = pos) {
                            onPlayInLastWave?.invoke() ?: musicPlayer.play(playable)
                            onDismiss()
                        }
                    }
                    add { pos -> MenuActionRow(Icons.Filled.QueuePlayNext, "Play next", position = pos) { musicPlayer.playNext(playable); onDismiss() } }
                    add { pos -> MenuActionRow(Icons.Filled.QueueMusic, "Add to queue", position = pos) { musicPlayer.addToQueue(playable); onDismiss() } }
                    add { pos -> MenuActionRow(Icons.Filled.PlaylistAdd, "Add to playlist", position = pos) { addToPlaylist(playable); onDismiss() } }
                    add { pos -> MenuActionRow(Icons.Filled.Language, "Open in Last.fm", position = pos) { openUrl(context, buildLastFmUrl(target)); onDismiss() } }
                    if (onRefreshArtwork != null) {
                        add { pos -> MenuActionRow(Icons.Filled.Refresh, "Refresh Cover Art", position = pos) { onRefreshArtwork(); onDismiss() } }
                    }
                    if (capabilities.showCopyActions) {
                        add { pos -> MenuActionRow(Icons.Filled.ContentCopy, "Copy Song", position = pos) { clipboard.setText(AnnotatedString("${t.name} \u2014 ${t.artist}")); onDismiss() } }
                    }
                    if (capabilities.showDeleteScrobble && onDeleteScrobble != null) {
                        add { pos -> MenuActionRow(Icons.Filled.Delete, "Delete Scrobble", danger = true, position = pos) { onDeleteScrobble(t.name, t.artist); onDismiss() } }
                    }
                }
                ExpressiveGroup(rowCount = rows.size) { index, position -> rows[index](position) }
            } else if (target is TrackMenuTarget.Artist) {
                ExpressiveGroup(rowCount = 1) { _, position ->
                    MenuActionRow(Icons.Filled.Person, "Open in Last.fm", position = position) { openUrl(context, buildLastFmUrl(target)); onDismiss() }
                }
            } else if (target is TrackMenuTarget.Album) {
                ExpressiveGroup(rowCount = 1) { _, position ->
                    MenuActionRow(Icons.Filled.OpenInNew, "Open in Last.fm", position = position) { openUrl(context, buildLastFmUrl(target)); onDismiss() }
                }
            }
        }
    }
}

/**
 * "Start Mix with this Song" (§6) — a featured card rather than a plain
 * text row, so it reads as the primary action in the sheet. Colored with
 * the app's live accent (MaterialTheme.colorScheme.primary), which already
 * reflects whichever source is currently driving the theme — a manual
 * preset, Monochrome, wallpaper Dynamic Color, or (when active) the
 * Dynamic Now Playing Theme's extracted artwork palette. There's no
 * separate "which accent source" branch to maintain here: reading
 * colorScheme.primary is inherently correct for all of them since that's
 * exactly the value ThemeRepository recomputes for whichever source is
 * currently active.
 */
@Composable
private fun StartMixCard(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "startMixScale",
    )

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
        // 0dp deliberately — see ModeCard/SettingsToggleCard for why a
        // nonzero tonalElevation would blend a second tinted layer on top
        // of containerColor here.
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(22.dp),
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .scale(scale),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Shuffle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(16.dp))
            Text(
                "Start Mix with this Song",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A rounded, elevated-feeling row (real Card, not a flat clickable Row) —
 *  each menu action is one row in the shared ExpressiveGroup surface (see
 *  the call site), so the whole set of actions reads as one continuous
 *  premium container instead of separate floating rows — same language as
 *  Settings/Generator's grouped lists. */
@Composable
private fun MenuActionRow(icon: ImageVector, label: String, danger: Boolean = false, position: GroupPosition = GroupPosition.SINGLE, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale = rememberGroupPressScale(interactionSource)
    val contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val badgeColor = if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val badgeContentColor = if (danger) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = groupShape(position),
        interactionSource = interactionSource,
        modifier = Modifier.fillMaxWidth().scale(scale),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(badgeColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = badgeContentColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MenuInfoRow(
    icon: ImageVector,
    text: String,
    loading: Boolean,
    position: GroupPosition = GroupPosition.SINGLE,
    onClick: (() -> Unit)? = null,
) {
    Card(
        onClick = onClick ?: {},
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = groupShape(position),
        // Not actually interactive when there's no genre to open yet
        // (still resolving, or resolution came back empty) — no ripple,
        // no press feedback pretending there's something to tap.
        enabled = onClick != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (loading) {
                ExpressiveInlineLoadingIndicator(
                    modifier = Modifier.padding(start = 8.dp),
                    size = 14.dp,
                    strokeWidth = 2.dp,
                )
            } else if (onClick != null) {
                Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            }
        }
    }
}
