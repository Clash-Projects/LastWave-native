package com.lastwave.app.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lastwave.app.ui.common.ExpressiveGroupSelectRow
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.groupPositionFor
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance

@Composable
fun ScrobblerAppsScreen(
    onBack: () -> Unit = {},
    viewModel: ScrobblerAppsViewModel = hiltViewModel(),
) {
    val apps by viewModel.apps.collectAsState()
    val selected by viewModel.selectedPackages.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val query by viewModel.query.collectAsState()

    val detectedCount = apps.count { it.isKnownMusicPlayer }
    val undetectedSelected = detectedCount > 0 && apps.filter { it.isKnownMusicPlayer }.any { it.packageName !in selected }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ExpressiveHeader(
                title = "Choose apps",
                subtitle = "${selected.size} app${if (selected.size == 1) "" else "s"} selected",
                onBack = onBack,
            )

            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )

            if (detectedCount > 0 && undetectedSelected) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = viewModel::selectAllDetectedMusicPlayers) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                        Text("Select detected music players")
                    }
                }
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (apps.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isBlank()) "No apps found" else "No apps match \"$query\"",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = 32.dp + LocalMiniPlayerScrollClearance.current +
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    var lastWasSelected = true
                    itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                        val isSelected = app.packageName in selected
                        // Section label only where the sorted list actually
                        // transitions from selected to not-selected — apps
                        // is already grouped selected-first by the
                        // ViewModel, so a newly-selected app (whether
                        // auto-detected or picked by hand) immediately
                        // joins the rest of the selected apps at the top
                        // instead of staying wherever it happened to fall
                        // alphabetically.
                        if (query.isBlank() && index == 0 && isSelected) {
                            Text(
                                "Selected",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 4.dp, start = 4.dp),
                            )
                        }
                        if (query.isBlank() && lastWasSelected && !isSelected) {
                            Text(
                                "All apps",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp),
                            )
                        }
                        lastWasSelected = isSelected

                        ExpressiveGroupSelectRow(
                            icon = Icons.Filled.MusicNote,
                            title = app.label,
                            subtitle = app.packageName,
                            selected = isSelected,
                            position = groupPositionFor(index, apps.size),
                            onClick = { viewModel.toggle(app.packageName) },
                            leadingContent = if (app.icon != null) {
                                {
                                    Image(
                                        bitmap = app.icon,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                    )
                                }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}
