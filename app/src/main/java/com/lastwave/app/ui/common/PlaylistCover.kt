package com.lastwave.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lastwave.app.data.artwork.ArtworkNormalizer
import com.lastwave.app.data.playlist.SavedPlaylist

/** Custom cover first, then the earliest track carrying real artwork metadata. */
@Composable
fun PlaylistCover(
    playlist: SavedPlaylist,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
) {
    val shape = RoundedCornerShape(cornerRadius)
    var customCoverFailed by remember(playlist.customCoverUri) { mutableStateOf(false) }
    val automaticTrack = remember(playlist.tracks) {
        playlist.tracks.firstOrNull { ArtworkNormalizer.isRealImage(it.artworkUrl) }
            ?: playlist.tracks.firstOrNull()
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (!playlist.customCoverUri.isNullOrBlank() && !customCoverFailed) {
            AsyncImage(
                model = playlist.customCoverUri,
                contentDescription = "${playlist.title} cover",
                contentScale = ContentScale.Crop,
                onError = { customCoverFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (automaticTrack != null) {
            ArtworkImage(
                name = automaticTrack.name,
                artist = automaticTrack.artist,
                embeddedUrl = automaticTrack.artworkUrl,
                fallbackIcon = Icons.Filled.MusicNote,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
