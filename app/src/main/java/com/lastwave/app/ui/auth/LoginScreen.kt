package com.lastwave.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.browser.customtabs.CustomTabsIntent
import com.lastwave.app.data.model.AuthState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    authState: AuthState,
    initialApiKey: String,
    initialApiSecret: String,
    onSaveCredentials: (apiKey: String, apiSecret: String) -> Unit,
    onStartAuth: (apiKey: String, apiSecret: String) -> Unit,
    onContinueAfterAuth: () -> Unit,
    onSignOut: () -> Unit,
    onDismissError: () -> Unit,
) {
    var apiKey by remember { mutableStateOf(initialApiKey) }
    var apiSecret by remember { mutableStateOf(initialApiSecret) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
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

                else -> Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it; onSaveCredentials(it, apiSecret) },
                        label = { Text("Last.fm API key") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = apiSecret,
                        onValueChange = { apiSecret = it; onSaveCredentials(apiKey, it) },
                        label = { Text("Last.fm API secret") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(20.dp))

                    AuthStatusRow(state)

                    Spacer(Modifier.height(12.dp))

                    val busy = state is AuthState.RequestingToken || state is AuthState.ExchangingToken
                    Button(
                        onClick = { onStartAuth(apiKey, apiSecret) },
                        enabled = !busy && apiKey.isNotBlank() && apiSecret.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state is AuthState.AwaitingAuthorization) "Open Last.fm again" else "Connect with Last.fm")
                    }

                    if (state is AuthState.AwaitingAuthorization) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onContinueAfterAuth, modifier = Modifier.fillMaxWidth()) {
                            Text("I've authorized, continue")
                        }
                    }

                    if (state is AuthState.Error) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onDismissError) { Text("Dismiss") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthStatusRow(state: AuthState) {
    when (state) {
        AuthState.RequestingToken -> StatusLine("Requesting token…", showSpinner = true)
        AuthState.AwaitingAuthorization -> StatusLine("Waiting for authorization in your browser…", showSpinner = false)
        AuthState.ExchangingToken -> StatusLine("Exchanging token…", showSpinner = true)
        is AuthState.Error -> StatusLine(state.message, isError = true)
        else -> {}
    }
}

@Composable
private fun StatusLine(text: String, showSpinner: Boolean = false, isError: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showSpinner) {
            CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(0.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = if (showSpinner) 8.dp else 0.dp),
        )
    }
}

@Composable
private fun SignedInCard(username: String, onSignOut: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("Signed in as $username", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onSignOut) { Text("Sign out") }
        }
    }
}

/** Opens the Last.fm authorize URL in Chrome Custom Tabs — call this from the
 *  NavHost composable's LaunchedEffect(authorizeUrl) once that state is wired
 *  in; kept as a standalone function so the browser launch isn't buried
 *  inside ViewModel code, which has no Context to launch an Intent with. */
fun openAuthorizeUrl(context: android.content.Context, url: String) {
    CustomTabsIntent.Builder().build().launchUrl(context, android.net.Uri.parse(url))
}
