package com.lastwave.app.ui.common

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * The one overflow ("more options") trigger used across every song list in
 * the app — Home, Discover, Playlist, Search, and Genre Detail all use this
 * same composable instead of each screen styling its own IconButton, so
 * the container looks and behaves identically everywhere.
 *
 * Narrower than tall (26dp x 32dp, not a 32dp square) — the left/right
 * padding around the 18dp icon is what shrank, height and corner radius
 * are unchanged from the previous pass. The leading padding below is the
 * real fix for the collision reports: none of the 5 call sites had a
 * Spacer between the preceding chip/badge and this button (checked all of
 * them directly — Home's "x2/x3" badge Surface sat flush against the old
 * IconButton with zero gap), so the gap needs to live here, once, rather
 * than be added at each call site and risk drifting out of sync.
 */
@Composable
fun OverflowMenuButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val darkThemeTone = lerp(
        MaterialTheme.colorScheme.surfaceContainerHigh,
        MaterialTheme.colorScheme.primaryContainer,
        0.55f,
    )
    FilledTonalIconButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = darkThemeTone,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = modifier
            .padding(start = 10.dp)
            .width(26.dp)
            .height(32.dp),
    ) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More options", modifier = Modifier.size(18.dp))
    }
}
