package com.lastwave.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.lastwave.app.data.repository.HomeTrack
import com.lastwave.app.ui.common.ArtworkImage

/**
 * 3x3 Symmetrical, Horizontally Scrollable Quick Play Grid.
 * Dynamically computes tile width so exactly 3 columns fit the screen width
 * (0% of 4th column peeking; accessible only via scrolling).
 * Displays thin, clearly visible song titles.
 */
@Composable
fun QuickPlayGrid(
    tracks: List<HomeTrack>,
    onTrackClick: (HomeTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Quick play",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(2.dp))

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val spacing = 6.dp
            // Exactly 3 columns fit 100% of the available width with no 4th column peeking
            val tileWidth = (maxWidth - (spacing * 2)) / 3

            LazyHorizontalGrid(
                rows = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                items(tracks, key = { "${it.name}|${it.artist}" }) { track ->
                    QuickPlaySymmetricalTile(
                        track = track,
                        onClick = { onTrackClick(track) },
                        modifier = Modifier.width(tileWidth),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickPlaySymmetricalTile(
    track: HomeTrack,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 1.dp, vertical = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Square Artwork Container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f)),
        ) {
            ArtworkImage(
                name = track.name,
                artist = track.artist,
                embeddedUrl = track.artworkUrl,
                fallbackIcon = Icons.Filled.MusicNote,
                modifier = Modifier.fillMaxSize(),
            )
            // Sleek, compact play badge overlay
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.5.dp)
                    .size(14.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(8.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(2.dp))

        // Thin, clearly visible song title
        Text(
            text = track.name,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Normal,
                fontSize = TextUnit(8.5f, TextUnitType.Sp),
                lineHeight = TextUnit(10.5f, TextUnitType.Sp),
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 1.dp),
        )
    }
}
