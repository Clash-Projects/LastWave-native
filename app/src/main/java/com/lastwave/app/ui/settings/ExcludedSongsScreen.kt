package com.lastwave.app.ui.settings

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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.generate.GenerateRepository
import com.lastwave.app.data.local.db.RecommendationExclusionEntity
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.LiquidGlassCard
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ExcludedSongsViewModel @Inject constructor(
    private val generateRepository: GenerateRepository,
) : ViewModel() {
    val exclusions = generateRepository.observeRecommendationExclusions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun allowAgain(trackKey: String) {
        viewModelScope.launch {
            generateRepository.removeRecommendationExclusion(trackKey)
        }
    }
}

@Composable
fun ExcludedSongsScreen(
    onBack: () -> Unit,
    viewModel: ExcludedSongsViewModel = hiltViewModel(),
) {
    val exclusions by viewModel.exclusions.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 860.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
        ExpressiveHeader(
            title = "Excluded Songs",
            subtitle = "${exclusions.size} song${if (exclusions.size == 1) "" else "s"} hidden from recommendations",
            onBack = onBack,
        )

        if (exclusions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(84.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Block,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                    Text(
                        "No Excluded Songs",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Songs you mark “Don't recommend again” will appear here. You can restore each one separately.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 14.dp,
                    bottom = 28.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(exclusions, key = RecommendationExclusionEntity::trackKey) { exclusion ->
                    ExcludedSongRow(
                        exclusion = exclusion,
                        onAllowAgain = { viewModel.allowAgain(exclusion.trackKey) },
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun ExcludedSongRow(
    exclusion: RecommendationExclusionEntity,
    onAllowAgain: () -> Unit,
) {
    val legacyParts = exclusion.trackKey.split('|', limit = 2)
    val title = exclusion.trackName.ifBlank {
        legacyParts.firstOrNull().orEmpty().legacyDisplayName("Unknown song")
    }
    val artist = exclusion.artistName.ifBlank {
        legacyParts.getOrNull(1).orEmpty().legacyDisplayName("Unknown artist")
    }
    val date = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        .format(Date(exclusion.excludedAtMillis))

    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.size(46.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Block,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$artist • Excluded $date",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            FilledTonalButton(
                onClick = onAllowAgain,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Filled.RestartAlt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text("Restore")
            }
        }
    }
}

private fun String.legacyDisplayName(fallback: String): String =
    trim().takeIf(String::isNotEmpty)
        ?.replaceFirstChar { if (it.isLowerCase()) it.uppercaseChar() else it }
        ?: fallback
