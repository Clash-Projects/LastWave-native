package com.lastwave.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.repository.LastFmAuthCallbackCoordinator
import com.lastwave.app.ui.navigation.LastWaveNavHost
import com.lastwave.app.ui.navigation.Screen
import com.lastwave.app.ui.player.PlayerHost
import com.lastwave.app.ui.theme.LastWaveTheme
import com.lastwave.app.ui.theme.ThemeViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// Last.fm redirects its Custom Tab to this Activity after approval.
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var lastFmAuthCallback: LastFmAuthCallbackCoordinator

    @Inject
    lateinit var linkPlaybackResolver: dagger.Lazy<com.lastwave.app.playback.LinkPlaybackResolver>

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate() and before setContent().
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        lastFmAuthCallback.capture(intent)
        handlePlaybackIntent(intent)
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
        runCatching { enableEdgeToEdge() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val themeViewModel: ThemeViewModel = hiltViewModel()
            val themeState by themeViewModel.uiState.collectAsStateWithLifecycle()

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

    override fun onResume() {
        super.onResume()
        requestHighestSupportedRefreshRate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                val hasAccess = androidx.core.app.NotificationManagerCompat
                    .getEnabledListenerPackages(this)
                    .contains(packageName)
                if (hasAccess) {
                    android.service.notification.NotificationListenerService.requestRebind(
                        android.content.ComponentName(this, com.lastwave.app.service.MediaScrobbleListenerService::class.java),
                    )
                }
            }
        }
    }

    /**
     * Ask the window scheduler for the panel's fastest supported rate. This is
     * a preference, not a forced mode: Android can still lower it for battery,
     * thermals or a user's display setting, and 60 Hz panels remain at 60 Hz.
     */
    @Suppress("DEPRECATION")
    private fun requestHighestSupportedRefreshRate() {
        runCatching {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display ?: windowManager.defaultDisplay
            } else {
                windowManager.defaultDisplay
            }
            val currentMode = display.mode
            val matchingModes = display.supportedModes.filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }
            val highestMode = matchingModes.maxByOrNull { it.refreshRate } ?: currentMode
            val attributes = window.attributes
            var changed = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && attributes.preferredDisplayModeId != highestMode.modeId) {
                attributes.preferredDisplayModeId = highestMode.modeId
                changed = true
            }
            if (attributes.preferredRefreshRate != highestMode.refreshRate) {
                attributes.preferredRefreshRate = highestMode.refreshRate
                changed = true
            }
            if (changed) {
                window.attributes = attributes
            }
        }
    }

    private fun handlePlaybackIntent(intent: Intent?) {
        val uri = intent?.data
        val host = uri?.host.orEmpty().lowercase()
        val isSupportedMusicLink = intent?.action == Intent.ACTION_VIEW &&
            (uri?.scheme == "http" || uri?.scheme == "https") &&
            (host == "youtube.com" || host == "www.youtube.com" ||
                host == "m.youtube.com" || host == "music.youtube.com" || host == "youtu.be" ||
                host == "open.spotify.com" || host == "spotify.link")
        val hasPlaybackTarget = isSupportedMusicLink || intent?.action == Intent.ACTION_SEND
        if (hasPlaybackTarget) {
            runCatching { linkPlaybackResolver.get().handleIntent(intent) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lastFmAuthCallback.capture(intent)
        handlePlaybackIntent(intent)
    }
}
