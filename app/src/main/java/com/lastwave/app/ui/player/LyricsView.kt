package com.lastwave.app.ui.player

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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lastwave.app.data.local.LyricsAnimation
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.liquidGlassChrome
import kotlin.math.abs

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
    lyricsAnimation: LyricsAnimation = LyricsAnimation.APPLE_FLUID,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.current ?: return
    val liquidGlass = LocalLiquidGlass.current

    // Static ambient glass glow: depth without continuously invalidating the
    // full lyrics viewport while text is scrolling and animating.
    val ambientModifier = if (liquidGlass) {
        val primary = MaterialTheme.colorScheme.primary
        val tertiary = MaterialTheme.colorScheme.tertiary

        Modifier.drawBehind {
            val maxDim = maxOf(size.width, size.height).coerceAtLeast(1f)
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primary.copy(alpha = 0.16f),
                        primary.copy(alpha = 0.04f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.28f, size.height * 0.25f),
                    radius = maxDim * 0.80f,
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        tertiary.copy(alpha = 0.12f),
                        tertiary.copy(alpha = 0.03f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.82f, size.height * 0.70f),
                    radius = maxDim * 0.75f,
                ),
            )
        }
    } else {
        Modifier
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
                            )
                        } else if (targetState.isSynced && targetState.lines.isNotEmpty()) {
                            SyncedLyricsList(
                                lines = targetState.lines,
                                currentPositionMs = state.positionMs,
                                isPlaying = state.isPlaying,
                                onSeek = player::seekTo,
                                animationStyle = lyricsAnimation,
                                liquidGlass = liquidGlass,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else if (!targetState.plainLyrics.isNullOrBlank()) {
                            PlainLyricsView(
                                plainLyrics = targetState.plainLyrics,
                                liquidGlass = liquidGlass,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            EmptyLyricsView(
                                title = track.title,
                                artist = track.artist,
                                isInstrumental = false,
                                onRetry = onRetry,
                                liquidGlass = liquidGlass,
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
            liquidGlass = liquidGlass,
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

    // Sync lead compensation (600ms): compensates for AudioTrack buffer latency,
    // decoder output buffer, and animation interpolation so the active line highlights on the exact vocal onset.
    val activeIndex = remember(lines, currentPositionMs) {
        val adjustedPositionMs = currentPositionMs + 600L
        lines.indexOfLast { it.timeMs <= adjustedPositionMs }
    }

    if (listState.isScrollInProgress) {
        userScrolledTime = System.currentTimeMillis()
    }

    LaunchedEffect(activeIndex, isPlaying) {
        val timeSinceUserScroll = System.currentTimeMillis() - userScrolledTime
        if (timeSinceUserScroll > 2500L && activeIndex in lines.indices) {
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
            val distance = if (activeIndex >= 0) abs(index - activeIndex) else 10

            // ── Motion Physics Dispatcher (8 Distinct Movement Styles) ──

            val scaleTarget = when (animationStyle) {
                LyricsAnimation.APPLE_FLUID -> if (isActive) 1.06f else 1f
                LyricsAnimation.KARAOKE_PULSE -> if (isActive) 1.09f else 1f
                LyricsAnimation.KINETIC_SLIDE -> if (isActive) 1.03f else 1f
                LyricsAnimation.CINEMATIC_BLUR -> if (isActive) 1.04f else 0.98f
                LyricsAnimation.LOSSLESS_GLOW -> if (isActive) 1.05f else 1f
                LyricsAnimation.CARD_POP -> if (isActive) 1.04f else 0.99f
                LyricsAnimation.APPLE_ZOOM -> when {
                    isActive -> 1.15f
                    distance == 1 -> 0.96f
                    else -> 0.90f
                }
                LyricsAnimation.MINIMAL_WAVE -> 1f
            }

            val scaleSpec: AnimationSpec<Float> = when (animationStyle) {
                LyricsAnimation.KARAOKE_PULSE -> spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                )
                LyricsAnimation.APPLE_FLUID, LyricsAnimation.APPLE_ZOOM -> spring(
                    dampingRatio = 0.78f,
                    stiffness = Spring.StiffnessMediumLow,
                )
                LyricsAnimation.CARD_POP -> spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
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

            // Horizontal Slide (Kinetic Slide style)
            val translationXTarget = when (animationStyle) {
                LyricsAnimation.KINETIC_SLIDE -> if (isActive) 0f else -14f
                else -> 0f
            }
            val translationX by animateFloatAsState(
                targetValue = translationXTarget,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                label = "lyricTransX_$index",
            )

            // Vertical Drift (Cinematic Focus style)
            val translationYTarget = when (animationStyle) {
                LyricsAnimation.CINEMATIC_BLUR -> when {
                    isActive -> 0f
                    isPast -> -4f
                    else -> 4f
                }
                else -> 0f
            }
            val translationY by animateFloatAsState(
                targetValue = translationYTarget,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium),
                label = "lyricTransY_$index",
            )

            // Alpha Floor
            val alphaTarget = when (animationStyle) {
                LyricsAnimation.MINIMAL_WAVE -> if (isActive) 1f else 0.32f
                LyricsAnimation.CINEMATIC_BLUR -> if (isActive) 1f else if (distance <= 1) 0.38f else 0.22f
                LyricsAnimation.APPLE_ZOOM -> if (isActive) 1f else if (distance == 1) 0.45f else 0.28f
                else -> when {
                    isActive -> 1f
                    isPast -> 0.42f
                    else -> 0.30f
                }
            }
            val alpha by animateFloatAsState(
                targetValue = alphaTarget,
                animationSpec = tween(if (animationStyle == LyricsAnimation.MINIMAL_WAVE) 90 else 160),
                label = "lyricAlpha_$index",
            )

            val primaryColor = MaterialTheme.colorScheme.primary
            val textColor by animateColorAsState(
                targetValue = if (isActive) {
                    when {
                        liquidGlass -> MaterialTheme.colorScheme.onPrimaryContainer
                        animationStyle == LyricsAnimation.LOSSLESS_GLOW -> primaryColor
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(140),
                label = "lyricColor_$index",
            )

            // Container Background & Border (Liquid Glass and Card Pop integration)
            val pillShape = RoundedCornerShape(18.dp)
            val cardBg = when {
                isActive && liquidGlass -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.90f)
                isActive && animationStyle == LyricsAnimation.CARD_POP -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f)
                isActive -> primaryColor.copy(alpha = 0.08f)
                liquidGlass && animationStyle == LyricsAnimation.CARD_POP -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.90f)
                else -> Color.Transparent
            }

            val strokeBorder = when {
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
                else -> null
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                        this.translationX = translationX * density
                        this.translationY = translationY * density
                        if (animationStyle == LyricsAnimation.CARD_POP && isActive) {
                            shadowElevation = 8.dp.toPx()
                            shape = pillShape
                            clip = true
                        }
                    }
                    .clip(pillShape)
                    .liquidGlassChrome(
                        pillShape,
                        liquidGlass && (isActive || animationStyle == LyricsAnimation.CARD_POP),
                    )
                    .background(cardBg, pillShape)
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
                // Text typography & styling
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

                Text(
                    text = line.text.ifBlank { "♪" },
                    style = fontStyle,
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
    liquidGlass: Boolean,
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
            color = if (liquidGlass) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f)
            else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.70f),
            border = if (liquidGlass) BorderStroke(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.25f),
                        Color.White.copy(alpha = 0.05f),
                    ),
                ),
            ) else null,
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
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f),
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
                color = if (liquidGlass) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f)
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = if (liquidGlass) BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.08f)),
                    ),
                ) else null,
                modifier = Modifier
                    .size(72.dp)
                    .liquidGlassChrome(iconShape, liquidGlass),
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
    liquidGlass: Boolean,
    modifier: Modifier = Modifier,
) {
    val barShape = RoundedCornerShape(26.dp)
    val barBg = if (liquidGlass) {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f)
    }

    Surface(
        shape = barShape,
        color = barBg,
        tonalElevation = 2.dp,
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
            val end = state.durationMs.coerceAtLeast(1).toFloat()
            val shown = if (dragging) dragValue else state.positionMs.coerceIn(0, state.durationMs.coerceAtLeast(0)).toFloat()

            Slider(
                value = shown.coerceIn(0f, end),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { player.seekTo(dragValue.toLong()); dragging = false },
                valueRange = 0f..end,
                enabled = state.durationMs > 0,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = player::previous,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(24.dp))
                    }

                    Surface(
                        onClick = player::togglePlayPause,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
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
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(Icons.Filled.SkipNext, "Next", Modifier.size(24.dp))
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
