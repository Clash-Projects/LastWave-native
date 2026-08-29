package com.lastwave.app.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.lastwave.app.data.repository.HomeTrack
import com.lastwave.app.ui.common.ArtworkImage

/**
 * 3x3 Symmetrical, Horizontally Scrollable Quick Play Grid with smooth snap animation.
 * Features YouTube Music style album art cards with fused bottom black gradients and song names.
 */
@Composable
fun QuickPlayGrid(
    tracks: List<HomeTrack>,
    onTrackClick: (HomeTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty()) return

    val gridState = rememberLazyGridState()
    val flingBehavior = rememberSnapFlingBehavior(lazyGridState = gridState)

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

        Spacer(Modifier.height(3.dp))

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val spacing = 4.dp
            // Exactly 3 columns fit 100% of the available width with tight, elegant spacing
            val tileWidth = (maxWidth - (spacing * 2)) / 3
            // 3 square tiles + 2 vertical gaps of 4.dp
            val gridHeight = (tileWidth * 3) + (spacing * 2)

            LazyHorizontalGrid(
                rows = GridCells.Fixed(3),
                state = gridState,
                flingBehavior = flingBehavior,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(gridHeight),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalArrangement = Arrangement.spacedBy(spacing),
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
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "quickPlayTileScale",
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        // Full background album artwork
        ArtworkImage(
            name = track.name,
            artist = track.artist,
            embeddedUrl = track.artworkUrl,
            fallbackIcon = Icons.Filled.MusicNote,
            modifier = Modifier.fillMaxSize(),
        )

        // Lower black gradient overlay (YouTube Music style)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.60f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.50f),
                            Color.Black.copy(alpha = 0.90f),
                        ),
                    ),
                ),
        )

        // Fused song title directly on top of the gradient
        Text(
            text = track.name,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Medium,
                fontSize = TextUnit(9.5f, TextUnitType.Sp),
                lineHeight = TextUnit(11.5f, TextUnitType.Sp),
            ),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 6.dp, vertical = 5.dp),
        )
    }
}
