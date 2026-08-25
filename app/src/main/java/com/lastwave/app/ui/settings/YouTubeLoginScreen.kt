package com.lastwave.app.ui.settings

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.ui.common.ExpressiveHeader

/**
 * Native-feeling YouTube Music sign-in: a WebView loads Google's own sign-in
 * flow (no embedded passwords, no OAuth project), and once music.youtube.com
 * has session cookies the app captures them via the system CookieManager —
 * exactly how the browser itself persists a logged-in session. No cookie is
 * ever sent anywhere except back to YouTube's own endpoints.
 */
@Composable
fun YouTubeLoginScreen(
    onBack: () -> Unit,
    onConnected: () -> Unit,
    viewModel: YouTubeLoginViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var loadProgressVisible by remember { mutableStateOf(true) }
    var webViewError by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        ExpressiveHeader(
            title = "Connect YouTube Music",
            subtitle = "Sign in with your Google account",
            onBack = onBack,
        )

        Box(Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    var createdWebView: WebView? = null
                    try {
                        WebView(ctx).also { createdWebView = it }.apply {
                        @SuppressLint("SetJavaScriptEnabled")
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Google blocks the default WebView user agent on
                        // accounts.google.com ("may not be secure"); a plain
                        // Chrome UA signs in normally.
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                loadProgressVisible = false
                                // Read state live — a factory-captured copy would be stale.
                                val current = viewModel.uiState.value
                                if (current.verifying || current.connectedName != null) return
                                val cookies = readYouTubeCookies()
                                val hasSession = listOf("__Secure-3PAPISID=", "SAPISID=").any { token ->
                                    cookies?.contains(token) == true
                                }
                                if (hasSession) {
                                    viewModel.attemptConnect(cookies)
                                }
                            }
                        }
                            loadUrl(LOGIN_URL)
                        }
                    } catch (error: Exception) {
                        runCatching { createdWebView?.destroy() }
                        loadProgressVisible = false
                        webViewError = "Android System WebView is unavailable. Install or enable a WebView provider to connect YouTube Music."
                        android.widget.FrameLayout(ctx)
                    } catch (error: LinkageError) {
                        runCatching { createdWebView?.destroy() }
                        loadProgressVisible = false
                        webViewError = "This ROM's WebView implementation is incompatible. YouTube Music sign-in is disabled safely."
                        android.widget.FrameLayout(ctx)
                    }
                },
            )

            if (webViewError != null) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                ) {
                    Text(
                        webViewError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = state.connectedName != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            ) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                        }
                        Text(
                            "Connected",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            state.connectedName.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                        Button(onClick = onConnected, shape = CircleShape) {
                            Text("Continue")
                        }
                    }
                }
            }

            if (loadProgressVisible && state.connectedName == null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                )
            }
        }

        // Bottom helper bar — native pattern for embedded web flows.
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CloudSync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "LastWave never sees your password — sign-in happens entirely inside Google's page.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.errorMessage != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        viewModel.attemptConnect(readYouTubeCookies())
                    },
                    enabled = webViewError == null && !state.verifying && state.connectedName == null,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    if (state.verifying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Verifying account...")
                    } else {
                        Text("I'm signed in")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "After signing in you'll be returned here automatically.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com%2F&hl=en"

private fun readYouTubeCookies(): String? = try {
    CookieManager.getInstance().getCookie("https://music.youtube.com")
} catch (error: Exception) {
    null
} catch (error: LinkageError) {
    null
}
