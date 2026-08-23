package com.lastwave.app.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.data.lyrics.LyricsResult
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.ui.common.ExpressiveMotion
import kotlinx.coroutines.delay

sealed interface LyricsUiState {
    data object Idle : LyricsUiState
    data object Loading : LyricsUiState
    data class Success(
        val lines: List<LyricLine>,
        val isSynced: Boolean,
        val plainLyrics: String? = null,
        val isInstrumental: Boolean = false,
    ) : LyricsUiState
    data object Empty : LyricsUiState
    data class Error(val message: String) : LyricsUiState
}

@Composable
fun LyricsPanel(
    state: MusicPlayerState,
    player: MusicPlayer,
    lyricsState: LyricsUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.current ?: return

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        // Main lyrics display area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AnimatedContent(
                targetState = lyricsState,
                transitionSpec = {
                    (fadeIn(tween(ExpressiveMotion.Quick)) +
                        androidx.compose.animation.scaleIn(ExpressiveMotion.spatialSpring(), initialScale = 0.96f)) togetherWith
                        (fadeOut(tween(ExpressiveMotion.Quick)) +
                            androidx.compose.animation.scaleOut(tween(ExpressiveMotion.Quick), targetScale = 0.96f))
                },
                label = "lyricsStateContent",
                modifier = Modifier.fillMaxSize(),
            ) { targetState ->
                when (targetState) {
                    is LyricsUiState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                ExpressiveInlineLoadingIndicator(
                                    size = 42.dp,
                                    strokeWidth = 3.5.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    "Finding lyrics…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    is LyricsUiState.Empty -> {
                        EmptyLyricsView(
                            title = track.title,
                            artist = track.artist,
                            isInstrumental = false,
                            onRetry = onRetry,
                        )
                    }

                    is LyricsUiState.Error -> {
                        EmptyLyricsView(
                            title = track.title,
                            artist = track.artist,
                            isInstrumental = false,
                            onRetry = onRetry,
                        )
                    }

                    is LyricsUiState.Success -> {
                        if (targetState.isInstrumental) {
                            EmptyLyricsView(
                                title = track.title,
                                artist = track.artist,
                                isInstrumental = true,
                                onRetry = onRetry,
                            )
                        } else if (targetState.isSynced && targetState.lines.isNotEmpty()) {
                            SyncedLyricsList(
                                lines = targetState.lines,
                                currentPositionMs = state.positionMs,
                                isPlaying = state.isPlaying,
                                onSeek = player::seekTo,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else if (!targetState.plainLyrics.isNullOrBlank()) {
                            PlainLyricsView(
                                plainLyrics = targetState.plainLyrics,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            EmptyLyricsView(
                                title = track.title,
                                artist = track.artist,
                                isInstrumental = false,
                                onRetry = onRetry,
                            )
                        }
                    }

                    is LyricsUiState.Idle -> {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }

        // Bottom compact playback bar in lyrics view
        LyricsBottomControls(
            state = state,
            player = player,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp, top = 8.dp),
        )
    }
}

@Composable
private fun SyncedLyricsList(
    lines: List<LyricLine>,
    currentPositionMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var userScrolledTime by remember { mutableLongStateOf(0L) }

    // Find active line index: the last line whose timestamp <= current playback position
    val activeIndex = remember(lines, currentPositionMs) {
        lines.indexOfLast { it.timeMs <= currentPositionMs }
    }

    // Detect user dragging to avoid fighting user manual scroll
    if (listState.isScrollInProgress) {
        userScrolledTime = System.currentTimeMillis()
    }

    // Auto-scroll when active index changes and user isn't actively interacting
    LaunchedEffect(activeIndex, isPlaying) {
        val timeSinceUserScroll = System.currentTimeMillis() - userScrolledTime
        if (timeSinceUserScroll > 2500L && activeIndex in lines.indices) {
            // Scroll to keep active line comfortably in upper-middle view
            listState.animateScrollToItem(
                index = activeIndex,
                scrollOffset = -180,
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(
            top = 40.dp,
            bottom = 120.dp,
            start = 8.dp,
            end = 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        itemsIndexed(lines, key = { index, line -> "$index:${line.timeMs}" }) { index, line ->
            val isActive = index == activeIndex
            val isPast = activeIndex >= 0 && index < activeIndex

            val scale by animateFloatAsState(
                targetValue = if (isActive) 1.04f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                label = "lyricScale_$index",
            )
            val alpha by animateFloatAsState(
                targetValue = when {
                    isActive -> 1f
                    isPast -> 0.45f
                    else -> 0.35f
                },
                animationSpec = tween(120),
                label = "lyricAlpha_$index",
            )
            val textColor by animateColorAsState(
                targetValue = if (isActive) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(120),
                label = "lyricColor_$index",
            )
            val bgTint by animateColorAsState(
                targetValue = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else Color.Transparent,
                animationSpec = tween(120),
                label = "lyricBg_$index",
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .clip(RoundedCornerShape(16.dp))
                    .background(bgTint)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        onSeek(line.timeMs)
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = line.text.ifBlank { "♪" },
                    style = if (isActive) {
                        MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.2).sp,
                            lineHeight = 34.sp,
                        )
                    } else {
                        MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 30.sp,
                        )
                    },
                    color = textColor,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

@Composable
private fun PlainLyricsView(
    plainLyrics: String,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(top = 24.dp, bottom = 80.dp, start = 16.dp, end = 16.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 20.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.SyncDisabled,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Lyrics not time-synced",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = plainLyrics,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 18.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
        )
    }
}

@Composable
private fun EmptyLyricsView(
    title: String,
    artist: String,
    isInstrumental: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(72.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isInstrumental) Icons.Filled.MusicOff else Icons.Filled.Lyrics,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = if (isInstrumental) "Instrumental" else "No lyrics",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = if (isInstrumental) {
                    "This track has no vocal lyrics."
                } else {
                    "Couldn't find lyrics for this song on LRCLIB."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (!isInstrumental) {
                Spacer(Modifier.height(6.dp))
                FilledTonalButton(
                    onClick = onRetry,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Try again")
                }
            }
        }
    }
}

@Composable
private fun LyricsBottomControls(
    state: MusicPlayerState,
    player: MusicPlayer,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
        tonalElevation = 2.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            var dragging by remember { mutableStateOf(false) }
            var dragValue by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
            val end = state.durationMs.coerceAtLeast(1).toFloat()
            val shown = if (dragging) dragValue else state.positionMs.coerceIn(0, state.durationMs.coerceAtLeast(0)).toFloat()

            // Seek slider
            Slider(
                value = shown.coerceIn(0f, end),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { player.seekTo(dragValue.toLong()); dragging = false },
                valueRange = 0f..end,
                enabled = state.durationMs > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatTime(shown.toLong()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = player::previous,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(22.dp))
                    }

                    Surface(
                        onClick = player::togglePlayPause,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(42.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (state.isBuffering) {
                                ExpressiveInlineLoadingIndicator(
                                    size = 20.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                AnimatedPlayPauseIcon(state.isPlaying, Modifier.size(24.dp))
                            }
                        }
                    }

                    IconButton(
                        onClick = player::next,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Filled.SkipNext, "Next", Modifier.size(22.dp))
                    }
                }

                Text(
                    "−${formatTime((state.durationMs - shown.toLong()).coerceAtLeast(0))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
