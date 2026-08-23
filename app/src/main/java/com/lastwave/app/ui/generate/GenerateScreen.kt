package com.lastwave.app.ui.generate

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lastwave.app.data.generate.RECOMMENDATION_TRACK_COUNT
import com.lastwave.app.ui.common.ExpressiveGroup
import com.lastwave.app.ui.common.ExpressiveGroupSelectRow
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.wobbleOverscroll
import com.lastwave.app.ui.shell.FloatingNavDefaults
import com.lastwave.app.ui.theme.ExpressivePillShape

// Local expressive shape scale, matching the Settings screen's redesign so
// Generator feels like part of the same premium visual language.
private val GeneratorCardShape = RoundedCornerShape(20.dp)
private val GeneratorOuterCardShape = RoundedCornerShape(24.dp)
private val IconBadgeShape = RoundedCornerShape(14.dp)

/**
 * Playlist generation itself (GenerateViewModel: mode selection, options,
 * track count, the generate call) is completely untouched here — this file
 * only changes how it's presented: expressive mode cards with icon badges
 * and press feedback, a cleaner options card, and Material 3 Expressive
 * typography/spacing throughout.
 */
@Composable
fun GenerateScreen(
    onNavigateToPlaylist: () -> Unit = {},
    viewModel: GenerateViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.navEvents.collect { event ->
            when (event) {
                GenerateNavEvent.NavigateToPlaylistLoading -> onNavigateToPlaylist()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        ExpressiveHeader(
            title = "Generator",
            subtitle = "Choose a mode to generate a playlist",
        )

        com.lastwave.app.ui.common.WithoutPlatformOverscroll {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = FloatingNavDefaults.contentBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds().wobbleOverscroll(),
        ) {
            if (state.isGenerating) {
                item(key = "loadingOverlay") {
                    com.lastwave.app.ui.common.GenerationProgressCard(message = state.loadingMessage)
                }
            }

            item(key = "modeGroup") {
                val modes = GenerateMode.entries.toList()
                ExpressiveGroup(rowCount = modes.size) { index, position ->
                    val mode = modes[index]
                    val (badgeContainer, badgeTint) = badgeColorsFor(iconFor(mode))
                    ExpressiveGroupSelectRow(
                        icon = iconFor(mode),
                        title = mode.label,
                        subtitle = mode.description,
                        selected = state.selectedMode == mode,
                        position = position,
                        onClick = { if (!state.isGenerating) viewModel.selectMode(mode) },
                        badgeContainer = badgeContainer,
                        badgeTint = badgeTint,
                    )
                }
            }

            state.selectedMode?.let { mode ->
                if (!state.isGenerating) {
                    item(key = "options") {
                        Card(
                            shape = GeneratorOuterCardShape,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(20.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(36.dp)
                                            .clip(IconBadgeShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.Tune,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text("Options", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(16.dp))

                                when (mode) {
                                    GenerateMode.TOP, GenerateMode.LIBRARY -> PeriodOptions(state.period, viewModel::setPeriod)
                                    GenerateMode.RECENT -> HintText("Uses your most recent scrobbles")
                                    GenerateMode.SIMILAR_TRACKS -> SimilarTrackSeedOptions(state, viewModel)
                                    GenerateMode.SIMILAR_ARTISTS -> SimilarArtistSeedOptions(state, viewModel)
                                    GenerateMode.TAG -> TagOptions(state.tagInput, viewModel::setTagInput, viewModel::setGenreChip)
                                    GenerateMode.MIX -> HintText("Mix includes: top tracks, recent plays & similar artists' tracks.")
                                    GenerateMode.RECOMMENDATIONS -> HintText(
                                        "$RECOMMENDATION_TRACK_COUNT tracks, all outside Discovery History",
                                    )
                                }

                                Spacer(Modifier.height(20.dp))
                                if (mode == GenerateMode.RECOMMENDATIONS) {
                                    Text(
                                        "Track count: $RECOMMENDATION_TRACK_COUNT (fixed)",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Medium,
                                    )
                                } else {
                                    Text("Track count: ${state.trackCount}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                                    Slider(
                                        value = state.trackCount.toFloat(),
                                        onValueChange = { viewModel.setTrackCount(it.toInt()) },
                                        valueRange = 5f..35f,
                                        steps = 29,
                                        enabled = !state.isGenerating,
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = viewModel::generate,
                                    enabled = !state.isGenerating,
                                    shape = ExpressivePillShape,
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                ) {
                                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Generate Playlist", fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }

            state.error?.let { error ->
                item(key = "error") {
                    Card(
                        shape = GeneratorCardShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(Modifier.width(10.dp))
                                Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                            }
                            TextButton(onClick = viewModel::dismissError) { Text("Dismiss") }
                        }
                    }
                }
            }
        }
        }
    }
}

private fun iconFor(mode: GenerateMode): ImageVector = when (mode) {
    GenerateMode.TOP -> Icons.Filled.ThumbUp
    GenerateMode.RECENT -> Icons.Filled.History
    GenerateMode.SIMILAR_TRACKS -> Icons.Filled.MusicNote
    GenerateMode.SIMILAR_ARTISTS -> Icons.Filled.People
    GenerateMode.TAG -> Icons.Filled.Sell
    GenerateMode.MIX -> Icons.Filled.Shuffle
    GenerateMode.RECOMMENDATIONS -> Icons.Filled.AutoAwesome
    GenerateMode.LIBRARY -> Icons.Filled.LibraryMusic
}

/** Rotates through a few tonal container/content pairs so the mode list
 *  doesn't read as one flat column of identical badges — purely a visual
 *  rhythm device, no meaning attached to which mode gets which tone. */
@Composable
private fun badgeColorsFor(mode: ImageVector): Pair<Color, Color> {
    val palette = listOf(
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer,
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer,
    )
    val index = (mode.name.hashCode().let { if (it < 0) -it else it }) % palette.size
    return palette[index]
}

@Composable
private fun HintText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PeriodOptions(period: String, onPeriodChange: (String) -> Unit) {
    Text("Time Period", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(GENERATE_PERIODS, key = { it.first }) { (value, label) ->
            FilterChip(selected = period == value, onClick = { onPeriodChange(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun TagOptions(tag: String, onTagChange: (String) -> Unit, onChipPick: (String) -> Unit) {
    Text("Genre or Tag", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = tag,
        onValueChange = onTagChange,
        placeholder = { Text("e.g. rock, lofi, jazz\u2026") },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(GENRE_QUICK_CHIPS, key = { it }) { chip ->
            FilterChip(selected = tag == chip, onClick = { onChipPick(chip) }, label = { Text(chip) })
        }
    }
}

@Composable
private fun SimilarTrackSeedOptions(state: GenerateUiState, viewModel: GenerateViewModel) {
    Text("Seed Track", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.seedTrackName,
            onValueChange = viewModel::setSeedTrackName,
            placeholder = { Text("Track name\u2026") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = viewModel::searchSeedTrack) { Text("Search") }
    }
    Spacer(Modifier.height(10.dp))
    Text("Seed Artist", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.seedArtistName,
        onValueChange = viewModel::setSeedArtistName,
        placeholder = { Text("Artist name\u2026") },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    TextButton(onClick = viewModel::loadTopTracksForSeed) { Text("Load My Top Tracks") }
    if (state.seedTrackResults.isNotEmpty()) {
        Column(Modifier.height(160.dp)) {
            LazyColumn {
                items(state.seedTrackResults, key = { it.key }) { t ->
                    TextButton(
                        onClick = { viewModel.pickSeedTrack(t) },
                        modifier = Modifier.fillMaxWidth().animateItem(),
                    ) {
                        Text("${t.name} \u2014 ${t.artist}", modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SimilarArtistSeedOptions(state: GenerateUiState, viewModel: GenerateViewModel) {
    Text("Seed Artist", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.seedArtistQuery,
            onValueChange = viewModel::setSeedArtistQuery,
            placeholder = { Text("Artist name\u2026") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = viewModel::searchSeedArtist) { Text("Search") }
    }
    Spacer(Modifier.height(10.dp))
    TextButton(onClick = viewModel::loadTopArtistsForSeed) { Text("Load My Top Artists") }
    if (state.seedArtistResults.isNotEmpty()) {
        Column(Modifier.height(160.dp)) {
            LazyColumn {
                items(state.seedArtistResults, key = { it }) { name ->
                    TextButton(
                        onClick = { viewModel.pickSeedArtist(name) },
                        modifier = Modifier.fillMaxWidth().animateItem(),
                    ) {
                        Text(name, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
