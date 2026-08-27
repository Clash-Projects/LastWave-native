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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
import com.lastwave.app.ui.theme.liquidGlassChrome
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
    ambientPrimary: Color? = null,
    ambientSecondary: Color? = null,
    ambientDeep: Color? = null,
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

    // Reuse the exact artwork-derived palette already animating behind the
    // player tab. Theme colors remain a safe fallback when artwork extraction
    // is unavailable, so switching tabs feels like one continuous surface.
    val primary = ambientPrimary ?: MaterialTheme.colorScheme.primary
    val companion = ambientSecondary ?: androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.tertiary,
        primary,
        0.42f,
    )
    val deep = ambientDeep ?: androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.secondary,
        primary,
        0.25f,
    )
    val ambientModifier = Modifier.drawBehind {
        val maxDim = maxOf(size.width, size.height).coerceAtLeast(1f)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    primary.copy(alpha = if (liquidGlass) 0.19f else 0.12f),
                    primary.copy(alpha = if (liquidGlass) 0.055f else 0.025f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.22f, size.height * 0.22f),
                radius = maxDim * 0.78f,
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    companion.copy(alpha = if (liquidGlass) 0.16f else 0.10f),
                    deep.copy(alpha = if (liquidGlass) 0.045f else 0.024f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.88f, size.height * 0.72f),
                radius = maxDim * 0.72f,
            ),
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .then(ambientModifier),
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

                    is LyricsUiState.Empty, is LyricsUiState.Error -> {
                        EmptyLyricsView(
                            title = track.title,
                            artist = track.artist,
                            isInstrumental = false,
                            onRetry = onRetry,
                            liquidGlass = liquidGlass,
                            accentPrimary = primary,
                            accentSecondary = companion,
                        )
                    }

                    is LyricsUiState.Success -> {
                        if (targetState.isInstrumental) {
                            EmptyLyricsView(
                                title = track.title,
                                artist = track.artist,
                                isInstrumental = true,
                                onRetry = onRetry,
                                liquidGlass = liquidGlass,
                                accentPrimary = primary,
                                accentSecondary = companion,
                            )
                        } else if (targetState.isSynced && targetState.lines.isNotEmpty()) {
                            SyncedLyricsList(
                                lines = targetState.lines,
                                currentPositionMs = smoothedPositionMs,
                                isPlaying = state.isPlaying,
                                onSeek = player::seekTo,
                                animationStyle = lyricsAnimation,
                                liquidGlass = liquidGlass,
                                accentPrimary = primary,
                                accentSecondary = companion,
                                accentDeep = deep,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else if (!targetState.plainLyrics.isNullOrBlank()) {
                            PlainLyricsView(
                                plainLyrics = targetState.plainLyrics,
                                liquidGlass = liquidGlass,
                                accentPrimary = primary,
                                accentSecondary = companion,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            EmptyLyricsView(
                                title = track.title,
                                artist = track.artist,
                                isInstrumental = false,
                                onRetry = onRetry,
                                liquidGlass = liquidGlass,
                                accentPrimary = primary,
                                accentSecondary = companion,
                            )
                        }
                    }

                    is LyricsUiState.Idle -> {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(42.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.54f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(58.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
                            ),
                        ),
                    ),
            )
        }

        // Bottom compact playback bar in lyrics view
        LyricsBottomControls(
            state = state,
            currentPositionMs = smoothedPositionMs,
            totalDurationMs = if (progress.durationMs > 0) progress.durationMs else state.durationMs,
            player = player,
            liquidGlass = liquidGlass,
            accentPrimary = primary,
            accentSecondary = companion,
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
    accentPrimary: Color,
    accentSecondary: Color,
    accentDeep: Color,
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

            val primaryColor = accentPrimary
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

            // Container Background & Border
            val pillShape = RoundedCornerShape(18.dp)
            val cardBg = when {
                liquidGlass && animationStyle == LyricsAnimation.CARD_POP -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.90f)
                animationStyle == LyricsAnimation.CARD_POP -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.56f)
                else -> Color.Transparent
            }
            val activeBrush = if (!isActive || animationStyle == LyricsAnimation.MINIMAL_WAVE) {
                null
            } else {
                val colors = when (animationStyle) {
                    LyricsAnimation.APPLE_FLUID -> listOf(
                        primaryColor.copy(alpha = if (liquidGlass) 0.34f else 0.20f),
                        accentSecondary.copy(alpha = if (liquidGlass) 0.20f else 0.10f),
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.34f),
                    )
                    LyricsAnimation.KARAOKE_PULSE -> listOf(
                        primaryColor.copy(alpha = if (liquidGlass) 0.42f else 0.27f),
                        accentDeep.copy(alpha = if (liquidGlass) 0.30f else 0.18f),
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.30f),
                    )
                    LyricsAnimation.KINETIC_SLIDE -> listOf(
                        primaryColor.copy(alpha = if (liquidGlass) 0.38f else 0.23f),
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.26f),
                        Color.Transparent,
                    )
                    LyricsAnimation.CINEMATIC_BLUR -> listOf(
                        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = if (liquidGlass) 0.74f else 0.52f),
                        primaryColor.copy(alpha = 0.14f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.22f),
                    )
                    LyricsAnimation.LOSSLESS_GLOW -> listOf(
                        primaryColor.copy(alpha = if (liquidGlass) 0.50f else 0.32f),
                        accentSecondary.copy(alpha = if (liquidGlass) 0.36f else 0.23f),
                        accentDeep.copy(alpha = if (liquidGlass) 0.24f else 0.15f),
                    )
                    LyricsAnimation.CARD_POP -> listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (liquidGlass) 0.88f else 0.68f),
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (liquidGlass) 0.66f else 0.44f),
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.52f),
                    )
                    LyricsAnimation.APPLE_ZOOM -> listOf(
                        primaryColor.copy(alpha = if (liquidGlass) 0.30f else 0.16f),
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.30f),
                        Color.Transparent,
                    )
                    LyricsAnimation.MINIMAL_WAVE -> listOf(Color.Transparent, Color.Transparent)
                }
                Brush.linearGradient(colors)
            }

            val strokeBorder = when {
                isActive && animationStyle == LyricsAnimation.MINIMAL_WAVE -> null
                isActive && animationStyle == LyricsAnimation.LOSSLESS_GLOW -> BorderStroke(
                    1.25.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = if (liquidGlass) 0.52f else 0.20f),
                            primaryColor.copy(alpha = 0.72f),
                            accentSecondary.copy(alpha = 0.42f),
                        ),
                    ),
                )
                isActive && liquidGlass -> BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.40f),
                            primaryColor.copy(alpha = 0.50f),
                            Color.White.copy(alpha = 0.12f),
                        ),
                    ),
                )
                isActive && animationStyle == LyricsAnimation.CARD_POP -> BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                )
                isActive -> BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            primaryColor.copy(alpha = 0.44f),
                            accentDeep.copy(alpha = 0.24f),
                            Color.White.copy(alpha = 0.08f),
                        ),
                    ),
                )
                else -> null
            }

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
                        val elevated = animationStyle == LyricsAnimation.CARD_POP ||
                            animationStyle == LyricsAnimation.LOSSLESS_GLOW ||
                            animationStyle == LyricsAnimation.APPLE_ZOOM
                        if (isActive && elevated) {
                            shadowElevation = when (animationStyle) {
                                LyricsAnimation.CARD_POP -> 12.dp.toPx()
                                LyricsAnimation.LOSSLESS_GLOW -> 8.dp.toPx()
                                else -> 5.dp.toPx()
                            }
                            shape = pillShape
                            clip = animationStyle == LyricsAnimation.CARD_POP
                        }
                    }
                    .clip(pillShape)
                    .liquidGlassChrome(
                        pillShape,
                        liquidGlass && (isActive || animationStyle == LyricsAnimation.CARD_POP),
                    )
                    .then(
                        if (activeBrush != null) Modifier.background(activeBrush, pillShape)
                        else Modifier.background(cardBg, pillShape),
                    )
                    .then(if (strokeBorder != null) Modifier.border(strokeBorder, pillShape) else Modifier)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        onSeek(line.timeMs)
                    }
                    .padding(
                        horizontal = if (isActive || animationStyle == LyricsAnimation.CARD_POP) 16.dp else 12.dp,
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
    liquidGlass: Boolean,
    accentPrimary: Color,
    accentSecondary: Color,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val pillShape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(top = 24.dp, bottom = 90.dp, start = 16.dp, end = 16.dp),
    ) {
        Surface(
            shape = pillShape,
            color = if (liquidGlass) MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.74f)
            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.60f),
            border = BorderStroke(
                1.dp,
                if (liquidGlass) {
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.25f),
                            accentPrimary.copy(alpha = 0.24f),
                            accentSecondary.copy(alpha = 0.12f),
                            Color.White.copy(alpha = 0.05f),
                        ),
                    )
                } else {
                    Brush.linearGradient(
                        listOf(
                            accentPrimary.copy(alpha = 0.32f),
                            accentSecondary.copy(alpha = 0.16f),
                        ),
                    )
                },
            ),
            modifier = Modifier
                .padding(bottom = 20.dp)
                .liquidGlassChrome(pillShape, liquidGlass),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
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
    title: String,
    artist: String,
    isInstrumental: Boolean,
    onRetry: () -> Unit,
    liquidGlass: Boolean,
    accentPrimary: Color,
    accentSecondary: Color,
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
            val iconShape = CircleShape
            Surface(
                shape = iconShape,
                color = if (liquidGlass) MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.74f)
                else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.60f),
                border = BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            if (liquidGlass) Color.White.copy(alpha = 0.35f)
                            else accentPrimary.copy(alpha = 0.52f),
                            accentSecondary.copy(alpha = 0.22f),
                            Color.White.copy(alpha = 0.06f),
                        ),
                    ),
                ),
                tonalElevation = 1.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .size(72.dp)
                    .liquidGlassChrome(iconShape, liquidGlass),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isInstrumental) Icons.Filled.MusicOff else Icons.Filled.Lyrics,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        tint = if (liquidGlass) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
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
                    "No synced lyrics found for this track."
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
    currentPositionMs: Long,
    totalDurationMs: Long,
    player: MusicPlayer,
    liquidGlass: Boolean,
    accentPrimary: Color,
    accentSecondary: Color,
    modifier: Modifier = Modifier,
) {
    val barShape = RoundedCornerShape(26.dp)
    val barBg = if (liquidGlass) {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.60f)
    }

    Surface(
        shape = barShape,
        color = barBg,
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = if (liquidGlass) 0.32f else 0.12f),
                    accentPrimary.copy(alpha = 0.34f),
                    accentSecondary.copy(alpha = 0.18f),
                ),
            ),
        ),
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
        modifier = modifier.liquidGlassChrome(barShape, liquidGlass),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            var dragging by remember { mutableStateOf(false) }
            var dragValue by remember { mutableFloatStateOf(0f) }
            val end = totalDurationMs.coerceAtLeast(1).toFloat()
            val shown = if (dragging) dragValue else currentPositionMs.coerceIn(0, totalDurationMs.coerceAtLeast(0)).toFloat()

            PlayerProgressSlider(
                value = shown.coerceIn(0f, end),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { player.seekTo(dragValue.toLong()); dragging = false },
                valueRange = 0f..end,
                enabled = totalDurationMs > 0,
                modifier = Modifier.fillMaxWidth(),
            )

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
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.58f)),
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
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f),
                        ),
                        tonalElevation = 2.dp,
                        shadowElevation = 6.dp,
                        modifier = Modifier.size(44.dp),
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
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.58f)),
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
}
