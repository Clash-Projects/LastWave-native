package com.lastwave.app.ui.player

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.local.LyricsAnimation
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlaybackProgressState
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.theme.LocalLiquidGlass
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

sealed interface LyricsUiState {
    data object Idle : LyricsUiState
    data object Loading : LyricsUiState
    data class Success(
        val lines: List<LyricLine>,
        val isSynced: Boolean,
        val isWordSynced: Boolean = false,
        val plainLyrics: String? = null,
        val isInstrumental: Boolean = false,
        val source: String? = null,
    ) : LyricsUiState
    data object Empty : LyricsUiState
    data class Error(val message: String) : LyricsUiState
}

@Composable
fun LyricsPanel(
    state: MusicPlayerState,
    player: MusicPlayer,
    lyricsState: LyricsUiState,
    progressState: StateFlow<PlaybackProgressState>? = null,
    lyricsAnimation: LyricsAnimation = LyricsAnimation.APPLE_FLUID,
    wavySeekbarEnabled: Boolean = true,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.current ?: return
    val liquidGlass = LocalLiquidGlass.current

    // High-frequency live progress stream
    val progress by (progressState ?: player.progressState).collectAsStateWithLifecycle(
        initialValue = PlaybackProgressState(positionMs = state.positionMs, durationMs = state.durationMs),
    )

    // High-precision frame-level monotonic position clock for 60/120fps bit-perfect vocal sync
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

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        // Weighted layout only: deliberately no card, background, border, or shadow.
        AnimatedContent(
            targetState = lyricsState,
            transitionSpec = {
                (fadeIn(tween(ExpressiveMotion.Quick)) +
                    androidx.compose.animation.scaleIn(ExpressiveMotion.spatialSpring(), initialScale = 0.96f)) togetherWith
                    (fadeOut(tween(ExpressiveMotion.Quick)) +
                        androidx.compose.animation.scaleOut(tween(ExpressiveMotion.Quick), targetScale = 0.96f))
            },
            label = "lyricsStateContent",
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
                    EmptyLyricsView(
                        isInstrumental = false,
                        onRetry = onRetry,
                    )
                }

                is LyricsUiState.Success -> {
                    if (targetState.isInstrumental) {
                        EmptyLyricsView(
                            isInstrumental = true,
                            onRetry = onRetry,
                        )
                    } else if (targetState.isSynced && targetState.lines.isNotEmpty()) {
                        SyncedLyricsList(
                            lines = targetState.lines,
                            currentPositionMs = smoothedPositionMs,
                            isPlaying = state.isPlaying,
                            onSeek = player::seekTo,
                            animationStyle = lyricsAnimation,
                            liquidGlass = liquidGlass,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (!targetState.plainLyrics.isNullOrBlank()) {
                        PlainLyricsView(
                            plainLyrics = targetState.plainLyrics,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        EmptyLyricsView(
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

        // Transparent playback controls; no separate player-bar container.
        LyricsPlaybackControls(
            state = state,
            currentPositionMs = smoothedPositionMs,
            totalDurationMs = if (progress.durationMs > 0) progress.durationMs else state.durationMs,
            player = player,
            wavySeekbarEnabled = wavySeekbarEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp, top = 6.dp),
        )
    }
}

@Composable
private fun SyncedLyricsList(
    lines: List<LyricLine>,
    currentPositionMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    animationStyle: LyricsAnimation,
    liquidGlass: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var userScrolledTime by remember { mutableLongStateOf(0L) }

    // Active line detection: Exact millisecond vocal onset matching
    val activeIndex by remember(lines, currentPositionMs) {
        androidx.compose.runtime.derivedStateOf {
            lines.indexOfLast { it.timeMs <= currentPositionMs }
        }
    }

    if (listState.isScrollInProgress) {
        userScrolledTime = System.currentTimeMillis()
    }

    LaunchedEffect(activeIndex, isPlaying) {
        val timeSinceUserScroll = System.currentTimeMillis() - userScrolledTime
        if (timeSinceUserScroll > 2200L && activeIndex in lines.indices) {
            val scrollOffset = when (animationStyle) {
                LyricsAnimation.APPLE_ZOOM -> -210
                LyricsAnimation.CINEMATIC_BLUR -> -190
                else -> -180
            }
            listState.animateScrollToItem(
                index = activeIndex,
                scrollOffset = scrollOffset,
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(
            top = 40.dp,
            bottom = 130.dp,
            start = 12.dp,
            end = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(
            when (animationStyle) {
                LyricsAnimation.APPLE_ZOOM -> 22.dp
                LyricsAnimation.CARD_POP -> 16.dp
                else -> 18.dp
            },
        ),
    ) {
        itemsIndexed(lines, key = { index, line -> "$index:${line.timeMs}" }) { index, line ->
            val isActive = index == activeIndex
            val isPast = activeIndex >= 0 && index < activeIndex
            val distance = kotlin.math.abs(index - activeIndex)

            // Each profile gets a distinct motion signature. These targets only
            // change when focus changes (except the short onset pulse below), so
            // off-screen/inactive rows never run a permanent animation loop.
            val scaleTarget = when (animationStyle) {
                LyricsAnimation.APPLE_FLUID -> if (isActive) 1.085f else if (distance == 1) 0.99f else 0.975f
                LyricsAnimation.KARAOKE_PULSE -> if (isActive) 1.10f else if (distance == 1) 0.99f else 0.97f
                LyricsAnimation.KINETIC_SLIDE -> if (isActive) 1.045f else if (isPast) 0.99f else 0.975f
                LyricsAnimation.CINEMATIC_BLUR -> if (isActive) 1.065f else if (distance == 1) 0.96f else 0.93f
                LyricsAnimation.LOSSLESS_GLOW -> if (isActive) 1.075f else if (distance == 1) 0.99f else 0.97f
                LyricsAnimation.CARD_POP -> if (isActive) 1.065f else 0.985f
                LyricsAnimation.APPLE_ZOOM -> when {
                    isActive -> 1.18f
                    distance == 1 -> 0.94f
                    else -> 0.88f
                }
                LyricsAnimation.MINIMAL_WAVE -> 1f
            }

            val scaleSpec: AnimationSpec<Float> = when (animationStyle) {
                LyricsAnimation.KARAOKE_PULSE -> spring(
                    dampingRatio = 0.62f,
                    stiffness = Spring.StiffnessLow,
                )
                LyricsAnimation.APPLE_FLUID, LyricsAnimation.APPLE_ZOOM -> spring(
                    dampingRatio = 0.74f,
                    stiffness = Spring.StiffnessMediumLow,
                )
                LyricsAnimation.CARD_POP -> spring(
                    dampingRatio = 0.68f,
                    stiffness = Spring.StiffnessMedium,
                )
                LyricsAnimation.MINIMAL_WAVE -> tween(100)
                else -> spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                )
            }

            val scale by animateFloatAsState(
                targetValue = scaleTarget,
                animationSpec = scaleSpec,
                label = "lyricScale_$index",
            )

            // A short, position-locked vocal onset pulse. It settles cleanly
            // when paused and does not need an infinite transition clock.
            val onsetElapsedMs = (currentPositionMs - line.timeMs).coerceAtLeast(0L)
            val onsetPhase = (onsetElapsedMs / 520f).coerceIn(0f, 1f)
            val onsetWave = if (isActive && isPlaying && onsetPhase < 1f) {
                kotlin.math.sin(Math.PI.toFloat() * onsetPhase)
            } else 0f
            val pulseScale = when (animationStyle) {
                LyricsAnimation.KARAOKE_PULSE -> 1f + 0.045f * onsetWave
                LyricsAnimation.APPLE_FLUID -> 1f + 0.014f * onsetWave
                LyricsAnimation.LOSSLESS_GLOW -> 1f + 0.010f * onsetWave
                else -> 1f
            }

            // Horizontal focus tracking / directional entry and exit.
            val translationXTarget = when (animationStyle) {
                LyricsAnimation.APPLE_FLUID -> when {
                    isActive -> 4f
                    isPast -> 0f
                    else -> -5f
                }
                LyricsAnimation.KARAOKE_PULSE -> if (isActive) 3f else 0f
                LyricsAnimation.KINETIC_SLIDE -> when {
                    isActive -> 0f
                    isPast -> 12f
                    else -> -24f
                }
                LyricsAnimation.LOSSLESS_GLOW -> if (isActive) 2f else 0f
                LyricsAnimation.MINIMAL_WAVE -> when {
                    isActive -> 2f
                    isPast -> 0f
                    else -> -2f
                }
                else -> 0f
            }
            val translationX by animateFloatAsState(
                targetValue = translationXTarget,
                animationSpec = when (animationStyle) {
                    LyricsAnimation.KINETIC_SLIDE -> spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
                    LyricsAnimation.APPLE_FLUID -> spring(dampingRatio = 0.80f, stiffness = Spring.StiffnessMediumLow)
                    LyricsAnimation.MINIMAL_WAVE -> tween(110)
                    else -> spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
                },
                label = "lyricTransX_$index",
            )

            // Vertical depth drift gives past/future lines a readable direction.
            val translationYTarget = when (animationStyle) {
                LyricsAnimation.APPLE_FLUID -> when {
                    isActive -> -2f
                    isPast -> -1f
                    else -> 3f
                }
                LyricsAnimation.KARAOKE_PULSE -> if (isActive) -2f else 1f
                LyricsAnimation.KINETIC_SLIDE -> if (isActive) -1f else 1f
                LyricsAnimation.CINEMATIC_BLUR -> when {
                    isActive -> 0f
                    isPast -> -10f
                    else -> 10f
                }
                LyricsAnimation.LOSSLESS_GLOW -> if (isActive) -2f else 1f
                LyricsAnimation.CARD_POP -> if (isActive) -4f else 2f
                LyricsAnimation.APPLE_ZOOM -> when {
                    isActive -> -3f
                    isPast -> -1f
                    else -> 2f
                }
                LyricsAnimation.MINIMAL_WAVE -> if (isPast) -1f else if (isActive) 0f else 1f
            }
            val translationY by animateFloatAsState(
                targetValue = translationYTarget,
                animationSpec = when (animationStyle) {
                    LyricsAnimation.CINEMATIC_BLUR -> spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessLow)
                    LyricsAnimation.CARD_POP -> spring(dampingRatio = 0.70f, stiffness = Spring.StiffnessMediumLow)
                    LyricsAnimation.MINIMAL_WAVE -> tween(100)
                    else -> spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMedium)
                },
                label = "lyricTransY_$index",
            )

            val rotationTarget = when (animationStyle) {
                LyricsAnimation.KINETIC_SLIDE -> when {
                    isActive -> 0f
                    isPast -> 0.35f
                    else -> -0.65f
                }
                LyricsAnimation.CARD_POP -> when {
                    isActive -> 0f
                    isPast -> -0.35f
                    else -> 0.55f
                }
                else -> 0f
            }
            val rotation by animateFloatAsState(
                targetValue = rotationTarget,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
                label = "lyricRotation_$index",
            )
            val depthRotationTarget = when (animationStyle) {
                LyricsAnimation.CINEMATIC_BLUR -> when {
                    isActive -> 0f
                    isPast -> -1.25f
                    else -> 1.25f
                }
                LyricsAnimation.CARD_POP -> when {
                    isActive -> 0f
                    isPast -> -0.6f
                    else -> 0.8f
                }
                else -> 0f
            }
            val depthRotation by animateFloatAsState(
                targetValue = depthRotationTarget,
                animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                label = "lyricDepth_$index",
            )

            // Alpha Floor
            val alphaTarget = when (animationStyle) {
                LyricsAnimation.APPLE_FLUID -> if (isActive) 1f else if (distance == 1) 0.64f else if (isPast) 0.50f else 0.43f
                LyricsAnimation.KARAOKE_PULSE -> if (isActive) 1f else if (isPast) 0.62f else 0.49f
                LyricsAnimation.KINETIC_SLIDE -> if (isActive) 1f else if (isPast) 0.54f else 0.42f
                LyricsAnimation.CINEMATIC_BLUR -> if (isActive) 1f else if (distance <= 1) 0.58f else 0.28f
                LyricsAnimation.LOSSLESS_GLOW -> if (isActive) 1f else if (distance == 1) 0.66f else 0.46f
                LyricsAnimation.CARD_POP -> if (isActive) 1f else if (isPast) 0.62f else 0.48f
                LyricsAnimation.APPLE_ZOOM -> if (isActive) 1f else if (distance == 1) 0.55f else 0.32f
                LyricsAnimation.MINIMAL_WAVE -> if (isActive) 1f else if (distance == 1) 0.58f else 0.38f
            }
            val alpha by animateFloatAsState(
                targetValue = alphaTarget,
                animationSpec = tween(if (animationStyle == LyricsAnimation.MINIMAL_WAVE) 90 else 160),
                label = "lyricAlpha_$index",
            )

            val interactiveColor = MaterialTheme.colorScheme.primary
            val textColor by animateColorAsState(
                targetValue = if (isActive) {
                    when {
                        liquidGlass -> MaterialTheme.colorScheme.onPrimaryContainer
                        animationStyle == LyricsAnimation.LOSSLESS_GLOW -> interactiveColor
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                animationSpec = tween(140),
                label = "lyricColor_$index",
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale * pulseScale
                        scaleY = scale * pulseScale
                        this.alpha = alpha
                        this.translationX = translationX * density
                        this.translationY = translationY * density
                        rotationZ = rotation
                        rotationX = depthRotation
                        if (depthRotation != 0f) cameraDistance = 24f * density
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        onSeek(line.timeMs)
                    }
                    .padding(
                        horizontal = if (animationStyle == LyricsAnimation.CARD_POP) 16.dp else 12.dp,
                        vertical = if (isActive) 10.dp else 8.dp,
                    ),
            ) {
                val fontStyle = if (isActive) {
                    MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = if (animationStyle == LyricsAnimation.APPLE_ZOOM) FontWeight.Black else FontWeight.ExtraBold,
                        letterSpacing = (-0.3).sp,
                        lineHeight = if (animationStyle == LyricsAnimation.APPLE_ZOOM) 38.sp else 34.sp,
                    )
                } else {
                    MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 30.sp,
                    )
                }

                WordByWordLyricLine(
                    line = line,
                    currentPositionMs = currentPositionMs,
                    isActive = isActive,
                    activeColor = textColor,
                    inactiveColor = MaterialTheme.colorScheme.onSurface,
                    liquidGlass = liquidGlass,
                    accentColor = interactiveColor,
                    animationStyle = animationStyle,
                    fontStyle = fontStyle,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordByWordLyricLine(
    line: LyricLine,
    currentPositionMs: Long,
    isActive: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    liquidGlass: Boolean,
    accentColor: Color,
    animationStyle: LyricsAnimation,
    fontStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    if (!line.hasSyllables || !isActive) {
        Column(modifier = modifier) {
            Text(
                text = line.text.ifBlank { "♪" },
                style = fontStyle,
                color = if (isActive) activeColor else inactiveColor,
                textAlign = TextAlign.Start,
            )
            if (!line.transliteration.isNullOrBlank() && isActive) {
                Text(
                    text = line.transliteration,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.2.sp,
                    ),
                    color = activeColor.copy(alpha = 0.72f),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        return
    }

    Column(modifier = modifier) {
        FlowRow(
            horizontalArrangement = Arrangement.Start,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            line.syllables.forEachIndexed { sIndex, syllable ->
                val sylStart = syllable.timeMs
                val sylEnd = syllable.timeMs + syllable.durationMs
                val isSyllableActive = currentPositionMs in sylStart until sylEnd
                val isSyllablePast = currentPositionMs >= sylEnd

                val sylScaleTarget = if (isSyllableActive) {
                    when (animationStyle) {
                        LyricsAnimation.APPLE_FLUID -> 1.08f
                        LyricsAnimation.KARAOKE_PULSE -> 1.13f
                        LyricsAnimation.KINETIC_SLIDE -> 1.07f
                        LyricsAnimation.CINEMATIC_BLUR -> 1.05f
                        LyricsAnimation.LOSSLESS_GLOW -> 1.09f
                        LyricsAnimation.CARD_POP -> 1.07f
                        LyricsAnimation.APPLE_ZOOM -> 1.11f
                        LyricsAnimation.MINIMAL_WAVE -> 1.02f
                    }
                } else 1f
                val sylScale by animateFloatAsState(
                    targetValue = sylScaleTarget,
                    animationSpec = when (animationStyle) {
                        LyricsAnimation.KARAOKE_PULSE -> spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)
                        LyricsAnimation.APPLE_FLUID, LyricsAnimation.APPLE_ZOOM -> spring(
                            dampingRatio = 0.72f,
                            stiffness = Spring.StiffnessMediumLow,
                        )
                        LyricsAnimation.MINIMAL_WAVE -> tween(70)
                        else -> spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
                    },
                    label = "sylScale_${sIndex}",
                )

                val sylLiftTarget = if (isSyllableActive) {
                    when (animationStyle) {
                        LyricsAnimation.KARAOKE_PULSE, LyricsAnimation.CARD_POP, LyricsAnimation.APPLE_ZOOM -> -3f
                        LyricsAnimation.APPLE_FLUID, LyricsAnimation.KINETIC_SLIDE, LyricsAnimation.LOSSLESS_GLOW -> -2f
                        LyricsAnimation.CINEMATIC_BLUR -> -1f
                        LyricsAnimation.MINIMAL_WAVE -> 0f
                    }
                } else 0f
                val sylLift by animateFloatAsState(
                    targetValue = sylLiftTarget,
                    animationSpec = if (animationStyle == LyricsAnimation.MINIMAL_WAVE) {
                        tween(70)
                    } else {
                        spring(dampingRatio = 0.76f, stiffness = Spring.StiffnessMediumLow)
                    },
                    label = "sylLift_${sIndex}",
                )

                val sylAlphaTarget = when {
                    isSyllableActive -> 1f
                    isSyllablePast -> 0.96f
                    else -> when (animationStyle) {
                        LyricsAnimation.CINEMATIC_BLUR -> 0.28f
                        LyricsAnimation.APPLE_ZOOM -> 0.34f
                        LyricsAnimation.MINIMAL_WAVE -> 0.52f
                        else -> 0.40f
                    }
                }
                val sylAlpha by animateFloatAsState(
                    targetValue = sylAlphaTarget,
                    animationSpec = tween(60),
                    label = "sylAlpha_${sIndex}",
                )

                val sylColor by animateColorAsState(
                    targetValue = when {
                        isSyllableActive -> if (liquidGlass) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            accentColor
                        }
                        isSyllablePast -> activeColor
                        else -> inactiveColor.copy(alpha = 0.40f)
                    },
                    animationSpec = tween(80),
                    label = "sylColor_${sIndex}",
                )

                Text(
                    text = syllable.text,
                    style = fontStyle,
                    color = sylColor,
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = sylScale
                            scaleY = sylScale
                            translationY = sylLift * density
                            alpha = sylAlpha
                        },
                )
            }
        }

        if (!line.transliteration.isNullOrBlank()) {
            Text(
                text = line.transliteration,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp,
                ),
                color = activeColor.copy(alpha = 0.76f),
                modifier = Modifier.padding(top = 4.dp),
            )
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
private fun EmptyLyricsView(
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
                TextButton(
                    onClick = onRetry,
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
private fun LyricsPlaybackControls(
    state: MusicPlayerState,
    currentPositionMs: Long,
    totalDurationMs: Long,
    player: MusicPlayer,
    wavySeekbarEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // This Column performs layout only. It intentionally draws no container.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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
