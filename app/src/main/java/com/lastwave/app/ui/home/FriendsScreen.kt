package com.lastwave.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveGroup
import com.lastwave.app.ui.common.ExpressiveGroupTrackRow
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.common.safeHorizontalContentPadding
private val FriendsContainerShape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * A real pushed screen now, not a Dialog/ModalBottomSheet — see the long
 * comment trail this replaced in HomeScreen.kt's old FriendsSheet for the
 * full history. Every other pushed screen in this app (Settings, Search,
 * Discover, Genres, ScrobblerApps) already renders correctly edge-to-edge
 * using this exact Scaffold-less ExpressiveHeader + Column pattern, so
 * Friends now just follows the same proven structure instead of being a
 * one-off overlay fighting Compose's Dialog/BottomSheet sizing quirks.
 *
 * Shares HomeViewModel with the Home tab (see NavGraph.kt — scoped to
 * MainShell's own back stack entry) rather than owning its own state, so
 * switching to a friend's profile here is immediately reflected on Home
 * once you navigate back.
 */
@Composable
fun FriendsScreen(
    viewModel: HomeViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    // Loads the friends list the first time this screen is reached, same
    // trigger openFriendsSheet() used to provide via the old sheet's open
    // action — now driven by navigation arriving here instead.
    LaunchedEffect(Unit) {
        viewModel.openFriendsSheet()
    }

    val friends = uiState.sortedFriends
    val pinnedFriends = uiState.pinnedFriends
    val isLoading = uiState.isLoadingFriends
    val isViewingFriend = uiState.isViewingFriend

    Column(Modifier.fillMaxSize()) {
        ExpressiveHeader(
            title = "Friends",
            subtitle = if (friends.isNotEmpty()) "Long-press a friend to pin them to the top" else null,
            onBack = onBack,
        )

        androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))

        // Wrapped in the app's own rounded "list container" surface, same
        // idea as Home's own track list — but using a shape rounded ONLY
        // at the top (matching Home's own local override, not the theme
        // default's all-four-corners version): rounding the BOTTOM
        // corners too, on a box that's flush with the true screen edge,
        // clips those corners right at the physical edge and leaves a
        // small curved sliver of the plain screen background showing
        // through in both bottom corners — exactly the persistent "gap
        // near the nav gesture area" this was. Flush + square at the
        // bottom is what actually reads as genuinely edge-to-edge; the
        // "container's end" is instead made visible by real bottom
        // padding around the last row (below), not a corner cutout.
        Box(
            Modifier
                .fillMaxSize()
                .clip(FriendsContainerShape)
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeHorizontalContentPadding()
                    .padding(horizontal = 12.dp)
                    .padding(
                        top = 12.dp,
                        bottom = 24.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
                    )
                    // The actual bug: this Column never had a scroll
                    // modifier at all, so anything past whatever fit in
                    // one screen's worth of height was simply unreachable
                    // — not a sizing issue this time, just a genuinely
                    // missing modifier from the Dialog-to-screen
                    // conversion.
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
            when {
                isLoading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    com.lastwave.app.ui.common.ExpressiveLoadingIndicator(message = "Loading friends")
                }
                friends.isEmpty() -> Text(
                    "No friends found on this Last.fm account.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                else -> {
                    val rowCount = friends.size + if (isViewingFriend) 1 else 0
                    ExpressiveGroup(rowCount = rowCount) { index, position ->
                        if (isViewingFriend && index == 0) {
                            ExpressiveGroupTrackRow(
                                title = "Back to my profile",
                                subtitle = "View your own data again",
                                position = position,
                                onClick = {
                                    viewModel.returnToOwnProfile()
                                    onBack()
                                },
                                leading = {
                                    Box(
                                        Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                },
                            )
                        } else {
                            val friend = friends[index - if (isViewingFriend) 1 else 0]
                            val isPinned = friend.name in pinnedFriends
                            ExpressiveGroupTrackRow(
                                title = friend.displayName,
                                subtitle = "@${friend.name}",
                                position = position,
                                onClick = {
                                    viewModel.viewFriend(friend)
                                    onBack()
                                },
                                onLongClick = { viewModel.toggleFriendPinned(friend.name) },
                                leading = {
                                    if (!friend.avatarUrl.isNullOrBlank()) {
                                        ArtworkImage(
                                            name = "friend",
                                            artist = friend.name,
                                            embeddedUrl = friend.avatarUrl,
                                            fallbackIcon = Icons.Filled.AccountCircle,
                                            modifier = Modifier.size(44.dp).clip(CircleShape),
                                        )
                                    } else {
                                        Box(
                                            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                },
                                trailing = if (isPinned) {
                                    { Icon(Icons.Filled.PushPin, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
                                } else null,
                            )
                        }
                    }
                }
            }
        }
        }
    }
}
