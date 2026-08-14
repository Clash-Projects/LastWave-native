package com.lastwave.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lastwave.app.ui.common.ExpressiveHeader

/**
 * Watch MediaScrobbleListenerService's actual decisions live — added after
 * several rounds of hypothesis-fixes for "scrobbles never appear" that
 * were each plausible but unconfirmed without any way to see what the
 * service was actually doing. Open this WHILE a track plays: every track
 * detection, threshold calculation, reset, and scrobble attempt (success
 * or the exact failure reason) shows up here as it happens, no adb/logcat
 * needed. In-memory only — clears when the app process restarts.
 */
@Composable
fun ScrobblerDebugLogScreen(
    onBack: () -> Unit = {},
    viewModel: ScrobblerDebugLogViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsState()

    Column(Modifier.fillMaxSize()) {
        ExpressiveHeader(
            title = "Scrobbler debug log",
            subtitle = "Live \u2014 keep this open while a track plays",
            onBack = onBack,
            actions = {
                IconButton(onClick = viewModel::clear) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear log")
                }
            },
        )

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing logged yet.\nPlay a track from one of your chosen apps and watch it appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(entries) { entry ->
                    Text(
                        entry,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
