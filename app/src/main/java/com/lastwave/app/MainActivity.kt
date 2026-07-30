package com.lastwave.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import com.lastwave.app.ui.auth.AuthDeepLinkDispatcher
import com.lastwave.app.ui.navigation.LastWaveNavHost
import com.lastwave.app.ui.theme.LastWaveTheme
import com.lastwave.app.ui.theme.ThemeViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var deepLinkDispatcher: AuthDeepLinkDispatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate() and before setContent().
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleAuthDeepLink(intent)

        setContent {
            val themeViewModel: ThemeViewModel = hiltViewModel()
            val themeState by themeViewModel.uiState.collectAsState()

            LastWaveTheme(themeState = themeState) {
                LastWaveNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthDeepLink(intent)
    }

    /** Extracts `token` from `lastwave://auth?token=...` and forwards it to
     *  whichever AuthViewModel is currently listening. Mirrors the Java
     *  MainActivity's deep-link handling that used to call
     *  webView.evaluateJavascript("window._lfmDeepLink('$token')"). */
    private fun handleAuthDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "lastwave" && uri.host == "auth") {
            uri.getQueryParameter("token")?.let { token ->
                deepLinkDispatcher.onTokenReceived(token)
            }
        }
    }
}
