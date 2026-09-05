package com.lastwave.app.ui.auth

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lastwave.app.data.model.AuthState
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.theme.ExpressivePillShape

/**
 * Real one-tap sign-in: tap Connect, approve LastWave in the system
 * browser (Chrome Custom Tabs — the actual installed Chrome/default
 * browser, with its own saved passwords and autofill, not a bare WebView
 * this app draws itself), done. No API key, secret, username, or password
 * fields at all — the app key is baked in (LastFmAppCredentials) and the
 * session comes straight from Last.fm's own auth.getSession exchange. The
 * callback returns to LastWave and completes sign-in automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    authState: AuthState,
    webAuthState: WebAuthState,
    onBeginSignIn: () -> Unit,
    onReturnedFromBrowser: () -> Unit,
    onCancelWebAuth: () -> Unit,
    onSignOut: () -> Unit,
    onRestoreBackupAndSignIn: (String) -> Unit,
    onDismissError: () -> Unit,
) {
    val context = LocalContext.current
    var restoreReadError by remember { mutableStateOf<String?>(null) }
    val restoreBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val content = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not read this backup")
            }
            content.onSuccess {
                restoreReadError = null
                onRestoreBackupAndSignIn(it)
            }.onFailure {
                restoreReadError = it.message ?: "Could not read this backup"
            }
        }
    }

    // The web flow returns its authorized token through the app callback.
    val awaitingUrl = (webAuthState as? WebAuthState.AwaitingApproval)?.authUrl
    LaunchedEffect(awaitingUrl) {
        val state = webAuthState as? WebAuthState.AwaitingApproval ?: return@LaunchedEffect
        CustomTabsIntent.Builder().build().launchUrl(context, android.net.Uri.parse(state.authUrl))
    }

    // Fallback for a resumed Activity; MainActivity normally delivers the
    // callback directly before this lifecycle event.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onReturnedFromBrowser()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold { padding ->
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .adaptiveContentWidth(maxWidth = 480.dp)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
            Box(Modifier.padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            Text("LastWave", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Connect to Last.fm or restore your LastWave backup",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))

            if (webAuthState is WebAuthState.RestoringBackup) {
                com.lastwave.app.ui.common.ExpressiveLoadingIndicator(
                    message = "Restoring your music and settings…",
                )
            } else when (val state = authState) {
                is AuthState.SignedIn -> SignedInCard(username = state.username, onSignOut = onSignOut)

                AuthState.Unknown -> com.lastwave.app.ui.common.ExpressiveLoadingIndicator()

                else -> Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (webAuthState) {
                        is WebAuthState.AwaitingApproval -> {
                            com.lastwave.app.ui.common.ExpressiveLoadingIndicator(
                                message = "Waiting for approval in the browser\u2026",
                            )
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(
                                onClick = { CustomTabsIntent.Builder().build().launchUrl(context, android.net.Uri.parse(webAuthState.authUrl)) },
                                shape = ExpressivePillShape,
                            ) {
                                Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                                Text("Open Last.fm again")
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = onCancelWebAuth) { Text("Cancel") }
                        }
                        else -> {
                            val busy = state is AuthState.SigningIn || webAuthState is WebAuthState.CompletingSignIn
                            Button(
                                onClick = onBeginSignIn,
                                enabled = !busy,
                                shape = ExpressivePillShape,
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) {
                                if (busy) {
                                    com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator(
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        size = 20.dp,
                                    )
                                } else {
                                    Text("Connect with Last.fm")
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { restoreBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                                enabled = !busy,
                                shape = ExpressivePillShape,
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) {
                                Icon(
                                    Icons.Filled.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                Text("Restore backup & login")
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Choose a LastWave backup, then approve Last.fm. Your restored data and new login are kept together.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }

                    val errorMessage = (state as? AuthState.Error)?.message
                        ?: (webAuthState as? WebAuthState.Error)?.message
                        ?: restoreReadError
                    if (errorMessage != null) {
                        Spacer(Modifier.height(16.dp))
                        Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = onDismissError) { Text("Dismiss") }
                    }
                }
            }
            if (authState is AuthState.SignedIn && webAuthState is WebAuthState.Error) {
                Spacer(Modifier.height(16.dp))
                Text(
                    webAuthState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onDismissError) { Text("Continue") }
            }
        }
    }
}
}

@Composable
private fun SignedInCard(username: String, onSignOut: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("Signed in as $username", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onSignOut, shape = ExpressivePillShape) { Text("Sign out") }
        }
    }
}
