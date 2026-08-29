package com.lastwave.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
 * Displays 3 rows x 3 columns with large square album art, single-line thin song title,
 * and a subtle play badge overlay.
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
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Quick play",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(4.dp))

        LazyHorizontalGrid(
            rows = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxWidth()
                .height(375.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
        ) {
            items(tracks, key = { "${it.name}|${it.artist}" }) { track ->
                QuickPlaySymmetricalTile(
                    track = track,
                    onClick = { onTrackClick(track) },
                    modifier = Modifier.width(108.dp),
                )
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
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f),
        tonalElevation = 0.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Large Prominent 1:1 Square Artwork Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)),
            ) {
                ArtworkImage(
                    name = track.name,
                    artist = track.artist,
                    embeddedUrl = track.artworkUrl,
                    fallbackIcon = Icons.Filled.MusicNote,
                    modifier = Modifier.fillMaxSize(),
                )
                // Symmetrical Play Badge on Bottom Right
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(22.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Thin, small, single-line title only (no artist)
            Text(
                text = track.name,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Normal,
                    fontSize = TextUnit(9f, TextUnitType.Sp),
                    lineHeight = TextUnit(11f, TextUnitType.Sp),
                ),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 2.dp),
            )
        }
    }
}
