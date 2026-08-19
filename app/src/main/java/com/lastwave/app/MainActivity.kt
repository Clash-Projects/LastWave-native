package com.lastwave.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lastwave.app.ui.navigation.LastWaveNavHost
import com.lastwave.app.ui.navigation.Screen
import com.lastwave.app.ui.player.PlayerHost
import com.lastwave.app.ui.theme.LastWaveTheme
import com.lastwave.app.ui.theme.ThemeViewModel
import dagger.hilt.android.AndroidEntryPoint

// Sign-in opens Last.fm's real web-auth flow in Chrome Custom Tabs (see
// ui/auth/LoginScreen.kt / AuthRepository.authUrl). The AndroidManifest
// registers this Activity for lastwave://auth-callback (Last.fm's cb=
// redirect target) so approving in the browser brings this app back to
// the foreground automatically — singleTop reuses this same instance so
// Compose/ViewModel state (the in-progress AwaitingApproval token) isn't
// lost, and LoginScreen's own onResume check then completes sign-in.
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate() and before setContent().
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setOnExitAnimationListener { provider ->
            provider.view.animate()
                .alpha(0f)
                .scaleX(1.025f)
                .scaleY(1.025f)
                .setDuration(260L)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .withEndAction { provider.remove() }
                .start()
        }
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val themeViewModel: ThemeViewModel = hiltViewModel()
            val themeState by themeViewModel.uiState.collectAsState()

            LastWaveTheme(themeState = themeState) {
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val hasBottomNavigation = backStackEntry?.destination?.route == Screen.MainShell.route

                PlayerHost(hasBottomNavigation = hasBottomNavigation) {
                    LastWaveNavHost(navController)
                }
            }
        }
    }
}
