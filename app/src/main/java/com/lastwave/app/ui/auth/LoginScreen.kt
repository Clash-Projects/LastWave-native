package com.lastwave.app.ui.auth

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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lastwave.app.data.model.AuthState
import com.lastwave.app.ui.theme.ExpressivePillShape

/**
 * Real one-tap sign-in: tap Connect, approve LastWave in the system
 * browser (Chrome Custom Tabs — the actual installed Chrome/default
 * browser, with its own saved passwords and autofill, not a bare WebView
 * this app draws itself), done. No API key, secret, username, or password
 * fields at all — the app key is baked in (LastFmAppCredentials) and the
 * session comes straight from Last.fm's own auth.getSession exchange. See
 * AuthViewModel's doc comment for why completion is detected by resuming
 * the app rather than a redirect callback.
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
    onDismissError: () -> Unit,
) {
    val context = LocalContext.current

    // Opens the Custom Tab the moment we have a token to approve. Keyed on
    // the token itself (not just "is AwaitingApproval") so re-entering
    // this same state after a failed completion attempt (see
    // AuthViewModel.onReturnedFromBrowser — same token, browser reopens)
    // re-triggers this launch too.
    val awaitingToken = (webAuthState as? WebAuthState.AwaitingApproval)?.token
    LaunchedEffect(awaitingToken) {
        val state = webAuthState as? WebAuthState.AwaitingApproval ?: return@LaunchedEffect
        CustomTabsIntent.Builder().build().launchUrl(context, android.net.Uri.parse(state.authUrl))
    }

    // The actual completion trigger: when this Activity/screen resumes —
    // meaning the person switched back from the browser — attempt the
    // token exchange. Only matters while AwaitingApproval; harmless
    // no-op otherwise (see AuthViewModel.onReturnedFromBrowser's own guard
    // too, this is just belt-and-suspenders against calling it from an
    // unrelated resume).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onReturnedFromBrowser()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
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
                "Connect your Last.fm account to get started",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))

            when (val state = authState) {
                is AuthState.SignedIn -> SignedInCard(username = state.username, onSignOut = onSignOut)

                AuthState.Unknown -> CircularProgressIndicator()

                else -> Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (webAuthState) {
                        is WebAuthState.AwaitingApproval -> {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Waiting for approval in the browser\u2026",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
                            val busy = state is AuthState.SigningIn || webAuthState is WebAuthState.FetchingToken || webAuthState is WebAuthState.CompletingSignIn
                            Button(
                                onClick = onBeginSignIn,
                                enabled = !busy,
                                shape = ExpressivePillShape,
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                } else {
                                    Text("Connect with Last.fm")
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Opens Last.fm in your browser to approve LastWave \u2014 no password typed into this app.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }

                    val errorMessage = (state as? AuthState.Error)?.message
                        ?: (webAuthState as? WebAuthState.Error)?.message
                    if (errorMessage != null) {
                        Spacer(Modifier.height(16.dp))
                        Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = onDismissError) { Text("Dismiss") }
                    }
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
