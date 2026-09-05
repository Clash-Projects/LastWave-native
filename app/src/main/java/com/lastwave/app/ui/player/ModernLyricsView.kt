package com.lastwave.app.ui.player

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlaybackProgressState
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.ui.common.ExpressiveMotion
import com.mocharealm.accompanist.lyrics.core.model.Artist
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

@Composable
fun ModernLyricsPanel(
    state: MusicPlayerState,
    player: MusicPlayer,
    lyricsState: LyricsUiState,
    progressState: StateFlow<PlaybackProgressState>? = null,
    wavySeekbarEnabled: Boolean = true,
    onOpenPlayer: (() -> Unit)? = null,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val track = state.current ?: return

    val progress by (progressState ?: player.progressState).collectAsStateWithLifecycle(
        initialValue = PlaybackProgressState(positionMs = state.positionMs, durationMs = state.durationMs),
    )

    var smoothedPositionMs by remember(track.videoId) { mutableLongStateOf(progress.positionMs) }
    var basePositionMs by remember(track.videoId) { mutableLongStateOf(progress.positionMs) }
    var lastSyncTime by remember(track.videoId) { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(progress.positionMs, state.isPlaying) {
        basePositionMs = progress.positionMs
        lastSyncTime = SystemClock.elapsedRealtime()
        smoothedPositionMs = progress.positionMs
    }

    LaunchedEffect(state.isPlaying) {
        if (!state.isPlaying) return@LaunchedEffect
        while (isActive) {
            withFrameMillis {
                val elapsed = SystemClock.elapsedRealtime() - lastSyncTime
                val dur = progress.durationMs.takeIf { it > 0 } ?: state.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
                smoothedPositionMs = (basePositionMs + elapsed).coerceIn(0L, dur)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = lyricsState,
            transitionSpec = {
                (fadeIn(tween(ExpressiveMotion.Quick)) +
                    androidx.compose.animation.scaleIn(ExpressiveMotion.spatialSpring(), initialScale = 0.96f)) togetherWith
                    (fadeOut(tween(ExpressiveMotion.Quick)) +
                        androidx.compose.animation.scaleOut(tween(ExpressiveMotion.Quick), targetScale = 0.96f))
            },
            label = "modernLyricsStateContent",
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
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

                is LyricsUiState.Empty, is LyricsUiState.Error -> {
                    ModernEmptyLyricsView(
                        isInstrumental = false,
                        onRetry = onRetry,
                    )
                }

                is LyricsUiState.Success -> {
                    if (targetState.isInstrumental) {
                        ModernEmptyLyricsView(
                            isInstrumental = true,
                            onRetry = onRetry,
                        )
                    } else if (targetState.isSynced && targetState.lines.isNotEmpty()) {
                        val syncedLyrics = remember(targetState.lines, track.title, track.artist) {
                            targetState.lines.toSyncedLyrics(track.title, track.artist)
                        }

                        val initialLineIndex = remember(syncedLyrics) {
                            val time = smoothedPositionMs.toInt()
                            val idx = syncedLyrics.lines.indexOfFirst { time in it.start..it.end }
                            if (idx != -1) idx else syncedLyrics.lines.indexOfFirst { it.start > time }.takeIf { it != -1 } ?: 0
                        }
                        val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialLineIndex)

                        KaraokeLyricsView(
                            listState = listState,
                            lyrics = syncedLyrics,
                            showTranslation = true,
                            showPhonetic = true,
                            currentPosition = { smoothedPositionMs.toInt() },
                            onLineClicked = { line ->
                                player.seekTo(line.start.toLong())
                            },
                            onLinePressed = {},
                            modifier = Modifier.fillMaxSize(),
                            offset = 64.dp,
                            normalLineTextStyle = LocalTextStyle.current.copy(
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Black,
                                textMotion = TextMotion.Animated,
                            ),
                            accompanimentLineTextStyle = LocalTextStyle.current.copy(
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                textMotion = TextMotion.Animated,
                            ),
                            textColor = Color.White,
                        )
                    } else if (!targetState.plainLyrics.isNullOrBlank()) {
                        ModernPlainLyricsView(
                            plainLyrics = targetState.plainLyrics,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        ModernEmptyLyricsView(
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

        ModernLyricsControls(
            state = state,
            currentPositionMs = smoothedPositionMs,
            totalDurationMs = if (progress.durationMs > 0) progress.durationMs else state.durationMs,
            player = player,
            wavySeekbarEnabled = wavySeekbarEnabled,
            onOpenPlayer = onOpenPlayer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp, top = 6.dp),
        )
    }
}

private fun LyricLine.toISyncedLine(): ISyncedLine {
    val lineStart = timeMs.toInt()
    val lineEnd = if (durationMs > 0) (timeMs + durationMs).toInt()
    else if (syllables.isNotEmpty()) (syllables.last().timeMs + syllables.last().durationMs).toInt()
    else lineStart + 1500

    return if (hasSyllables) {
        KaraokeLine.MainKaraokeLine(
            syllables = syllables.map { syl ->
                val sStart = syl.timeMs.toInt()
                val sEnd = (syl.timeMs + syl.durationMs).toInt().coerceAtLeast(sStart)
                KaraokeSyllable(
                    content = syl.text,
                    start = sStart,
                    end = sEnd,
                )
            },
            translation = null,
            phonetic = transliteration,
            alignment = KaraokeAlignment.Unspecified,
            start = lineStart,
            end = lineEnd.coerceAtLeast(lineStart),
        )
    } else {
        SyncedLine(
            start = lineStart,
            end = lineEnd.coerceAtLeast(lineStart),
            content = text,
            translation = transliteration,
        )
    }
}

private fun List<LyricLine>.toSyncedLyrics(title: String, artist: String): SyncedLyrics {
    return SyncedLyrics(
        lines = map { it.toISyncedLine() },
        title = title,
        artists = listOf(Artist(type = "artist", name = artist)),
    )
}

@Composable
private fun ModernPlainLyricsView(
    plainLyrics: String,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(top = 24.dp, bottom = 90.dp, start = 16.dp, end = 16.dp),
    ) {
        Row(
            modifier = Modifier.padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.SyncDisabled,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Lyrics not time-synced",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = plainLyrics,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 19.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.1.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.94f),
        )
    }
}

@Composable
private fun ModernEmptyLyricsView(
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
            Icon(
                imageVector = if (isInstrumental) Icons.Filled.MusicOff else Icons.Filled.Lyrics,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

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
                    "No synced lyrics found for this track."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (!isInstrumental) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Try again")
                }
            }
        }
    }
}

@Composable
private fun ModernLyricsControls(
    state: MusicPlayerState,
    currentPositionMs: Long,
    totalDurationMs: Long,
    player: MusicPlayer,
    wavySeekbarEnabled: Boolean = true,
    onOpenPlayer: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (onOpenPlayer != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val playerInteraction = remember { MutableInteractionSource() }
                val isPlayerPressed by playerInteraction.collectIsPressedAsState()
                val playerScale by animateFloatAsState(
                    targetValue = if (isPlayerPressed) 0.82f else 1.0f,
                    animationSpec = ExpressiveMotion.spatialSpring(),
                    label = "playerTabScale",
                )
                Surface(
                    onClick = onOpenPlayer,
                    interactionSource = playerInteraction,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.40f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .size(46.dp)
                        .graphicsLayer {
                            scaleX = playerScale
                            scaleY = playerScale
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Fullscreen,
                            contentDescription = "Now playing",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }

        var dragging by remember { mutableStateOf(false) }
        var dragValue by remember { mutableFloatStateOf(0f) }
        val end = totalDurationMs.coerceAtLeast(1).toFloat()
        val shown = if (dragging) dragValue else currentPositionMs.coerceIn(0, totalDurationMs.coerceAtLeast(0)).toFloat()

        if (wavySeekbarEnabled) {
            WavySeekBar(
                positionMs = currentPositionMs,
                durationMs = totalDurationMs,
                isPlaying = state.isPlaying,
                onSeek = player::seekTo,
                isTranslucent = false,
                trackKey = state.current?.let { it.videoId ?: "${it.artist}|${it.title}" },
                showTimeLabels = false,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            PlayerProgressSlider(
                value = shown.coerceIn(0f, end),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { player.seekTo(dragValue.toLong()); dragging = false },
                valueRange = 0f..end,
                enabled = totalDurationMs > 0,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatTime(shown.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.94f),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = player::previous,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.40f)),
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        "Previous",
                        Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Surface(
                    onClick = player::togglePlayPause,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (state.isBuffering) {
                            ExpressiveInlineLoadingIndicator(
                                size = 22.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.5.dp,
                            )
                        } else {
                            AnimatedPlayPauseIcon(state.isPlaying, Modifier.size(28.dp))
                        }
                    }
                }

                IconButton(
                    onClick = player::next,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.40f)),
                ) {
                    Icon(
                        Icons.Filled.SkipNext,
                        "Next",
                        Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Text(
                "−${formatTime((totalDurationMs - shown.toLong()).coerceAtLeast(0))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.94f),
            )
        }
    }
}
