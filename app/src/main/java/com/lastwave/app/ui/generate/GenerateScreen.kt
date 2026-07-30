package com.lastwave.app.ui.generate

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lastwave.app.ui.shell.FloatingNavDefaults

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = FloatingNavDefaults.contentBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    Text("Generator", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Choose a mode to generate a playlist", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (state.isGenerating) {
                item(key = "loadingOverlay") {
                    com.lastwave.app.ui.common.GenerationProgressCard(message = state.loadingMessage)
                }
            }

            items(GenerateMode.entries.toList(), key = { it.name }) { mode ->
                ModeRow(
                    label = mode.label,
                    description = mode.description,
                    icon = iconFor(mode),
                    selected = state.selectedMode == mode,
                    onClick = { if (!state.isGenerating) viewModel.selectMode(mode) },
                )
            }

            state.selectedMode?.let { mode ->
                if (!state.isGenerating) {
                    item(key = "options") {
                        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            Column(Modifier.padding(16.dp)) {
                                when (mode) {
                                    GenerateMode.TOP, GenerateMode.LIBRARY -> PeriodOptions(state.period, viewModel::setPeriod)
                                    GenerateMode.RECENT -> HintText("Uses your most recent scrobbles")
                                    GenerateMode.SIMILAR_TRACKS -> SimilarTrackSeedOptions(state, viewModel)
                                    GenerateMode.SIMILAR_ARTISTS -> SimilarArtistSeedOptions(state, viewModel)
                                    GenerateMode.TAG -> TagOptions(state.tagInput, viewModel::setTagInput, viewModel::setGenreChip)
                                    GenerateMode.MIX -> HintText("Mix includes: top tracks, recent plays & similar artists' tracks.")
                                    GenerateMode.RECOMMENDATIONS -> HintText("Track count is set by the slider below")
                                }

                                Spacer(Modifier.height(16.dp))
                                Text("Track count: ${state.trackCount}", style = MaterialTheme.typography.labelLarge)
                                Slider(
                                    value = state.trackCount.toFloat(),
                                    onValueChange = { viewModel.setTrackCount(it.toInt()) },
                                    valueRange = 5f..35f,
                                    steps = 29,
                                    enabled = !state.isGenerating,
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = viewModel::generate, enabled = !state.isGenerating, modifier = Modifier.fillMaxWidth()) {
                                    Text("Generate Playlist")
                                }
                            }
                        }
                    }
                }
            }

            state.error?.let { error ->
                item(key = "error") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = viewModel::dismissError) { Text("Dismiss") }
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

@Composable
private fun HintText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ModeRow(label: String, description: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "modeCardBg",
    )
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PeriodOptions(period: String, onPeriodChange: (String) -> Unit) {
    Text("Time Period", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(GENERATE_PERIODS, key = { it.first }) { (value, label) ->
            FilterChip(selected = period == value, onClick = { onPeriodChange(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun TagOptions(tag: String, onTagChange: (String) -> Unit, onChipPick: (String) -> Unit) {
    Text("Genre or Tag", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(value = tag, onValueChange = onTagChange, placeholder = { Text("e.g. rock, lofi, jazz\u2026") }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(GENRE_QUICK_CHIPS, key = { it }) { chip ->
            FilterChip(selected = tag == chip, onClick = { onChipPick(chip) }, label = { Text(chip) })
        }
    }
}

@Composable
private fun SimilarTrackSeedOptions(state: GenerateUiState, viewModel: GenerateViewModel) {
    Text("Seed Track", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.seedTrackName,
            onValueChange = viewModel::setSeedTrackName,
            placeholder = { Text("Track name\u2026") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = viewModel::searchSeedTrack) { Text("Search") }
    }
    Spacer(Modifier.height(10.dp))
    Text("Seed Artist", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = state.seedArtistName,
        onValueChange = viewModel::setSeedArtistName,
        placeholder = { Text("Artist name\u2026") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    TextButton(onClick = viewModel::loadTopTracksForSeed) { Text("Load My Top Tracks") }
    if (state.seedTrackResults.isNotEmpty()) {
        Column(Modifier.height(160.dp)) {
            LazyColumn {
                items(state.seedTrackResults, key = { it.key }) { t ->
                    TextButton(onClick = { viewModel.pickSeedTrack(t) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${t.name} \u2014 ${t.artist}", modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SimilarArtistSeedOptions(state: GenerateUiState, viewModel: GenerateViewModel) {
    Text("Seed Artist", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.seedArtistQuery,
            onValueChange = viewModel::setSeedArtistQuery,
            placeholder = { Text("Artist name\u2026") },
            singleLine = true,
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
                    TextButton(onClick = { viewModel.pickSeedArtist(name) }, modifier = Modifier.fillMaxWidth()) {
                        Text(name, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
